// Disposable acceptance environment. Always invoke through `mvn -Prules-full verify`
// except for the documented intentional failure/cleanup probe.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const assert=require('node:assert/strict');
const {command,validateManifest,verifyJavaReports,junit}=require('./support.cjs');
const root=path.resolve(__dirname,'../..');
const args=process.argv.slice(2);
const jarIndex=args.indexOf('--jar');
const jar=path.resolve(jarIndex>=0?args[jarIndex+1]:path.join(root,'webapp/target/metis-web-0.1.0-SNAPSHOT.jar'));
const probe=args.includes('--probe-failure');
const runId=`metis-rules-${Date.now()}-${crypto.randomUUID().slice(0,8)}`;
const artifact=path.join(root,'target','rules-suite',runId);
const network=runId, db=`${runId}-db`, app=`${runId}-app`;
const label=`metis.rules.run=${runId}`;
const database='metis_rules_test', password=crypto.randomBytes(16).toString('hex');
const stages=[],abort=new AbortController();
let resourcesStarted=false;
fs.mkdirSync(artifact,{recursive:true});
const summary={schemaVersion:1,runId,artifact,probe,node:process.version,status:'running',stages};
const save=()=>{fs.writeFileSync(path.join(artifact,'summary.json'),JSON.stringify(summary,null,2));fs.writeFileSync(path.join(artifact,'TEST-rules-full.xml'),junit(stages));};
for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>abort.abort());
async function stage(name,work) {
 const start=Date.now(),entry={name,seconds:0};stages.push(entry);console.log(`[rules-full] ${name}`);
 try{return await work();}catch(error){entry.error=error.stack || String(error);throw error;}
 finally{entry.seconds=(Date.now()-start)/1000;save();}
}
const run=(exe,arguments_,options={})=>command(exe,arguments_,{cwd:root,signal:abort.signal,...options});
const docker=(arguments_,options={})=>run('docker',arguments_,options);
async function waitFor(description,check) {
 const deadline=Date.now()+180000;let last;
 while(Date.now()<deadline) {
  if(abort.signal.aborted)throw Error('Suite interrupted');
  try{if(await check())return;}catch(error){last=error;}
  await new Promise(resolve=>setTimeout(resolve,300));
 }
 throw Error(`Timed out waiting for ${description}: ${last || 'not ready'}`);
}
const psql=sql=>docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-tAc',sql]);
async function discoverUrl() {
 const port=await docker(['port',app,'8080/tcp']);assert.match(port,/^127\.0\.0\.1:\d+$/);
 summary.baseUrl=`http://${port}`;
}
async function sqlFile(file) {
 assert.match(file,/^[a-z_]+\.sql$/);
 return docker(['exec','-e','PGOPTIONS=-c plpgsql.check_asserts=on',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-f',`/tests/${file}`],{log:path.join(artifact,file+'.log')});
}
async function collectLogs() {
 for(const name of [app,db]) {
  try{await command('docker',['logs',name],{log:path.join(artifact,name.endsWith('-db')?'postgres.log':'application.log'),timeout:20000});}catch{/* A container might not have been created. */}
 }
}
async function cleanup() {
 const containers=await command('docker',['ps','-aq','--filter',`label=${label}`],{timeout:20000});
 if(containers)await command('docker',['rm','-f','-v',...containers.split(/\s+/)],{timeout:30000});
 const networks=await command('docker',['network','ls','-q','--filter',`label=${label}`],{timeout:20000});
 if(networks)await command('docker',['network','rm',...networks.split(/\s+/)],{timeout:30000});
 assert.equal(await command('docker',['ps','-aq','--filter',`label=${label}`]),'','Leaked suite containers');
 assert.equal(await command('docker',['network','ls','-q','--filter',`label=${label}`]),'','Leaked suite network');
 summary.cleanup='verified';
}
async function main() {
 let failure;
 try {
  const manifest=validateManifest(JSON.parse(fs.readFileSync(path.join(__dirname,'manifest.json'),'utf8')));
  await stage('prerequisites-and-java-reports',async()=>{
   assert.ok(Number(process.versions.node.split('.')[0])>=22,'Node 22+ required; CI pins .nvmrc');
   assert.ok(fs.existsSync(jar),`Packaged application missing: ${jar}`);
   summary.java=verifyJavaReports(root,manifest.javaModules);
   assert.equal(await docker(['info','--format','{{.OSType}}'],{timeout:20000}),'linux','Docker Linux containers required');
  });
  await stage('runner-failure-contracts',async()=>{
   const output=await run(process.execPath,['--test','--test-reporter=tap',path.join(__dirname,'runner.test.cjs')],{log:path.join(artifact,'runner-tests.log')});
   assert.match(output,/# tests [1-9]\d*/,'No runner contract tests executed');
  });
  await stage('pinned-browser-install',async()=>{
   await run('npm',['ci','--ignore-scripts','--no-audit','--no-fund'],{cwd:path.join(root,'webapp'),log:path.join(artifact,'npm.log')});
   const cli=path.join(root,'webapp/node_modules/playwright/cli.js');
   await run(process.execPath,[cli,'install',...(process.platform==='linux'?['--with-deps']:[]),'chromium'],{timeout:600000,log:path.join(artifact,'browser-install.log')});
  });
  await stage('disposable-database',async()=>{
   resourcesStarted=true;
   await docker(['network','create','--label',label,network]);
   await docker(['run','-d','--name',db,'--label',label,'--network',network,
    '-e','POSTGRES_USER=metis','-e',`POSTGRES_PASSWORD=${password}`,'-e',`POSTGRES_DB=${database}`,
    '--mount','type=tmpfs,destination=/var/lib/postgresql/data',
    '--mount',`type=bind,source=${path.join(root,'docker/init/sql')},target=/docker-entrypoint-initdb.d,readonly`,
    '--mount',`type=bind,source=${path.join(root,'database/src/test/sql')},target=/tests,readonly`,'postgres:16.15'],{timeout:180000});
   await waitFor('PostgreSQL bootstrap',async()=>{await docker(['exec',db,'pg_isready','-h','127.0.0.1','-U','metis','-d',database],{timeout:10000});return true;});
  });
  await stage('application-and-flyway',async()=>{
   await docker(['run','-d','--name',app,'--label',label,'--network',network,'-p','127.0.0.1::8080',
    '-e',`DB_URL=jdbc:postgresql://${db}:5432/${database}?escapeSyntaxCallMode=callIfNoReturn`,
     '-e','DB_USER=metis','-e',`DB_PASSWORD=${password}`,
     '-e','METIS_RULES_CPU_TICK_MS=80',
    '--mount',`type=bind,source=${jar},target=/app/app.jar,readonly`,'eclipse-temurin:21-jre','java','-jar','/app/app.jar'],{timeout:180000});
   await discoverUrl();
   await waitFor('application',async()=>(await fetch(summary.baseUrl+'/login',{signal:AbortSignal.timeout(3000)})).status===200);
   const migrations=fs.readdirSync(path.join(root,'database/src/main/resources/db/migration')).map(name=>name.match(/^V(\d+)__/)).filter(Boolean).map(match=>Number(match[1])).sort((a,b)=>a-b);
   const applied=JSON.parse(await psql('SELECT json_agg(version::integer ORDER BY installed_rank) FROM game_engine.flyway_schema_history WHERE success'));
   assert.deepEqual(applied,[31,...migrations]);summary.migrations=applied;
  });
  if(probe)await stage('intentional-post-startup-failure',()=>run(process.execPath,['-e',"require('node:assert/strict').fail('intentional M0 lifecycle probe')"]));
  await stage('repeat-startup-checksums',async()=>{
   const query='SELECT json_agg(row_to_json(x) ORDER BY installed_rank) FROM game_engine.flyway_schema_history x';
   const before=await psql(query);
   await docker(['restart',app]);
   // Docker may allocate a different host port when restarting an ephemeral mapping.
   await discoverUrl();
   await waitFor('restarted application',async()=>(await fetch(summary.baseUrl+'/login',{signal:AbortSignal.timeout(3000)})).status===200);
   assert.equal(await psql(query),before,'Repeated startup modified migration history');
  });
  for(const sql of manifest.sql)await stage(`sql-${sql}`,()=>sqlFile(sql));
  await stage('synthetic-multiplayer-fixtures',()=>sqlFile(manifest.fixtureSql));
  const env={...process.env,TEST_BASE_URL:summary.baseUrl,TEST_DB_CONTAINER:db,TEST_DB_NAME:database,
   REQUIRE_BROWSER:'1',PLAYWRIGHT_MODULE:path.join(root,'webapp/node_modules/playwright'),TEST_BROWSER_CHANNEL:'',TEST_ARTIFACT_DIR:artifact,TEST_APP_CONTAINER:app};
  for(const test of manifest.integrations)await stage(test.id,async()=>{
   const output=await run(process.execPath,[path.join(root,test.script)],{env,timeout:180000,echo:true,log:path.join(artifact,test.id+'.log')});
   for(const marker of test.requiredOutput)assert.ok(output.includes(marker),`Required acceptance checkpoint did not run: ${marker}`);
   await discoverUrl();env.TEST_BASE_URL=summary.baseUrl;
  });
  summary.status='passed';
 } catch(error) {failure=error;summary.status='failed';summary.error=error.stack || String(error);}
 finally {
  if(resourcesStarted) {
   await collectLogs();
   try{await stage('cleanup-verification',cleanup);}catch(error){summary.status='failed';summary.cleanup='failed';failure ||= error;}
  }
  save();console.log(`[rules-full] ${summary.status.toUpperCase()} — ${artifact}`);
 }
 if(failure)throw failure;
}
main().catch(error=>{console.error(error.stack || error);process.exitCode=1;});
