import {request,element,option,wire,ExactNumber,storage} from './rules-common.js';
const $=id=>document.getElementById(id),user=document.body.dataset.user;
let users=[],entries=[],definitions=[],draft=null,seatNumber=0;
let rosterState=null,scopeVersion=0,rosterLoading=false,rosterLoadError=null;
let presets=[],presetLoading=false,presetBusy=false,presetLoadError=null,presetRequest=0,authLost=false;
const presetRoot='/api/v1/rules/fleet-presets';
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
 $('create').disabled=!$('definition').options.length;await scope();
}
async function scope() {
 const selected=$('definition').value,roster=$('catalogue-roster'),version=++scopeVersion;
 rosterState=null;rosterLoading=!!selected;rosterLoadError=null;
 roster.replaceChildren();roster.hidden=true;
 $('scope').replaceChildren();const entry=entries.find(e=>`c:${e.id}`===selected);if(!selected)return;
 if(entry)for(const [name,value] of Object.entries(entry.scope))$('scope').append(element('p',`${name}: ${Array.isArray(value)?value.join('; '):value}`));
 const [kind,id]=selected.split(':'),root=`/api/v1/rules/${kind==='c'?'catalogue':'definitions'}/${id}/roster`;
 try {
  const {data}=await request(root);
  if(version!==scopeVersion||!data.available)return;
  const key=`metis-rosters:${user}:${data.fingerprint}`;let saved;
  try{saved=JSON.parse(storage(key)||'null');}catch{}
  const selections=data.seats.map((seat,index)=>{
   const previous=saved?.[index];
   if(data.selectable&&seat.choices.some(p=>p.code===previous?.policy)&&Array.isArray(previous.units)&&previous.units.every(code=>typeof code==='string'&&data.units.some(u=>u.code===code)))return previous;
   return {policy:seat.policy,units:[...seat.units]};
  });
  rosterState={data,selections,key,root,definitionId:entry?.definitionId||new ExactNumber(id),results:[],validation:0,
   presetDrafts:data.seats.map(()=>({name:'',id:''})),presetMessages:[]};renderRoster();
 }catch(error){if(version===scopeVersion){rosterLoadError=error.message;message(error.message,true);}}
 finally{if(version===scopeVersion)rosterLoading=false;}
}
function renderRoster() {
 const roster=$('catalogue-roster'),model=rosterState;if(!model)return;
 roster.replaceChildren();roster.hidden=false;roster.append(element('h3',model.data.selectable?'Choose starting rosters':'Captured roster and points'));
 const changed=()=>{model.validation++;model.results=[];storage(model.key,wire(model.selections));renderRoster();};
 for(const seat of model.data.seats) {
  const index=Number(seat.seat),selection=model.selections[index],policy=seat.choices.find(p=>p.code===selection.policy);
  const panel=element('fieldset',undefined,{'data-roster-seat':String(index)});panel.append(element('legend',`Seat ${index+1} roster`));
  if(model.data.selectable) {
   const label=element('label',`Seat ${index+1} faction`),select=element('select',undefined,{'aria-label':`Seat ${index+1} faction`});
   for(const choice of seat.choices)select.append(option(choice.code,choice.factions.join(' / ')||choice.code));select.value=selection.policy;
   select.onchange=()=>{selection.policy=select.value;const next=seat.choices.find(p=>p.code===select.value);selection.units=Array.from({length:Number(next.minimum)},()=>next.units[0]);changed();};
   label.append(select);panel.append(label,element('p',`Allowance ${policy.budget} points; ${policy.minimum}–${policy.maximum} units.`));
   selection.units.forEach((code,slot)=>{
    const row=element('div',undefined,{class:'rules-toolbar'}),unit=element('select',undefined,{'aria-label':`Seat ${index+1} unit ${slot+1}`});
    for(const profile of model.data.units.filter(u=>policy.units.includes(u.code)))unit.append(option(profile.code,`${profile.name} (${profile.points} points)`));
    unit.value=code;unit.onchange=()=>{selection.units[slot]=unit.value;changed();};
    const remove=element('button','Remove unit',{type:'button','aria-label':`Remove seat ${index+1} unit ${slot+1}`});remove.onclick=()=>{selection.units.splice(slot,1);changed();};row.append(unit,remove);panel.append(row);
   });
   const add=element('button','Add unit',{type:'button','aria-label':`Add unit to seat ${index+1}`});add.disabled=selection.units.length>=Number(policy.maximum)||!policy.units.length;
   add.onclick=()=>{selection.units.push(policy.units[0]);changed();};panel.append(add);
  } else {
   const units=selection.units.map(code=>model.data.units.find(unit=>unit.code===code));
   panel.append(element('p',`Seat ${index+1}: ${units.map(unit=>`${unit.name} (${unit.points} points)`).join(', ')} / allowance ${policy.budget}`));
  }
  for(const code of new Set(selection.units)) {
   const unit=model.data.units.find(u=>u.code===code),source=JSON.parse(unit.provenance);
   panel.append(element('p',`Source: ${source.file}, revision ${source.revision}, entry ${source.entryId}.`,{class:'muted'}));
  }
  const report=model.results[index];panel.append(element('p',report?`Seat ${index+1}: ${report.valid?'valid':report.code} / ${report.points??'?'} of ${report.budget} points`:'Validate to check this roster.',{role:'status','data-roster-result':String(index)}));roster.append(panel);
  if(model.data.selectable)renderPresets(panel,model,index);
 }
 const check=element('button','Validate rosters',{type:'button',id:'roster-check'});check.onclick=async()=>{check.disabled=true;try{await validateRosters(model);}catch(error){message(error.message,true);}finally{check.disabled=false;}};
 roster.append(check,element('p',model.data.selectable?'Your selections are captured permanently when the session is created.':'Fixed training rosters. The engine validates them when creating and starting the session.',{class:'muted'}));
 if(model.data.selectable) {
  const refresh=element('button','Refresh saved fleets',{type:'button',id:'refresh-presets'});refresh.disabled=presetLoading||presetBusy;refresh.onclick=refreshPresets;roster.append(refresh);
  if(presetLoadError)roster.append(element('p','Saved fleets unavailable: '+presetLoadError,{role:'status'}));
 }
}
async function refreshPresets() {
 const revision=++presetRequest;presetLoading=true;
 try {const {data}=await request(presetRoot);if(revision===presetRequest&&!authLost){presets=data;presetLoadError=null;}}
 catch(error){if(revision===presetRequest&&!authLost)presetLoadError=error.message;}
 finally{if(revision===presetRequest){presetLoading=false;if(rosterState)renderRoster();}}
}
function renderPresets(panel,model,index) {
 const draft=model.presetDrafts[index],saved=presets.find(p=>String(p.id)===draft.id);
 panel.append(element('h4','Personal fleet presets'));
 const listLabel=element('label','Saved fleet'),list=element('select',undefined,{'aria-label':`Seat ${index+1} saved fleet`});
 list.append(option('','Choose a saved fleet'));
 for(const preset of presets)list.append(option(String(preset.id),`${preset.name} / ${preset.snapshot.points} points / ${preset.snapshot.definition} · ${preset.snapshot.revision.slice(0,12)}`));
 list.value=saved?draft.id:'';list.disabled=presetLoading||presetBusy;
 list.onchange=()=>{draft.id=list.value;const chosen=presets.find(p=>String(p.id)===list.value);if(chosen)draft.name=chosen.name;model.presetMessages[index]='';renderRoster();};
 listLabel.append(list);panel.append(listLabel);
 const nameLabel=element('label','Fleet name'),name=element('input',undefined,{type:'text',maxlength:'100','aria-label':`Seat ${index+1} fleet name`,value:draft.name});
 name.disabled=presetBusy;
 name.oninput=()=>draft.name=name.value;nameLabel.append(name);panel.append(nameLabel);
 const buttons=element('div',undefined,{class:'rules-toolbar'});
 const button=(text,handler,disabled=false)=>{const b=element('button',text,{type:'button','aria-label':`${text} for seat ${index+1}`});b.disabled=presetBusy||disabled;b.onclick=()=>presetAction(model,index,handler);buttons.append(b);};
 button('Save fleet',async()=>{
  const selection=model.selections[index],body={name:draft.name,definitionId:model.definitionId,seat:index,policy:selection.policy,units:[...selection.units]};
  const {data}=await request(presetRoot,{method:'POST',body});if(authLost)return;
  presets=[data,...presets];draft.id=String(data.id);draft.name=data.name;model.presetMessages[index]=`Saved ${data.name}: ${data.snapshot.points} points.`;
 });
 button('Load fleet',async()=>{
  const version=model.validation,snapshot=wire(model.selections[index]),presetId=draft.id;
  const {data}=await request(`${presetRoot}/${presetId}/load`,{method:'POST',body:{definitionId:model.definitionId,seat:index}});
  if(rosterState!==model||model.validation!==version||snapshot!==wire(model.selections[index])||draft.id!==presetId)throw Error('Roster changed while the preset was loading. Load it again.');
  if(!data.valid){model.presetMessages[index]=`Fleet could not be loaded: ${data.code}${data.points==null?'':` / ${data.points} of ${data.budget} points`}.`;return;}
  model.selections[index]=data.selection;model.validation++;model.results[index]=data;storage(model.key,wire(model.selections));
  model.presetMessages[index]=`Loaded ${saved.name}: ${data.points} of ${data.budget} points.${data.revisionChanged?` Revalidated for this revision (saved cost: ${data.savedPoints}).`:''}`;
 },!saved||presetLoading);
 button('Rename fleet',async()=>{
  const {data}=await request(`${presetRoot}/${saved.id}`,{method:'PATCH',body:{name:draft.name,expectedVersion:saved.version}});if(authLost)return;
  presets=presets.map(p=>String(p.id)===String(data.id)?data:p);draft.name=data.name;model.presetMessages[index]=`Renamed to ${data.name}.`;
 },!saved||presetLoading);
 button('Delete fleet',async()=>{
  await request(`${presetRoot}/${saved.id}?expectedVersion=${saved.version}`,{method:'DELETE'});if(authLost)return;
  presets=presets.filter(p=>String(p.id)!==String(saved.id));draft.id='';model.presetMessages[index]=`Deleted ${saved.name}.`;
 },!saved||presetLoading);
 panel.append(buttons,element('p',model.presetMessages[index]||'Presets are private to your account and retain their saved revision.',{role:'status','data-preset-message':String(index)}));
}
async function presetAction(model,index,action) {
 if(presetBusy||rosterState!==model)return;presetBusy=true;renderRoster();
 try {await action();}
 catch(error){model.presetMessages[index]=error.message;if(error.message==='STALE_PRESET'||error.message==='PRESET_NOT_FOUND')await refreshPresets();}
 finally{presetBusy=false;if(rosterState===model)renderRoster();}
}
async function validateRosters(model) {
 const revision=++model.validation,snapshot=wire(model.selections);
 const reports=await Promise.all(model.selections.map(async (selection,seat)=>(await request(model.root+'/validate',{method:'POST',body:{seat,...selection}})).data));
 if(rosterState!==model||revision!==model.validation||snapshot!==wire(model.selections))throw Error('Roster changed during validation. Validate again.');
 model.results=reports;renderRoster();return reports;
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
  const setupStamp=()=>wire([...document.querySelectorAll('#seats input, #seats select, #visibility, #viewers, #cpu-limit')].map(input=>input.multiple?[...input.selectedOptions].map(o=>o.value):input.value));
  const submittedSetup=setupStamp();
  const seats=[...$('seats').children].map(row=>{const playerId=row.querySelector('input').value,controller=row.querySelector('select').value;return controller==='cpu'?{playerId,kind:'CPU'}:{playerId,userId:new ExactNumber(controller)};});
  const body={visibility:$('visibility').value,seats,viewers:[...$('viewers').selectedOptions].map(o=>new ExactNumber(o.value)),cpuCommandLimit:new ExactNumber($('cpu-limit').value)};
  if(rosterLoading)throw Error('Roster choices are loading. Try again shortly.');
  if(rosterLoadError)throw Error('Reload roster choices before creating a session: '+rosterLoadError);
  if(rosterState?.data.selectable) {
   const model=rosterState;
   if(seats.length!==model.data.seats.length)throw Error(`This format requires ${model.data.seats.length} seats.`);
   const reports=await validateRosters(model),invalid=reports.findIndex(report=>!report.valid);
   if(invalid>=0)throw Error(`Seat ${invalid+1}: ${reports[invalid].code}. Correct the roster before creating the session.`);
   if(setupStamp()!==submittedSetup)throw Error('Setup changed during validation. Review the seats and create the session again.');
   body.rosters=model.selections.map(selection=>({policy:selection.policy,units:[...selection.units]}));
  }
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
window.addEventListener('rules-auth-lost',()=>{authLost=true;scopeVersion++;presetRequest++;presets=[];rosterState=null;document.querySelector('main').replaceChildren(element('a','Sign in again',{href:'/login?next=/rules'}));});
window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
(async()=>{ users=(await request('/api/v1/rules/users')).data;for(const u of users)$('viewers').append(option(String(u.userId),u.displayName));seat();seat();await Promise.all([choices(),sessions(),refreshPresets()]);if($('draft-list'))await drafts();if(!rosterLoadError)message('Ready.'); })().catch(error=>message(error.message,true));
