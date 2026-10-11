const assert=require('node:assert/strict');
const {randomUUID}=require('node:crypto');
const {spawn}=require('node:child_process');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
let browser;const presets=[];
const literal=v=>Array.isArray(v.value)?v.value.map(literal):v.value&&typeof v.value==='object'?v.value.id:v.value;
const input=offer=>Object.fromEntries(Object.entries(offer.parameters).map(([key,value])=>[key,literal(value)]));
const unit=actor=>actor==='alice'?'seat0-ship0':'seat1-ship0',user=actor=>actor==='alice'?1:2;
const stored=id=>JSON.parse(h.sql(`SELECT checkpoint_json FROM game_instance.rules_session WHERE session_id='${id}'`));
const pose=(actor,speed,yaw=Array(speed).fill(0))=>({entity:unit(actor),speed,yaw,side:'PORT'});
async function create(definitionId,rosters,cpu=false) {
 const game=await h.request(1,'/api/v1/rules/sessions','POST',{definitionId,visibility:'private',seats:cpu?[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}]:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3],rosters},201);
 h.sessions.push(game.id);await h.command(game.id,1,{type:'start'});return game.id;
}
async function next(id) {
 const view=await h.state(id);if(view.state.status==='FINISHED')return view;
 if(view.state.decision) { const d=view.state.decision;return (await h.command(id,user(d.player),{type:'respond',actor:d.player,decisionId:d.id,option:0})).view; }
 for(const number of [1,2])for(const actor of (await h.request(number,h.route(id)+'/actions')).actors) {
  if(actor.controls.includes('activate'))return (await h.command(id,number,{type:'activate',actor:actor.actor,entityId:actor.entities[0]})).view;
  if(actor.offered.length){const offer=actor.offered[0];return (await h.command(id,number,{type:'act',actor:actor.actor,action:offer.action,parameters:input(offer)})).view;}
  if(actor.controls.includes('pass'))return (await h.command(id,number,{type:'pass',actor:actor.actor})).view;
 }
 assert.fail('Navigation course has no progress command');
}
async function deploy(id) { for(let i=0;(await h.state(id)).state.phase==='deployment';i++){assert.ok(i<8);await next(id);}assert.equal((await h.state(id)).state.phase,'planning'); }
const plan=(id,actor,orders=['navigate'])=>h.command(id,user(actor),{type:'act',actor,action:'planOrders',parameters:{entity:unit(actor),orders}});
async function planBoth(id) { await plan(id,'alice');await plan(id,'bob'); }
async function reveal(id,actor) { await h.command(id,user(actor),{type:'activate',actor,entityId:unit(actor)});return (await h.command(id,user(actor),{type:'act',actor,action:'revealCommand',parameters:{entity:unit(actor)}})).view; }
async function choose(id,actor,choice) { const d=(await h.state(id,user(actor))).state.decision;return (await h.command(id,user(actor),{type:'respond',actor,decisionId:d.id,option:d.options.findIndex(v=>literal(v)===choice)})).view; }
const move=(id,actor,action,speed,yaw)=>h.command(id,user(actor),{type:'act',actor,action,parameters:pose(actor,speed,yaw)});
async function bobFinishes(id) { const view=await reveal(id,'bob');if(view.state.decision)await choose(id,'bob','keepDial');await move(id,'bob','maneuver',2); }
async function finish(id) {
 for(let i=0;i<160;i++) {
  const view=await h.state(id);
  if(view.state.status==='FINISHED') {
   assert.equal(view.state.outcome,'maneuver-training-complete');
   for(const number of [1,2,3,5]) { const current=await h.state(id,number);assert.deepEqual((await h.request(number,h.route(id)+'/history/'+current.state.version)).state,current.state); }
   return view;
  }
  await next(id);
 }
 assert.fail('Navigation course exceeded its command bound');
}
async function preview(id,actor,action,parameters) { return h.request(user(actor),h.route(id)+'/preview','POST',{actor,action,parameters,expectedStateVersion:(await h.state(id,user(actor))).state.version}); }
async function reject(id,actor,action,parameters,code) {
 const before=stored(id),p=await preview(id,actor,action,parameters);assert.equal(p.allowed,false);assert.equal(p.code,code);assert.equal(p.scope,'MOVEMENT');
 const result=await h.command(id,user(actor),{type:'act',actor,action,parameters},422);assert.equal(result.code,code);assert.deepEqual(stored(id),before);
}
async function main() {
 await h.login();
 try {
  const version=Number(h.sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));
  const importBody={gameVersionId:version,pack:'armada-ship-navigation-v1'};
  const [entries,again]=await Promise.all([h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody),h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody)]);assert.deepEqual(entries,again);assert.equal(entries.length,2);
  const entry=entries.find(e=>e.mode==='short-course'),roster=await h.request(1,`/api/v1/rules/catalogue/${entry.id}/roster`);
  const rosters=[{policy:'rebel',units:[roster.units[0].code]},{policy:'imperial',units:[roster.units[1].code]}];assert.deepEqual(roster.units.map(u=>u.points),[44,44]);
  const capture=JSON.parse(h.sql(`SELECT source_snapshot FROM game_engine.rules_catalogue_entry WHERE entry_id=${entry.id}`));assert.deepEqual(capture.units.map(u=>u.commandValue),[1,1]);assert.equal(capture.rulesSource.sha256,'c48ee9e97676aec77e998b58d36fe88e3024740a7206060352f64531c472e7fb');
  for(const profile of capture.units){assert.deepEqual(profile.commandSource.provenance,profile.pointsSource);assert.equal(profile.commandSource.rawValue,'1');}
  const old=(await h.request(1,'/api/v1/rules/catalogue')).find(e=>e.pack==='armada-ship-maneuvers-v1'&&e.mode==='standard');assert.ok(old);
  const preset=await h.request(1,'/api/v1/rules/fleet-presets','POST',{name:'Reusable Navigate corvette',definitionId:old.definitionId,seat:0,...rosters[0]},201);presets.push(preset.id);
  const reused=await h.request(1,`/api/v1/rules/fleet-presets/${preset.id}/load`,'POST',{definitionId:entry.definitionId,seat:0});assert.equal(reused.valid,true);assert.equal(reused.points,44);assert.equal(reused.revisionChanged,true);
  const game=await create(entry.definitionId,rosters);await deploy(game);await plan(game,'alice',['repair']);const checkpoint=stored(game),seed=checkpoint.control.mechanics.random.seed;
  assert.deepEqual(literal((await h.state(game)).state.entities[unit('alice')].attributes.orders),['repair']);
  for(const number of [2,3,5]) {
   const view=await h.state(game,number);assert.ok(!('orders' in view.state.entities[unit('alice')].attributes));assert.equal(view.state.events.filter(e=>e.type==='game.commandRevealed').length,0);
   assert.ok(!JSON.stringify(view).includes(seed));assert.ok(!('orders' in (await h.request(number,h.route(game)+'/history/'+view.state.version)).state.entities[unit('alice')].attributes));
   if(number!==2)assert.equal((await h.request(number,h.route(game)+'/actions')).actors.length,0);
  }
  await h.command(game,1,{type:'act',actor:'alice',action:'planOrders',parameters:{entity:unit('alice'),orders:['navigate']}},422);
  await h.command(game,2,{type:'act',actor:'alice',action:'planOrders',parameters:{entity:unit('alice'),orders:['navigate']}},403);assert.deepEqual(stored(game),checkpoint);
  await h.restart();assert.deepEqual(stored(game),checkpoint);await plan(game,'bob');await reveal(game,'alice');
  for(const number of [1,2,3,5])assert.equal(literal((await h.state(game,number)).state.entities[unit('alice')].attributes.revealedCommand),'repair');
  await reject(game,'alice','maneuver',pose('alice',3),'SCENARIO_REQUIREMENT');await move(game,'alice','maneuver',2);
  const pending=await reveal(game,'bob');assert.equal(pending.state.decision.player,'bob');await choose(game,'bob','bankToken');
  assert.equal((await h.state(game,2)).state.entities[unit('bob')].resources.navigateToken,1);
  await reject(game,'bob','navigateToken',pose('bob',3,[1,0,0]),'MANEUVER_YAW');await move(game,'bob','navigateToken',1);
  const roundTwo=await h.state(game);assert.equal(roundTwo.state.phase,'planning');assert.equal(literal(roundTwo.state.entities[unit('bob')].attributes.speed),1);assert.equal(roundTwo.state.entities[unit('bob')].resources.navigateToken,0);await finish(game);
  console.log('PASS: Navigate source capture and reusable fleets, private FIFO planning/reveal, persistent speed and token-only limits survive restart without leaking plans.');

  const recovery=await create(entry.definitionId,rosters);await deploy(recovery);await planBoth(recovery);const decision=await reveal(recovery,'alice');
  const bank={type:'respond',actor:'alice',commandId:randomUUID(),expectedStateVersion:decision.state.version,decisionId:decision.state.decision.id,option:decision.state.decision.options.findIndex(v=>literal(v)==='bankToken')};
  const beforeBank=stored(recovery);await h.request(3,h.route(recovery)+'/commands','POST',bank,403);await h.request(2,h.route(recovery)+'/commands','POST',{...bank,actor:'bob',commandId:randomUUID()},422);
  h.sql(`INSERT INTO rules_test_support.fault VALUES('${recovery}','raise')`);
  try{await h.request(1,h.route(recovery)+'/commands','POST',bank,503);assert.deepEqual(stored(recovery),beforeBank);}finally{h.sql(`DELETE FROM rules_test_support.fault WHERE session_id='${recovery}'`);}
  await h.restart();assert.deepEqual(stored(recovery),beforeBank);const banked=await h.request(1,h.route(recovery)+'/commands','POST',bank);assert.deepEqual(await h.request(1,h.route(recovery)+'/commands','POST',bank),banked);
  assert.equal(banked.view.state.entities[unit('alice')].resources.navigateToken,1);assert.equal(banked.view.state.entities[unit('alice')].resources.navigateDial,0);
  await move(recovery,'alice','maneuver',2);await bobFinishes(recovery);assert.equal((await h.state(recovery)).state.entities[unit('alice')].resources.navigateToken,1);
  await planBoth(recovery);await reveal(recovery,'alice');await choose(recovery,'alice','keepDial');
  await reject(recovery,'alice','navigateDialAndToken',{...pose('alice',4),side:'invalid'},'MANEUVER_SIDE');
  await reject(recovery,'alice','navigateDialAndToken',pose('alice',3,[1,2,0]),'SCENARIO_REQUIREMENT');
  const beforePayment=stored(recovery),beforeView=await h.state(recovery),paymentPreview=await preview(recovery,'alice','navigateDialAndToken',pose('alice',4));assert.equal(paymentPreview.allowed,true);assert.equal(paymentPreview.resources.length,2);assert.deepEqual(stored(recovery),beforePayment);
  const payment={type:'act',actor:'alice',action:'navigateDialAndToken',parameters:pose('alice',4),commandId:randomUUID(),expectedStateVersion:beforeView.state.version};
  h.sql(`INSERT INTO rules_test_support.fault VALUES('${recovery}','wait')`);
  const holder=spawn('docker',['exec',h.db,'psql','-X','-U','metis','-d',h.database,'-c',"SET application_name='navigate-test-gate'; SELECT pg_advisory_lock(771236); SELECT pg_sleep(120)"],{stdio:'ignore'});
  try {
   await h.eventually(()=>h.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='navigate-test-gate' AND wait_event='PgSleep'")==='1','Navigate transaction gate acquired');
   const inFlight=fetch(h.base+h.route(recovery)+'/commands',{method:'POST',headers:{Cookie:h.cookies[1],'Content-Type':'application/json'},body:JSON.stringify(payment),signal:AbortSignal.timeout(30000)}).then(r=>r.text()).catch(()=>null);
   await h.eventually(()=>h.sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=771236 AND NOT granted")==='1','Navigate write reached transaction barrier');
   await h.restart();await inFlight;assert.deepEqual(stored(recovery),beforePayment);
   h.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='navigate-test-gate'");h.sql(`DELETE FROM rules_test_support.fault WHERE session_id='${recovery}'`);
   const paid=await h.request(1,h.route(recovery)+'/commands','POST',payment);assert.deepEqual(await h.request(1,h.route(recovery)+'/commands','POST',payment),paid);
   const entity=paid.view.state.entities[unit('alice')];assert.equal(entity.resources.navigateDial,0);assert.equal(entity.resources.navigateToken,0);assert.equal(literal(entity.attributes.speed),4);
   assert.deepEqual({x:literal(entity.attributes.x),y:literal(entity.attributes.y),heading:literal(entity.attributes.heading)},paymentPreview.movements[0].to);
   assert.deepEqual((await h.request(1,h.route(recovery)+'/history/'+beforeView.state.version)).state,beforeView.state);assert.deepEqual((await h.request(1,h.route(recovery)+'/history/'+paid.view.state.version)).state,paid.view.state);
  }finally{h.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='navigate-test-gate'");h.sql(`DELETE FROM rules_test_support.fault WHERE session_id='${recovery}'`);holder.kill();}
  await finish(recovery);const cpu=await create(entry.definitionId,[rosters[1],rosters[0]],true);
  await h.eventually(async()=>{const view=await h.state(cpu);assert.equal(view.paused,false,view.cpu.pauseReason);return view.state.status==='FINISHED';},'Navigate CPU game completes',60000);
  const cpuEnd=await h.state(cpu);assert.equal(cpuEnd.state.events.filter(e=>e.type==='game.navigationResolved').length,6);assert.deepEqual((await h.request(1,h.route(cpu)+'/history/'+cpuEnd.state.version)).state,cpuEnd.state);assert.equal(stored(cpu).control.mechanics.random.position,0);
  console.log('PASS: Navigate bank decisions and combined payment are atomic across SQL failures, process kills, exact retries, human/CPU completion and replay.');

  const browserGame=await create(entry.definitionId,rosters);await deploy(browserGame);
  browser=await chromium.launch({headless:true});const context=await browser.newContext();await context.request.post(h.base+'/login',{form:{userId:'1',next:'/rules'},maxRedirects:0});const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(h.base+'/rules/sessions/'+browserGame);await page.getByLabel('orders',{exact:true}).waitFor();await page.getByLabel('orders',{exact:true}).fill('["navigate"]');
  await plan(browserGame,'bob',['repair']);await page.locator('#refresh').click();assert.equal(await page.getByLabel('orders',{exact:true}).inputValue(),'["navigate"]');await page.reload();await page.getByLabel('orders',{exact:true}).waitFor();assert.equal(await page.getByLabel('orders',{exact:true}).inputValue(),'["navigate"]');
  await page.locator('#execute').click();await page.locator('[data-activate="seat0-ship0"]').waitFor();await page.locator('[data-activate="seat0-ship0"]').click();
  await page.waitForFunction(()=>document.querySelector('#action').value==='revealCommand');await page.locator('#execute').click();await page.locator('#respond').waitFor();
  await page.locator('#decision-option').selectOption('1');await page.reload();await page.locator('#respond').waitFor();assert.equal(await page.locator('#decision-option').inputValue(),'1');await page.locator('#respond').click();
  await page.locator('#maneuver-joints').waitFor();await page.locator('#action').selectOption('maneuver');assert.deepEqual(await page.getByLabel('speed',{exact:true}).locator('option').evaluateAll(options=>options.map(o=>o.value)),['2']);
  assert.ok((await page.locator('[data-maneuver-note]').innerText()).includes('Maintain current speed'));
  await page.locator('#action').selectOption('navigateToken');assert.deepEqual(await page.getByLabel('speed',{exact:true}).locator('option').evaluateAll(options=>options.map(o=>o.value)),['2','1','3']);
  await page.getByLabel('speed',{exact:true}).selectOption('3');assert.equal(await page.getByLabel('Joint 1 yaw',{exact:true}).locator('option').count(),1);
  await page.getByLabel('speed',{exact:true}).selectOption('1');await page.getByLabel('Joint 1 yaw',{exact:true}).selectOption('0');await page.locator('#preview').click();await page.locator('.maneuver-destination').waitFor();
  const browserPreview=JSON.parse(await page.locator('#preview-result').innerText());assert.equal(browserPreview.resources.length,1);assert.equal(browserPreview.resources[0].resource,'navigateToken');assert.ok((await page.locator('#maneuver-measurement').innerText()).includes('1 → 0'));
  assert.equal((await h.state(browserGame)).state.entities[unit('alice')].resources.navigateToken,1);
  if(process.env.TEST_ARTIFACT_DIR)await page.screenshot({path:path.join(process.env.TEST_ARTIFACT_DIR,'navigate-command-preview.png'),fullPage:true});
  const beforeBrowserMove=await h.state(browserGame);let acknowledged,rejectAck;const lost=new Promise((resolve,reject)=>{acknowledged=resolve;rejectAck=reject;});
  await page.route('**/commands',async route=>{try{const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');acknowledged();}catch(error){rejectAck(error);}});
  await Promise.all([page.locator('#execute').click(),lost]);await page.locator('#pending-request').waitFor({state:'visible'});const once=stored(browserGame);
  await page.unroute('**/commands');await page.reload();await page.locator('#pending-request').waitFor({state:'visible'});await page.locator('#retry-command').click();await page.locator('#pending-request').waitFor({state:'hidden'});assert.deepEqual(stored(browserGame),once);
  const afterBrowserMove=await h.state(browserGame);assert.equal(afterBrowserMove.state.entities[unit('alice')].resources.navigateToken,0);assert.equal(literal(afterBrowserMove.state.entities[unit('alice')].attributes.speed),1);assert.equal(afterBrowserMove.state.events.filter(e=>e.type==='game.navigationResolved').length,1);
  const history=await h.request(1,h.route(browserGame)+'/history/'+beforeBrowserMove.state.version);assert.equal(history.state.entities[unit('alice')].resources.navigateToken,1);
  await page.locator('#history').evaluate((input,value)=>{input.value=String(value);input.dispatchEvent(new Event('change',{bubbles:true}));},beforeBrowserMove.state.version);
  await page.waitForFunction(v=>document.querySelector('#history-label').textContent.startsWith('Replay v'+v),beforeBrowserMove.state.version);assert.equal(await page.locator('#execute').isDisabled(),true);
  assert.deepEqual(errors,[]);console.log('PASS: browser private command drafts, reveal/bank recovery, definition-owned speed/yaw controls and paid previews survive reload, lost acknowledgement and read-only replay.');
 }finally{
  if(browser)await browser.close();
  for(const id of presets){const preset=await h.request(1,'/api/v1/rules/fleet-presets/'+id);await h.request(1,`/api/v1/rules/fleet-presets/${id}?expectedVersion=${preset.version}`,'DELETE',undefined,204);}
  await h.cleanup();
 }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
