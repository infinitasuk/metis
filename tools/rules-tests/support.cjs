const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

function command(executable, args, options={}) {
 return new Promise((resolve,reject)=>{
  if(options.signal?.aborted) return reject(new Error('Suite interrupted'));
  // npm ships as a .cmd on Windows. Arguments here are fixed repository-owned flags.
  if(process.platform==='win32' && executable==='npm') {
   executable=process.env.ComSpec || 'cmd.exe'; args=['/d','/s','/c','npm',...args];
  }
  const child=spawn(executable,args,{cwd:options.cwd,env:options.env || process.env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  let stdout='', stderr='', timedOut=false, killTimer;
  const log=options.log ? fs.createWriteStream(options.log) : null;
  child.stdout.on('data',chunk=>{stdout+=chunk;log?.write(chunk);if(options.echo)process.stdout.write(chunk);});
  child.stderr.on('data',chunk=>{stderr+=chunk;log?.write(chunk);if(options.echo)process.stderr.write(chunk);});
  const stop=()=>{child.kill();killTimer=setTimeout(()=>child.kill('SIGKILL'),2000);killTimer.unref();};
  const abort=()=>stop();options.signal?.addEventListener('abort',abort,{once:true});
  const timer=setTimeout(()=>{timedOut=true;stop();},options.timeout || 120000);
  const finish=()=>{clearTimeout(timer);clearTimeout(killTimer);options.signal?.removeEventListener('abort',abort);log?.end();};
  child.on('error',error=>{finish();reject(error);});
  child.on('close',(code,signal)=>{
   finish();
   if(code!==0 || timedOut || options.signal?.aborted) {
    const error=new Error(`${path.basename(executable)} failed (${timedOut?'timeout':signal || code})\n${stderr || stdout}`);
    error.exitCode=code;error.stdout=stdout;error.stderr=stderr;reject(error);
   } else resolve(stdout.trim());
  });
 });
}

function validateManifest(manifest) {
 if(manifest.schemaVersion!==1)throw Error('Unsupported suite manifest');
 for(const field of ['javaModules','sql','integrations']) if(!Array.isArray(manifest[field]) || !manifest[field].length)throw Error(`Empty required suite: ${field}`);
 const ids=new Set();
 for(const test of manifest.integrations) {
  if(!/^[a-z0-9-]+$/.test(test.id) || ids.has(test.id) || !test.script || !test.requiredOutput?.length)throw Error('Invalid or duplicate integration suite');
  ids.add(test.id);
 }
 return manifest;
}

function verifyJavaReports(root, modules) {
 const counts={tests:0,failures:0,errors:0,skipped:0};
 for(const module of modules) {
  const folder=path.join(root,module,'target','surefire-reports');
  const files=fs.readdirSync(folder).filter(file=>/^TEST-.*\.xml$/.test(file));
  if(!files.length)throw Error(`No JUnit reports for ${module}; run Maven tests first`);
  let moduleTests=0;
  for(const file of files) {
   const header=fs.readFileSync(path.join(folder,file),'utf8').match(/<testsuite\s[^>]+>/)?.[0];
   if(!header)throw Error(`Invalid JUnit report ${file}`);
   for(const key of Object.keys(counts)) {
    const match=header.match(new RegExp(`\\b${key}="(\\d+)"`));
    if(!match)throw Error(`Missing ${key} in ${file}`);
    counts[key]+=Number(match[1]); if(key==='tests')moduleTests+=Number(match[1]);
   }
  }
  if(moduleTests===0)throw Error(`Zero tests for ${module}`);
 }
 if(counts.failures || counts.errors || counts.skipped)throw Error(`Java suite is not green: ${JSON.stringify(counts)}`);
 return counts;
}

const xml=value=>String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&apos;');
function junit(stages) {
 return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="rules-full" tests="${stages.length}" failures="${stages.filter(s=>s.error).length}" errors="0" skipped="0">\n`
  +stages.map(s=>`<testcase classname="rules.acceptance" name="${xml(s.name)}" time="${s.seconds}">${s.error?`<failure message="${xml(s.error)}"/>`:''}</testcase>`).join('\n')+'\n</testsuite>\n';
}
module.exports={command,validateManifest,verifyJavaReports,junit};
