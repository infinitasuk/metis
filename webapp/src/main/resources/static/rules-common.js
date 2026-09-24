// Preserve exact typed integer/decimal tokens; game values must not round through JS Number.
export class ExactNumber {
 constructor(source) { if(!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(source)||source.length>256)throw Error('Invalid JSON number');this.source=source; }
 toString() { return this.source; }
 valueOf() { return Number(this.source); }
}
export function parse(text) {
 return JSON.parse(text,(key,value,context)=>{
  if(typeof value!=='number')return value;
  if(!context?.source) { if(!Number.isSafeInteger(value))throw Error('Exact-number JSON parsing requires a current browser');return value; }
  return !Number.isSafeInteger(value)||/[.eE]/.test(context.source)?new ExactNumber(context.source):value;
 });
}
export function wire(value) {
 if(value instanceof ExactNumber)return value.source;
 if(Array.isArray(value))return '['+value.map(wire).join(',')+']';
 if(value&&typeof value==='object')return '{'+Object.entries(value).filter(([,v])=>v!==undefined).map(([k,v])=>JSON.stringify(k)+':'+wire(v)).join(',')+'}';
 return JSON.stringify(value);
}
export const literal=v=>v.type.kind==='LIST'?v.value.map(literal):v.type.kind==='ENTITY'?v.value.id:v.value;
export const label=s=>String(s).replace(/[_-]/g,' ').replace(/\b\w/g,c=>c.toUpperCase());
export function element(tag,text,attributes={}) { const node=document.createElement(tag);if(text!==undefined)node.textContent=text;for(const [key,value] of Object.entries(attributes))node.setAttribute(key,value);return node; }
export const option=(value,text)=>element('option',text,{value});
export function storage(key,value) { try { if(arguments.length===1)return sessionStorage.getItem(key);if(value===null)sessionStorage.removeItem(key);else sessionStorage.setItem(key,value); }catch{}return null; }
export async function request(url,{method='GET',body,etag}={}) {
 const headers={'Content-Type':'application/json'};if(etag)headers['If-None-Match']=etag;
 const response=await fetch(url,{method,headers,body:body===undefined?undefined:typeof body==='string'?body:wire(body),cache:'no-store',signal:AbortSignal.timeout(15000)});
 const identity=response.headers.get('x-metis-user-id');
 if(response.status===401||identity&&identity!==document.body.dataset.user) { window.dispatchEvent(new CustomEvent('rules-auth-lost',{detail:{identityChanged:!!identity&&identity!==document.body.dataset.user}}));throw Object.assign(Error('Sign in again to continue.'),{status:401}); }
 if(response.status===304)return {unchanged:true,etag:response.headers.get('etag')};
 const text=await response.text();let data;try { data=text?parse(text):null; }catch { throw Object.assign(Error('Invalid server response'),{status:response.status}); }
 if(!response.ok)throw Object.assign(Error(data?.code||`HTTP ${response.status}`),{status:response.status,data});
 return {data,etag:response.headers.get('etag')};
}
