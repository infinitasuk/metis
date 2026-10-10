// Run against the isolated fixture database/app described in MULTIPLAYER_CONTRACT.md.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const base = process.env.TEST_BASE_URL || 'http://localhost:18081';
const container = process.env.TEST_DB_CONTAINER || 'metis-f06-db';
const database = process.env.TEST_DB_NAME || 'metis_test';

async function main() {
 const cookies = {};
 for (const user of [1,2,3,5]) {
  const r=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/`});
  cookies[user]=r.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');
 }
 async function api(user,path,method='GET',body,status=200) {
  const r=await fetch(base+path,{method,headers:{Cookie:cookies[user],'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  const text=await r.text();
  assert.equal(r.status,status,`${method} ${path}: ${text}`);
  if (r.ok) { assert.equal(r.headers.get('cache-control'),'no-store'); assert.equal(r.headers.get('x-metis-user-id'),String(user)); }
  return text?JSON.parse(text):null;
 }
 const query=`SELECT json_agg(row_to_json(x)) FROM (SELECT g.game_name,g.game_id,v.game_version_id,f.faction_name,f.faction_id,u.unit_variant_id
  FROM game_engine.game g JOIN game_engine.game_version v USING(game_id) JOIN game_engine.faction f ON f.game_version_id=v.game_version_id
  JOIN game_engine.unit_classification c USING(faction_id) JOIN game_engine.unit_type t USING(unit_classification_id)
  JOIN game_engine.unit_variant u USING(unit_type_id) WHERE g.game_name IN ('F06 private fixture','F06 response fixture')) x`;
 const rows=JSON.parse(execFileSync('docker',['exec',container,'psql','-U','metis','-d',database,'-tAc',query],{encoding:'utf8'}));
 const cleanup=[];
 async function create(kind,cpu=false) {
  const specs=rows.filter(row=>row.game_name===`F06 ${kind} fixture`);
  const session=await api(1,`/api/v1/games/${specs[0].game_id}/sessions`,'POST',{name:`F06 ${kind} verification`,visibility:'private'},201);
  const id=session.sessionId; cleanup.push(id);
  const players=[]; const fleets=[]; const units=[];
  for(const [index,side,user] of [[1,'A',1],[2,'B',2]]) {
   const spec=specs.find(row=>row.faction_name===side);
   const player=await api(1,`/api/v1/sessions/${id}/players`,'POST',{seatNumber:index,displayName:side,playerKind:cpu&&index===2?'cpu':'human',factionId:spec.faction_id,userId:user},201);
   players.push(player.sessionPlayerId);
   const actor=cpu?1:user;
   const fleet=await api(actor,`/api/v1/players/${player.sessionPlayerId}/fleets`,'POST',{fleetName:`PRIVATE_${side}_FLEET`,pointsTotal:0},201);
   fleets.push(fleet.fleetId);
   units.push((await api(actor,`/api/v1/fleets/${fleet.fleetId}/units`,'POST',{unitVariantId:spec.unit_variant_id,unitName:`PRIVATE_${side}_ROSTER`,positionX:100,positionY:40,status:'ready'},201)).unitInstanceId);
  }
  await api(1,`/api/v1/sessions/${id}/invitations`,'POST',{userId:3});
  await api(1,`/api/v1/sessions/${id}/start`,'POST',{boardCode:'custom',width:600,height:900,options:{distancePerSpeed:100,maximumShipsPerPlayer:1,maximumSquadronsPerPlayer:0,
   note:{$visibility:'OWNER',ownerPlayerId:players[1],value:'BOB_NOTE_SENTINEL'},opaque:{$visibility:'SERVER',value:'DECK_SENTINEL'}}});
  return {id,players,units,fleets,gameId:specs[0].game_id};
 }
 async function action(game,user,type,actor,parameters={},accepted=true) {
  const s=await api(user,`/api/v1/sessions/${game.id}/state`);
  const result=await api(user,`/api/v1/sessions/${game.id}/actions`,'POST',{type,actorPlayerId:actor,expectedStateVersion:s.stateVersion,parameters});
  assert.equal(result.accepted,accepted,JSON.stringify(result));
  return result;
 }
 try {
  const hidden=await create('private');
  assert.ok((await api(2,`/api/v1/sessions?gameId=${hidden.gameId}`)).some(s=>s.sessionId===hidden.id),'Assigned player sees private game without viewer invitation');
  for(const user of [1,2,3,5]) {
   const state=await api(user,`/api/v1/sessions/${hidden.id}/state`);
   assert.equal(state.units.length,user===1||user===2?1:0);
   assert.equal(state.values.note,user===2?'BOB_NOTE_SENTINEL':undefined);
   assert.equal(state.values.opaque,undefined); assert.equal(state.values.serverOnly,undefined); assert.equal(state.values.initialSetupOptions,undefined);
   const detail=await api(user,`/api/v1/sessions/${hidden.id}`);
   assert.deepEqual(JSON.parse(detail.state),state);
   const roster=await api(user,`/api/v1/fleets/${hidden.fleets[1]}/units`);
   assert.equal(roster.length,user===2?1:0);
   const response=await fetch(base+`/simulator/sessions/${hidden.id}`,{headers:{Cookie:cookies[user]}});
   assert.equal(response.status,200); const html=await response.text();
   assert.equal(html.includes(`data-unit-id="${hidden.units[1]}"`),user===2);
   assert.ok(!html.includes('DECK_SENTINEL'));
   for(const script of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) new vm.Script(script[1]);
  }
  for(const user of [3,5]) await api(user,`/api/v1/sessions/${hidden.id}/actions`,'POST',{type:'advance_phase',actorPlayerId:0,expectedStateVersion:0,parameters:{}},403);
  await api(2,`/api/v1/sessions/${hidden.id}/actions`,'POST',{type:'move_unit',actorPlayerId:hidden.players[0],expectedStateVersion:0,parameters:{}},403);
  await api(1,`/api/v1/sessions/${hidden.id}`,'PATCH',{state:{values:{}}},403);
  await api(1,`/api/v1/sessions/${hidden.id}/seats/${hidden.players[1]}`,'POST',{userId:1},403);
  await action(hidden,1,'advance_phase',0);
  await action(hidden,1,'submit_private_choice',hidden.players[0],{inputCode:'intent',value:'advance'});
  assert.equal((await action(hidden,1,'advance_phase',0,{},false)).code,'PLANNING_INCOMPLETE');
  await action(hidden,2,'submit_private_choice',hidden.players[1],{inputCode:'intent',value:'hold'});
  for(const user of [1,2,3,5]) {
   const state=await api(user,`/api/v1/sessions/${hidden.id}/state`);
   const selections=state.values.privateChoices['1:planning'];
   assert.deepEqual(Object.keys(selections),user===1?[String(hidden.players[0])]:user===2?[String(hidden.players[1])]:[]);
   const events=await api(user,`/api/v1/sessions/${hidden.id}/events`);
   const privateEvents=events.filter(e=>e.type==='private_choice_submitted');
   assert.equal(privateEvents.length,user===1||user===2?1:0);
  }
  for(const path of [`/api/v1/admin/sessions/${hidden.id}/engine-logs`,`/admin/engine-logs/sessions/${hidden.id}`]) {
   const logs=await api(5,path);
   assert.ok(logs.length); assert.ok(logs.every(log=>log.context==='{}'));
  }
  const original=await api(2,`/api/v1/sessions/${hidden.id}/state`);
  const restart=await api(1,`/api/v1/sessions/${hidden.id}/restart`,'POST'); cleanup.push(restart.sessionId);
  assert.deepEqual(await api(2,`/api/v1/sessions/${hidden.id}/state`),original);
  assert.equal((await api(2,`/api/v1/sessions/${restart.sessionId}/state`)).values.note,'BOB_NOTE_SENTINEL');
  assert.equal((await api(1,`/api/v1/sessions/${restart.sessionId}/state`)).values.note,undefined);

  const game=await create('response');
  await action(game,1,'advance_phase',0); await action(game,1,'advance_phase',0);
  const opened=await action(game,1,'attack_unit',game.players[0],{unitId:game.units[0],targetUnitId:game.units[1],privateRequest:'REQUEST_SENTINEL'});
  assert.equal(opened.code,'RESPONSE_REQUIRED');
  assert.ok(!JSON.stringify(opened).includes('REQUEST_SENTINEL'));
  const window=opened.resultingState.values.responseWindow;
  assert.equal(window.choices,undefined);
  assert.equal((await action(game,1,'end_activation',game.players[0],{},false)).code,'RESPONSE_PENDING');
  await api(1,`/api/v1/sessions/${game.id}/actions`,'POST',{type:'respond',actorPlayerId:game.players[1],parameters:{},expectedStateVersion:opened.resultingState.stateVersion},403);
  const legal=await api(2,`/api/v1/sessions/${game.id}/legal-actions`);
  assert.ok(legal.length); assert.ok(legal.every(a=>a.type==='respond'));
  assert.equal((await api(1,`/api/v1/sessions/${game.id}/legal-actions`)).length,0);
  assert.equal((await action(game,2,'respond',game.players[1],{windowId:'old',choice:'decline'},false)).code,'STALE_RESPONSE');
  assert.equal((await action(game,2,'respond',game.players[1],{windowId:window.windowId,choice:'forged'},false)).code,'RESPONSE_INVALID');
  const resolved=await action(game,2,'respond',game.players[1],{windowId:window.windowId,choice:'decline',targetUnitId:game.units[0]});
  assert.equal(resolved.resultingState.values.responseWindow,undefined);
  assert.equal(resolved.resultingState.activePlayerId,game.players[0]);
  assert.equal(resolved.resultingState.values.activation.uses.attack,1);
  assert.equal(resolved.resultingState.events.at(-1).payload.targetUnitId,game.units[1]);
  assert.equal((await action(game,2,'respond',game.players[1],{windowId:window.windowId,choice:'decline'},false)).code,'NO_RESPONSE_WINDOW');
  const logs=await api(5,`/api/v1/admin/sessions/${game.id}/engine-logs`); assert.ok(!JSON.stringify(logs).includes('REQUEST_SENTINEL'));
  const cpu=await create('response',true);
  async function waitFor(game,predicate) {
   for(let attempt=0;attempt<30;attempt++) {
    const s=await api(1,`/api/v1/sessions/${game.id}/state`);
    if(predicate(s)) return s;
    await new Promise(resolve=>setTimeout(resolve,1000));
   }
   throw new Error('CPU response did not complete');
  }
  await waitFor(cpu,s=>s.phaseCode==='engage');
  await api(1,`/api/v1/sessions/${cpu.id}/actions`,'POST',{type:'end_activation',actorPlayerId:cpu.players[1],expectedStateVersion:0,parameters:{}},403);
  const cpuWindow=await action(cpu,1,'attack_unit',cpu.players[0],{unitId:cpu.units[0],targetUnitId:cpu.units[1]});
  assert.equal(cpuWindow.code,'RESPONSE_REQUIRED');
  const afterCpu=await waitFor(cpu,s=>!s.values.responseWindow&&s.values.activation?.uses?.attack===1);
   assert.equal(afterCpu.activePlayerId,cpu.players[0]);
   assert.deepEqual(afterCpu.values.attackRoll.dice,cpuWindow.resultingState.values.attackRoll.dice);
   assert.equal(afterCpu.values.attackRoll.status,'resolved');
  assert.ok(!JSON.stringify(afterCpu).includes('BOB_NOTE_SENTINEL'));
  console.log('PASS: seat membership, spectator/admin limits, actor spoof rejection, hidden units/values/choices across state/DTOs/events/HTML/logs, safe restart, response pause/decline/resume and stale/duplicate rejection.');
  console.log('PASS: CPU handles its own response window; human cannot operate CPU seat.');
 } finally { for(const id of cleanup.reverse()) await api(1,`/api/v1/sessions/${id}`,'DELETE',undefined,204); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
