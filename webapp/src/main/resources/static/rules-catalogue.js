import {request,element,option,wire,ExactNumber} from './rules-common.js';
const $=id=>document.getElementById(id),user=document.body.dataset.user;
let users=[],entries=[],definitions=[],draft=null,seatNumber=0;
const message=(text,error=false)=>{ if($('connection')) { $('connection').textContent=text;$('connection').dataset.error=String(error); } };
async function sessions() {
 const {data}=await request('/api/v1/rules/sessions');$('sessions').replaceChildren();
 for(const s of data) { const link=element('a',`${s.name} · ${s.paused?'PAUSED':s.status} · v${s.version}${s.error?' · '+s.error:''}`,{href:`/rules/sessions/${s.id}`,class:'rules-session-link'});$('sessions').append(link); }
 if(!data.length)$('sessions').append(element('p','No visible sessions yet.'));
}
async function choices() {
 [entries,definitions]=await Promise.all(['/api/v1/rules/catalogue','/api/v1/rules/definitions'].map(async url=>(await request(url)).data));
 const selected=$('definition').value;$('definition').replaceChildren();
 const registered=new Set(entries.map(e=>String(e.definitionId)));
 for(const e of entries)$('definition').append(option(`c:${e.id}`,e.name));
 for(const d of definitions)if(!registered.has(String(d.id)))$('definition').append(option(`d:${d.id}`,`${d.key} · ${d.revision}`));
 if([...$('definition').options].some(o=>o.value===selected))$('definition').value=selected;
 $('create').disabled=!$('definition').options.length;scope();
}
function scope() {
 $('scope').replaceChildren();const entry=entries.find(e=>`c:${e.id}`===$('definition').value);if(!entry)return;
 for(const [name,value] of Object.entries(entry.scope))$('scope').append(element('p',`${name}: ${Array.isArray(value)?value.join('; '):value}`));
}
function seat() {
 const row=element('div',undefined,{class:'rules-seat'});const name=element('input',undefined,{value:`player${++seatNumber}`,required:'',pattern:'[a-zA-Z0-9_-]{1,128}','aria-label':'Player identity',class:'seat-player'});
 const controller=element('select',undefined,{'aria-label':'Seat controller',class:'seat-controller'});for(const u of users)controller.append(option(String(u.userId),u.displayName));controller.append(option('cpu','CPU — baseline policy'));controller.value=seatNumber===1?user:'cpu';
 const remove=element('button','Remove',{type:'button'});remove.onclick=()=>row.remove();row.append(name,controller,remove);$('seats').append(row);
}
$('add-seat').onclick=seat;$('definition').onchange=scope;$('refresh-sessions').onclick=()=>sessions().catch(e=>message(e.message,true));
$('create-session').onsubmit=async event=>{
 event.preventDefault();$('create').disabled=true;
 try {
  const seats=[...$('seats').children].map(row=>{const playerId=row.querySelector('input').value,controller=row.querySelector('select').value;return controller==='cpu'?{playerId,kind:'CPU'}:{playerId,userId:new ExactNumber(controller)};});
  const body={visibility:$('visibility').value,seats,viewers:[...$('viewers').selectedOptions].map(o=>new ExactNumber(o.value)),cpuCommandLimit:new ExactNumber($('cpu-limit').value)};
  const [kind,id]=$('definition').value.split(':');if(kind==='d')body.definitionId=new ExactNumber(id);
  const {data}=await request(kind==='c'?`/api/v1/rules/catalogue/${id}/sessions`:'/api/v1/rules/sessions',{method:'POST',body});location.assign(`/rules/sessions/${data.id}`);
 }catch(error) { message(error.message,true);if($('create'))$('create').disabled=false; }
};
async function drafts() {
 const {data}=await request('/api/v1/admin/rules/definitions');const selected=$('draft-list').value;$('draft-list').replaceChildren(option('','New definition'));
 for(const d of data)$('draft-list').append(option(String(d.id),`${d.key} · ${d.revision} · ${d.lifecycle}`));$('draft-list').value=selected;
}
function authorState(dirty=false) {
 const published=draft?.lifecycle==='published';$('source').readOnly=published;$('save-definition').disabled=published;
 for(const id of ['validate-definition','resolve-definition','publish-definition'])$(id).disabled=!draft||dirty||(published&&id==='publish-definition');
}
function showError(error) { if($('author-result'))$('author-result').textContent=error.data?wire(error.data):error.message; }
if($('draft-list')) {
 $('draft-list').onchange=async()=>{try { draft=$('draft-list').value?(await request(`/api/v1/admin/rules/definitions/${$('draft-list').value}`)).data:null;$('source').value=draft?.source||'';$('author-result').textContent='';authorState(); }catch(error){showError(error);} };
 $('source').oninput=()=>authorState(true);
 $('save-definition').onclick=async()=>{try {
  const result=await request(draft?`/api/v1/admin/rules/definitions/${draft.id}`:'/api/v1/admin/rules/definitions',{method:draft?'PUT':'POST',body:draft?{expectedVersion:draft.version,source:$('source').value}:$('source').value});draft=result.data;
  await drafts();$('draft-list').value=String(draft.id);authorState();$('author-result').textContent=`Saved draft version ${draft.version}.`;
 }catch(error){showError(error);} };
 for(const [button,action] of [['validate-definition','validate'],['resolve-definition','resolved'],['publish-definition','publish']])$(button).onclick=async()=>{try {
  const {data}=await request(`/api/v1/admin/rules/definitions/${draft.id}/${action}`,{method:action==='resolved'?'GET':'POST',body:action==='publish'?{expectedVersion:draft.version}:undefined});
  $('author-result').textContent=wire(data);
  if(action==='publish') { draft=data;await drafts();await choices();$('definition').value=`d:${draft.id}`;scope();authorState(); }
 }catch(error){showError(error);} };
 $('import-catalogue').onsubmit=async event=>{event.preventDefault();try {const {data}=await request('/api/v1/admin/rules/catalogue/imports',{method:'POST',body:{gameVersionId:new ExactNumber($('catalogue-version').value),pack:$('catalogue-pack').value}});$('author-result').textContent=wire(data);await choices();await drafts();}catch(error){showError(error);} };
 authorState();
}
window.addEventListener('rules-auth-lost',()=>{document.querySelector('main').replaceChildren(element('a','Sign in again',{href:'/login?next=/rules'}));});
(async()=>{ users=(await request('/api/v1/rules/users')).data;for(const u of users)$('viewers').append(option(String(u.userId),u.displayName));seat();seat();await choices();await sessions();if($('draft-list'))await drafts();message('Ready.'); })().catch(error=>message(error.message,true));
