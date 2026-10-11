const assert=require('node:assert/strict');
const {randomUUID}=require('node:crypto');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
let browser;const presets=[];
const literal=v=>Array.isArray(v.value)?v.value.map(literal):v.value&&typeof v.value==='object'?v.value.id:v.value;
const inputs=offer=>Object.fromEntries(Object.entries(offer.parameters).map(([key,value])=>[key,literal(value)]));
const stored=id=>JSON.parse(h.sql(`SELECT checkpoint_json FROM game_instance.rules_session WHERE session_id='${id}'`));
async function create(definitionId,rosters,cpu=false) {
 const game=await h.request(1,'/api/v1/rules/sessions','POST',{definitionId,visibility:'private',seats:cpu?[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}]:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3],rosters},201);
 h.sessions.push(game.id);await h.command(game.id,1,{type:'start'});return game.id;
}
async function next(id) {
 for(const user of [1,2]) {
  const actions=await h.request(user,h.route(id)+'/actions');
  for(const actor of actions.actors) {
   if(actor.controls.includes('activate'))return (await h.command(id,user,{type:'activate',actor:actor.actor,entityId:actor.entities[0]})).view;
   if(actor.offered.length) { const offer=actor.offered[0];return (await h.command(id,user,{type:'act',actor:actor.actor,action:offer.action,parameters:inputs(offer)})).view; }
   if(actor.controls.includes('pass'))return (await h.command(id,user,{type:'pass',actor:actor.actor})).view;
  }
 }
 assert.fail('No progress command offered for ship training');
}
async function activate(id) {
 for(let n=0;(await h.state(id)).state.phase==='deployment';n++){assert.ok(n<8);await next(id);}
 await h.command(id,1,{type:'activate',actor:'alice',entityId:'seat0-ship0'});
}
async function finish(id) {
 for(let n=0;n<80;n++) {
  const view=await h.state(id);if(view.state.status==='FINISHED') { assert.equal(view.state.outcome,'maneuver-training-complete');assert.deepEqual((await h.request(1,h.route(id)+'/history/'+view.state.version)).state,view.state);return view; }
  await next(id);
 }
 assert.fail('Ship training exceeded its command bound');
}
const plan=(speed,yaw,side='PORT')=>({entity:'seat0-ship0',speed,yaw,side});
async function preview(id,parameters) { const view=await h.state(id);return h.request(1,h.route(id)+'/preview','POST',{actor:'alice',action:'maneuver',parameters,expectedStateVersion:view.state.version}); }
async function reject(id,parameters,code) {
 const before=stored(id);assert.equal((await preview(id,parameters)).code,code);
 assert.equal((await h.command(id,1,{type:'act',actor:'alice',action:'maneuver',parameters},422)).code,code);assert.deepEqual(stored(id),before);
}
async function main() {
 await h.login();
 try {
  const version=Number(h.sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));
  const body={gameVersionId:version,pack:'armada-ship-maneuvers-v1'};
  const [entries,again]=await Promise.all([h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',body),h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',body)]);
  assert.deepEqual(entries,again);assert.equal(entries.length,2);const entry=entries.find(e=>e.mode==='short-course');
  const roster=await h.request(1,`/api/v1/rules/catalogue/${entry.id}/roster`);assert.equal(roster.selectable,true);assert.deepEqual(roster.units.map(u=>u.points),[44,44]);
  const rosters=[{policy:'rebel',units:[roster.units[0].code]},{policy:'imperial',units:[roster.units[1].code]}];
  const definition=await h.request(5,`/api/v1/admin/rules/definitions/${entry.definitionId}/resolved`),source=JSON.parse(definition.source);
  assert.deepEqual(source.state.charts.initial,[[[],[2],[1,2],[0,1,2],[0,1,1,2]],[[],[2],[2,2],[0,1,1],[0,1,1,1]]]);
  assert.equal(source.space.boards.table.unit,'tts');assert.equal(source.space.bodies.ship.width,2.796);assert.equal(source.state.segment.initial,2.6265);
  const capture=JSON.parse(h.sql(`SELECT source_snapshot FROM game_engine.rules_catalogue_entry WHERE entry_id=${entry.id}`));
  assert.equal(capture.units[0].maneuverSource.commit,'598f7741a7e3d09dd0e362b47914dd5d54ec1fe9');assert.equal(capture.units[0].maneuverSource.calibration.length,2);
  const preset=await h.request(1,'/api/v1/rules/fleet-presets','POST',{name:'Source-backed corvette',definitionId:entry.definitionId,seat:0,...rosters[0]},201);presets.push(preset.id);
  assert.equal(preset.snapshot.points,44);assert.equal((await h.request(1,`/api/v1/rules/fleet-presets/${preset.id}/load`,'POST',{definitionId:entry.definitionId,seat:1})).valid,true);
  const game=await create(entry.definitionId,rosters);await activate(game);const before=await h.state(game),checkpoint=stored(game);
  assert.deepEqual((await h.request(1,h.route(game)+'/roster')).seats.map(s=>s.points),[44,44]);
  for(let speed=0;speed<=4;speed++)for(const side of ['PORT','STARBOARD']) {
   const p=await preview(game,plan(speed,Array(speed).fill(0),side));assert.equal(p.allowed,true,p.message);assert.ok(Math.abs(p.movements[0].to.x-(12+2.6265*speed))<1e-12);
   assert.equal(p.movements[0].maneuver.tool.length,speed+1);assert.equal(p.movements[0].maneuver.side,side);
  }
  await reject(game,plan(3,[1,0,0]),'MANEUVER_YAW');await reject(game,plan(2,[0]),'MANEUVER_JOINTS');await reject(game,plan(5,[0,0,0,0,0]),'MANEUVER_SPEED');await reject(game,plan(1,[0],'left'),'MANEUVER_SIDE');
  const previewBody={actor:'alice',action:'maneuver',parameters:plan(2,[0,0]),expectedStateVersion:before.state.version};
  for(const user of [2,3,5])await h.request(user,h.route(game)+'/preview','POST',previewBody,403);
  await h.request(1,h.route(game)+'/preview','POST',{...previewBody,expectedStateVersion:0},409);assert.deepEqual(stored(game),checkpoint);
  console.log('PASS: sourced ship costs, chart/calibration captures and presets import idempotently; speed, yaw, subject, side and preview permissions are authoritative.');

  // Invalid final poses come from published test modes, never edits to durable checkpoints.
  for(const fixture of [
   {code:'BOARD_BOUNDS',parameters:plan(1,[0]),overrides:[{op:'replace',path:'/state/startX/initial',value:[69,60]},{op:'replace',path:'/space/zones/west/width',value:72}]},
   {code:'MOVEMENT_COLLISION',parameters:plan(2,[0,0]),overrides:[{op:'replace',path:'/state/startX/initial',value:[12,17.253]},{op:'replace',path:'/state/startY/initial',value:[12,12]},{op:'replace',path:'/space/zones/east/x',value:0},{op:'replace',path:'/space/zones/east/width',value:72}]},
   {code:'MANEUVER_TOOL_OVERLAP',parameters:plan(1,[0]),overrides:[{op:'replace',path:'/state/anchor/initial',value:[0,0]}]}
  ]) {
   const mode={modeVersion:1,id:'ship-boundary-'+randomUUID(),revision:'1',extends:{id:source.id,revision:source.revision,fingerprint:roster.fingerprint},overrides:fixture.overrides};
   const draft=await h.request(5,'/api/v1/admin/rules/definitions','POST',mode,201),published=await h.request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});
   const test=await create(published.id,rosters);await activate(test);await reject(test,fixture.parameters,fixture.code);
  }
  const planned=await preview(game,plan(2,[0,0])),command={type:'act',actor:'alice',action:'maneuver',parameters:previewBody.parameters,commandId:randomUUID(),expectedStateVersion:before.state.version};
  h.sql(`INSERT INTO rules_test_support.fault VALUES('${game}','raise')`);
  try{await h.request(1,h.route(game)+'/commands','POST',command,503);assert.deepEqual(stored(game),checkpoint);}finally{h.sql(`DELETE FROM rules_test_support.fault WHERE session_id='${game}'`);}
  await h.restart();assert.deepEqual(stored(game),checkpoint);assert.deepEqual(await preview(game,plan(2,[0,0])),planned);
  const committed=await h.request(1,h.route(game)+'/commands','POST',command),pose=committed.view.state.entities['seat0-ship0'].attributes;
  assert.deepEqual({x:literal(pose.x),y:literal(pose.y),heading:literal(pose.heading)},planned.movements[0].to);assert.deepEqual(await h.request(1,h.route(game)+'/commands','POST',command),committed);
  await h.restart();assert.deepEqual(await h.request(1,h.route(game)+'/commands','POST',command),committed);
  assert.deepEqual((await h.request(1,h.route(game)+'/history/'+before.state.version)).state,before.state);await finish(game);
  const cpu=await create(entry.definitionId,[rosters[1],rosters[0]],true);
  await h.eventually(async()=>{const view=await h.state(cpu);assert.equal(view.paused,false,view.cpu.pauseReason);return view.state.status==='FINISHED';},'ship CPU course completes',60000);
  const cpuEnd=await h.state(cpu);assert.equal(cpuEnd.state.events.filter(e=>e.type==='rules.maneuvered').length,6);assert.deepEqual((await h.request(1,h.route(cpu)+'/history/'+cpuEnd.state.version)).state,cpuEnd.state);
  console.log('PASS: ship maneuver bounds, final overlaps, rollback, process restart, exact retries and human/CPU terminal replay agree.');

  const browserGame=await create(entry.definitionId,rosters);await activate(browserGame);
  browser=await chromium.launch({headless:true});const context=await browser.newContext();await context.request.post(h.base+'/login',{form:{userId:'1',next:'/rules'},maxRedirects:0});const page=await context.newPage(),errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(h.base+'/rules');await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');await page.locator('#definition').selectOption(`c:${entry.id}`);
  await page.getByLabel('Seat 1 faction',{exact:true}).waitFor();assert.ok((await page.locator('#scope').innerText()).includes('tts'));assert.ok((await page.locator('#catalogue-roster').innerText()).includes('44'));
  await page.goto(h.base+'/rules/sessions/'+browserGame);await page.locator('#maneuver-joints').waitFor();
  await page.getByLabel('speed',{exact:true}).selectOption('4');await page.getByLabel('side',{exact:true}).selectOption('STARBOARD');
  assert.equal(await page.locator('[data-joint]').count(),4);assert.equal(await page.getByLabel('Joint 1 yaw',{exact:true}).locator('option').count(),1);
  await page.getByLabel('Joint 4 yaw',{exact:true}).selectOption('2');await page.reload();await page.locator('#maneuver-joints').waitFor();
  assert.equal(await page.getByLabel('speed',{exact:true}).inputValue(),'4');assert.equal(await page.getByLabel('side',{exact:true}).inputValue(),'STARBOARD');assert.equal(await page.getByLabel('Joint 4 yaw',{exact:true}).inputValue(),'2');
  await page.getByLabel('speed',{exact:true}).selectOption('0');assert.equal(await page.locator('[data-joint]').count(),0);await page.locator('#preview').click();
  await page.waitForFunction(()=>document.querySelector('#preview-result').textContent.includes('"allowed":true'));assert.equal(JSON.parse(await page.locator('#preview-result').innerText()).movements[0].distance,0);
  const offers=await h.request(1,h.route(browserGame)+'/actions'),chosen=offers.actors[0].offered.map(inputs).find(p=>p.speed===2&&p.yaw.some(v=>v!==0));assert.ok(chosen);
  await page.getByLabel('speed',{exact:true}).selectOption(String(chosen.speed));await page.getByLabel('side',{exact:true}).selectOption(chosen.side);
  for(let i=0;i<chosen.yaw.length;i++)await page.getByLabel(`Joint ${i+1} yaw`,{exact:true}).selectOption(String(chosen.yaw[i]));
  // A slow preview response cannot redraw a plan after inputs change at the same version.
  let release,arrive;const gate=new Promise(resolve=>{release=resolve;}),arrived=new Promise(resolve=>{arrive=resolve;});
  await page.route('**/preview',async route=>{const response=await route.fetch();arrive();await gate;await route.fulfill({response});});
  await page.locator('#preview').click();await arrived;await page.getByLabel('speed',{exact:true}).selectOption('1');release();await page.unrouteAll({behavior:'wait'});
  assert.equal(await page.locator('.maneuver-destination').count(),0);assert.ok((await page.locator('#preview-result').innerText()).includes('Inputs changed'));
  await page.getByLabel('speed',{exact:true}).selectOption(String(chosen.speed));for(let i=0;i<chosen.yaw.length;i++)await page.getByLabel(`Joint ${i+1} yaw`,{exact:true}).selectOption(String(chosen.yaw[i]));
  const expected=await preview(browserGame,chosen);assert.equal(expected.allowed,true);await page.locator('#preview').click();await page.locator('.maneuver-destination').waitFor();
  assert.equal(await page.locator('.maneuver-tool').count(),1);const destination=expected.movements[0].to;
  assert.equal(await page.locator('.maneuver-destination').getAttribute('transform'),`translate(${destination.x} ${destination.y}) rotate(${destination.heading})`);
  assert.ok((await page.locator('#maneuver-measurement').innerText()).includes('tts'));
  if(process.env.TEST_ARTIFACT_DIR)await page.screenshot({path:path.join(process.env.TEST_ARTIFACT_DIR,'ship-maneuver-preview.png'),fullPage:true});
  const browserBefore=await h.state(browserGame);let acknowledge,rejectAck;const lost=new Promise((resolve,reject)=>{acknowledge=resolve;rejectAck=reject;});
  await page.route('**/commands',async route=>{try{const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');acknowledge();}catch(error){rejectAck(error);}});
  await Promise.all([page.locator('#execute').click(),lost]);await page.locator('#pending-request').waitFor({state:'visible'});const once=stored(browserGame);
  await page.unroute('**/commands');await page.reload();await page.locator('#pending-request').waitFor({state:'visible'});await page.locator('#retry-command').click();await page.locator('#pending-request').waitFor({state:'hidden'});assert.deepEqual(stored(browserGame),once);
  const after=await h.state(browserGame);assert.equal(after.state.events.filter(e=>e.type==='rules.maneuvered').length,1);const moved=after.state.entities['seat0-ship0'].attributes;
  assert.deepEqual({x:literal(moved.x),y:literal(moved.y),heading:literal(moved.heading)},destination);
  await page.locator('#history').evaluate((input,value)=>{input.value=String(value);input.dispatchEvent(new Event('change',{bubbles:true}));},browserBefore.state.version);
  await page.waitForFunction(v=>document.querySelector('#history-label').textContent.startsWith('Replay v'+v),browserBefore.state.version);
  const original=browserBefore.state.entities['seat0-ship0'].attributes;
  await page.waitForFunction(transform=>document.querySelector('#battlefield .unit[data-entity="seat0-ship0"]')?.getAttribute('transform')===transform,`translate(${literal(original.x)} ${literal(original.y)}) rotate(${literal(original.heading)})`);
  assert.equal(await page.locator('#execute').isDisabled(),true);await page.locator('#go-live').click();
  await page.waitForFunction(transform=>document.querySelector('#battlefield .unit[data-entity="seat0-ship0"]')?.getAttribute('transform')===transform,`translate(${destination.x} ${destination.y}) rotate(${destination.heading})`);
  assert.deepEqual(errors,[]);console.log('PASS: browser speed/joint/side controls retain drafts, show authoritative tool and final-base previews, discard stale previews and recover a lost maneuver acknowledgement with exact replay.');
 }finally{
  if(browser)await browser.close();
  for(const id of presets){const preset=await h.request(1,'/api/v1/rules/fleet-presets/'+id);await h.request(1,`/api/v1/rules/fleet-presets/${id}?expectedVersion=${preset.version}`,'DELETE',undefined,204);}
  await h.cleanup();
 }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
