// M6 publication/capture and catalogue tests: disposable environments only.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync}=require('node:child_process');
const {randomUUID}=require('node:crypto');
let base=process.env.TEST_BASE_URL;
const db=process.env.TEST_DB_CONTAINER,app=process.env.TEST_APP_CONTAINER,database=process.env.TEST_DB_NAME;
assert.ok(db?.startsWith('metis-rules-')&&app?.startsWith('metis-rules-')&&database==='metis_rules_test');
const docker=args=>execFileSync('docker',args,{encoding:'utf8'}).trim();
const sql=statement=>docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-tAc',statement]);
const cookies={},sessions=[];
async function login() {
 for(const user of [1,2,3,5]) { const r=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/`});assert.equal(r.status,302);cookies[user]=r.headers.getSetCookie().map(x=>x.split(';')[0]).join('; '); }
}
async function request(user,url,method='GET',body,status=200) {
 const r=await fetch(base+url,{method,headers:{Cookie:cookies[user],'Content-Type':'application/json'},body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const text=await r.text();assert.equal(r.status,status,`${method} ${url}: ${text}`);assert.equal(r.headers.get('cache-control'),'no-store');return text?JSON.parse(text):null;
}
const route=id=>`/api/v1/rules/sessions/${id}`;
const author=id=>`/api/v1/admin/rules/definitions/${id}`;
const state=(id,user=1)=>request(user,route(id));
const createDefinition=source=>request(5,'/api/v1/admin/rules/definitions','POST',source,201);
const publish=draft=>request(5,author(draft.id)+'/publish','POST',{expectedVersion:draft.version});
const mode=(key,parent,patches)=>({modeVersion:1,id:key,revision:'1',extends:{id:parent.key,revision:parent.revision,fingerprint:parent.fingerprint},overrides:patches});
const members={visibility:'private',seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3]};
async function create(definition) { const game=await request(1,'/api/v1/rules/sessions','POST',{definitionId:definition.id,...members},201);sessions.push(game.id);return game.id; }
async function command(id,user,extra,status=200) { const view=await state(id,user);return request(user,route(id)+'/commands','POST',{commandId:randomUUID(),expectedStateVersion:view.state.version,...extra},status); }
const captured=id=>sql(`SELECT md5(captured_definition||COALESCE(captured_resolution::text,'')) FROM game_instance.rules_session WHERE session_id='${id}'`);
async function restart() {
 docker(['kill','--signal=KILL',app]);docker(['start',app]);base='http://'+docker(['port',app,'8080/tcp']);
 const deadline=Date.now()+30000;while(true) { try { if((await fetch(base+'/login',{signal:AbortSignal.timeout(1000)})).status===200)break; }catch{}assert.ok(Date.now()<deadline,'Restart timed out');await new Promise(r=>setTimeout(r,100)); }await login();
}
function literal(v) { return Array.isArray(v.value)?v.value.map(literal):v.value&&typeof v.value==='object'?v.value.id:v.value; }
async function finish(id) {
 for(let n=0;n<100;n++) {
  const view=await state(id);if(view.state.status==='FINISHED') { assert.ok(['elimination','round-limit-draw'].includes(view.state.outcome));assert.deepEqual((await request(1,route(id)+'/history/'+view.state.version)).state,view.state);return view; }
  let submitted=false;
  for(const [user,actor] of [[1,'alice'],[2,'bob']]) {
   const forms=await request(user,route(id)+'/actions'),offer=forms.actors.find(a=>a.actor===actor);let extra;
   if(offer?.controls.includes('activate'))extra={type:'activate',actor,entityId:offer.entities[0]};
   else if(offer?.offered.length) { const a=offer.offered[0];extra={type:'act',actor,action:a.action,parameters:Object.fromEntries(Object.entries(a.parameters).map(([k,v])=>[k,literal(v)]))}; }
   else if(offer?.controls.includes('pass'))extra={type:'pass',actor};
   if(extra) { assert.equal((await command(id,user,extra)).accepted,true);submitted=true;break; }
  }
  assert.ok(submitted,'Catalogue session has no projected command');
 }
 assert.fail('Catalogue game did not reach its authored ending');
}
async function main() {
 await login();
 try {
  const source=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../../../engine-core/src/main/resources/rules/examples/resource-race.json'),'utf8'));source.id=`m6-base-${randomUUID()}`;
  const parent=await publish(await createDefinition(source));
  const wrapper=mode(`m6-fast-${randomUUID()}`,parent,[{op:'replace',path:'/state/target/initial',value:2}]);const draft=await createDefinition(wrapper);
  assert.equal((await request(5,author(draft.id)+'/validate','POST')).valid,true);
  const resolved=await request(5,author(draft.id)+'/resolved');assert.equal(JSON.parse(resolved.source).state.target.initial,2);assert.equal(resolved.resolution.origins['/state/target/initial'].id,wrapper.id);assert.equal(resolved.resolution.origins['/state/target/type'].id,parent.key);assert.equal(resolved.resolution.layers.length,2);
  const child=await publish(draft);assert.equal(JSON.parse(child.source).modeVersion,1);assert.equal(child.fingerprint,resolved.fingerprint);await request(1,author(draft.id)+'/resolved','GET',undefined,403);
  const game=await create(child);await command(game,1,{type:'start'});await command(game,1,{type:'act',actor:'alice',action:'collect',parameters:{counter:'alice'}});const before=await state(game),capture=captured(game);
  const newer={...source,revision:'2',state:{...source.state,target:{...source.state.target,initial:100}}};await publish(await createDefinition(newer));assert.equal(captured(game),capture);
  sql(`DO $$ DECLARE denied boolean:=false; BEGIN
   BEGIN UPDATE game_engine.rules_definition SET resolved_json='{}' WHERE definition_id=${child.id}; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END; ASSERT denied; denied:=false;
   BEGIN UPDATE game_instance.rules_session SET captured_resolution='{}' WHERE session_id='${game}'; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END; ASSERT denied;
   ASSERT (SELECT count(*) FROM game_engine.rules_definition_dependency WHERE definition_id=${child.id})=1; END $$`);
  await restart();assert.deepEqual(await state(game),before);assert.equal(captured(game),capture);assert.equal((await request(1,route(game)+'/compatibility')).resolverVersion,'rules-resolver-1');
  await command(game,2,{type:'act',actor:'bob',action:'collect',parameters:{counter:'bob'}});const ended=await command(game,1,{type:'act',actor:'alice',action:'collect',parameters:{counter:'alice'}});assert.equal(ended.view.state.status,'FINISHED');assert.equal(ended.view.state.entities.alice.attributes.score.value,2);
  assert.deepEqual((await request(1,route(game)+'/history/'+ended.view.state.version)).state,ended.view.state);
  for(const [modify,code] of [
   [m=>m.extends.fingerprint='0'.repeat(64),'BASE_FINGERPRINT'],
   [m=>m.extends.id='missing','BASE_NOT_FOUND'],
   [m=>m.extends.id=m.id,'MODE_CYCLE'],
   [m=>m.modeVersion=2,'MODE_VERSION'],
   [m=>m.overrides=[{op:'replace',path:'/flow/phases/turn/steps/0',value:{}}],'OVERRIDE_PATH']
  ]) { const invalid=mode(`m6-bad-${randomUUID()}`,parent,[]);modify(invalid);const d=await createDefinition(invalid);const response=await request(5,author(d.id)+'/publish','POST',{expectedVersion:0},422);assert.equal(response.diagnostics[0].code,code);assert.equal((await request(5,author(d.id))).lifecycle,'draft'); }
  const precise=mode(`m6-exact-${randomUUID()}`,parent,[{op:'add',path:'/state/exact',value:{type:'decimal',initial:'EXACT',visibility:'PUBLIC'}}]);const exact=await publish(await createDefinition(JSON.stringify(precise).replace('"EXACT"','0.1234567890123456789012345678901234')));assert.ok((await request(5,author(exact.id)+'/resolved')).source.includes('0.1234567890123456789012345678901234'));
  // Exercise the pre-M6 stored shape, including a null resolution capture.
  const oldSource={...source,id:`m6-legacy-${randomUUID()}`};const oldDraft=await createDefinition(oldSource);const oldResolved=await request(5,author(oldDraft.id)+'/resolved');
  sql(`DO $$ DECLARE d game_engine.rules_definition%ROWTYPE; BEGIN SELECT * INTO d FROM game_engine.rules_definition WHERE definition_id=${oldDraft.id}; CALL game_engine.sp_rules_definition_publish(d.definition_id,0,d.source_json,'${oldResolved.fingerprint}','rules-compiler-3','rules-runtime-2','{"core":"1"}'); END $$`);
  const old=await create(oldDraft);assert.equal((await request(1,route(old)+'/compatibility')).resolverVersion,'none');await command(old,1,{type:'start'});
  const incompatible=await create(child);sql(`SET session_replication_role=replica; UPDATE game_instance.rules_session SET runtime_version='rules-runtime-future',checkpoint_json=jsonb_set(checkpoint_json::jsonb,'{runtimeVersion}','"rules-runtime-future"')::text WHERE session_id='${incompatible}';`);assert.equal((await request(1,route(incompatible)+'/compatibility')).compatible,false);await request(1,route(incompatible),'GET',undefined,409);
  sql(`SET session_replication_role=replica; UPDATE game_instance.rules_session SET runtime_version='rules-runtime-2',checkpoint_json=jsonb_set(checkpoint_json::jsonb,'{runtimeVersion}','"rules-runtime-2"')::text,captured_resolution=jsonb_set(captured_resolution,'{resolverVersion}','"rules-resolver-future"') WHERE session_id='${incompatible}';`);assert.equal((await request(1,route(incompatible)+'/compatibility')).compatible,false);await request(1,route(incompatible),'GET',undefined,409);
  const malformed=await createDefinition({...source,id:`m6-metadata-${randomUUID()}`});sql(`DO $$ DECLARE denied boolean:=false; BEGIN BEGIN UPDATE game_engine.rules_definition SET resolved_json='{}',resolution_json='{}' WHERE definition_id=${malformed.id}; EXCEPTION WHEN check_violation THEN denied:=true; END; ASSERT denied; END $$`);
  console.log('PASS: pinned mode resolution, exact numbers, provenance, immutable capture, newer revisions, old captures and explicit incompatible-version rejection.');

  const version=Number(sql("SELECT game_engine.fn_published_definition_id(game_id) FROM game_engine.game WHERE game_name='Star Wars: Armada'"));const importBody={gameVersionId:version,pack:'armada-squadron-training-v1'};
  await request(1,'/api/v1/admin/rules/catalogue/imports','POST',importBody,403);
  sql(`CREATE FUNCTION rules_test_support.fail_catalogue() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.mode_code='short-battle' THEN RAISE EXCEPTION 'Injected last-mode registration failure'; END IF; RETURN NEW; END $$;
   CREATE TRIGGER rules_test_catalogue BEFORE INSERT ON game_engine.rules_catalogue_entry FOR EACH ROW EXECUTE FUNCTION rules_test_support.fail_catalogue();`);
  try {
   await request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody,422);
   assert.equal(Number(sql("SELECT count(*) FROM game_engine.rules_definition WHERE definition_key LIKE 'armada-squadron-training%'")),0,'Failure must roll back base, modes and dependencies');
   assert.equal(Number(sql('SELECT count(*) FROM game_engine.rules_catalogue_entry')),0);
  } finally { sql('DROP TRIGGER rules_test_catalogue ON game_engine.rules_catalogue_entry; DROP FUNCTION rules_test_support.fail_catalogue();'); }
  const [entries,concurrent]=await Promise.all([request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody),request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody)]);assert.equal(entries.length,3);assert.deepEqual(concurrent,entries);assert.deepEqual(await request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody),entries);
  const catalogue=await request(1,'/api/v1/rules/catalogue');assert.equal(catalogue.filter(e=>e.sourceVersionId===version).length,3);assert.ok(catalogue.every(e=>!('snapshot' in e)&&!('source' in e)));
  const standard=entries.find(e=>e.mode==='standard');const data=JSON.parse((await request(5,author(standard.definitionId)+'/resolved')).source);assert.deepEqual(data.state.hulls.initial,[3,5]);assert.deepEqual(data.state.speeds.initial,[400,300]);assert.deepEqual(data.state.pools.initial.map(p=>p.length),[3,4]);assert.ok(data.state.catalogueIds.initial.every(v=>/^\d+$/.test(v)));
  sql(`DO $$ DECLARE denied boolean:=false; BEGIN BEGIN UPDATE game_engine.rules_catalogue_entry SET scope_json='{}' WHERE entry_id=${standard.id}; EXCEPTION WHEN SQLSTATE '55000' THEN denied:=true; END; ASSERT denied; END $$`);
  await request(1,`/api/v1/rules/catalogue/${standard.id}/sessions`,'POST',{...members,definitionId:parent.id},400);
  for(const entry of entries) {
   const session=await request(1,`/api/v1/rules/catalogue/${entry.id}/sessions`,'POST',members,201);sessions.push(session.id);assert.equal(session.definitionId,entry.definitionId);await command(session.id,1,{type:'start'});
   const start=await state(session.id);assert.equal(start.state.entities.alice.attributes.name.value,'TIE Fighter Squadron');assert.equal(start.state.entities.bob.resources.health,5);
   if(entry.mode==='close-engagement')assert.equal(start.state.entities.alice.attributes.x.value,300);if(entry.mode==='short-battle')assert.equal(start.state.globals.roundLimit.value,3);
   await finish(session.id);
  }
  const m8Body={gameVersionId:version,pack:'armada-squadron-training-m8'};
  const m8Entries=await request(5,'/api/v1/admin/rules/catalogue/imports','POST',m8Body);assert.equal(m8Entries.length,3);
  const m8Standard=m8Entries.find(e=>e.mode==='standard');const m8Source=JSON.parse((await request(5,author(m8Standard.definitionId)+'/resolved')).source);
  assert.equal(m8Source.flow.initial,'deployment');assert.equal(m8Source.scenario.rosters.fleet.maximum,1);assert.equal(m8Source.space.zones.deployment0.ownerSeat,0);assert.equal(m8Source.space.zones.deployment1.ownerSeat,1);
  const m8Session=await request(1,`/api/v1/rules/catalogue/${m8Standard.id}/sessions`,'POST',members,201);sessions.push(m8Session.id);await command(m8Session.id,1,{type:'start'});assert.equal((await state(m8Session.id)).state.phase,'deployment');
  await command(m8Session.id,1,{type:'activate',actor:'alice',entityId:'alice'});await command(m8Session.id,1,{type:'act',actor:'alice',action:'deploy',parameters:{entity:'alice',zone:'deployment1',x:800,y:300,heading:0}},422);await command(m8Session.id,1,{type:'act',actor:'alice',action:'deploy',parameters:{entity:'alice',zone:'deployment0',x:200,y:300,heading:0}});
  await command(m8Session.id,2,{type:'activate',actor:'bob',entityId:'bob'});await command(m8Session.id,2,{type:'act',actor:'bob',action:'deploy',parameters:{entity:'bob',zone:'deployment1',x:800,y:300,heading:0}});
  assert.equal((await state(m8Session.id)).state.phase,'squadrons');
  assert.deepEqual(await request(5,'/api/v1/admin/rules/catalogue/imports','POST',m8Body),m8Entries);
  await restart();assert.deepEqual(await request(5,'/api/v1/admin/rules/catalogue/imports','POST',importBody),entries);
  assert.deepEqual(await request(5,'/api/v1/admin/rules/catalogue/imports','POST',m8Body),m8Entries);
  console.log('PASS: catalogue profiles imported atomically and idempotently; base and two modes complete through interpreted commands with replay and restart.');
 } finally { for(const id of sessions)await request(1,route(id),'DELETE',undefined,204); }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
