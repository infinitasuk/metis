// The runner supplies disposable labeled app/database containers. Never use production.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync,spawn}=require('node:child_process');
const {randomUUID}=require('node:crypto');
let base=process.env.TEST_BASE_URL;
const db=process.env.TEST_DB_CONTAINER,app=process.env.TEST_APP_CONTAINER,database=process.env.TEST_DB_NAME;
assert.ok(db?.startsWith('metis-rules-')&&app?.startsWith('metis-rules-')&&database==='metis_rules_test','Disposable rules environment required');
const docker=args=>execFileSync('docker',args,{encoding:'utf8'}).trim();
const sql=statement=>docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-tAc',statement]);
const cookies={},sessions=[];
async function login() {
 for(const user of [1,2,3,4,5]) {
  const response=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/`});
  assert.equal(response.status,302);cookies[user]=response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');
 }
}
async function request(user,url,method='GET',body,expected=200) {
 const response=await fetch(base+url,{method,headers:{Cookie:cookies[user]||'','Content-Type':'application/json'},body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const text=await response.text();assert.equal(response.status,expected,`${method} ${url}: ${text}`);
 if(user&&expected!==405) { assert.equal(response.headers.get('cache-control'),'no-store');assert.equal(response.headers.get('x-metis-user-id'),String(user)); }
 return text?JSON.parse(text):null;
}
const url=game=>`/api/v1/rules/sessions/${game.id}`;
const state=(game,user=1)=>request(user,url(game));
async function publish(example,revision='1',key,edit) {
 const source=JSON.parse(fs.readFileSync(path.resolve(__dirname,`../../../../engine-core/src/main/resources/rules/examples/${example}.json`),'utf8'));
 source.id=key || `m3-${randomUUID()}`;source.revision=revision;
 if(edit)edit(source);
 const draft=await request(5,'/api/v1/admin/rules/definitions','POST',source,201);
 const published=await request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});return {source,draft,published};
}
async function create(definition,options={}) {
 const game=await request(1,'/api/v1/rules/sessions','POST',{definitionId:definition.id,visibility:'private',seats:options.seats || [{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3]},201);
 sessions.push(game.id);return game;
}
async function command(game,user,type,extra={},expected=200,key=randomUUID(),version) {
 const current=version??(await state(game,user)).state.version;
 const body={type,commandId:key,expectedStateVersion:current,...extra};
 return {body,result:await request(user,url(game)+'/commands','POST',body,expected)};
}
const counts=game=>JSON.parse(sql(`SELECT json_build_object('version',state_version,'commits',(SELECT count(*) FROM game_instance.rules_transition WHERE session_id='${game.id}'),'receipts',(SELECT count(*) FROM game_instance.rules_command WHERE session_id='${game.id}')) FROM game_instance.rules_session WHERE session_id='${game.id}'`));
async function eventually(check,label) {
 const until=Date.now()+30000;
 while(Date.now()<until) { if(await check())return;await new Promise(resolve=>setTimeout(resolve,100)); }
 throw Error(`Timeout: ${label}`);
}
async function restart(kill=true) {
 docker(kill?['kill','--signal=KILL',app]:['stop',app]);docker(['start',app]);
 const port=docker(['port',app,'8080/tcp']);assert.match(port,/^127\.0\.0\.1:\d+$/);base='http://'+port;
 await eventually(async()=>{try{return (await fetch(base+'/login',{signal:AbortSignal.timeout(1000)})).status===200;}catch{return false;}},'restarted application');
 await login();
}
async function main() {
 await login();docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-f','/tests/rules_faults.sql']);
 try {
  await request(null,'/api/v1/rules/definitions','GET',undefined,401);
  await request(1,'/api/v1/admin/rules/definitions','GET',undefined,403);
  const invalid={languageVersion:1,id:`invalid-${randomUUID()}`,revision:'1'};
  const bad=await request(5,'/api/v1/admin/rules/definitions','POST',invalid,201);
  assert.equal((await request(5,`/api/v1/admin/rules/definitions/${bad.id}/validate`,'POST')).valid,false);
  await request(5,`/api/v1/admin/rules/definitions/${bad.id}/publish`,'POST',{expectedVersion:0},422);
  assert.equal((await request(5,`/api/v1/admin/rules/definitions/${bad.id}`)).lifecycle,'draft');
  const race=await publish('resource-race'),reaction=await publish('reaction-race');
  const repaired=await request(5,`/api/v1/admin/rules/definitions/${bad.id}`,'PUT',{expectedVersion:0,source:{...race.source,id:bad.key}});
  assert.equal(repaired.version,1);await request(5,`/api/v1/admin/rules/definitions/${bad.id}/publish`,'POST',{expectedVersion:0},409);
  await request(5,`/api/v1/admin/rules/definitions/${bad.id}/publish`,'POST',{expectedVersion:1});
  await request(5,`/api/v1/admin/rules/definitions/${race.published.id}`,'PUT',{expectedVersion:race.published.version,source:race.source},409);
  await request(5,`/api/v1/admin/rules/definitions/${bad.id}/publish`,'POST',{},400);
  const catalogue=await request(1,'/api/v1/rules/definitions');assert.ok(catalogue.some(d=>d.id===race.published.id));assert.ok(catalogue.every(d=>d.source===null&&d.lifecycle==='published'));
  const precise={...race.source,id:`precise-${randomUUID()}`,state:{...race.source.state,exact:{type:'decimal',initial:'EXACT_NUMBER',visibility:'PUBLIC'}}};
  const preciseJson=JSON.stringify(precise).replace('"EXACT_NUMBER"','0.1234567890123456789012345678901234');
  const preciseDraft=await request(5,'/api/v1/admin/rules/definitions','POST',preciseJson,201);assert.ok(preciseDraft.source.includes('0.1234567890123456789012345678901234'));
  await request(5,`/api/v1/admin/rules/definitions/${preciseDraft.id}/publish`,'POST',{expectedVersion:0});
  const game=await create(reaction.published);await request(4,url(game),'GET',undefined,404);
  sql(`DO $$ DECLARE denied boolean:=false; BEGIN
   BEGIN UPDATE game_engine.rules_definition SET source_json='{}' WHERE definition_id=${reaction.published.id}; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END;
   ASSERT denied; denied:=false;
   BEGIN UPDATE game_instance.rules_session SET captured_definition='{}' WHERE session_id='${game.id}'; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END;
   ASSERT denied; END $$`);
  await command(game,1,'start');const prefix=await command(game,1,'act',{actor:'alice',action:'collect',parameters:{counter:'alice'}});
  assert.equal(prefix.result.code,'WAITING_FOR_DECISION');assert.equal(prefix.result.view.state.entities.alice.resources.energy,1);
  for(const user of [1,2,3,5]) {
   const current=await state(game,user);const text=JSON.stringify(current);
   assert.ok(!text.includes('DECK_SERVER_SENTINEL'));assert.ok(!text.includes('continuation'));assert.ok(!text.includes('captured_definition'));
   assert.equal(current.state.decision.options.length,user===1?2:0);
   assert.deepEqual(await request(user,url(game)+'/state'),current);
   const history=await request(user,url(game)+'/history/2');assert.deepEqual(history,current);
   const events=await request(user,url(game)+'/events');assert.deepEqual(events,current.state.events);
  }
  for(const user of [2,3,5])await command(game,user,'respond',{actor:'alice',decisionId:prefix.result.view.state.decision.id,option:0},403);
  await request(1,url(game),'PATCH',{checkpoint_json:{}},405);
  const paused=await command(game,1,'pause');assert.equal(paused.result.view.paused,true);
  await command(game,1,'respond',{actor:'alice',decisionId:prefix.result.view.state.decision.id,option:0},422);
  await command(game,1,'resume');
  const plan=await command(game,1,'respond',{actor:'alice',decisionId:prefix.result.view.state.decision.id,option:0});
  assert.equal(plan.result.view.state.entities.alice.attributes.plan.value,'OWNER_ALPHA_SENTINEL');assert.equal(plan.result.view.state.decision.player,'bob');
  for(const user of [2,3,5])assert.ok(!JSON.stringify(await state(game,user)).includes('OWNER_ALPHA_SENTINEL'));
  assert.deepEqual(await request(1,url(game)+'/commands','POST',prefix.body),prefix.result,'Retry returns original projected response even after later state advances');
  await request(1,url(game)+'/commands','POST',{...prefix.body,parameters:{counter:'bob'}},409);
  const bob=await state(game,2);assert.equal((await request(2,url(game)+'/actions')).actors[0].controls[0],'respond');
  const guard=await command(game,2,'respond',{actor:'bob',decisionId:bob.state.decision.id,option:0});assert.equal(guard.result.view.state.decision.player,'alice');
  assert.equal(guard.result.view.state.entities.bob.resources.energy,1);
  const beforeRestart=await state(game);const beforeCounts=counts(game);await restart();
  assert.deepEqual(await state(game),beforeRestart);assert.deepEqual(await request(2,url(game)+'/commands','POST',guard.body),guard.result);assert.deepEqual(counts(game),beforeCounts);
  const finished=await command(game,1,'respond',{actor:'alice',decisionId:beforeRestart.state.decision.id,option:0});assert.equal(finished.result.view.state.status,'FINISHED');
  assert.equal(finished.result.view.state.entities.alice.attributes.score.value,1);assert.equal(finished.result.view.state.entities.bob.resources.energy,1);
  assert.equal(finished.result.view.state.globals.guarded.value,true);assert.equal(finished.result.view.state.globals.acknowledged.value,true);
  assert.deepEqual((await request(1,url(game)+'/history/'+finished.result.view.state.version)).state,finished.result.view.state);
  sql(`DO $$ DECLARE denied boolean:=false; BEGIN
   BEGIN UPDATE game_instance.rules_command SET response_json='{}' WHERE session_id='${game.id}'; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END;
   ASSERT denied; denied:=false;
   BEGIN UPDATE game_instance.rules_transition SET commit_json='{}' WHERE session_id='${game.id}'; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END;
   ASSERT denied; END $$`);
  const v2=await publish('reaction-race','2',reaction.source.id);assert.notEqual(v2.published.fingerprint,reaction.published.fingerprint);assert.equal((await state(game)).fingerprint,reaction.published.fingerprint);
  const ownerOnly=await create(race.published,{seats:[{playerId:'alice',userId:2},{playerId:'bob',userId:3}]});await command(ownerOnly,1,'start');await command(ownerOnly,1,'act',{actor:'alice',action:'collect',parameters:{counter:'alice'}},403);
  const pinned=await create(race.published);await command(pinned,1,'start');
  await publish('resource-race','2',race.source.id,source=>{source.state.target.initial=50;});
  for(const [actor,action,user] of [['alice','boost',1],['bob','collect',2],['alice','boost',1],['bob','collect',2],['alice','collect',1]])
   await command(pinned,user,'act',{actor,action,parameters:{counter:actor}});
  assert.equal((await state(pinned)).state.status,'FINISHED','Existing session still uses captured goal 5, not newly published goal 50');
  const hotseat=await create(race.published,{seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:1}]});
  await command(hotseat,1,'start',{},200,'same-key');
  await command(hotseat,1,'act',{actor:'alice',action:'collect',parameters:{counter:'alice'}},200,'same-key');
  await command(hotseat,1,'act',{actor:'bob',action:'collect',parameters:{counter:'bob'}},200,'same-key');assert.equal(counts(hotseat).commits,3);
  console.log('PASS: interpreted publication, immutable capture, exact numeric source, seat-bound commands, projected state/history/actions, private decisions, nested reactions, lifecycle and idempotency.');

  for(const duplicate of [false,true]) {
   const concurrent=await create(race.published);await command(concurrent,1,'start');const a={type:'act',actor:'alice',action:'collect',parameters:{counter:'alice'},expectedStateVersion:1,commandId:randomUUID()};
   const b={...a,commandId:duplicate?a.commandId:randomUUID()};
   const responses=await Promise.all([a,b].map(async body=>{const r=await fetch(base+url(concurrent)+'/commands',{method:'POST',headers:{Cookie:cookies[1],'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,body:await r.json()};}));
   assert.deepEqual(responses.map(r=>r.status).sort(),duplicate?[200,200]:[200,409]);if(duplicate)assert.deepEqual(responses[0].body,responses[1].body);
   assert.equal((await state(concurrent)).state.entities.alice.attributes.score.value,1);assert.equal(counts(concurrent).commits,2);
   const stable=counts(concurrent);await request(1,url(concurrent)+'/preview','POST',{actor:'alice',action:'collect',parameters:{counter:'alice'},expectedStateVersion:2});assert.deepEqual(counts(concurrent),stable);
  }
  const rollback=await create(race.published);await command(rollback,1,'start');const baseline=await state(rollback);const baselineCounts=counts(rollback);
  sql(`INSERT INTO rules_test_support.fault VALUES('${rollback.id}','raise')`);
  const body={type:'act',actor:'alice',action:'collect',parameters:{counter:'alice'},expectedStateVersion:1,commandId:randomUUID()};
  const failed=await request(1,url(rollback)+'/commands','POST',body,503);assert.ok(!JSON.stringify(failed).includes('PRIVATE_DB_SENTINEL'));
  assert.deepEqual(await state(rollback),baseline);assert.deepEqual(counts(rollback),baselineCounts);
  sql(`DELETE FROM rules_test_support.fault WHERE session_id='${rollback.id}'`);await request(1,url(rollback)+'/commands','POST',body);assert.equal(counts(rollback).commits,2);
  console.log('PASS: transactional rollback, two-writer conflicts, concurrent identical retries, no partial checkpoint/commit/receipt and read-only preview.');

  const lost=await create(race.published);await command(lost,1,'start');
  const lostBody={type:'act',actor:'alice',action:'collect',parameters:{counter:'alice'},expectedStateVersion:1,commandId:randomUUID()};
  let committedResponse,proxyFailure;
  const proxy=require('node:http').createServer(async(req,res)=>{
   try {
    const upstream=await fetch(base+url(lost)+'/commands',{method:'POST',headers:{Cookie:cookies[1],'Content-Type':'application/json'},body:JSON.stringify(lostBody)});
    assert.equal(upstream.status,200);committedResponse=await upstream.json();
   }catch(error){proxyFailure=error;}finally{res.destroy();}
  });
  await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));
  try {
   await assert.rejects(fetch(`http://127.0.0.1:${proxy.address().port}/lost-ack`,{method:'POST',body:JSON.stringify(lostBody)}));
   assert.equal(proxyFailure,undefined);assert.ok(committedResponse);assert.equal(counts(lost).commits,2);
   assert.deepEqual(await request(1,url(lost)+'/commands','POST',lostBody),committedResponse);assert.equal(counts(lost).commits,2);
  } finally {await new Promise(resolve=>proxy.close(resolve));}

  const crash=await create(race.published);await command(crash,1,'start');const crashBefore=await state(crash),crashCounts=counts(crash);
  sql(`INSERT INTO rules_test_support.fault VALUES('${crash.id}','wait')`);
  // Holding this advisory lock is an explicit barrier; sleeps do not decide correctness.
  const holder=spawn('docker',['exec',db,'psql','-X','-U','metis','-d',database,'-c',"SET application_name='rules-test-gate'; SELECT pg_advisory_lock(771236); SELECT pg_sleep(120)"],{stdio:'ignore'});
  try {
   await eventually(()=>sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='rules-test-gate' AND wait_event='PgSleep'")==='1','test gate acquired');
   const crashBody={...body,commandId:randomUUID()};
   const inFlight=fetch(base+url(crash)+'/commands',{method:'POST',headers:{Cookie:cookies[1],'Content-Type':'application/json'},body:JSON.stringify(crashBody),signal:AbortSignal.timeout(30000)}).then(r=>r.text()).catch(()=>null);
   await eventually(()=>sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=771236 AND NOT granted")==='1','command blocked after checkpoint update');
   await restart();await inFlight;
   assert.deepEqual(await state(crash),crashBefore);assert.deepEqual(counts(crash),crashCounts);
   sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='rules-test-gate'");
   sql(`DELETE FROM rules_test_support.fault WHERE session_id='${crash.id}'`);
   const recovered=await request(1,url(crash)+'/commands','POST',crashBody);assert.equal(recovered.view.state.entities.alice.attributes.score.value,1);
   assert.deepEqual(await request(1,url(crash)+'/commands','POST',crashBody),recovered);assert.equal(counts(crash).commits,2);
  } finally {sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='rules-test-gate'");holder.kill();}
  console.log('PASS: durable restart at nested decision, post-commit retry recovery, and actual process kill during an uncommitted transition.');
 } finally {for(const id of sessions.reverse())await request(1,`/api/v1/rules/sessions/${id}`,'DELETE',undefined,204);}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
