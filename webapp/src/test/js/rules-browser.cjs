const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {randomUUID}=require('node:crypto');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
const errors=[];
let browser,contexts=[];
async function pageFor(user) {
 const context=await browser.newContext({viewport:{width:1400,height:1100}});contexts.push(context);
 assert.equal((await context.request.post(h.base+'/login',{form:{userId:String(user),next:'/rules'},maxRedirects:0})).status(),302);
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));return page;
}
async function open(page,id) { await page.goto(h.base+'/rules/sessions/'+id);await page.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('Version')); }
async function click(page,selector) {
 const response=page.waitForResponse(r=>r.url().endsWith('/commands')&&r.request().method()==='POST');await page.locator(selector).first().click();const r=await response;const data=await r.json();if(r.status()!==409||data.code!=='STALE_STATE') {assert.equal(r.status(),200,JSON.stringify(data));assert.equal(data.accepted,true);}
 await page.waitForFunction(v=>Number(document.querySelector('#status')?.textContent.match(/Version (\d+)/)?.[1])>=v,data.view.state.version);return data;
}
async function finish(page,id,onDecision) {
 const deadline=Date.now()+90000;
 for(let n=0;n<180;) {
  assert.ok(Date.now()<deadline,'Browser progress timeout');
  const state=await h.state(id);if(state.state.status==='FINISHED') { await page.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('FINISHED'));return; }
  await page.waitForFunction(v=>Number(document.querySelector('#status')?.textContent.match(/Version (\d+)/)?.[1])>=v,state.state.version);
  if(await page.locator('#respond').isVisible()) { if(onDecision)await onDecision();await click(page,'#respond'); }
  else if(await page.locator('[data-activate]').count())await click(page,'[data-activate]');
  else if(await page.locator('#offer option:not([value=""])').count()&&await page.locator('#execute').isVisible())await click(page,'#execute');
  else if(await page.locator('#controls [data-command="advanceStep"]').count())await click(page,'#controls [data-command="advanceStep"]');
  else if(await page.locator('#controls [data-command="pass"]').count())await click(page,'#controls [data-command="pass"]');
  else { await page.waitForTimeout(100);continue; }
  n++;
 }
 assert.fail('Browser game exceeded bounded command count');
}
async function createInBrowser(page,definition) {
 await page.goto(h.base+'/rules');await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');await page.locator('#definition').selectOption('d:'+definition.id);
 await page.locator('.seat-player').nth(0).fill('alice');await page.locator('.seat-player').nth(1).fill('bob');await page.locator('.seat-controller').nth(0).selectOption('1');await page.locator('.seat-controller').nth(1).selectOption('1');
 await page.locator('#create').click();await page.waitForURL('**/rules/sessions/*');const id=page.url().split('/').pop();h.sessions.push(id);await page.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('Version'));return id;
}
async function savePublish(page,source) {
 await page.locator('#draft-list').selectOption('');await page.locator('#source').fill(source);await page.locator('#save-definition').click();await page.waitForFunction(()=>document.querySelector('#author-result')?.textContent.startsWith('Saved draft'));
 await page.locator('#validate-definition').click();await page.waitForFunction(()=>document.querySelector('#author-result')?.textContent.includes('"valid":true'));
 const response=page.waitForResponse(r=>r.url().endsWith('/publish')&&r.request().method()==='POST');await page.locator('#publish-definition').click();const r=await response;assert.equal(r.status(),200,await r.text());return r.json();
}
async function main() {
 await h.login();browser=await chromium.launch({headless:true});
 try {
  const admin=await pageFor(5),owner=await pageFor(1);await admin.goto(h.base+'/rules');await admin.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');
  const source=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../../../engine-core/src/main/resources/rules/examples/grid-skirmish.json'),'utf8'));source.id='m7-browser-'+randomUUID();
  source.state.big={type:'integer',initial:'EXACT',visibility:'PUBLIC'};source.state.fraction={type:'decimal',initial:0,visibility:'PUBLIC'};source.state.serverSecret={type:'string',initial:'M7_SERVER_SENTINEL',visibility:'SERVER'};source.state.markup={type:'string',initial:'<img src=x onerror=alert(1)>',visibility:'PUBLIC'};
  source.procedures.calibrate={parameters:{magnitude:'integer',ratio:'decimal'},steps:[{op:'core.setState',field:'big',value:{var:'magnitude'}},{op:'core.setState',field:'fraction',value:{var:'ratio'}}]};
  source.actions.calibrate={parameters:{entity:'entity:piece',magnitude:'integer',ratio:'decimal'},availableWhen:true,procedure:'calibrate',args:{magnitude:{var:'magnitude'},ratio:{var:'ratio'}}};source.flow.phases.battle.steps.unshift({id:'calibrate',actions:['calibrate'],minimum:0,maximum:1});
  const base=await savePublish(admin,JSON.stringify(source).replace('"EXACT"','9223372036854775807'));
  const steps=structuredClone(source.flow.phases.battle.steps);for(const s of steps)s.actions=s.actions.map(a=>a==='attack'?'pulse':a);
  const mode={modeVersion:1,id:'m7-mode-'+randomUUID(),revision:'1',extends:{id:base.key,revision:base.revision,fingerprint:base.fingerprint},overrides:[{op:'add',path:'/actions/pulse',value:source.actions.attack},{op:'remove',path:'/actions/attack'},{op:'replace',path:'/flow/phases/battle/steps',value:steps},{op:'replace',path:'/entities/piece/resources/health/initial',value:1}]};
  const published=await savePublish(admin,JSON.stringify(mode));await admin.locator('#resolve-definition').click();await admin.waitForFunction(()=>document.querySelector('#author-result')?.textContent.includes('rules-resolver-1'));
  const game=await createInBrowser(owner,published);await click(owner,'[data-command="start"]');await click(owner,'[data-activate]');assert.equal(await owner.locator('#action option[value="pulse"]').count(),1);assert.equal(await owner.locator('#action option[value="attack"]').count(),0);
  assert.ok((await owner.locator('#globals').innerText()).includes('9223372036854775807'));assert.equal(await owner.locator('#globals img').count(),0);assert.ok(!(await owner.locator('body').innerText()).includes('M7_SERVER_SENTINEL'));
  await owner.locator('#action').selectOption('calibrate');await owner.getByLabel('magnitude',{exact:true}).fill('9223372036854775806');await owner.getByLabel('ratio',{exact:true}).fill('0.1234567890123456789012345678901234');await click(owner,'#execute');
  assert.equal(h.sql(`SELECT checkpoint_json::jsonb#>>'{globals,big,value}' FROM game_instance.rules_session WHERE session_id='${game}'`),'9223372036854775806');assert.equal(h.sql(`SELECT checkpoint_json::jsonb#>>'{globals,fraction,value}' FROM game_instance.rules_session WHERE session_id='${game}'`),'0.1234567890123456789012345678901234');
  await owner.locator('#action').selectOption('move');const versionBefore=(await h.state(game)).state.version;await owner.locator('#preview').click();await owner.waitForFunction(()=>document.querySelector('#preview-result')?.textContent.includes('"allowed":true'));assert.equal((await h.state(game)).state.version,versionBefore);assert.equal(await owner.locator('#battlefield polyline').count(),1);
  await finish(owner,game);console.log('PASS: generic browser authors and publishes a base/mode, creates a session, preserves exact numbers and completes JSON-renamed actions with authoritative movement preview.');

  const vector=await h.publish('vector-orders');const privateGame=await h.create(vector,{seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}]});await h.command(privateGame,1,{type:'start'});await open(owner,privateGame);
  const draft='["PRIVATE_DRAFT_SENTINEL"]';await owner.getByLabel('orders',{exact:true}).fill(draft);await owner.locator('#zoom').evaluate(e=>{e.value='165';e.dispatchEvent(new Event('input',{bubbles:true}));});await owner.locator('#show-labels').uncheck();await owner.locator('#entity-list [data-entity="alice"]').click();await owner.getByLabel('orders',{exact:true}).focus();
  await h.command(privateGame,2,{type:'act',actor:'bob',action:'plan',parameters:{entity:'bob',orders:['focus']}});await owner.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('Version 2'));
  assert.equal(await owner.getByLabel('orders',{exact:true}).inputValue(),draft);assert.equal(await owner.evaluate(()=>document.activeElement?.dataset.parameter),'orders');assert.equal(await owner.locator('#zoom').inputValue(),'165');assert.equal(await owner.locator('#show-labels').isChecked(),false);assert.equal(await owner.locator('#battlefield .selected').getAttribute('data-entity'),'alice');
  const box=await owner.locator('#battlefield').boundingBox();await owner.mouse.move(box.x+15,box.y+15);await owner.mouse.down();await owner.mouse.move(box.x+50,box.y+40);const pan=await owner.locator('#battlefield').getAttribute('viewBox');await h.command(privateGame,1,{type:'pause'});await owner.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('PAUSED'));assert.equal(await owner.locator('#battlefield').getAttribute('viewBox'),pan);await owner.mouse.up();await h.command(privateGame,1,{type:'resume'});await owner.reload();await owner.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('Version 4'));assert.equal(await owner.getByLabel('orders',{exact:true}).inputValue(),draft);assert.equal(await owner.locator('#zoom').inputValue(),'165');
  await owner.context().setOffline(true);await owner.waitForFunction(()=>document.querySelector('#connection')?.dataset.error==='true');assert.equal(await owner.getByLabel('orders',{exact:true}).inputValue(),draft);await owner.context().setOffline(false);await owner.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Connected.');
  const etagReply=await fetch(h.base+h.route(privateGame)+'/workspace',{headers:{Cookie:h.cookies[1]}});assert.equal(etagReply.status,200);const tag=etagReply.headers.get('etag');assert.ok(tag);await h.request(1,h.route(privateGame)+'/workspace','GET',undefined,304,{'If-None-Match':tag});await h.request(4,h.route(privateGame)+'/workspace','GET',undefined,404,{'If-None-Match':tag});
  for(const user of [2,3,5]) { const page=await pageFor(user);await open(page,privateGame);assert.ok(!(await page.locator('body').innerText()).includes('PRIVATE_DRAFT_SENTINEL'));const w=await h.request(user,h.route(privateGame)+'/workspace');assert.equal(w.view.state.entities.alice.attributes.orders,undefined);if(user!==2)assert.deepEqual(w.choices.actors,[]); }
  console.log('PASS: private drafts, focus, selection, zoom, layers and active pan survive polling, reconnect and reload; viewer roles and conditional reads preserve privacy.');

  const hidden=await h.publish('hidden-grid-cpu');const hiddenGame=await h.create(hidden);await open(owner,hiddenGame);await click(owner,'[data-command="start"]');
  let intercepted;
  await owner.route('**/commands',async route=>{if(!intercepted) {intercepted=JSON.parse(route.request().postData());const r=await route.fetch();assert.equal(r.status(),200);await route.abort('failed');}else await route.continue();});
  await owner.locator('#respond').click();await owner.locator('#pending-request').waitFor({state:'visible'});await h.eventually(async()=>((await h.state(hiddenGame)).state.version===2),'lost acknowledgement committed');
  const pending=(await h.state(hiddenGame)).state.decision;await h.command(hiddenGame,1,{type:'respond',actor:'bob',decisionId:pending.id,option:8});await owner.locator('#retry-command').click();await owner.locator('#pending-request').waitFor({state:'hidden'});await owner.waitForFunction(()=>document.querySelector('#status')?.textContent.includes('Version 3'));assert.equal((await h.state(hiddenGame)).state.version,3);await owner.unroute('**/commands');
  await owner.locator('#history').evaluate(e=>{e.value='1';e.dispatchEvent(new Event('change',{bubbles:true}));});await owner.waitForFunction(()=>document.querySelector('#history-label')?.textContent.startsWith('Replay v1'));assert.equal(await owner.locator('#management button').first().isDisabled(),true);await owner.locator('#go-live').click();await finish(owner,hiddenGame);
  const vectorUi=await h.create(vector);await open(owner,vectorUi);await click(owner,'[data-command="start"]');await finish(owner,vectorUi);
  const dice=await h.publish('dice-duel',s=>{
   s.actions.plan.choices={fighter:{filter:{source:{entities:'fighter'},as:'e',where:{op:'eq',args:[{owner:{var:'e'}},{var:'actor'}]}}},orders:{list:{elementType:{list:'string'},items:[{list:{elementType:'string',items:['focus','guard']}}]}}};
   s.actions.attack.choices={fighter:{entities:'fighter'},target:{entities:'fighter'}};
  });const rich=await h.create(dice);await open(owner,rich);await click(owner,'[data-command="start"]');await finish(owner,rich);assert.ok((await owner.locator('#mechanics').innerText()).includes('dice'));
  const mixed=await h.create(vector,{seats:[{playerId:'alice',userId:1},{playerId:'bob',kind:'CPU'}]});await open(owner,mixed);await click(owner,'[data-command="start"]');let waited=false;
  await finish(owner,mixed,async()=>{if(waited)return;waited=true;const before=h.snapshot(mixed);const tick=h.sql(`SELECT cpu_checked_at FROM game_instance.rules_session WHERE session_id='${mixed}'`);await h.eventually(()=>h.sql(`SELECT (cpu_checked_at>'${tick}'::timestamptz)::text FROM game_instance.rules_session WHERE session_id='${mixed}'`)==='true','CPU waits for human response');assert.deepEqual(h.snapshot(mixed),before);});assert.equal(waited,true);
  await owner.context().request.post(h.base+'/login',{form:{userId:'2',next:'/rules'},maxRedirects:0});await owner.getByText('Sign in again',{exact:true}).waitFor();assert.equal(await owner.locator('#mechanics').count(),0);assert.equal(await owner.evaluate(()=>Object.keys(sessionStorage).some(k=>k.startsWith('metis-rules:1:'))),false);
  assert.deepEqual(errors,[]);console.log('PASS: all three game styles plus card/reroll/token decisions finish in the generic browser; lost-response retry and read-only replay do not duplicate or regress state.');
 } finally { for(const context of contexts)await context.close();if(browser)await browser.close();await h.cleanup(); }
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
