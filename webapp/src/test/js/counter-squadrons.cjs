const assert=require('node:assert/strict');
const {randomUUID,createHmac}=require('node:crypto');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
let browser;const presets=[];
const literal=v=>Array.isArray(v.value)?v.value.map(literal):v.value&&typeof v.value==='object'?v.value.id:v.value;
const stored=id=>JSON.parse(h.sql(`SELECT checkpoint_json FROM game_instance.rules_session WHERE session_id='${id}'`));
async function create(definitionId,rosters,cpu=false) {
 const game=await h.request(1,'/api/v1/rules/sessions','POST',{definitionId,visibility:'private',seats:cpu?[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}]:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3],rosters},201);
 h.sessions.push(game.id);return game.id;
}
async function next(id) {
 const view=await h.state(id);
 if(view.state.status==='FINISHED')return view;
 if(view.state.decision) {
  const d=view.state.decision;return (await h.command(id,d.player==='alice'?1:2,{type:'respond',actor:d.player,decisionId:d.id,option:0})).view;
 }
 for(const user of [1,2]) {
  const choices=await h.request(user,h.route(id)+'/actions');
  for(const actor of choices.actors) {
   let command;
   if(actor.controls.includes('activate'))command={type:'activate',actor:actor.actor,entityId:actor.entities[0]};
   else if(actor.offered.length) {const a=actor.offered[0];command={type:'act',actor:actor.actor,action:a.action,parameters:Object.fromEntries(Object.entries(a.parameters).map(([k,v])=>[k,literal(v)]))};}
   else if(actor.controls.includes('pass'))command={type:'pass',actor:actor.actor};
   if(command)return (await h.command(id,user,command)).view;
  }
 }
 assert.fail('Mixed squadron game has no progress command');
}
async function finish(id) {
 for(let i=0;i<350;i++) {const view=await next(id);if(view.state.status==='FINISHED'){assert.deepEqual((await h.request(1,h.route(id)+'/history/'+view.state.version)).state,view.state);return view;}}
 assert.fail('Mixed squadron game exceeded its bounded command count');
}
function face(seed,position) {
 const counter=Buffer.alloc(8);counter.writeBigInt64BE(BigInt(position));
 const ticket=Number((createHmac('sha256',Buffer.from(seed,'hex')).update(counter).digest().readBigUInt64BE()&((1n<<63n)-1n))%8n);
 return ticket<4?0:ticket<6?1:2;
}
async function main() {
 await h.login();
 try {
  const version=Number(h.sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));
  const importBody={gameVersionId:version,pack:'armada-squadron-counter-v1'};
  const [entries,again]=await Promise.all([h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody),h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody)]);
  assert.deepEqual(entries,again);assert.equal(entries.length,3);
  const entry=entries.find(e=>e.mode==='short-battle'),roster=await h.request(1,`/api/v1/rules/catalogue/${entry.id}/roster`);
  assert.equal(h.sql(`SELECT source_snapshot::jsonb #>> '{rulesSource,sha256}' FROM game_engine.rules_catalogue_entry WHERE entry_id=${entry.id}`),'c48ee9e97676aec77e998b58d36fe88e3024740a7206060352f64531c472e7fb');
  assert.deepEqual(roster.units.map(u=>u.points),[8,13,11,11]);assert.deepEqual(roster.seats.map(s=>s.choices.map(p=>p.units.length)),[[2,2],[2,2]]);
  const code=name=>roster.units.find(u=>u.name===name).code;
  const tie=code('TIE Fighter Squadron'),xwing=code('X-Wing Squadron'),awing=code('A-Wing Squadron'),interceptor=code('TIE Interceptor Squadron');
  const resolved=await h.request(5,`/api/v1/admin/rules/definitions/${entry.definitionId}/resolved`);const source=JSON.parse(resolved.source);
  assert.deepEqual(source.state.hulls.initial,[3,5,4,3]);assert.deepEqual(source.state.speeds.initial,[400,300,500,500]);assert.deepEqual(source.state.pools.initial.map(p=>p.length),[3,4,3,4]);
  assert.deepEqual(source.state.counterPools.initial,[[],[],['blue','blue'],['blue','blue']]);assert.deepEqual(source.state.escortProfiles.initial,[false,true,false,false]);
  const mixed=[{policy:'imperial',units:[tie,interceptor]},{policy:'rebel',units:[xwing,awing]}];
  const preset=await h.request(1,'/api/v1/rules/fleet-presets','POST',{name:'Mixed counter patrol',definitionId:entry.definitionId,seat:0,...mixed[0]},201);presets.push(preset.id);
  assert.equal(preset.snapshot.points,19);assert.equal((await h.request(1,`/api/v1/rules/fleet-presets/${preset.id}/load`,'POST',{definitionId:entry.definitionId,seat:0})).valid,true);
  const oldEntry=(await h.request(1,'/api/v1/rules/catalogue')).find(e=>e.pack==='armada-squadron-rosters-v1'&&e.mode==='standard');
  const oldRoster=await h.request(1,`/api/v1/rules/catalogue/${oldEntry.id}/roster`);
  const oldPreset=await h.request(1,'/api/v1/rules/fleet-presets','POST',{name:'Existing fighter patrol',definitionId:oldEntry.definitionId,seat:0,policy:'imperial',units:oldRoster.seats[0].units},201);presets.push(oldPreset.id);
  const imported=await h.request(1,`/api/v1/rules/fleet-presets/${oldPreset.id}/load`,'POST',{definitionId:entry.definitionId,seat:0});assert.equal(imported.valid,true);assert.equal(imported.revisionChanged,true);assert.equal(imported.points,8);
  const game=await create(entry.definitionId,mixed);await h.command(game,1,{type:'start'});
  assert.deepEqual((await h.request(1,h.route(game)+'/roster')).seats.map(s=>s.points),[19,24]);await finish(game);
  const cpu=await create(entry.definitionId,mixed,true);await h.command(cpu,1,{type:'start'});
  await h.eventually(async()=>{const view=await h.state(cpu);assert.equal(view.paused,false,view.cpu.pauseReason);return view.state.status==='FINISHED';},'mixed Counter CPU game completes',90000);
  const cpuEnd=await h.state(cpu);assert.deepEqual((await h.request(1,h.route(cpu)+'/history/'+cpuEnd.state.version)).state,cpuEnd.state);
  console.log('PASS: four source-backed squadron profiles import idempotently, mixed fleets and old/new presets validate, and human/CPU games finish with replay.');

  // A published test mode brings four units into engagement and lowers the Counter defender's hull.
  // All positions/resources are still created by authored setup, never by editing a live checkpoint.
  const mode={modeVersion:1,id:'counter-recovery-'+randomUUID(),revision:'1',extends:{id:source.id,revision:source.revision,fingerprint:roster.fingerprint},overrides:[
   {op:'replace',path:'/state/startX/initial',value:[300,400]},
   {op:'replace',path:'/space/zones/deployment1/x',value:350},{op:'replace',path:'/space/zones/deployment1/width',value:650},
   {op:'replace',path:'/state/deploymentY/initial',value:[200,300,500]},{op:'replace',path:'/state/hulls/initial',value:[3,5,4,1]}
  ]};
  const draft=await h.request(5,'/api/v1/admin/rules/definitions','POST',mode,201);const published=await h.request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});
  const battle=await create(published.id,[{policy:'rebel',units:[awing,xwing]},{policy:'imperial',units:[interceptor,tie]}]);await h.command(battle,1,{type:'start'});
  for(let n=0;(await h.state(battle)).state.phase==='deployment';n++){assert.ok(n<12);await next(battle);}
  await h.command(battle,1,{type:'activate',actor:'alice',entityId:'seat0-unit0'});
  await h.command(battle,1,{type:'act',actor:'alice',action:'attack',parameters:{entity:'seat0-unit0',target:'seat1-unit0'}});
  const pending=await h.state(battle),checkpoint=stored(battle),seed=checkpoint.control.mechanics.random.seed;
  assert.equal(checkpoint.control.mechanics.random.position,3);assert.equal(pending.state.decision.player,'bob');assert.ok(pending.state.decision.options.some(o=>literal(o)==='counter'));
  const response={type:'respond',actor:'bob',commandId:randomUUID(),expectedStateVersion:pending.state.version,decisionId:pending.state.decision.id,option:pending.state.decision.options.findIndex(o=>literal(o)==='counter')};
  await h.request(3,h.route(battle)+'/commands','POST',response,403);
  const wrong=await h.request(1,h.route(battle)+'/commands','POST',{...response,actor:'alice',commandId:randomUUID()},422);assert.equal(wrong.code,'WRONG_RESPONDER');
  assert.deepEqual(stored(battle),checkpoint);
  h.sql(`INSERT INTO rules_test_support.fault VALUES('${battle}','raise')`);
  try{await h.request(2,h.route(battle)+'/commands','POST',response,503);assert.deepEqual(stored(battle),checkpoint);}
  finally{h.sql(`DELETE FROM rules_test_support.fault WHERE session_id='${battle}'`);}
  await h.restart();assert.deepEqual(stored(battle),checkpoint);
  const counter=await h.request(2,h.route(battle)+'/commands','POST',response);
  assert.deepEqual(await h.request(2,h.route(battle)+'/commands','POST',response),counter);
  const counterRoll=Object.values(counter.view.state.mechanics.rolls)[0];assert.equal(counterRoll.owner,'bob');assert.equal(counterRoll.dice.length,2);
  assert.deepEqual(counterRoll.dice.map(d=>d.face),[face(seed,3),face(seed,4)]);assert.equal(stored(battle).control.mechanics.random.position,5);
  assert.equal(counter.view.state.decision.player,'bob');assert.ok(counter.view.state.decision.options.some(o=>literal(o)==='reroll'));
  for(const user of [1,2,3,5]) {const view=await h.state(battle,user);assert.deepEqual(view.state.mechanics.rolls[counterRoll.id].dice,counterRoll.dice);assert.ok(!JSON.stringify(view).includes(seed));}
  const saved=stored(battle);await h.restart();assert.deepEqual(stored(battle),saved);
  console.log('PASS: Counter authorization, transaction rollback, exact dice and nested Swarm survive process restarts and identical command retries.');

  browser=await chromium.launch({headless:true});const contexts=[];const errors=[];
  async function pageFor(user){const context=await browser.newContext();contexts.push(context);await context.request.post(h.base+'/login',{form:{userId:String(user),next:'/rules'},maxRedirects:0});const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));return page;}
  const defender=await pageFor(2);await defender.goto(h.base+'/rules/sessions/'+battle);await defender.locator('#respond').waitFor();
  await defender.locator('#decision-option').selectOption(String(counter.view.state.decision.options.findIndex(o=>literal(o)==='reroll')));await defender.locator('#respond').click();
  await h.eventually(async()=>typeof literal((await h.state(battle,2)).state.decision.options[0])==='number','Counter die selection');
  await defender.waitForFunction(()=>document.querySelector('#decision-option option')?.textContent==='0');
  const beforeReroll=stored(battle);assert.equal(beforeReroll.control.mechanics.random.position,5);
  let acknowledgeAbort,rejectAbort;const lostAcknowledgement=new Promise((resolve,reject)=>{acknowledgeAbort=resolve;rejectAbort=reject;});
  await defender.route('**/commands',async route=>{
   try{const result=await route.fetch();assert.equal(result.status(),200);await route.abort('failed');acknowledgeAbort();}
   catch(error){rejectAbort(error);}
  });
  await Promise.all([defender.locator('#respond').click(),lostAcknowledgement]);await defender.locator('#pending-request').waitFor({state:'visible'});
  await h.eventually(()=>stored(battle).control.mechanics.random.position===6,'lost acknowledgement committed exactly one reroll');
  const afterReroll=stored(battle);await defender.unroute('**/commands');await defender.locator('#retry-command').click();await defender.locator('#pending-request').waitFor({state:'hidden'});
  assert.deepEqual(stored(battle),afterReroll);
  const result=await h.state(battle,2),rerolled=result.state.events.filter(e=>e.type==='rules.diceRerolled');assert.equal(rerolled.length,1);
  assert.deepEqual(literal(rerolled[0].values.faces),[face(seed,5),counterRoll.dice[1].face]);
  assert.equal(result.state.events.filter(e=>e.type==='game.attackResolved').length,2);
  assert.equal(result.state.entities['seat0-unit0'].resources.health,4-[face(seed,5),face(seed,4)].filter(f=>f===0).length);
  assert.equal(afterReroll.control.flow.progress.alice.uses.act,1);assert.deepEqual(afterReroll.control.flow.progress.bob?.uses||{},{});
  const history=await h.request(2,h.route(battle)+'/history/'+counter.view.state.version);assert.deepEqual(history.state.mechanics.rolls[counterRoll.id].dice,counterRoll.dice);

  const player=await pageFor(1);await player.goto(h.base+'/rules');await player.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');
  await player.locator('#definition').selectOption(`c:${entry.id}`);await player.getByLabel('Seat 1 faction',{exact:true}).waitFor();
  await player.getByRole('button',{name:'Add unit to seat 1',exact:true}).click();await player.getByLabel('Seat 1 unit 2',{exact:true}).selectOption(interceptor);
  await player.getByRole('button',{name:'Add unit to seat 2',exact:true}).click();await player.getByLabel('Seat 2 unit 2',{exact:true}).selectOption(awing);
  await player.locator('#roster-check').click();await player.waitForFunction(()=>document.querySelector('[data-roster-result="1"]')?.textContent.includes('valid / 24 of 26'));
  assert.ok((await player.locator('[data-roster-result="0"]').innerText()).includes('valid / 19 of 26'));
  await player.locator('.seat-controller').nth(1).selectOption('1');await player.locator('#create').click();await player.waitForURL('**/rules/sessions/*');const created=player.url().split('/').pop();h.sessions.push(created);
  const captured=await h.request(1,h.route(created)+'/roster');assert.deepEqual(captured.seats.map(s=>s.units.map(u=>u.code)),mixed.map(s=>s.units));
  await player.reload();await player.locator('#starting-rosters').waitFor({state:'visible'});assert.ok((await player.locator('#starting-rosters').innerText()).includes('A-Wing Squadron'));
  assert.deepEqual(errors,[]);console.log('PASS: generic browser builds mixed fleets and resumes Counter/Swarm dice choices after a lost acknowledgement with exact replayed outcomes.');
 }finally{
  if(browser)await browser.close();
  for(const id of presets){const p=await h.request(1,'/api/v1/rules/fleet-presets/'+id);await h.request(1,`/api/v1/rules/fleet-presets/${id}?expectedVersion=${p.version}`,'DELETE',undefined,204);}
  await h.cleanup();
 }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
