// Verifies the legacy simulator list exposes teams, controllers and recorded results.
const assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const base=process.env.TEST_BASE_URL;
const db=process.env.TEST_DB_CONTAINER;
const database=process.env.TEST_DB_NAME;
assert.ok(base?.startsWith('http://127.0.0.1:')&&db?.startsWith('metis-rules-')&&database==='metis_rules_test','Disposable rules environment required');
const docker=args=>execFileSync('docker',args,{encoding:'utf8'}).trim();
const sql=statement=>docker(['exec',db,'psql','-X','-U','metis','-d',database,'-v','ON_ERROR_STOP=1','-tAc',statement]);
const cookies={};
const sessions=[];

async function login(user) {
 const response=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`userId=${user}&next=/simulator`});
 assert.equal(response.status,302);cookies[user]=response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');
}
async function request(user,url,method='GET',body,expected=200) {
 const response=await fetch(base+url,{method,headers:{Cookie:cookies[user]||'','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const text=await response.text();assert.equal(response.status,expected,`${method} ${url}: ${text}`);
 if(user) { assert.equal(response.headers.get('cache-control'),'no-store');assert.equal(response.headers.get('x-metis-user-id'),String(user)); }
 return text?JSON.parse(text):null;
}
async function createSession(gameId,name) {
 const session=await request(1,`/api/v1/games/${gameId}/sessions`,'POST',{name,visibility:'public'},201);
 sessions.push(session.sessionId);return session;
}
async function addPlayer(sessionId,seatNumber,displayName,playerKind,factionId,userId=null) {
 return request(1,`/api/v1/sessions/${sessionId}/players`,'POST',{seatNumber,displayName,playerKind,factionId,userId},201);
}
async function main() {
 await login(1);
 const gameId=Number(sql("SELECT game_id FROM game_engine.game WHERE game_name='Star Wars: Armada' LIMIT 1"));
 const factions=JSON.parse(sql(`SELECT json_agg(json_build_object('id',faction_id,'name',faction_name) ORDER BY faction_id) FROM game_engine.faction WHERE game_id=${gameId}`));
 assert.ok(gameId&&factions.length>=2);
 const live=await createSession(gameId,'Card projection live');
 await addPlayer(live.sessionId,1,'Alice','human',factions[0].id,1);
 await addPlayer(live.sessionId,2,'CPU','cpu',factions[1].id);
 sql(`UPDATE game_instance.game_session SET status='running',current_round=1,updated_at=now(),state_json='{}'::jsonb WHERE session_id=${live.sessionId}`);
 const completed=await createSession(gameId,'Card projection history');
 const winner=await addPlayer(completed.sessionId,1,'Alice','human',factions[0].id,1);
 await addPlayer(completed.sessionId,2,'CPU','cpu',factions[1].id);
 sql(`UPDATE game_instance.game_session SET status='finished',current_round=6,updated_at=now(),state_json=jsonb_build_object('values',jsonb_build_object('outcome','victory','winnerPlayerId',${winner.sessionPlayerId})) WHERE session_id=${completed.sessionId}`);
 const liveCards=await request(1,'/api/v1/sessions?status=running');
 const liveCard=liveCards.find(card=>card.sessionId===live.sessionId);
 const cards=await request(1,'/api/v1/sessions');
 const completedCard=cards.find(card=>card.sessionId===completed.sessionId);
 assert.deepEqual(liveCard.participants.map(player=>[player.teamName,player.controllerName,player.playerKind]),[[factions[0].name,'Alice','human'],[factions[1].name,null,'cpu']]);
 assert.equal(completedCard.resultLabel,`WINNER: ${factions[0].name} / Alice`);
 const page=await fetch(base+'/simulator',{headers:{Cookie:cookies[1]},signal:AbortSignal.timeout(15000)});
 assert.equal(page.status,200);
 const html=await page.text();
 for(const value of [factions[0].name,factions[1].name,'Alice','CPU',completedCard.resultLabel])assert.ok(html.includes(value),`${value} missing from simulator page`);
 console.log('PASS: simulator cards show teams, human/CPU controllers and recorded historical winners.');
 try { for(const sessionId of sessions.reverse()) await request(1,`/api/v1/sessions/${sessionId}`,'DELETE',undefined,204); }
 catch(error) { for(const sessionId of sessions) { try { await request(1,`/api/v1/sessions/${sessionId}`,'DELETE',undefined,204); } catch {} } throw error; }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
