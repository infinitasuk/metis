// Runs only against the full suite's disposable app/database.
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
const cookies={},games=[];
async function login() {
 for(const user of [1,2,3,5]) {
  const result=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/`});
  assert.equal(result.status,302);cookies[user]=result.headers.getSetCookie().map(x=>x.split(';')[0]).join('; ');
 }
}
async function request(user,url,method='GET',body,status=200) {
 const result=await fetch(base+url,{method,headers:{Cookie:cookies[user],'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const text=await result.text();assert.equal(result.status,status,`${method} ${url}: ${text}`);assert.equal(result.headers.get('cache-control'),'no-store');return text?JSON.parse(text):null;
}
const route=id=>`/api/v1/rules/sessions/${id}`;
const state=(id,user=1)=>request(user,route(id));
async function create(name) {
 const source=JSON.parse(fs.readFileSync(path.resolve(__dirname,`../../../../engine-core/src/main/resources/rules/examples/${name}.json`),'utf8'));source.id=`m5-${randomUUID()}`;
 const draft=await request(5,'/api/v1/admin/rules/definitions','POST',source,201);
 const definition=await request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});assert.equal(definition.compilerVersion,'rules-compiler-5');assert.equal(definition.runtimeVersion,'rules-runtime-4');
 const game=await request(1,'/api/v1/rules/sessions','POST',{definitionId:definition.id,visibility:'private',seats:[{playerId:'alice',userId:1},{playerId:'bob',userId:2}],viewers:[3]},201);games.push(game.id);
 await command(game.id,1,{type:'start'});return game.id;
}
async function command(id,user,extra,status=200,version,key=randomUUID()) {
 const body={expectedStateVersion:version??(await state(id,user)).state.version,commandId:key,...extra};return {body,result:await request(user,route(id)+'/commands','POST',body,status)};
}
const persisted=id=>sql(`SELECT json_build_object('version',state_version,'checkpoint',md5(checkpoint_json),'transitions',(SELECT count(*) FROM game_instance.rules_transition WHERE session_id='${id}')) FROM game_instance.rules_session WHERE session_id='${id}'`);
function literal(value) { return Array.isArray(value.value)?value.value.map(literal):value.value&&typeof value.value==='object'?value.value.id:value.value; }
async function finish(id) {
 for(let i=0;i<300;i++) {
  const current=await state(id);if(current.state.status==='FINISHED') {
   assert.ok(current.state.winners.length);assert.deepEqual((await request(1,route(id)+'/history/'+current.state.version)).state,current.state);return current;
  }
  let submitted=false;
  for(const [user,actor] of [[1,'alice'],[2,'bob']]) {
   const view=await state(id,user),forms=await request(user,route(id)+'/actions'),offered=forms.actors.find(a=>a.actor===actor);
   let extra;
   if(view.state.decision?.player===actor) { assert.ok(view.state.decision.options.length);extra={type:'respond',actor,decisionId:view.state.decision.id,option:0}; }
   else if(offered?.controls.includes('activate'))extra={type:'activate',actor,entityId:offered.entities[0]};
   else if(offered?.offered.length) { const choice=offered.offered[0];extra={type:'act',actor,action:choice.action,parameters:Object.fromEntries(Object.entries(choice.parameters).map(([key,value])=>[key,literal(value)]))}; }
   else if(offered?.controls.includes('advanceStep'))extra={type:'advanceStep',actor};
   else if(offered?.controls.includes('pass'))extra={type:'pass',actor};
   if(extra) {
    if(extra.action==='move') { const p=await request(user,route(id)+'/preview','POST',{actor,action:'move',parameters:extra.parameters,expectedStateVersion:view.state.version});assert.equal(p.allowed,true);assert.ok(p.movements.length); }
    const result=await command(id,user,extra,200,view.state.version);assert.equal(result.result.accepted,true);submitted=true;break;
   }
  }
  assert.ok(submitted,`No projected command at ${JSON.stringify(current.state)}`);
 }
 assert.fail('Operational command cap reached before a rules ending');
}
async function main() {
 await login();
 try {
  const grid=await create('grid-skirmish');await command(grid,1,{type:'activate',actor:'alice',entityId:'alice-a'});
  const before=await state(grid),saved=persisted(grid),previewBody={actor:'alice',action:'move',parameters:{entity:'alice-a',heading:0,path:[[2,1],[3,1]]},expectedStateVersion:before.state.version};
  const preview=await request(1,route(grid)+'/preview','POST',previewBody);assert.equal(preview.allowed,true);assert.deepEqual(preview.movements[0].to,{x:3,y:1,heading:0});assert.equal(preview.movements[0].distance,2);
  assert.equal((await request(1,route(grid)+'/actions')).actors[0].offered.some(a=>a.action==='move'),true);
  assert.equal(persisted(grid),saved,'Preview and offers cannot persist speculative effects');
  for(const user of [2,3,5])await request(user,route(grid)+'/preview','POST',previewBody,403);
  for(const user of [3,5])assert.equal((await request(user,route(grid)+'/actions')).actors.length,0);
  await request(1,route(grid)+'/preview','POST',{...previewBody,expectedStateVersion:0},409);
  const invalid={...previewBody,parameters:{...previewBody.parameters,path:[[3,1]]}};assert.equal((await request(1,route(grid)+'/preview','POST',invalid)).code,'GRID_PATH');
  const denied=await command(grid,1,{type:'act',actor:'alice',action:'move',parameters:invalid.parameters},422);assert.equal(denied.result.code,'GRID_PATH');assert.equal(persisted(grid),saved);
  const moved=await command(grid,1,{type:'act',actor:'alice',action:'move',parameters:previewBody.parameters});assert.equal(moved.result.view.state.entities['alice-a'].attributes.x.value,preview.movements[0].to.x);
  assert.deepEqual(await request(1,route(grid)+'/commands','POST',moved.body),moved.result);assert.equal((await request(1,route(grid)+'/history/'+before.state.version)).state.entities['alice-a'].attributes.x.value,1);
  const views=new Map();for(const user of [1,2,3,5])views.set(user,await state(grid,user));docker(['kill','--signal=KILL',app]);docker(['start',app]);base='http://'+docker(['port',app,'8080/tcp']);
  const deadline=Date.now()+30000;while(true) { try { if((await fetch(base+'/login',{signal:AbortSignal.timeout(1000)})).status===200)break; }catch{}assert.ok(Date.now()<deadline,'Restart timeout');await new Promise(r=>setTimeout(r,100)); }
  await login();for(const user of [1,2,3,5])assert.deepEqual(await state(grid,user),views.get(user));assert.deepEqual(await request(1,route(grid)+'/commands','POST',moved.body),moved.result);
  console.log('PASS: authoritative spatial preview, atomic commit, actor privacy, stale inputs, idempotency and process restart.');
  await finish(grid);for(const name of ['vector-orders','hidden-grid-cpu'])await finish(await create(name));
  console.log('PASS: three contrasting games complete via projected HTTP offers with terminal replay agreement.');
 } finally { for(const id of games)await request(1,route(id),'DELETE',undefined,204); }
}
main().catch(error=>{console.error(error.stack||error);process.exitCode=1;});
