const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync,spawn}=require('node:child_process');
const {randomUUID,createHmac}=require('node:crypto');
let base=process.env.TEST_BASE_URL;
const db=process.env.TEST_DB_CONTAINER,app=process.env.TEST_APP_CONTAINER,database=process.env.TEST_DB_NAME;
assert.ok(db?.startsWith('metis-rules-')&&app?.startsWith('metis-rules-')&&database==='metis_rules_test');
const docker=args=>execFileSync('docker',args,{encoding:'utf8'}).trim();
const sql=statement=>docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-tAc',statement]);
const cookies={};let id;
async function login(){for(const user of [1,2,3,5]){const r=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/`});cookies[user]=r.headers.getSetCookie().map(v=>v.split(';')[0]).join('; ');}}
async function api(user,url,method='GET',body,status=200){const r=await fetch(base+url,{method,headers:{Cookie:cookies[user],'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});const text=await r.text();assert.equal(r.status,status,text);return text?JSON.parse(text):null;}
const endpoint=()=>`/api/v1/rules/sessions/${id}`;
const view=(user=1)=>api(user,endpoint());
const stored=()=>JSON.parse(sql(`SELECT checkpoint_json FROM game_instance.rules_session WHERE session_id='${id}'`));
async function cmd(user,type,extra={},status=200,body){const request=body||{type,commandId:randomUUID(),expectedStateVersion:(await view(user)).state.version,...extra};return {body:request,result:await api(user,endpoint()+'/commands','POST',request,status)};}
async function decision(user,choice){const state=(await view(user)).state;const option=state.decision.options.findIndex(v=>v.value===choice);assert.ok(option>=0);return cmd(user,'respond',{actor:state.decision.player,decisionId:state.decision.id,option});}
async function wait(check){const until=Date.now()+30000;while(Date.now()<until){if(await check())return;await new Promise(r=>setTimeout(r,100));}throw Error('Recovery checkpoint timeout');}
async function restart(){docker(['kill','--signal=KILL',app]);docker(['start',app]);base='http://'+docker(['port',app,'8080/tcp']);await wait(async()=>{try{return(await fetch(base+'/login',{signal:AbortSignal.timeout(1000)})).status===200;}catch{return false;}});await login();}
function face(seed,position){const counter=Buffer.alloc(8);counter.writeBigInt64BE(BigInt(position));const bits=createHmac('sha256',Buffer.from(seed,'hex')).update(counter).digest().readBigUInt64BE()&((1n<<63n)-1n);return Number(bits%2n);}
async function main(){await login();try{
 const source=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../../../engine-core/src/main/resources/rules/examples/dice-duel.json'),'utf8'));source.id=`m4-${randomUUID()}`;
 const draft=await api(5,'/api/v1/admin/rules/definitions','POST',source,201);const published=await api(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:0});
 assert.equal(published.compilerVersion,'rules-compiler-4');assert.equal(published.runtimeVersion,'rules-runtime-3');
 const setup={definitionId:published.id,visibility:'private',seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3]};
 await api(1,'/api/v1/rules/sessions','POST',{...setup,seed:'chosen-by-client'},400);
 const game=await api(1,'/api/v1/rules/sessions','POST',setup,201);id=game.id;const seed=stored().control.mechanics.random.seed;assert.match(seed,/^[0-9a-f]{64}$/);
 await cmd(1,'start');assert.equal(stored().control.mechanics.random.position,4);
 for(const [actor,user] of [['alice',1],['bob',2]])await cmd(user,'act',{actor,action:'plan',parameters:{fighter:actor,orders:['focus','guard']}});
 await cmd(1,'act',{actor:'alice',action:'attack',parameters:{fighter:'alice',target:'bob'}});
 const before=stored(),publicBefore=await view();assert.equal(publicBefore.state.decision.options.at(-1).value,'$pass');
 const pass={type:'respond',actor:'alice',commandId:randomUUID(),expectedStateVersion:publicBefore.state.version,decisionId:publicBefore.state.decision.id,option:1};
 sql(`INSERT INTO rules_test_support.fault VALUES('${id}','raise')`);await api(1,endpoint()+'/commands','POST',pass,503);assert.deepEqual(stored(),before);
 sql(`DELETE FROM rules_test_support.fault WHERE session_id='${id}'`);const rolled=await api(1,endpoint()+'/commands','POST',pass);
 const rollId=rolled.view.state.globals.roll.value;const dice=rolled.view.state.mechanics.rolls[rollId].dice;
 assert.deepEqual(dice.map(d=>d.face),[4,5,6].map(position=>face(seed,position)));assert.equal(stored().control.mechanics.random.position,7);
 assert.deepEqual(await api(1,endpoint()+'/commands','POST',pass),rolled);assert.equal(stored().control.mechanics.random.position,7);
 const previewBefore=stored();await api(1,endpoint()+'/preview','POST',{actor:'alice',action:'attack',parameters:{fighter:'alice',target:'bob'},expectedStateVersion:rolled.view.state.version});assert.deepEqual(stored(),previewBefore);
 for(const user of [1,2,3,5]){
  const state=await view(user);const encoded=JSON.stringify(state);assert.ok(!encoded.includes(seed));assert.ok(!encoded.includes('hmac-sha256-v1'));assert.ok(!encoded.includes('random'));
  assert.deepEqual(state.state.mechanics.rolls[rollId].dice,dice);
  const hands=Object.values(state.state.mechanics.piles).filter(p=>p.kind==='HAND');assert.equal(hands.length,user===1||user===2?1:0);
  const owner=state.state.entities.alice.attributes;assert.equal('orders' in owner,user===1);
  assert.ok(!JSON.stringify(await api(user,endpoint()+'/history/'+state.state.version)).includes(seed));
  assert.ok(!JSON.stringify(await api(user,endpoint()+'/events')).includes(seed));
 }
 const checkpoint=stored();await restart();assert.deepEqual(stored(),checkpoint);assert.deepEqual((await view()).state.mechanics.rolls[rollId].dice,dice);
 console.log('PASS: post-roll privacy, weighted outcome oracle, preview purity, same-request retry and process restart preserve committed results and server-only entropy.');
 await decision(1,'reroll');const pending=await view();const choice={type:'respond',actor:'alice',commandId:randomUUID(),expectedStateVersion:pending.state.version,decisionId:pending.state.decision.id,option:0};
 const checkpointBeforeReroll=stored();sql(`INSERT INTO rules_test_support.fault VALUES('${id}','wait')`);
 const holder=spawn('docker',['exec',db,'psql','-X','-U','metis','-d',database,'-c',"SET application_name='m4-test-gate'; SELECT pg_advisory_lock(771236); SELECT pg_sleep(120)"],{stdio:'ignore'});
 try{
  await wait(()=>sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='m4-test-gate' AND wait_event='PgSleep'")==='1');
  const inFlight=fetch(base+endpoint()+'/commands',{method:'POST',headers:{Cookie:cookies[1],'Content-Type':'application/json'},body:JSON.stringify(choice),signal:AbortSignal.timeout(30000)}).then(r=>r.text()).catch(()=>null);
  await wait(()=>sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=771236 AND NOT granted")==='1');
  await restart();await inFlight;assert.deepEqual(stored(),checkpointBeforeReroll);
  sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='m4-test-gate'");sql(`DELETE FROM rules_test_support.fault WHERE session_id='${id}'`);
  const result=await api(1,endpoint()+'/commands','POST',choice);assert.equal(result.view.state.mechanics.rolls[rollId].dice[0].face,face(seed,7));assert.equal(stored().control.mechanics.random.position,8);
  assert.equal(result.view.state.entities.alice.resources.rerollSpent,1);assert.equal(result.view.state.mechanics.rolls[rollId].dice[0].rerolls,1);
  assert.deepEqual(await api(1,endpoint()+'/commands','POST',choice),result);assert.equal(stored().control.mechanics.random.position,8);
 }finally{sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='m4-test-gate'");holder.kill();}
 // Exercise SQL guards with matching fake checkpoint/commit, so only stream protection rejects them.
 sql(`DO $$ DECLARE s game_instance.rules_session%ROWTYPE; c jsonb; n jsonb; denied boolean; mode text; BEGIN
 SELECT * INTO s FROM game_instance.rules_session WHERE session_id='${id}';
 FOREACH mode IN ARRAY ARRAY['seed','position'] LOOP
  n:=jsonb_set(s.checkpoint_json::jsonb,'{version}',to_jsonb(s.state_version+1));
  IF mode='seed' THEN n:=jsonb_set(n,'{control,mechanics,random,seed}',to_jsonb(CASE WHEN s.checkpoint_json::jsonb#>>'{control,mechanics,random,seed}'=repeat('0',64) THEN repeat('1',64) ELSE repeat('0',64) END));
  ELSE n:=jsonb_set(n,'{control,mechanics,random,position}','0'); END IF;
  c:=jsonb_build_object('runtimeVersion',s.runtime_version,'definitionFingerprint',s.fingerprint,'sessionId',s.session_id,'beforeVersion',s.state_version,'version',s.state_version+1,'globals','{}'::jsonb,'entities','{}'::jsonb,'removedEntities','[]'::jsonb,'control',n->'control','events','[]'::jsonb,'trace','[]'::jsonb);
  denied:=false; BEGIN CALL game_instance.sp_rules_command_save(s.session_id,s.state_version,n::text,c::text,false,1::bigint,'@owner','rng-tamper-'||mode,'unused','{}',200);
  EXCEPTION WHEN SQLSTATE '22023' THEN denied:=true; END; ASSERT denied;
 END LOOP; END $$`);
 const finished=await decision(2,'guard');assert.equal(finished.result.view.state.status,'FINISHED');assert.equal(finished.result.view.state.entities.bob.resources.health,0);
 assert.equal(stored().control.mechanics.random.position,8);assert.equal(finished.result.view.state.mechanics.rolls[rollId].active,false);
 const history=await api(1,endpoint()+'/history/'+rolled.view.state.version);assert.deepEqual(history.state.mechanics.rolls[rollId].dice,dice);
 console.log('PASS: random rollback after actual in-transaction process kill, deterministic reroll retry, single resource payment, SQL anti-reseed/rewind guards and outcome replay.');
}finally{if(id)await api(1,endpoint(),'DELETE',undefined,204);}}
main().catch(error=>{console.error(error);process.exitCode=1;});
