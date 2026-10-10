// Source costs, shared validation, immutable captures and generic browser/CPU acceptance.
const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
let browser;
async function finish(id) {
 for(let n=0;n<120;n++) {
  const view=await h.state(id);
  if(view.state.status==='FINISHED') {
   assert.deepEqual((await h.request(1,h.route(id)+'/history/'+view.state.version)).state,view.state);
   return view;
  }
  const offers=(await h.request(1,h.route(id)+'/actions')).actors;
  const actor=offers.find(a=>a.controls.includes('activate')||a.offered.length||a.controls.includes('pass'));
  assert.ok(actor,'Training game must offer progress');let action;
  if(actor.controls.includes('activate'))action={type:'activate',actor:actor.actor,entityId:actor.entities[0]};
  else if(actor.offered.length) {
   const offered=actor.offered[0];const literal=v=>Array.isArray(v.value)?v.value.map(literal):v.value&&typeof v.value==='object'?v.value.id:v.value;
   action={type:'act',actor:actor.actor,action:offered.action,parameters:Object.fromEntries(Object.entries(offered.parameters).map(([k,v])=>[k,literal(v)]))};
  }else action={type:'pass',actor:actor.actor};
  assert.equal((await h.command(id,1,action)).accepted,true);
 }
 assert.fail('Sourced training game did not finish');
}
async function create(entry,cpu=false) {
 const seats=cpu?[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}]:[{playerId:'alice',userId:1},{playerId:'bob',userId:1}];
 const result=await h.request(1,`/api/v1/rules/catalogue/${entry.id}/sessions`,'POST',{visibility:'private',seats,viewers:[]},201);
 h.sessions.push(result.id);return result.id;
}
async function main() {
 await h.login();
 try {
  const version=Number(h.sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));
  const body={gameVersionId:version,pack:'armada-squadron-points-v1'};
  const [entries,concurrent]=await Promise.all([h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',body),h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',body)]);
  assert.equal(entries.length,3);assert.deepEqual(entries,concurrent);
  const entry=entries.find(e=>e.mode==='standard'),url=`/api/v1/rules/catalogue/${entry.id}/roster`;
  const roster=await h.request(1,url);
  assert.equal(roster.available,true);assert.deepEqual(roster.units.map(u=>u.points),[8,13]);
  assert.deepEqual(roster.seats.map(s=>s.factions),[['Galactic Empire'],['Rebel Alliance']]);
  for(const unit of roster.units) {
   const source=JSON.parse(unit.provenance);assert.equal(source.commit,'47bfca1bddea459678568e5ee820597a433d9b41');
   assert.match(source.sha256,/^[a-f0-9]{64}$/);assert.equal(Number(source.rawPoints),unit.points);
  }
  const counts=h.sql('SELECT (SELECT count(*) FROM game_instance.rules_session)||\':\'||(SELECT count(*) FROM game_engine.rules_definition)');
  for(const user of [1,2,3,5]) {
   assert.deepEqual(await h.request(user,url),roster);
   assert.equal((await h.request(user,url+'/validate','POST',{seat:0,units:[roster.units[0].code]})).valid,true);
  }
  for(const [units,code] of [[[],'ROSTER_COUNT'],[[roster.units[1].code],'ROSTER_FACTION'],[[roster.units[0].code,roster.units[0].code],'ROSTER_COUNT'],[['missing'],'ROSTER_UNIT_UNKNOWN']]) {
   const report=await h.request(1,url+'/validate','POST',{seat:0,units});assert.equal(report.valid,false);assert.equal(report.code,code);
  }
  await h.request(1,url+'/validate','POST',{seat:0,units:[{code:roster.units[0].code,points:0}]},400);
  await h.request(1,url+'/validate','POST',{seat:0,units:[roster.units[0].code],budget:999},400);
  assert.equal(h.sql('SELECT (SELECT count(*) FROM game_instance.rules_session)||\':\'||(SELECT count(*) FROM game_engine.rules_definition)'),counts,'Validation is read-only');
  await h.request(1,`/api/v1/rules/catalogue/${entry.id}/sessions`,'POST',{visibility:'private',seats:[{playerId:'alice',userId:1}],viewers:[]},422);

  const game=await create(entry);await h.command(game,1,{type:'start'});
  const before=await h.state(game);assert.equal(before.state.entities.alice.attributes.points.value,8);assert.equal(before.state.entities.bob.attributes.points.value,13);
  assert.deepEqual(before.state.globals.pointSources.value.map(v=>JSON.parse(v.value)),roster.units.map(u=>JSON.parse(u.provenance)));
  await h.restart();assert.deepEqual(await h.state(game),before);assert.deepEqual(await h.request(1,url),roster);
  await finish(game);
  assert.deepEqual(await h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',body),entries);
  const cpu=await create(entries.find(e=>e.mode==='short-battle'),true);await h.command(cpu,1,{type:'start'});
  await h.eventually(async()=>(await h.state(cpu)).state.status==='FINISHED','priced CPU training completes',45000);
  const ended=await h.state(cpu);assert.equal(ended.state.entities.alice.attributes.points.value,8);assert.equal(ended.state.entities.bob.attributes.points.value,13);
  assert.deepEqual((await h.request(1,h.route(cpu)+'/history/'+ended.state.version)).state,ended.state);
  console.log('PASS: sourced points, read-only shared roster validation, immutable import, restart, human and CPU setup/play/replay.');

  browser=await chromium.launch({headless:true});const context=await browser.newContext();
  await context.request.post(h.base+'/login',{form:{userId:'1',next:'/rules'},maxRedirects:0});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(h.base+'/rules');await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');
  await page.locator('#definition').selectOption(`c:${entry.id}`);await page.locator('#roster-check').waitFor();
  const text=await page.locator('#catalogue-roster').innerText();assert.ok(text.includes('TIE Fighter Squadron (8 points)'));assert.ok(text.includes('X-Wing Squadron (13 points)'));assert.ok(text.includes('revision 56'));
  await page.locator('#roster-check').click();await page.waitForFunction(()=>document.querySelector('#catalogue-roster')?.textContent.includes('Seat 2: valid / 13 of 13 points'));
  await page.locator('#create').click();await page.waitForURL('**/rules/sessions/*');h.sessions.push(page.url().split('/').pop());
  assert.deepEqual(errors,[]);console.log('PASS: generic catalogue browser shows source-backed costs and validates both rosters before session creation.');
 } finally { if(browser)await browser.close();await h.cleanup(); }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
