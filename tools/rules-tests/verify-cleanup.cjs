// This command passes only when the deliberately failed child suite exits nonzero
// for the expected assertion and proves that its disposable resources were removed.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {command}=require('./support.cjs');
async function main() {
 let failure;
 try{await command(process.execPath,[path.join(__dirname,'run.cjs'),...process.argv.slice(2),'--probe-failure'],{timeout:900000});}
 catch(error){failure=error;}
 assert.ok(failure,'Deliberately failing suite unexpectedly passed');
 assert.notEqual(failure.exitCode,0);
 const output=failure.stdout || '';
 const location=output.match(/\[rules-full\] FAILED — ([^\r\n]+)/)?.[1];
 assert.ok(location,`Failure did not produce artifacts: ${failure}`);
 const summary=JSON.parse(fs.readFileSync(path.join(location,'summary.json'),'utf8'));
 assert.equal(summary.probe,true);assert.equal(summary.cleanup,'verified');
 assert.ok(summary.stages.some(stage=>stage.name==='intentional-post-startup-failure' && stage.error?.includes('intentional M0 lifecycle probe')),'Failed for an unexpected reason');
 assert.equal(await command('docker',['ps','-aq','--filter',`label=metis.rules.run=${summary.runId}`]),'');
 assert.equal(await command('docker',['network','ls','-q','--filter',`label=metis.rules.run=${summary.runId}`]),'');
 console.log(`PASS: injected assertion propagated and containers/network cleaned up. Expected-failure artifacts: ${location}`);
}
main().catch(error=>{console.error(error);process.exitCode=1;});
