const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const h=require('./rules-client-support.cjs');
async function main() {
 await h.login();
 try {
  const definitions={};for(const name of ['grid-skirmish','vector-orders','hidden-grid-cpu'])definitions[name]=await h.publish(name);
  const legacy=await h.publish('resource-race');const invalid=await h.request(1,'/api/v1/rules/sessions','POST',{definitionId:legacy.id,visibility:'private',seats:[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}],viewers:[]},422);assert.equal(invalid.code,'CPU_REQUIRES_PROJECTED_RUNTIME');
  for(const definition of Object.values(definitions)) {
   const id=await h.create(definition,{cpu:true});assert.equal(Number(h.sql(`SELECT count(*) FROM game_instance.fn_rules_cpu_queue() WHERE session_id='${id}'`)),0,'CPU cannot start an unstarted session');
   await h.command(id,1,{type:'start'});
   const done=await h.eventually(async()=>{const v=await h.state(id);return v.state.status==='FINISHED'?v:null;},'CPU completion');
   assert.deepEqual(done.controlledPlayers,[]);assert.ok(done.seats.every(s=>s.kind==='CPU'));assert.ok(done.cpu.steps>0);assert.equal(done.cpu.pauseReason,null);
   const stored=h.snapshot(id);assert.equal(stored.steps,done.cpu.steps);assert.equal(stored.receipts,stored.version);assert.equal(done.state.events.filter(e=>e.type==='rules.cpuCommand').length,done.cpu.steps);
   assert.deepEqual((await h.request(1,h.route(id)+'/history/'+done.state.version)).state,done.state);
   for(const user of [1,3,5]) {
    await h.request(user,h.route(id)+'/commands','POST',{type:'respond',actor:'alice',decisionId:'forged',option:0,expectedStateVersion:done.state.version,commandId:'forged-cpu'},403);
    const workspace=await h.request(user,h.route(id)+'/workspace');assert.deepEqual(workspace.choices.actors,[]);assert.equal(workspace.view.state.mechanics?.random,undefined);
   }
  }
  const limited=await h.create(definitions['hidden-grid-cpu'],{cpu:true,limit:1});await h.command(limited,1,{type:'start'});
  const paused=await h.eventually(async()=>{const v=await h.state(limited);return v.paused?v:null;},'quota pause');assert.equal(paused.cpu.pauseReason,'CPU_COMMAND_LIMIT');assert.equal(paused.cpu.steps,1);assert.equal(paused.state.status,'SETUP');assert.equal(paused.state.decision.player,'bob');assert.deepEqual(paused.state.decision.options,[]);
  for(const user of [1,3,5]) { const v=await h.state(limited,user);assert.equal(v.state.entities.alice.attributes.cell,undefined);assert.deepEqual(v.state.decision.options,[]); }
  const saved=h.snapshot(limited);await h.restart();assert.deepEqual(h.snapshot(limited),saved);assert.deepEqual(await h.state(limited),paused);
  for(let i=0;i<4&&(await h.state(limited)).state.status!=='FINISHED';i++) { await h.command(limited,1,{type:'resume'});await h.eventually(async()=>{const v=await h.state(limited);return v.paused||v.state.status==='FINISHED';},'bounded resume'); }
  assert.equal((await h.state(limited)).state.status,'FINISHED');
  const missing=await h.publish('hidden-grid-cpu',s=>{delete s.actions.guess.choices;s.actions.guess.priority=1;});const stalled=await h.create(missing,{cpu:true});await h.command(stalled,1,{type:'start'});
  const blocked=await h.eventually(async()=>{const v=await h.state(stalled);return v.paused?v:null;},'no-domain pause');assert.equal(blocked.cpu.pauseReason,'CPU_NO_COMMAND');assert.equal(blocked.state.status,'RUNNING');assert.deepEqual(blocked.state.winners,[]);
  const costly=await h.publish('hidden-grid-cpu',s=>{s.entities.target.resources.energy={initial:0,minimum:0,maximum:1,visibility:'PUBLIC'};s.procedures.guess.steps.unshift({op:'core.modifyResource',entity:{var:'ownTarget'},resource:'energy',delta:-1});});const rejected=await h.create(costly,{cpu:true});await h.command(rejected,1,{type:'start'});
  const halted=await h.eventually(async()=>{const v=await h.state(rejected);return v.paused?v:null;},'rejected CPU action pause');assert.equal(halted.cpu.pauseReason,'CPU_ACTION_REJECTED');assert.equal(halted.state.entities.alice.resources.energy,0);assert.equal(halted.state.entities.bob.attributes.hit.value,false);assert.equal(h.snapshot(rejected).receipts,halted.state.version+1);
  console.log('PASS: persistent CPU seats complete all three games, respect seat privacy, pause operationally and recover quota/continuations after restart.');

  const crash=await h.create(definitions['vector-orders'],{cpu:true,limit:2});
  h.sql(`CREATE FUNCTION rules_test_support.cpu_barrier() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
   IF NEW.session_id='${crash}' AND EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.commit_json::jsonb->'events') e WHERE e->>'type'='rules.cpuCommand') THEN PERFORM pg_advisory_xact_lock(771240); END IF; RETURN NEW; END $$;
   CREATE TRIGGER rules_test_cpu BEFORE INSERT ON game_instance.rules_transition FOR EACH ROW EXECUTE FUNCTION rules_test_support.cpu_barrier();`);
  const holder=spawn('docker',['exec',h.db,'psql','-X','-U','metis','-d',h.database,'-c',"SET application_name='rules-cpu-gate'; SELECT pg_advisory_lock(771240); SELECT pg_sleep(120)"],{stdio:'ignore'});
  try {
   await h.eventually(()=>h.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='rules-cpu-gate' AND wait_event='PgSleep'")==='1','CPU barrier acquired');
   await h.command(crash,1,{type:'start'});await h.eventually(()=>h.sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=771240 AND NOT granted")==='1','CPU write blocked');const before=h.snapshot(crash);assert.equal(before.version,1);assert.equal(before.steps,0);
   await h.restart();assert.deepEqual(h.snapshot(crash),before);h.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='rules-cpu-gate'");
   const recovered=await h.eventually(async()=>{const v=await h.state(crash);return v.paused?v:null;},'CPU write recovery');assert.equal(recovered.state.version,4);assert.equal(recovered.cpu.steps,2);assert.equal(h.snapshot(crash).receipts,4);
   assert.equal(Number(h.sql(`SELECT jsonb_array_length(checkpoint_json::jsonb#>'{entities,alice,attributes,orders,value}') FROM game_instance.rules_session WHERE session_id='${crash}'`)),1);
   assert.equal(Number(h.sql(`SELECT jsonb_array_length(checkpoint_json::jsonb#>'{entities,bob,attributes,orders,value}') FROM game_instance.rules_session WHERE session_id='${crash}'`)),1);
  } finally { h.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='rules-cpu-gate'");holder.kill();h.sql('DROP TRIGGER rules_test_cpu ON game_instance.rules_transition; DROP FUNCTION rules_test_support.cpu_barrier();'); }
  console.log('PASS: an actual process kill during a CPU write rolls back checkpoint, receipt, events and quota; retry pays each effect once.');
 } finally { await h.cleanup(); }
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
