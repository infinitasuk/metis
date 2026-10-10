// Isolated fixture app only. See ORDERS_CONTRACT.md; optional Playwright browser checks.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {execFileSync} = require('node:child_process');
const base = process.env.TEST_BASE_URL || 'http://localhost:18081';
const container = process.env.TEST_DB_CONTAINER || 'metis-f07-db';
const database = process.env.TEST_DB_NAME || 'metis_test';
const path = require('node:path');

async function main() {
 const cookies = {}, cleanup = []; let browser, page, context; const browserErrors=[];
 if(process.env.REQUIRE_BROWSER==='1')assert.ok(process.env.PLAYWRIGHT_MODULE,'Mandatory browser suite requires Playwright');
 for(const user of [1,2,3,5]) {
  const r=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/`});
  cookies[user]=r.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');
 }
 async function api(user,path,method='GET',body,status=200) {
  const r=await fetch(base+path,{method,headers:{Cookie:cookies[user],'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  const text=await r.text(); assert.equal(r.status,status,`${method} ${path}: ${text}`);return text?JSON.parse(text):null;
 }
 const rows=JSON.parse(execFileSync('docker',['exec',container,'psql','-U','metis','-d',database,'-tAc',`SELECT json_agg(row_to_json(x)) FROM (
 SELECT g.game_id,f.faction_name,f.faction_id,u.unit_variant_id FROM game_engine.game g JOIN game_engine.game_version v USING(game_id)
 JOIN game_engine.faction f USING(game_version_id) JOIN game_engine.unit_classification c USING(faction_id)
 JOIN game_engine.unit_type t USING(unit_classification_id) JOIN game_engine.unit_variant u USING(unit_type_id) WHERE g.game_name='F06 orders fixture') x`],{encoding:'utf8'}));
 const state=(game,user=1)=>api(user,`/api/v1/sessions/${game.id}/state`);
 async function create(cpu=false) {
  const session=await api(1,`/api/v1/games/${rows[0].game_id}/sessions`,'POST',{name:'F07 verification',visibility:'private'},201);
  const game={id:session.sessionId,players:[],units:[]}; cleanup.push(game.id);
  for(const [index,side,user] of [[1,'A',1],[2,'B',2]]) {
   const spec=rows.find(r=>r.faction_name===side);
   const player=await api(1,`/api/v1/sessions/${game.id}/players`,'POST',{seatNumber:index,displayName:side,playerKind:cpu&&index===2?'cpu':'human',factionId:spec.faction_id,userId:user},201);
   game.players.push(player.sessionPlayerId);
   const actor=cpu?1:user;
   const fleet=await api(actor,`/api/v1/players/${player.sessionPlayerId}/fleets`,'POST',{fleetName:side,pointsTotal:0},201);
   game.units.push((await api(actor,`/api/v1/fleets/${fleet.fleetId}/units`,'POST',{unitVariantId:spec.unit_variant_id,unitName:side,positionX:100,positionY:40,status:'ready'},201)).unitInstanceId);
  }
  await api(1,`/api/v1/sessions/${game.id}/invitations`,'POST',{userId:3});
  await api(1,`/api/v1/sessions/${game.id}/start`,'POST',{boardCode:'custom',width:600,height:900,options:{distancePerSpeed:100,maximumShipsPerPlayer:1,maximumSquadronsPerPlayer:0}});
  return game;
 }
 async function act(game,user,type,actor,parameters={},accepted=true) {
  for(let attempt=0;attempt<5;attempt++) {
   const s=await state(game,user);
   const result=await api(user,`/api/v1/sessions/${game.id}/actions`,'POST',{type,actorPlayerId:actor,expectedStateVersion:s.stateVersion,parameters});
   if(result.code==='STALE_STATE') continue;
   assert.equal(result.accepted,accepted,JSON.stringify(result));return result;
  }
  throw Error('Repeated state conflicts');
 }
 async function wait(game,predicate) {
  for(let i=0;i<60;i++) { const s=await state(game); if(predicate(s))return s; await new Promise(resolve=>setTimeout(resolve,350)); }
  throw Error('State did not reach expected checkpoint: '+JSON.stringify(await state(game)));
 }
 async function hold(game,user,id,actor) {
  const u=(await state(game,user)).units.find(u=>u.unitId===id);
  await act(game,user,'move_unit',actor,{unitId:id,positionX:u.positionX,positionY:u.positionY,facing:u.facing});
 }
 try {
  const game=await create(); const [a,b]=game.players, [u,v]=game.units;
  await act(game,1,'advance_phase',0);
  assert.equal((await act(game,1,'advance_phase',0,{},false)).code,'ORDER_PLANNING_INCOMPLETE');
  if(process.env.PLAYWRIGHT_MODULE) {
   const {chromium}=require(process.env.PLAYWRIGHT_MODULE);
   browser=await chromium.launch({headless:true,channel:process.env.TEST_BROWSER_CHANNEL || (process.env.REQUIRE_BROWSER==='1'?undefined:'msedge')});
   context=await browser.newContext();
   if(process.env.TEST_ARTIFACT_DIR)await context.tracing.start({screenshots:true,snapshots:true});
   await context.addCookies(cookies[1].split('; ').map(item=>{const [name,...value]=item.split('=');return {name,value:value.join('='),url:base};}));
   page=await context.newPage();page.on('pageerror',error=>browserErrors.push(error.message));
   await page.goto(`${base}/simulator/sessions/${game.id}`);
   await page.locator('.order-form select[name="slot0"]').waitFor();
   assert.equal(await page.locator('.order-form select[name="slot0"]').count(),1);
   await page.locator('select[name="slot0"]').selectOption('focus');await page.locator('select[name="slot1"]').selectOption('mend');
   await page.getByRole('button',{name:'Save private queue',exact:true}).click();
   await wait(game,s=>s.values.orderQueues?.[u]?.entries?.[0]==='focus');
   await page.locator('select[name="slot1"]').selectOption('thrust');
  } else await act(game,1,'plan_orders',a,{unitId:u,orders:['focus','mend']});
  await act(game,2,'plan_orders',b,{unitId:v,orders:['thrust','thrust']});
  if(page) {
   await page.waitForFunction(version=>Number(document.querySelector('#engine-status-version')?.textContent)===version,(await state(game)).stateVersion);
   assert.equal(await page.locator('select[name="slot1"]').inputValue(),'thrust','Unsent queue edit survives another player update');
   await page.locator('select[name="slot1"]').selectOption('mend');
  }
  for(const user of [1,2,3,5]) {
   const s=await state(game,user);assert.deepEqual(Object.keys(s.values.orderQueues),user===1?[String(u)]:user===2?[String(v)]:[]);
   assert.deepEqual(JSON.parse((await api(user,`/api/v1/sessions/${game.id}`)).state),s);
   const events=await api(user,`/api/v1/sessions/${game.id}/events`);
   assert.equal(events.filter(e=>e.type==='orders_planned').length,user===1||user===2?1:0);
   const legal=await api(user,`/api/v1/sessions/${game.id}/legal-actions`);
   assert.ok(legal.filter(a=>a.type==='plan_orders').every(a=>a.actorPlayerId===(user===1?game.players[0]:user===2?game.players[1]:-1)));
   const html=await (await fetch(`${base}/simulator/sessions/${game.id}`,{headers:{Cookie:cookies[user]}})).text();
   for(const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) new vm.Script(script[1]);
  }
  await api(1,`/api/v1/sessions/${game.id}/actions`,'POST',{type:'plan_orders',actorPlayerId:b,expectedStateVersion:0,parameters:{unitId:v,orders:['mend','mend']}},403);
  await act(game,1,'advance_phase',0);await act(game,1,'activate_unit',a,{unitId:u});
  assert.equal((await state(game,3)).values.orderState[u].revealed,'focus');assert.deepEqual((await state(game,2)).values.orderQueues[v].entries,['thrust','thrust']);
  if(page) {
   const form=page.locator('form.order-form').filter({hasText:'Focus / order'});
   await form.getByRole('button',{name:'Resolve',exact:true}).click();await wait(game,s=>s.values.orderState[u].available===false);
  } else await act(game,1,'resolve_order',a,{unitId:u,orderCode:'focus',source:'order'});
   const beforeAttack=await state(game);
   const opened=await act(game,1,'attack_unit',a,{unitId:u,targetUnitId:v});assert.equal(opened.code,'RESPONSE_REQUIRED');
   const roll=opened.resultingState.values.attackRoll;
   assert.equal(roll.status,'awaiting_defense');assert.equal(roll.dice.length,roll.attackDice);
   assert.deepEqual(opened.resultingState.units,beforeAttack.units,'Damage waits for the defender');
   const recorded=await api(1,`/api/v1/sessions/${game.id}/events`);
   assert.equal(recorded.length,opened.resultingState.events.length);
   assert.deepEqual(JSON.parse(recorded.at(-1).payload).roll,roll,'SQL history retains the authoritative roll');
   if(browser) {
    const defenderContext=await browser.newContext();
    await defenderContext.addCookies(cookies[2].split('; ').map(item=>{const [name,...value]=item.split('=');return {name,value:value.join('='),url:base};}));
    const defender=await defenderContext.newPage();defender.on('pageerror',error=>browserErrors.push(error.message));
    await defender.clock.install();await defender.clock.pauseAt(new Date());
    await defender.goto(`${base}/simulator/sessions/${game.id}`);
    await defender.locator('.attack-die').first().waitFor();
    const decline=defender.getByRole('button',{name:'Decline response',exact:true});
    assert.equal(await decline.isDisabled(),true);
    assert.equal(await defender.locator('.attack-die.rolling').count(),roll.dice.length);
    await defender.clock.runFor(360);
    assert.equal(await defender.locator('.attack-die.revealed').count(),1);
    await defender.reload();await defender.locator('.attack-die').first().waitFor();
    assert.equal(await decline.isDisabled(),true);
    await defender.clock.runFor(360*roll.dice.length);
    assert.equal(await decline.isEnabled(),true);
    assert.deepEqual(await defender.locator('.attack-die strong').allTextContents(),roll.dice.map(d=>String(d.face)));
    assert.deepEqual((await state(game)).values.attackRoll,roll,'Reload does not reroll');
    await defenderContext.close();
   }
  assert.equal((await state(game)).values.orderState[u].modifiers.attackDice,1);
  await act(game,2,'respond',b,{windowId:opened.resultingState.values.responseWindow.windowId,choice:'decline'});
   const after=await state(game);assert.equal(after.events.at(-1).payload.orderBonusDice,1);assert.equal(after.values.orderState[u].modifiers.attackDice,undefined);assert.equal(after.values.activation.uses.attack,1);
   assert.deepEqual(after.values.attackRoll.dice,roll.dice);assert.equal(after.values.attackRoll.rollId,roll.rollId);
   if(page) {
    const replay=await page.evaluate(({source,count})=>replayStateAt(source,count),{source:after,count:opened.resultingState.events.length});
    assert.equal(replay.values.attackRoll.status,'awaiting_defense');assert.deepEqual(replay.values.attackRoll.dice,roll.dice);
    assert.deepEqual(replay.units,beforeAttack.units);
   }
  await hold(game,1,u,a);await act(game,1,'end_activation',a);await act(game,2,'activate_unit',b,{unitId:v});await act(game,2,'store_order',b,{unitId:v});
  await hold(game,2,v,b);await act(game,2,'end_activation',b);
  assert.equal((await state(game)).round,2);
  assert.equal((await act(game,1,'plan_orders',a,{unitId:u,orders:['focus','focus']},false)).code,'QUEUE_LENGTH_INVALID');
  await act(game,1,'plan_orders',a,{unitId:u,orders:['thrust']});assert.deepEqual((await state(game)).values.orderQueues[u].entries,['mend','thrust']);
  const source=await state(game);const restarted=await api(1,`/api/v1/sessions/${game.id}/restart`,'POST');cleanup.push(restarted.sessionId);
  assert.deepEqual(await state(game),source);assert.equal((await state({id:restarted.sessionId})).values.orderQueues,undefined);
  const cpu=await create(true);
  await wait(cpu,s=>s.phaseCode==='planning');
  await act(cpu,1,'plan_orders',cpu.players[0],{unitId:cpu.units[0],orders:['thrust','focus']});
  await wait(cpu,s=>s.phaseCode==='engage'&&s.activePlayerId===cpu.players[0]);
  await act(cpu,1,'activate_unit',cpu.players[0],{unitId:cpu.units[0]});await hold(cpu,1,cpu.units[0],cpu.players[0]);await act(cpu,1,'end_activation',cpu.players[0]);
  await wait(cpu,s=>s.values.responseWindow || s.round>1);
  let cs=await state(cpu);
  if(cs.values.responseWindow) await act(cpu,1,'respond',cpu.players[0],{windowId:cs.values.responseWindow.windowId,choice:'decline'});
  cs=await wait(cpu,s=>s.round===2);
  assert.ok(cs.events.some(e=>e.type==='order_resolved'&&e.actorPlayerId===cpu.players[1]));
  assert.deepEqual(Object.keys(cs.values.orderQueues),[String(cpu.units[0])]);
  assert.deepEqual(browserErrors,[]);
  console.log('PASS: F07 queue privacy/readiness, actor ownership, locked tail, public reveal, single attack bonus across response pause, tokens, clean restart, CPU planning/resolution/handover.');
   if(page)console.log('PASS: browser private planning controls, draft preservation across opponent polling, order resolution, no browser errors.');
   if(page)console.log('PASS: authoritative attack roll survives reload and replay; defender controls wait for the dice reveal.');
 } catch(error) {
  if(page && process.env.TEST_ARTIFACT_DIR) {
   await page.screenshot({path:path.join(process.env.TEST_ARTIFACT_DIR,'orders-failure.png'),fullPage:true}).catch(()=>{});
  }
  throw error;
 } finally {
  if(context && process.env.TEST_ARTIFACT_DIR)await context.tracing.stop({path:path.join(process.env.TEST_ARTIFACT_DIR,'orders-browser.zip')});
  if(browser)await browser.close();for(const id of cleanup.reverse())await api(1,`/api/v1/sessions/${id}`,'DELETE',undefined,204);
 }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
