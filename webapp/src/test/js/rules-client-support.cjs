const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {execFileSync}=require('node:child_process');
const {randomUUID}=require('node:crypto');
let base=process.env.TEST_BASE_URL;
const db=process.env.TEST_DB_CONTAINER,app=process.env.TEST_APP_CONTAINER,database=process.env.TEST_DB_NAME;
assert.ok(db?.startsWith('metis-rules-')&&app?.startsWith('metis-rules-')&&database==='metis_rules_test','Disposable rules environment required');
const cookies={},sessions=[];
const docker=args=>execFileSync('docker',args,{encoding:'utf8'}).trim();
const sql=statement=>docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-tAc',statement]);
const route=id=>`/api/v1/rules/sessions/${id}`;
async function login() { for(const user of [1,2,3,4,5]) { const r=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/rules`});assert.equal(r.status,302);cookies[user]=r.headers.getSetCookie().map(c=>c.split(';')[0]).join('; '); } }
async function request(user,url,method='GET',body,status=200,headers={}) {
 const r=await fetch(base+url,{method,headers:{Cookie:cookies[user],'Content-Type':'application/json',...headers},body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
 const text=await r.text();assert.equal(r.status,status,`${method} ${url}: ${text}`);assert.equal(r.headers.get('cache-control'),'no-store');return text?JSON.parse(text):null;
}
async function publish(example,edit) {
 const source=JSON.parse(fs.readFileSync(path.resolve(__dirname,`../../../../engine-core/src/main/resources/rules/examples/${example}.json`),'utf8'));source.id=`m7-${randomUUID()}`;if(edit)edit(source);
 const draft=await request(5,'/api/v1/admin/rules/definitions','POST',source,201);return request(5,`/api/v1/admin/rules/definitions/${draft.id}/publish`,'POST',{expectedVersion:draft.version});
}
async function create(definition,{cpu=false,seats,limit=1000}={}) {
 const game=await request(1,'/api/v1/rules/sessions','POST',{definitionId:definition.id,visibility:'private',seats:seats||(cpu?[{playerId:'alice',kind:'CPU'},{playerId:'bob',kind:'CPU'}]:[{playerId:'alice',userId:1},{playerId:'bob',userId:1}]),viewers:[3],cpuCommandLimit:limit},201);sessions.push(game.id);return game.id;
}
const state=(id,user=1)=>request(user,route(id));
async function command(id,user,extra,status=200) { const current=await state(id,user);return request(user,route(id)+'/commands','POST',{type:extra.type,commandId:randomUUID(),expectedStateVersion:current.state.version,...extra},status); }
async function eventually(check,label,ms=30000) { const deadline=Date.now()+ms;while(Date.now()<deadline) { const value=await check();if(value)return value;await new Promise(r=>setTimeout(r,100)); }throw Error('Timeout: '+label); }
async function restart() { docker(['kill','--signal=KILL',app]);docker(['start',app]);base='http://'+docker(['port',app,'8080/tcp']);await eventually(async()=>{try{return (await fetch(base+'/login',{signal:AbortSignal.timeout(1000)})).status===200;}catch{return false;}},'app restart');await login(); }
const snapshot=id=>JSON.parse(sql(`SELECT json_build_object('version',state_version,'hash',md5(checkpoint_json),'steps',cpu_steps,'receipts',(SELECT count(*) FROM game_instance.rules_command WHERE session_id='${id}')) FROM game_instance.rules_session WHERE session_id='${id}'`));
async function cleanup() { for(const id of sessions)await request(1,route(id),'DELETE',undefined,204); }
module.exports={get base(){return base;},db,app,database,cookies,sessions,docker,sql,route,login,request,publish,create,state,command,eventually,restart,snapshot,cleanup};
