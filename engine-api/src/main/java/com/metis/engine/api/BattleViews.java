package com.metis.engine.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** One outbound boundary for state, action results, history, and legacy DTOs. */
@Service
public class BattleViews {
    private final BattleRepository battle;
    private final DefinitionRepository definitions;
    private final ObjectMapper mapper;
    public BattleViews(BattleRepository battle, DefinitionRepository definitions, ObjectMapper mapper) {
        this.battle=battle; this.definitions=definitions; this.mapper=mapper;
    }
    private record Context(StateProjection projection, Set<Long> owned) {}
    private Context context(long sessionId, MetisUser user) {
        var owned=battle.controlledPlayers(sessionId,user.userId());
        var session=battle.findSession(sessionId).orElseThrow();
        VisibilityRules rules=VisibilityRules.defaults();
        List<UnitState> units=List.of();
        try {
            var definition="setup".equals(session.status()) ? definitions.findEngineDefinition(session.gameVersionId())
                    : definitions.findSessionDefinition(sessionId,session.gameVersionId());
            if(definition.isPresent() && definition.get().turnStructure()!=null) rules=definition.get().turnStructure().visibility();
            if(session.state()!=null && !session.state().equals("{}")) units=mapper.readValue(session.state(),GameState.class).units();
        } catch(Exception error) { rules=new VisibilityRules(VisibilityRules.Audience.SERVER,List.of(),List.of()); }
        return new Context(new StateProjection(rules,owned,units),owned);
    }
    public Object project(Object body, Long sessionId, MetisUser user) {
        return project(body,sessionId,user,new HashMap<>());
    }
    private Object project(Object body, Long hint, MetisUser user, Map<Long,Context> contexts) {
        if(body==null) return null;
        if(body instanceof List<?> list) {
            var result=new ArrayList<>();
            for(Object item:list) { Object projected=project(item,hint,user,contexts); if(projected!=null) result.add(projected); }
            return result;
        }
        if(body instanceof ActionResult result) return new ActionResult(result.accepted(),result.code(),result.message(),
                (GameState)project(result.resultingState(),hint,user,contexts));
        if(body instanceof Map<?,?> map) {
            var result=new LinkedHashMap<String,Object>();
            map.forEach((key,value)->result.put(key.toString(),project(value,hint,user,contexts)));
            return result;
        }
        if(body instanceof BattleRepository.EngineLog log) {
            return new BattleRepository.EngineLog(log.logId(),log.sessionId(),log.level(),log.source(),"Engine diagnostic details are private.","{}",log.createdAt());
        }
        if (!(body instanceof GameState || body instanceof BattleRepository.BattleSessionDetail
                || body instanceof BattleRepository.SessionPlayer || body instanceof BattleRepository.SessionUnit
                || body instanceof BattleRepository.SessionEvent || body instanceof BattleRepository.FleetSummary
                || body instanceof BattleRepository.FleetUnit || body instanceof BattleRepository.SessionBoard
                || body instanceof BattleRepository.SessionTurn)) return body;
        Long id=hint;
        if(body instanceof GameState state) id=state.sessionId();
        else if(body instanceof BattleRepository.BattleSessionDetail session) id=session.sessionId();
        else if(body instanceof BattleRepository.SessionPlayer player) id=player.sessionId();
        else if(body instanceof BattleRepository.SessionUnit unit) id=unit.sessionId();
        else if(body instanceof BattleRepository.FleetSummary fleet) id=fleet.sessionId();
        else if(body instanceof BattleRepository.SessionBoard board) id=board.sessionId();
        else if(body instanceof BattleRepository.SessionTurn turn) id=turn.sessionId();
        else if(body instanceof BattleRepository.FleetUnit unit) id=battle.sessionIdForFleet(unit.fleetId()).orElse(null);
        if(id==null) return body;
        long sessionId=id;
        var ctx=contexts.computeIfAbsent(id,key->context(key,user));
        if(body instanceof GameState state) return ctx.projection().state(state);
        try {
            ObjectNode node=mapper.valueToTree(body);
            if(body instanceof BattleRepository.BattleSessionDetail session) {
                node.put("state",session.state()==null || session.state().equals("{}") ? "{}"
                        : mapper.writeValueAsString(ctx.projection().state(mapper.readValue(session.state(),GameState.class))));
            } else if(body instanceof BattleRepository.SessionEvent event) {
                var payload=mapper.readValue(event.payload(),new TypeReference<Map<String,Object>>() {});
                var projected=ctx.projection().event(new GameEvent(event.sequence(),event.type(),event.actorPlayerId(),payload));
                node.put("type",projected.type());
                node.set("actorPlayerId",mapper.valueToTree(projected.actorPlayerId()));
                node.put("payload",mapper.writeValueAsString(projected.payload()));
            } else if(body instanceof BattleRepository.SessionUnit unit) {
                if(!ctx.projection().canSeePlayerUnits(unit.sessionPlayerId())) return null;
                node.put("state","{}");
            } else if(body instanceof BattleRepository.FleetSummary fleet) {
                if(!ctx.projection().canSeePlayerUnits(fleet.sessionPlayerId())) return null;
                node.put("state","{}");
            } else if(body instanceof BattleRepository.FleetUnit unit) {
                var fleet=battle.findFleets(sessionId).stream().filter(item->item.fleetId()==unit.fleetId()).findFirst().orElseThrow();
                if(!ctx.projection().canSeePlayerUnits(fleet.sessionPlayerId())) return null;
                node.put("state","{}");
            } else if(body instanceof BattleRepository.SessionPlayer || body instanceof BattleRepository.SessionBoard || body instanceof BattleRepository.SessionTurn) {
                node.put("state","{}");
            } else return body;
            return mapper.treeToValue(node,body.getClass());
        } catch(Exception error) {
            // Never fall back to the unprojected record if a stored payload is invalid.
            throw new IllegalStateException("Could not build a player-safe battle view",error);
        }
    }
}
