const {test}=require('node:test');
const assert=require('node:assert/strict');
const {command,validateManifest,junit}=require('./support.cjs');
const {browserInstallCommands}=require('./browser.cjs');

test('a subprocess assertion failure is propagated, never converted to success',async()=>{
 await assert.rejects(command(process.execPath,['-e',"require('node:assert/strict').equal(1,2,'intentional runner probe')"]),error=>error.exitCode!==0 && error.stderr.includes('intentional runner probe'));
 assert.equal(await command(process.execPath,['-e',"console.log('positive probe')"]),'positive probe');
});
test('empty or duplicate mandatory suites cannot produce green reports',()=>{
 assert.throws(()=>validateManifest({schemaVersion:1,javaModules:[],sql:[],integrations:[]}));
 const item={id:'duplicate',script:'test.cjs',requiredOutput:['PASS']};
 assert.throws(()=>validateManifest({schemaVersion:1,javaModules:['core'],sql:['test.sql'],integrations:[item,item]}));
});
test('hung subprocesses fail within the configured deadline',async()=>{
 await assert.rejects(command(process.execPath,['-e','setInterval(()=>{},1000)'],{timeout:200}),/timeout/);
});
test('CI reports preserve failures and escape arbitrary assertion text',()=>{
 const report=junit([{name:'negative & positive',seconds:0.1,error:'<wrong "value">'}]);
 assert.match(report,/failures="1"/);assert.match(report,/&lt;wrong &quot;value&quot;&gt;/);
});
test('Linux browser OS dependencies receive non-interactive settings after sudo',()=>{
 const [deps,browser]=browserInstallCommands('/repo with spaces/cli.js',{platform:'linux',uid:1001,node:'/node'});
 assert.equal(deps.exe,'sudo');assert.deepEqual(deps.args,['-n','env','DEBIAN_FRONTEND=noninteractive','NEEDRESTART_MODE=l','/node','/repo with spaces/cli.js','install-deps','chromium']);
 assert.deepEqual(browser.args,['/repo with spaces/cli.js','install','chromium']);assert.notEqual(deps.log,browser.log);
 const [root]=browserInstallCommands('/cli',{platform:'linux',uid:0,node:'/node'});assert.equal(root.exe,'env');assert.equal(root.args[0],'DEBIAN_FRONTEND=noninteractive');
 assert.equal(browserInstallCommands('C:/cli',{platform:'win32',node:'C:/node'}).length,1);
});
