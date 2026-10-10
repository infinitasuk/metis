// Player-selected rosters: strict input boundary, immutable captures and actual browser/CPU play.
const assert=require('node:assert/strict');
const {randomUUID}=require('node:crypto');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
let browser;
const members={visibility:'private',seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:1}],viewers:[3]};
const literal=v=>Array.isArray(v.value)?v.value.map(literal):v.value&&typeof v.value==='object'?v.value.id:v.value;
const capture=id=>h.request(1,h.route(id)+'/roster');
async function create(url,body) { const result=await h.request(1,url,'POST',body,201);h.sessions.push(result.id);return result.id; }
async function finish(id) {
 for(let n=0;n<350;n++) {
  const view=await h.state(id);if(view.state.status==='FINISHED')return view;
  let command;
  if(view.state.decision) {
   const d=view.state.decision;let option=d.options.findIndex(v=>literal(v)==='$pass');
   command={type:'respond',actor:d.player,decisionId:d.id,option:option<0?0:option};
  }else {
   const actors=(await h.request(1,h.route(id)+'/actions')).actors;
   const actor=actors.find(a=>a.controls.includes('activate')||a.offered.length||a.controls.includes('pass'));
   assert.ok(actor,'Selected roster game must offer progress');
   if(actor.controls.includes('activate'))command={type:'activate',actor:actor.actor,entityId:actor.entities[0]};
   else if(actor.offered.length) {
    const action=actor.offered[0];command={type:'act',actor:actor.actor,action:action.action,parameters:Object.fromEntries(Object.entries(action.parameters).map(([k,v])=>[k,literal(v)]))};
   }else command={type:'pass',actor:actor.actor};
  }
  assert.equal((await h.command(id,1,command)).accepted,true);
 }
 assert.fail('Selected roster game exceeded its bounded command count');
}
async function main() {
 await h.login();
 try {
  const version=Number(h.sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));
  const entries=await h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',{gameVersionId:version,pack:'armada-squadron-rosters-v1'});
  const entry=entries.find(e=>e.mode==='short-battle'),root=`/api/v1/rules/catalogue/${entry.id}`;
  const roster=await h.request(1,root+'/roster');assert.equal(roster.selectable,true);
  assert.deepEqual(roster.seats.map(s=>s.choices.map(c=>c.code)),[['imperial','rebel'],['imperial','rebel']]);
  const tie=roster.units.find(u=>u.name==='TIE Fighter Squadron').code,xwing=roster.units.find(u=>u.name==='X-Wing Squadron').code;
  const rosters=[{policy:'rebel',units:[xwing,xwing]},{policy:'imperial',units:[tie,tie,tie]}];
  assert.equal((await h.request(1,root+'/roster/validate','POST',{seat:0,...rosters[0]})).points,26);
  assert.deepEqual(await h.request(1,`/api/v1/rules/definitions/${entry.definitionId}/roster`),roster);
  const count=()=>h.sql('SELECT (SELECT count(*) FROM game_instance.rules_session)||\':\'||(SELECT count(*) FROM game_instance.rules_seat)');
  const before=count();
  for(const [selected,code,status] of [
   [undefined,'ROSTER_REQUIRED',422],[[],'ROSTER_SEAT_COUNT',422],
   [[{policy:'rebel',units:[]},rosters[1]],'ROSTER_COUNT',422],
   [[{policy:'rebel',units:[xwing,xwing,xwing]},rosters[1]],'ROSTER_BUDGET',422],
   [[{policy:'rebel',units:[tie]},rosters[1]],'ROSTER_FACTION',422],
   [[{policy:'forged',units:[xwing]},rosters[1]],'ROSTER_POLICY',422],
   [[{policy:'rebel',units:['missing']},rosters[1]],'ROSTER_UNIT_UNKNOWN',422],
   [[{policy:'rebel',units:[xwing],points:0},rosters[1]],'ROSTER_INPUT_INVALID',400]
  ]) {
   const request={...members,...(selected===undefined?{}:{rosters:selected})};
   assert.equal((await h.request(1,root+'/sessions','POST',request,status)).code,code);
  }
  const overBudget=[{policy:'rebel',units:[xwing,xwing,xwing]},rosters[1]];
  assert.equal((await h.request(1,'/api/v1/rules/sessions','POST',{...members,definitionId:entry.definitionId,rosters:overBudget},422)).code,'ROSTER_BUDGET');
  assert.equal(count(),before,'Rejected creation must not leave sessions or seats');

  const id=await create(root+'/sessions',{...members,rosters});const initial=await h.state(id),original=await capture(id);
  assert.equal(initial.state.version,0);assert.deepEqual(initial.state.entities,{});
  assert.deepEqual(initial.state.globals.selectedRosters.value.map(literal),[[1,1],[0,0,0]]);
  assert.deepEqual(original.seats.map(s=>[s.playerId,s.policy,s.points,s.units.length]),[['alice','rebel',26,2],['bob','imperial',24,3]]);
  assert.equal(new Set(original.seats.flatMap(s=>s.entityIds)).size,5);
  assert.deepEqual(await h.request(3,h.route(id)+'/roster'),original);
  await h.request(4,h.route(id)+'/roster','GET',undefined,404);
  const savedHash=h.sql(`SELECT md5(initial_checkpoint||captured_definition) FROM game_instance.rules_session WHERE session_id='${id}'`);
  h.sql(`DO $$ DECLARE denied boolean:=false; BEGIN
   BEGIN UPDATE game_instance.rules_session SET initial_checkpoint=jsonb_set(initial_checkpoint::jsonb,'{globals,selectedRosters,value}','[]')::text WHERE session_id='${id}';
   EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END; ASSERT denied; END $$`);
  await h.restart();assert.deepEqual(await capture(id),original);assert.deepEqual(await h.state(id),initial);

  // A newer definition has changed costs, while this session keeps the exact captured values.
  const resolved=await h.request(5,`/api/v1/admin/rules/definitions/${entry.definitionId}/resolved`);
  const newer=JSON.parse(resolved.source);newer.revision='rosters-'+randomUUID();newer.state.points.initial[0]=9;
  const source=JSON.parse(newer.state.pointSources.initial[0]);source.rawPoints='9.0';newer.state.pointSources.initial[0]=JSON.stringify(source);
  const draft=await h.request(5,'/api/v1/admin/rules/definitions','POST',newer,201);
  const published=await h.request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});
  assert.equal((await h.request(1,`/api/v1/rules/definitions/${published.id}/roster/validate`,'POST',{seat:1,...rosters[1]})).code,'ROSTER_BUDGET');
  assert.deepEqual(await capture(id),original);

  await h.command(id,1,{type:'start'});const started=await h.state(id);assert.equal(Object.keys(started.state.entities).length,5);
  for(const seat of original.seats)for(let n=0;n<seat.entityIds.length;n++) {
   const entity=started.state.entities[seat.entityIds[n]];assert.equal(entity.owner,seat.playerId);assert.equal(entity.attributes.catalogueId.value,seat.units[n].code);assert.equal(entity.attributes.points.value,seat.units[n].points);
  }
  const ended=await finish(id);assert.deepEqual((await h.request(1,h.route(id)+'/history/'+ended.state.version)).state,ended.state);
  assert.deepEqual((await h.request(1,h.route(id)+'/history/0')).state,initial.state);assert.deepEqual(await capture(id),original);
  assert.equal(h.sql(`SELECT md5(initial_checkpoint||captured_definition) FROM game_instance.rules_session WHERE session_id='${id}'`),savedHash);
  const cpu=await create('/api/v1/rules/sessions',{...members,definitionId:entry.definitionId,seats:[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}],rosters});
  await h.command(cpu,1,{type:'start'});
  await h.eventually(async()=>{const s=await h.state(cpu);assert.equal(s.paused,false,s.cpu.pauseReason);return s.state.status==='FINISHED';},'selected CPU fleets complete',90000);
  assert.deepEqual((await capture(cpu)).seats.map(s=>s.units),original.seats.map(s=>s.units));
  console.log('PASS: selected factions and quantities validate atomically, capture exact costs and distinct units, and survive restart/new revisions/history with human and CPU play.');

  browser=await chromium.launch({headless:true});const context=await browser.newContext();
  await context.request.post(h.base+'/login',{form:{userId:'1',next:'/rules'},maxRedirects:0});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(h.base+'/rules');await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');
  await page.locator('#definition').selectOption(`c:${entry.id}`);await page.getByLabel('Seat 1 faction',{exact:true}).waitFor();
  await page.getByLabel('Seat 1 faction',{exact:true}).selectOption('rebel');
  await page.getByRole('button',{name:'Add unit to seat 1',exact:true}).click();await page.getByRole('button',{name:'Add unit to seat 1',exact:true}).click();
  await page.locator('#roster-check').click();await page.waitForFunction(()=>document.querySelector('[data-roster-result="0"]')?.textContent.includes('ROSTER_BUDGET'));
  const prior=count();await page.locator('#create').click();await page.waitForFunction(()=>document.querySelector('#connection')?.textContent.includes('Correct the roster'));assert.equal(count(),prior);
  await page.getByRole('button',{name:'Remove seat 1 unit 3',exact:true}).click();
  await page.getByLabel('Seat 2 faction',{exact:true}).selectOption('imperial');await page.getByRole('button',{name:'Add unit to seat 2',exact:true}).click();await page.getByRole('button',{name:'Add unit to seat 2',exact:true}).click();
  await page.reload();await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');
  await page.locator('#definition').selectOption(`c:${entry.id}`);await page.getByLabel('Seat 1 faction',{exact:true}).waitFor();
  assert.equal(await page.getByLabel('Seat 1 faction',{exact:true}).inputValue(),'rebel');assert.equal(await page.locator('[data-roster-seat="0"] select[aria-label*=" unit "]').count(),2);assert.equal(await page.locator('[data-roster-seat="1"] select[aria-label*=" unit "]').count(),3);
  await page.locator('.seat-controller').nth(1).selectOption('1');
  // A delayed preflight must not create a session with seat inputs edited in the meantime.
  let release,arrived;const blocked=new Promise(resolve=>arrived=resolve),gate=new Promise(resolve=>release=resolve);
  await page.route('**/roster/validate',async route=>{arrived();await gate;await route.continue();});
  await page.locator('#create').click();await blocked;await page.locator('.seat-player').nth(0).fill('chosen-player');release();
  await page.waitForFunction(()=>document.querySelector('#connection')?.textContent.includes('Setup changed during validation'));
  await page.unroute('**/roster/validate');assert.equal(count(),prior);
  await page.locator('#create').click();await page.waitForURL('**/rules/sessions/*');const browserId=page.url().split('/').pop();h.sessions.push(browserId);
  await page.locator('#starting-rosters').waitFor({state:'visible'});const shown=await page.locator('#starting-rosters').innerText();assert.ok(shown.includes('26 of 26 points'));assert.ok(shown.includes('24 of 26 points'));
  await h.command(browserId,1,{type:'start'});await page.reload();await page.locator('#starting-rosters').waitFor({state:'visible'});assert.equal(await page.locator('#starting-rosters').innerText(),shown);
  await page.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('Version 1'));
  await page.locator('#history').evaluate(node=>{node.value='0';node.dispatchEvent(new Event('change',{bubbles:true}));});await page.waitForFunction(()=>document.querySelector('#history-label')?.textContent.startsWith('Replay v0'));
  assert.equal(await page.locator('#starting-rosters').innerText(),shown);assert.deepEqual(errors,[]);
  console.log('PASS: browser roster edits and validation errors preserve drafts; chosen fleets are displayed unchanged after creation, reload and replay.');
 }finally{if(browser)await browser.close();await h.cleanup();}
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
