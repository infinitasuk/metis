const assert=require('node:assert/strict');
const {randomUUID}=require('node:crypto');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const h=require('./rules-client-support.cjs');
const root='/api/v1/rules/fleet-presets',saved=new Map();let browser;
async function save(user,body) { const preset=await h.request(user,root,'POST',body,201);saved.set(preset.id,user);return preset; }
async function main() {
 await h.login();
 try {
  const version=Number(h.sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));
  const entries=await h.request(5,'/api/v1/admin/rules/catalogue/imports','POST',{gameVersionId:version,pack:'armada-squadron-rosters-v1'});
  const entry=entries.find(e=>e.mode==='short-battle'),metadata=await h.request(1,`/api/v1/rules/catalogue/${entry.id}/roster`);
  const tie=metadata.units.find(u=>u.points===8).code,xwing=metadata.units.find(u=>u.points===13).code;
  const body={name:'API patrol',definitionId:entry.definitionId,seat:0,policy:'imperial',units:[tie,tie,tie]};
  const initialCount=Number(h.sql('SELECT count(*) FROM game_instance.rules_fleet_preset'));
  assert.equal((await h.request(1,root,'POST',{...body,points:0},400)).code,'REQUEST_FIELDS_INVALID');
  assert.equal((await h.request(1,root,'POST',{...body,ownerUserId:2},400)).code,'REQUEST_FIELDS_INVALID');
  assert.equal((await h.request(1,root,'POST',{...body,policy:'rebel',units:[xwing,xwing,xwing]},422)).code,'ROSTER_BUDGET');
  assert.equal(Number(h.sql('SELECT count(*) FROM game_instance.rules_fleet_preset')),initialCount);
  let preset=await save(1,body);assert.equal(preset.snapshot.points,24);assert.equal(preset.snapshot.fingerprint,metadata.fingerprint);
  assert.equal((await h.request(1,root,'POST',{...body,name:'api PATROL'},409)).code,'PRESET_NAME_EXISTS');
  for(const user of [2,3,5]) {
   assert.ok(!(await h.request(user,root)).some(p=>p.id===preset.id));
   await h.request(user,root+'/'+preset.id,'GET',undefined,404);
   await h.request(user,root+'/'+preset.id,'PATCH',{name:'Taken',expectedVersion:0},404);
   await h.request(user,root+'/'+preset.id+'?expectedVersion=0','DELETE',undefined,404);
   await h.request(user,root+'/'+preset.id+'/load','POST',{definitionId:entry.definitionId,seat:0},404);
  }
  const other=await save(2,body);assert.notEqual(other.id,preset.id,'Names are scoped to the owner');
  h.sql(`DO $$ DECLARE denied boolean:=false; BEGIN
   ASSERT NOT EXISTS(SELECT 1 FROM game_instance.fn_rules_fleet_preset_get(2,${preset.id}));
   BEGIN UPDATE game_instance.rules_fleet_preset SET snapshot_json=jsonb_set(snapshot_json,'{points}','0'),edit_version=edit_version+1 WHERE preset_id=${preset.id};
   EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END; ASSERT denied; END $$`);
  // Real transaction failure after insert must not leave a partial saved fleet.
  h.sql(`CREATE SCHEMA IF NOT EXISTS rules_test_support;
   CREATE FUNCTION rules_test_support.fail_preset() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.preset_name='Injected preset failure' THEN RAISE EXCEPTION 'Injected preset failure'; END IF; RETURN NEW; END $$;
   CREATE TRIGGER fail_preset AFTER INSERT ON game_instance.rules_fleet_preset FOR EACH ROW EXECUTE FUNCTION rules_test_support.fail_preset();`);
  try { await h.request(1,root,'POST',{...body,name:'Injected preset failure'},422);assert.equal(Number(h.sql('SELECT count(*) FROM game_instance.rules_fleet_preset')),initialCount+2); }
  finally { h.sql('DROP TRIGGER fail_preset ON game_instance.rules_fleet_preset; DROP FUNCTION rules_test_support.fail_preset();'); }
  const loaded=await h.request(1,`${root}/${preset.id}/load`,'POST',{definitionId:entry.definitionId,seat:1});
  assert.equal(loaded.valid,true);assert.equal(loaded.revisionChanged,false);assert.equal(loaded.points,24);assert.deepEqual(loaded.selection,preset.selection);
  await h.restart();assert.deepEqual(await h.request(1,root+'/'+preset.id),preset);

  async function rename(name) {
   const r=await fetch(h.base+root+'/'+preset.id,{method:'PATCH',headers:{Cookie:h.cookies[1],'Content-Type':'application/json'},body:JSON.stringify({name,expectedVersion:0})});
   return {status:r.status,body:await r.json()};
  }
  const races=await Promise.all([rename('Patrol A'),rename('Patrol B')]);assert.deepEqual(races.map(r=>r.status).sort(),[200,409]);
  assert.equal(races.find(r=>r.status===409).body.code,'STALE_PRESET');preset=races.find(r=>r.status===200).body;assert.equal(preset.version,1);
  assert.equal((await h.request(1,`${root}/${preset.id}?expectedVersion=0`,'DELETE',undefined,409)).code,'STALE_PRESET');
  assert.equal(preset.snapshot.points,24);

  const resolved=await h.request(5,`/api/v1/admin/rules/definitions/${entry.definitionId}/resolved`);
  async function revision(points,missing=false) {
   const source=JSON.parse(resolved.source);source.revision='preset-'+randomUUID();source.state.catalogueIds.initial[0]='remapped-tie';source.state.points.initial[0]=points;
   const origin=JSON.parse(source.state.pointSources.initial[0]);origin.rawPoints=String(points);if(missing)origin.entryId='another-source-unit';source.state.pointSources.initial[0]=JSON.stringify(origin);
   const draft=await h.request(5,'/api/v1/admin/rules/definitions','POST',source,201);
   return h.request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});
  }
  const costly=await revision(9),cheaper=await revision(7),missing=await revision(7,true);
  const beforeLoad=await h.request(1,root+'/'+preset.id);
  const over=await h.request(1,`${root}/${preset.id}/load`,'POST',{definitionId:costly.id,seat:0});assert.equal(over.valid,false);assert.equal(over.code,'ROSTER_BUDGET');assert.equal(over.points,27);assert.equal(over.selection,null);
  const remapped=await h.request(1,`${root}/${preset.id}/load`,'POST',{definitionId:cheaper.id,seat:0});assert.equal(remapped.valid,true);assert.equal(remapped.revisionChanged,true);assert.equal(remapped.savedPoints,24);assert.equal(remapped.points,21);assert.deepEqual(remapped.selection.units,['remapped-tie','remapped-tie','remapped-tie']);
  assert.equal((await h.request(1,`${root}/${preset.id}/load`,'POST',{definitionId:missing.id,seat:0})).code,'PRESET_UNIT_UNAVAILABLE');
  assert.deepEqual(await h.request(1,root+'/'+preset.id),beforeLoad,'Loading never rewrites a saved revision');
  const game=await h.request(1,'/api/v1/rules/sessions','POST',{definitionId:cheaper.id,visibility:'private',seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:1}],viewers:[],rosters:[remapped.selection,{policy:'rebel',units:[xwing]}]},201);h.sessions.push(game.id);
  const capture=await h.request(1,h.route(game.id)+'/roster');assert.equal(capture.seats[0].points,21);
  await h.request(1,`${root}/${preset.id}?expectedVersion=${preset.version}`,'DELETE',undefined,204);saved.delete(preset.id);
  assert.deepEqual(await h.request(1,h.route(game.id)+'/roster'),capture);await h.command(game.id,1,{type:'start'});
  assert.equal((await h.state(game.id)).state.entities['seat0-unit0'].attributes.points.value,7);
  console.log('PASS: private fleet presets persist, reject stale edits and cross-user access, roll back failures, and revalidate/remap revisions without changing saved fleets or captured sessions.');

  browser=await chromium.launch({headless:true});const context=await browser.newContext();await context.request.post(h.base+'/login',{form:{userId:'1',next:'/rules'},maxRedirects:0});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  async function open() { await page.goto(h.base+'/rules');await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='Ready.');await page.locator('#definition').selectOption(`c:${entry.id}`);await page.getByLabel('Seat 1 faction',{exact:true}).waitFor(); }
  await open();await page.getByRole('button',{name:'Add unit to seat 1',exact:true}).click();
  const browserName='Browser patrol <img src=x onerror=alert(1)>';
  await page.getByLabel('Seat 1 fleet name',{exact:true}).fill(browserName);await page.getByRole('button',{name:'Save fleet for seat 1',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('[data-preset-message="0"]')?.textContent.startsWith('Saved '));
  const browserPreset=(await h.request(1,root)).find(p=>p.name===browserName);assert.ok(browserPreset);saved.set(browserPreset.id,1);
  assert.equal(await page.locator('#catalogue-roster img').count(),0);
  await open();await page.getByLabel('Seat 1 saved fleet',{exact:true}).selectOption(String(browserPreset.id));
  await page.getByRole('button',{name:'Remove seat 1 unit 2',exact:true}).click();
  let release,arrived;const blocked=new Promise(resolve=>arrived=resolve),gate=new Promise(resolve=>release=resolve);
  await page.route(`**/fleet-presets/${browserPreset.id}/load`,async route=>{arrived();await gate;await route.continue();});
  await page.getByRole('button',{name:'Load fleet for seat 1',exact:true}).click();await blocked;
  await page.getByRole('button',{name:'Add unit to seat 1',exact:true}).click();await page.getByRole('button',{name:'Add unit to seat 1',exact:true}).click();release();
  await page.waitForFunction(()=>document.querySelector('[data-preset-message="0"]')?.textContent.includes('Roster changed'));
  assert.equal(await page.locator('[data-roster-seat="0"] select[aria-label*=" unit "]').count(),3);await page.unroute(`**/fleet-presets/${browserPreset.id}/load`);
  await page.getByRole('button',{name:'Load fleet for seat 1',exact:true}).click();await page.waitForFunction(()=>document.querySelector('[data-preset-message="0"]')?.textContent.startsWith('Loaded '));
  assert.equal(await page.locator('[data-roster-seat="0"] select[aria-label*=" unit "]').count(),2);
  await page.locator('.seat-controller').nth(1).selectOption('1');await page.locator('#create').click();await page.waitForURL('**/rules/sessions/*');const browserGame=page.url().split('/').pop();h.sessions.push(browserGame);
  const browserCapture=await h.request(1,h.route(browserGame)+'/roster');assert.equal(browserCapture.seats[0].points,16);
  await open();await page.getByLabel('Seat 1 saved fleet',{exact:true}).selectOption(String(browserPreset.id));await page.getByLabel('Seat 1 fleet name',{exact:true}).fill('Renamed browser patrol');
  await page.getByRole('button',{name:'Rename fleet for seat 1',exact:true}).click();await page.waitForFunction(()=>document.querySelector('[data-preset-message="0"]')?.textContent==='Renamed to Renamed browser patrol.');
  await page.getByRole('button',{name:'Delete fleet for seat 1',exact:true}).click();await page.waitForFunction(()=>document.querySelector('[data-preset-message="0"]')?.textContent==='Deleted Renamed browser patrol.');saved.delete(browserPreset.id);
  await h.request(1,root+'/'+browserPreset.id,'GET',undefined,404);assert.deepEqual(await h.request(1,h.route(browserGame)+'/roster'),browserCapture);
  // Identity changes clear preset names and controls along with the old user's view.
  await context.request.post(h.base+'/login',{form:{userId:'2',next:'/rules'},maxRedirects:0});await page.locator('#refresh-presets').click();await page.getByText('Sign in again',{exact:true}).waitFor();assert.equal(await page.locator('[data-roster-seat]').count(),0);
  assert.deepEqual(errors,[]);console.log('PASS: browser saves, reloads, renames and deletes personal fleets, handles stale loads, creates a match from a preset, and clears private controls on identity change.');
 }finally{
  if(browser)await browser.close();
  for(const [id,user] of saved){const row=await h.request(user,root+'/'+id);await h.request(user,`${root}/${id}?expectedVersion=${row.version}`,'DELETE',undefined,204);}
  await h.cleanup();
 }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
