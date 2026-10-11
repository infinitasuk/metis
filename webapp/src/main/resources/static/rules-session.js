import {request,element,option,wire,parse,ExactNumber,literal,label,storage} from './rules-common.js';
const $=id=>document.getElementById(id),id=document.body.dataset.session,user=document.body.dataset.user,root=`/api/v1/rules/sessions/${id}`;
const key=`metis-rules:${user}:${id}:`,saved=(()=>{try{return parse(storage(key+'drafts')||'{}');}catch{return {};}})();
let live=null,shown=null,etag=null,historyVersion=null,historyRequest=0,busy=false,polling=false,authLost=false,confirmedVersion=0n;
let actor=saved.actor||'',action='',actionContext='',formKey='',drafts=saved.fields||{},selected=saved.selected||'',board=saved.board||'',zoom=Number(saved.zoom)||100,pan=saved.pan||{x:0,y:0},drag=null,preview=null;
let pending=storage(key+'pending'),previewRequest=0;
const version=v=>BigInt(String(v)),svgNS='http://www.w3.org/2000/svg';
const typeName=t=>t.kind==='LIST'?`list<${typeName(t.element)}>`:t.kind==='ENTITY'?`entity:${t.entityType}`:t.kind.toLowerCase();
function persist() { if(!authLost)storage(key+'drafts',wire({actor,fields:drafts,selected,board,zoom,pan,zones:$('show-zones').checked,labels:$('show-labels').checked})); }
function feedback(text,error=false) { if($('feedback')) { $('feedback').textContent=text;$('feedback').dataset.error=String(error); } }
function rejection(error) {
 const reasons={STALE_STATE:'The session advanced. Review the current offers and try again.',ACTION_INELIGIBLE:'These inputs do not meet the captured action conditions.',ACTION_BUDGET:'This action is not available in the current activation step.',RESOURCE_BOUNDS:'A resource cost or limit would be exceeded.',STEP_REQUIRED:'Complete the required step before continuing.',SESSION_PAUSED:'Resume the session before gameplay.',SCENARIO_REQUIREMENT:'A definition-owned setup or scenario requirement was not met.',STORAGE_FAILURE:'Confirmation is unavailable. Retry uses the same command identity.'};
 return `${error.message}${reasons[error.message]?' — '+reasons[error.message]:''}${live?` (observed version ${live.view.state.version})`:''}`;
}
function connection(text,error=false) { if($('connection')) { $('connection').textContent=text;$('connection').dataset.error=String(error); } }
async function startingRosters() {
 try {
  const {data}=await request(root+'/roster'),panel=$('starting-rosters');if(!panel||!data)return;
  panel.hidden=false;panel.replaceChildren(element('h2','Captured starting rosters'));
  for(const seat of data.seats)panel.append(element('p',`${seat.playerId} / ${seat.policy}: ${seat.units.map(unit=>`${unit.name} (${unit.points})`).join(', ')} — ${seat.points} of ${seat.budget} points`));
 }catch(error){if(!authLost&&$('starting-rosters'))feedback('Could not load starting rosters: '+error.message,true);}
}
const isLive=()=>historyVersion===null;
const canSend=()=>!!live&&isLive()&&!busy&&!pending&&!authLost&&version(live.view.state.version)>=confirmedVersion;
function selectOptions(select,items,value) { select.replaceChildren(...items.map(([v,t])=>option(v,t)));if(items.some(([v])=>v===value))select.value=value; }
function currentActor() { return live?.choices.actors.find(a=>a.actor===actor); }
function currentCandidate() { return currentActor()?.candidates.find(c=>c.action===action); }
function fields() { const values={};for(const input of $('parameters').querySelectorAll('[data-parameter]'))values[input.dataset.parameter]=input.type==='checkbox'?input.checked:input.value;return values; }
function rememberFields() { if(formKey)drafts[formKey]=fields();persist(); }
function inputsChanged() { preview=null;previewRequest++;$('preview-result').textContent='Inputs changed. Preview again to validate.';drawBoard(); }
const parameter=name=>[...$('parameters').querySelectorAll('[data-parameter]')].find(i=>i.dataset.parameter===name);
function maneuverJoints() {
 const hint=currentCandidate()?.maneuver;if(!hint)return;
 const raw=parameter(hint.yaw),speed=Number(parameter(hint.speed).value),limits=hint.chart[speed]||[];let previous=[];
 try{previous=parse(raw.value).map(Number);}catch{/* A new form starts with straight joints. */}
 const yaw=limits.map((limit,i)=>Number.isInteger(previous[i])&&Math.abs(previous[i])<=Number(limit)?previous[i]:0);raw.value=wire(yaw);
 const panel=$('maneuver-joints');panel.replaceChildren(element('p',limits.length?'Joint yaw · negative turns port, positive turns starboard. Preview checks the final base and used tool.':'Speed zero holds the current pose.'));
 if(hint.note)panel.prepend(element('p',hint.note,{class:'muted','data-maneuver-note':'true'}));
 for(let i=0;i<limits.length;i++) {
  const wrapper=element('label',`Joint ${i+1} · up to ${limits[i]} clicks each way`),input=element('select',undefined,{'aria-label':`Joint ${i+1} yaw`,'data-joint':String(i)});
  for(let value=-Number(limits[i]);value<=Number(limits[i]);value++)input.append(option(String(value),value<0?`Port ${-value}`:value>0?`Starboard ${value}`:'Straight'));
  input.value=String(yaw[i]);input.disabled=!canSend();input.oninput=()=>{yaw[i]=Number(input.value);raw.value=wire(yaw);rememberFields();inputsChanged();};wrapper.append(input);panel.append(wrapper);
 }
}
function typed(value,type) {
 if(type.kind==='LIST') { if(!Array.isArray(value))throw Error('Expected a list');return value.map(v=>typed(v,type.element)); }
 if(type.kind==='INTEGER'||type.kind==='DECIMAL') {
  if(!(value instanceof ExactNumber)&&typeof value!=='number')throw Error('Expected a number');
  if(type.kind==='INTEGER'&&!/^-?(0|[1-9]\d*)$/.test(String(value)))throw Error('Expected an integer');return value;
 }
 if(type.kind==='BOOLEAN') { if(typeof value!=='boolean')throw Error('Expected true or false');return value; }
 if(typeof value!=='string')throw Error('Expected a string or identity');return value;
}
function parameters() {
 const candidate=currentCandidate();if(!candidate)throw Error('No current action');const result={};
 for(const [name,type] of Object.entries(candidate.parameters)) {
  const input=[...$('parameters').querySelectorAll('[data-parameter]')].find(i=>i.dataset.parameter===name);let value;
  if(type.kind==='BOOLEAN')value=input.checked;
  else if(type.kind==='LIST')value=parse(input.value);
  else if(type.kind==='INTEGER'||type.kind==='DECIMAL')value=new ExactNumber(input.value.trim());
  else value=input.value;
  try { result[name]=typed(value,type); }catch(error) { throw Error(`${name}: ${error.message}`); }
 }return result;
}
function setParameters(values) {
 for(const input of $('parameters').querySelectorAll('[data-parameter]'))if(Object.hasOwn(values,input.dataset.parameter)) {
  const value=values[input.dataset.parameter];if(input.type==='checkbox')input.checked=value;else input.value=Array.isArray(value)?wire(value):String(value);
 }
  maneuverJoints();rememberFields();inputsChanged();
}
function form() {
  const candidate=currentCandidate();$('action-form').hidden=!candidate;
  $('preview').disabled=!candidate||!canSend();$('execute').disabled=!candidate||!canSend();
 if(!candidate)return;
 const next=[actor,action,live.view.state.phase,live.view.state.round,candidate.entity||'',wire(candidate.parameters)].join('|')+(candidate.maneuver?'|'+wire(candidate.maneuver):'');
 if(next!==formKey) {
  formKey=next;$('parameters').replaceChildren();const cache=drafts[formKey];
  const offered=currentActor().offered.find(a=>a.action===action);const defaults=offered?Object.fromEntries(Object.entries(offered.parameters).map(([k,v])=>[k,literal(v)])):{};
   const hint=candidate.maneuver;
   for(const [name,type] of Object.entries(candidate.parameters)) {
    const wrapper=element('label',`${label(name)} · ${typeName(type)}`);let input;
    if(hint&&name===hint.speed) { input=element('select');(hint.speeds??hint.chart.map((row,speed)=>speed)).forEach(speed=>input.append(option(String(speed),`Speed ${speed}`))); }
    else if(hint&&name===hint.side) { input=element('select');input.append(option('PORT','Port'),option('STARBOARD','Starboard')); }
    else if(hint&&name===hint.yaw) { input=element('input',undefined,{type:'hidden'});wrapper.hidden=true; }
    else if(type.kind==='ENTITY'||type.kind==='PLAYER') {
    input=element('select');const ids=type.kind==='PLAYER'?live.view.state.players:Object.values(live.view.state.entities).filter(e=>type.entityType==='*'||e.type===type.entityType).map(e=>e.id);
    for(const value of ids)input.append(option(value,value));
   } else if(type.kind==='LIST')input=element('textarea',undefined,{rows:'3',spellcheck:'false'});
   else input=element('input',undefined,{type:type.kind==='BOOLEAN'?'checkbox':'text',...(type.kind==='INTEGER'||type.kind==='DECIMAL'?{inputmode:'decimal'}:{})});
   input.dataset.parameter=name;input.setAttribute('aria-label',name);
    const value=hint&&name===hint.entity?candidate.entity:cache&&Object.hasOwn(cache,name)?cache[name]:Object.hasOwn(defaults,name)?defaults[name]:name===candidate.entityParameter?candidate.entity:type.kind==='BOOLEAN'?false:type.kind==='LIST'?'[]':type.kind==='INTEGER'||type.kind==='DECIMAL'?'0':input.value||'';
   if(input.type==='checkbox')input.checked=value;
   else { const text=Array.isArray(value)?wire(value):String(value??'');if(hint?.speeds&&name===hint.speed)input.value=[...input.options].some(o=>o.value===text)?text:input.options[0]?.value||'';else {if(input.tagName==='SELECT'&&![...input.options].some(o=>o.value===text))input.append(option(text,text));input.value=text;} }
    input.oninput=()=>{if(hint&&name===hint.speed)maneuverJoints();rememberFields();inputsChanged();};wrapper.append(input);$('parameters').append(wrapper);
   }
   if(hint) { $('parameters').append(element('div',undefined,{id:'maneuver-joints',class:'maneuver-joints'}));maneuverJoints(); }
 }
 const previous=$('offer').value;const offers=currentActor().offered.map((o,i)=>({o,i})).filter(x=>x.o.action===action);
 selectOptions($('offer'),[['','Manual inputs'],...offers.map(({o,i})=>[String(i),wire(Object.fromEntries(Object.entries(o.parameters).map(([k,v])=>[k,literal(v)]))).slice(0,220)])],previous);
  for(const input of $('parameters').querySelectorAll('input,select,textarea'))input.disabled=!canSend();
  if(candidate.maneuver)parameter(candidate.maneuver.entity).disabled=true;
}
function controls() {
 if(!live)return;const choices=live.choices,view=live.view;
 $('management').replaceChildren();for(const type of choices.management) { const button=element('button',label(type),{type:'button','data-command':type});button.disabled=!canSend();button.onclick=()=>send({type});$('management').append(button); }
 const actors=choices.actors.map(a=>a.actor);if(!actors.includes(actor))actor=actors[0]||'';
 const active=a=>a.candidates.length||a.controls.length;
 const preferred=choices.actors.find(a=>a.actor===view.state.decision?.player&&active(a))||choices.actors.find(active);
 if(preferred&&!active(choices.actors.find(a=>a.actor===actor)||{candidates:[],controls:[]}))actor=preferred.actor;
 selectOptions($('actor'),actors.map(a=>[a,a]),actor);$('actor').disabled=!canSend();
 const context=[actor,view.state.phase,view.state.round,view.state.activeEntity].join('|');if(context!==actionContext) { actionContext=context;action='';formKey=''; }
 const own=currentActor();if(!own?.candidates.some(c=>c.action===action))action=own?.offered[0]?.action||own?.candidates[0]?.action||'';
 selectOptions($('action'),(own?.candidates||[]).map(c=>[c.action,label(c.action)]),action);action=$('action').value;
 $('action').disabled=!canSend();$('offer').disabled=!canSend();$('controls').replaceChildren();
 for(const type of own?.controls||[])if(type!=='respond') {
  if(type==='activate')for(const entity of own.entities) { const b=element('button',`Activate ${entity}`,{type:'button','data-activate':entity});b.disabled=!canSend();b.onclick=()=>send({type,actor,entityId:entity});$('controls').append(b); }
  else { const b=element('button',label(type),{type:'button','data-command':type});b.disabled=!canSend();b.onclick=()=>send({type,actor});$('controls').append(b); }
 }
 $('decision').replaceChildren();const decision=shown.state.decision;
 if(decision) {
  $('decision').append(element('h3',`Decision — ${decision.player}`));
  if(isLive()&&view.controlledPlayers.includes(decision.player)&&own?.controls.includes('respond')&&actor===decision.player) {
   const select=element('select',undefined,{'aria-label':'Decision option',id:'decision-option'});decision.options.forEach((v,i)=>select.append(option(String(i),wire(literal(v)))));
   const savedChoice=storage(key+'decision:'+decision.id);if(savedChoice!==null)select.value=savedChoice;select.onchange=()=>storage(key+'decision:'+decision.id,select.value);
   const button=element('button','Respond',{type:'button',id:'respond'});select.disabled=!canSend();button.disabled=!canSend();button.onclick=()=>send({type:'respond',actor:decision.player,decisionId:decision.id,option:Number(select.value)});$('decision').append(select,button);
  } else $('decision').append(element('p',decision.options.length?`Options: ${decision.options.map(v=>wire(literal(v))).join(', ')}`:'Waiting for the designated seat.'));
 }
 form();$('pending-request').hidden=!pending;$('retry-command').disabled=busy||!isLive();
}
function values(container,data) {
 container.replaceChildren();const list=element('dl',undefined,{class:'rules-values'});
 for(const [name,value] of Object.entries(data))list.append(element('dt',name),element('dd',wire(value?.type?literal(value):value)));container.append(list);
}
function render() {
 if(!shown||!live||authLost)return;
 $('game-name').textContent=label(live.presentation.name);$('identity').textContent=`${live.presentation.revision} · ${live.view.fingerprint.slice(0,12)} · ${id}`;
 const s=shown.state;$('status').textContent=`${shown.paused?'PAUSED':s.status} · Round ${s.round} · ${s.phase||'setup'} · Version ${s.version}\n${s.outcome?`${s.outcome} · Winners: ${s.winners.join(', ')||'none'}`:s.decision?`Waiting for ${s.decision.player}`:s.activePlayer?`Active: ${s.activePlayer}${s.activeEntity?' / '+s.activeEntity:''}`:'Simultaneous or setup decisions'}${shown.cpu.pauseReason?'\n'+shown.cpu.pauseReason:''}\nSeats: ${shown.seats.map(x=>`${x.playerId} (${x.kind})`).join(', ')} · CPU steps ${shown.cpu.steps}/${shown.cpu.limit}`;
 $('status').classList.toggle('paused',shown.paused);$('history').max=String(live.view.state.version);$('history').value=isLive()?String(live.view.state.version):String(historyVersion);$('history-label').textContent=isLive()?'Live':`Replay v${historyVersion} / live v${live.view.state.version}`;
 controls();
 const boardIds=Object.keys(live.presentation.boards);if(!boardIds.includes(board))board=boardIds[0]||'';selectOptions($('board'),boardIds.map(b=>[b,b]),board);$('board-wrap').hidden=!board;drawBoard();
 $('entity-list').replaceChildren();for(const entity of Object.values(s.entities)) { const b=element('button',entity.id,{type:'button','data-entity':entity.id});b.onclick=()=>{selected=entity.id;persist();details();drawBoard();};$('entity-list').append(b); }
 details();values($('globals'),s.globals);$('mechanics').replaceChildren();
 for(const [name,entries] of Object.entries(s.mechanics||{})) { $('mechanics').append(element('h3',label(name)));for(const [key,value] of Object.entries(entries)) { $('mechanics').append(element('h4',key));const block=element('div');values(block,value);$('mechanics').append(block); } }
 $('events').replaceChildren();for(const event of s.events.slice(-100))$('events').append(element('li',`${event.sequence} · ${event.type}${event.actor?' · '+event.actor:''}${event.path?' · '+event.path:''}${Object.keys(event.values).length?' · '+wire(Object.fromEntries(Object.entries(event.values).map(([k,v])=>[k,literal(v)]))):''}`));
}
function details() { const entity=shown?.state.entities[selected];$('entity-detail').replaceChildren();if(entity) { $('entity-detail').append(element('h3',`${entity.id} · ${entity.type} · ${entity.owner||'unowned'}`));const block=element('div');values(block,{...entity.attributes,...entity.resources});$('entity-detail').append(block); } }
function svg(tag,attrs,text) { const n=document.createElementNS(svgNS,tag);for(const [k,v] of Object.entries(attrs))n.setAttribute(k,String(v));if(text!==undefined)n.textContent=text;return n; }
function drawBoard() {
 if(!live||!shown||!board||drag)return;const surface=$('battlefield'),b=live.presentation.boards[board],width=Number(b.width),height=Number(b.height),scale=100/zoom;
  surface.setAttribute('viewBox',`${(width-width*scale)/2+Number(pan.x)} ${(height-height*scale)/2+Number(pan.y)} ${width*scale} ${height*scale}`);surface.replaceChildren(svg('rect',{x:0,y:0,width,height,fill:'#233a4c',stroke:'#97acbc','stroke-width':width/500}));
  $('board-measurement').textContent=`${width} × ${height} ${b.unit} · x right, y down · positive headings clockwise`;
  $('maneuver-measurement').textContent='';
 if($('show-zones').checked)for(const z of Object.values(live.presentation.zones))if(z.board===board)surface.append(svg('rect',{x:z.x,y:z.y,width:z.width,height:z.height,fill:'#68b8a4','fill-opacity':.12,stroke:'#68b8a4','stroke-width':width/600}));
 const owners=shown.state.players;
 for(const entity of Object.values(shown.state.entities)) {
  const body=live.presentation.bodies[entity.type];if(!body||body.board!==board||!literal(entity.attributes[body.placed]))continue;
  const grid=b.model==='GRID',x=Number(literal(entity.attributes[body.x]))+(grid?.5:0),y=Number(literal(entity.attributes[body.y]))+(grid?.5:0),heading=Number(literal(entity.attributes[body.facing]));
  const group=svg('g',{class:`unit${entity.id===selected?' selected':''}`,'data-entity':entity.id,transform:`translate(${x} ${y}) rotate(${heading})`});const color=['#65bbeb','#f18e74','#aabb65','#b993eb'][Math.max(0,owners.indexOf(entity.owner))%4];
  if(grid||body.shape==='RECTANGLE')group.append(svg('rect',{x:-(grid?1:Number(body.width))/2,y:-(grid?1:Number(body.height))/2,width:grid?1:body.width,height:grid?1:body.height,fill:color,stroke:'#d6e5ed','stroke-width':width/650}));
   else group.append(svg('circle',{r:body.shape==='CIRCLE'?body.radius:width/150,fill:color,stroke:'#d6e5ed','stroke-width':width/650}));
   if(!grid&&body.shape==='RECTANGLE')group.append(svg('path',{d:`M 0 0 L ${Number(body.width)/2} 0 M ${Number(body.width)/3} ${-Number(body.height)/5} L ${Number(body.width)/2} 0 L ${Number(body.width)/3} ${Number(body.height)/5}`,fill:'none',stroke:'#182937','stroke-width':width/650}));
  group.onclick=event=>{event.stopPropagation();selected=entity.id;persist();details();drawBoard();};surface.append(group);
  if($('show-labels').checked)surface.append(svg('text',{x,y:y-height/35,'font-size':width/65,'text-anchor':'middle'},entity.id));
 }
 if(preview&&isLive()&&version(preview.version)===version(live.view.state.version))for(const move of preview.movements)if(move.board===board) {
   const offset=b.model==='GRID'?.5:0;const points=[move.from,...move.path].map(p=>`${Number(p.x)+offset},${Number(p.y)+offset}`).join(' ');surface.append(svg('polyline',{points,fill:'none',stroke:'#ffe285','stroke-width':width/300,'stroke-dasharray':`${width/120} ${width/160}`}));
   if(move.maneuver) {
    const trace=move.maneuver,body=live.presentation.bodies[shown.state.entities[move.entity]?.type],toolPoints=trace.tool.map(p=>`${p.x},${p.y}`).join(' ');
    surface.append(svg('polyline',{class:'maneuver-tool',points:toolPoints,fill:'none',stroke:'#9df3d6','stroke-opacity':.45,'stroke-width':Math.max(Number(trace.toolWidth),width/800),'stroke-linejoin':'round','stroke-linecap':'round','pointer-events':'none'}));
    trace.tool.forEach((point,index)=>{surface.append(svg('circle',{cx:point.x,cy:point.y,r:width/650,fill:'#9df3d6','pointer-events':'none'}));surface.append(svg('text',{x:point.x,y:Number(point.y)-height/45,'font-size':width/100,'text-anchor':'middle'},String(index)));});
    if(body) {
     const destination=svg('g',{class:'maneuver-destination',transform:`translate(${move.to.x} ${move.to.y}) rotate(${move.to.heading})`,'pointer-events':'none'});
     if(body.shape==='RECTANGLE')destination.append(svg('rect',{x:-Number(body.width)/2,y:-Number(body.height)/2,width:body.width,height:body.height,fill:'#ffe285','fill-opacity':.3,stroke:'#ffe285','stroke-width':width/500}));
     else destination.append(svg('circle',{r:body.radius,fill:'#ffe285','fill-opacity':.3,stroke:'#ffe285','stroke-width':width/500}));
     destination.append(svg('line',{x1:0,y1:0,x2:body.shape==='RECTANGLE'?Number(body.width)/2:body.radius,y2:0,stroke:'#ffe285','stroke-width':width/400}));surface.append(destination);
    }
    const number=value=>Number(value).toFixed(4).replace(/\.?0+$/,'');
    $('maneuver-measurement').textContent=`Speed ${trace.speed} · ${trace.side.toLowerCase()} tool · yaw [${trace.yaw.join(', ')}] · final (${number(move.to.x)}, ${number(move.to.y)}) ${move.unit} · heading ${number(move.to.heading)}°`+(preview.resources.length?' · Resources: '+preview.resources.map(r=>`${label(r.resource)} ${r.before} → ${r.after}`).join(', '):'');
   }
 }
}
async function refresh(force=false) {
 if(polling||authLost)return;polling=true;
 try {
  const result=await request(root+'/workspace',{etag:force?null:etag});if(!result.unchanged) {
   if(!live||version(result.data.view.state.version)>=version(live.view.state.version)) {
    if(live&&version(result.data.view.state.version)!==version(live.view.state.version)) { preview=null;$('preview-result').textContent='State advanced. Preview again before relying on a previous path.'; }
    live=result.data;etag=result.etag;if(isLive())shown=live.view;render();
   }
  }
  connection('Connected.');
 }catch(error) { connection(`${error.message} — reconnecting; drafts are retained.`,true); }
 finally { polling=false; }
}
async function transmit() {
 if(!pending||busy||!isLive()||authLost)return;busy=true;controls();
 try {
  const {data}=await request(root+'/commands',{method:'POST',body:pending});if(data.view&&version(data.view.state.version)>confirmedVersion)confirmedVersion=version(data.view.state.version);pending=null;storage(key+'pending',null);feedback(data.code,false);etag=null;
 }catch(error) {
  feedback(rejection(error),true);
  if(error.data?.view&&version(error.data.view.state.version)>confirmedVersion)confirmedVersion=version(error.data.view.state.version);
  if(error.status&&error.status<500&&error.status!==401) { pending=null;storage(key+'pending',null);etag=null; }
 }finally { busy=false;controls();await refresh(true); }
}
async function send(extra) {
 if(!canSend()||!live)return;rememberFields();pending=wire({commandId:crypto.randomUUID(),expectedStateVersion:live.view.state.version,...extra});storage(key+'pending',pending);await transmit();
}
$('actor').onchange=()=>{rememberFields();actor=$('actor').value;action='';formKey='';inputsChanged();controls();persist();};
$('action').onchange=()=>{rememberFields();action=$('action').value;formKey='';inputsChanged();form();};
$('offer').onchange=()=>{const index=$('offer').value;if(index!=='') {const offered=currentActor().offered[Number(index)];setParameters(Object.fromEntries(Object.entries(offered.parameters).map(([k,v])=>[k,literal(v)])));} };
$('action-form').onsubmit=event=>{event.preventDefault();try{send({type:'act',actor,action,parameters:parameters()});}catch(error){feedback(error.message,true);} };
$('preview').onclick=async()=>{if(!canSend())return;const requestId=++previewRequest;try {const expected=live.view.state.version,context=formKey,inputs=parameters(),seat=actor,name=action;const {data}=await request(root+'/preview',{method:'POST',body:{actor:seat,action:name,parameters:inputs,expectedStateVersion:expected}});if(requestId!==previewRequest||!isLive()||!live||version(expected)!==version(live.view.state.version)||context!==formKey||seat!==actor||name!==action||wire(inputs)!==wire(parameters()))return;preview=data; $('preview-result').textContent=wire(data);drawBoard();}catch(error){if(requestId===previewRequest)feedback(error.message,true);} };
$('retry-command').onclick=transmit;$('refresh').onclick=()=>refresh(true);
$('history').onchange=async()=>{
 const cursor=$('history').value,requestId=++historyRequest;historyVersion=cursor;preview=null;controls();
 try {const {data}=await request(root+'/history/'+cursor);if(requestId!==historyRequest||historyVersion!==cursor)return;shown=data;render();}catch(error){feedback(error.message,true);}
};
$('go-live').onclick=()=>{historyVersion=null;historyRequest++;shown=live?.view;render();refresh(true);};
$('board').onchange=()=>{board=$('board').value;pan={x:0,y:0};persist();drawBoard();};
$('zoom').value=String(zoom);$('zoom').oninput=()=>{zoom=Number($('zoom').value);persist();drawBoard();};
for(const name of ['zones','labels']) { if(saved[name]!==undefined)$('show-'+name).checked=saved[name];$('show-'+name).onchange=()=>{persist();drawBoard();}; }
$('reset-view').onclick=()=>{zoom=100;pan={x:0,y:0};$('zoom').value='100';persist();drawBoard();};
const field=$('battlefield');field.onpointerdown=event=>{if(event.target.closest('.unit'))return;drag={pointer:event.pointerId,x:event.clientX,y:event.clientY,pan:{...pan},box:field.viewBox.baseVal.width};field.setPointerCapture(event.pointerId);};
field.onpointermove=event=>{if(!drag)return;const ratio=drag.box/field.getBoundingClientRect().width;pan={x:Number(drag.pan.x)-(event.clientX-drag.x)*ratio,y:Number(drag.pan.y)-(event.clientY-drag.y)*ratio};const old=drag;drag=null;drawBoard();drag=old;};
field.onpointerup=field.onpointercancel=()=>{drag=null;persist();drawBoard();};
window.addEventListener('rules-auth-lost',event=>{authLost=true;live=null;shown=null;drag=null;if(event.detail?.identityChanged) { try {for(const k of Object.keys(sessionStorage))if(k.startsWith(`metis-rules:${user}:`))sessionStorage.removeItem(k);}catch{} }document.querySelector('main').replaceChildren(element('a','Sign in again',{href:`/login?next=${encodeURIComponent('/rules/sessions/'+id)}`}));});
window.addEventListener('online',()=>refresh(true));window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
$('pending-request').hidden=!pending;await refresh(true);startingRosters();setInterval(()=>refresh(),1000);
