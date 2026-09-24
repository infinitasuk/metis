package com.metis.engine.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Persisted pre-resolution response windows; only the engine resumes a suspended action. */
public final class ResponseWindows {
    private ResponseWindows() {}
    public static Map<String,Object> object(Object value) {
        var result = new HashMap<String,Object>();
        if (value instanceof Map<?,?> map) map.forEach((k,v)->result.put(k.toString(),v));
        return result;
    }
    public static Map<String,Object> pending(GameState state) {
        return object(object(state.values().get("serverOnly")).get("pendingResponse"));
    }
    public static List<LegalAction> legal(GameState state) {
        var pending = pending(state);
        if (pending.isEmpty()) return List.of();
        long responder = ((Number)pending.get("responderPlayerId")).longValue();
        var choices = object(pending.get("choices")); var result = new ArrayList<LegalAction>();
        choices.forEach((code,value)->result.add(new LegalAction("respond",responder,String.valueOf(object(value).get("label")),
                Map.of("windowId","string","choice","string"),Map.of("windowId",pending.get("windowId"),"choice",code))));
        return result;
    }
    public static ActionResult open(GameDefinition definition, GameState state, Action action) {
        if (definition == null || definition.turnStructure() == null) return null;
        var rule = definition.turnStructure().responses().stream().filter(item->item.action().equals(action.type())).findFirst().orElse(null);
        if (rule == null || !(action.parameters().get(rule.targetUnitParameter()) instanceof Number targetId)) return null;
        var target = state.units().stream().filter(unit->unit.unitId()==targetId.longValue()).findFirst().orElse(null);
        if (target == null) return null;
        var choices = new HashMap<String,Object>();
        choices.put("decline",Map.of("label","Decline response","parameters",rule.declineParameters()));
        for (var choice : rule.choices()) choices.put(choice.code(),Map.of("label",choice.label(),"parameters",choice.parameters()));
        if (rule.resourceField() != null) {
            var resources = rule.resourceField().equals("readyDefenseTokens") ? target.readyDefenseTokens() : target.shieldZones();
            resources.forEach((name,count)-> { if(count>0) choices.put("resource:"+name,
                    Map.of("label","Use "+name,"parameters",Map.of(rule.resourceParameter(),name))); });
        }
        String window = state.sessionId()+":"+(state.stateVersion()+1);
        var parameters = new HashMap<>(action.parameters());
        // Choice-sensitive parameters must come from the responder, not the initiator.
        rule.declineParameters().keySet().forEach(parameters::remove);
        rule.choices().forEach(choice->choice.parameters().keySet().forEach(parameters::remove));
        if(rule.resourceParameter()!=null) parameters.remove(rule.resourceParameter());
        var pending = Map.<String,Object>of("windowId",window,"responderPlayerId",target.playerId(),
                "actionType",action.type(),"actorPlayerId",action.actorPlayerId(),"parameters",parameters,"choices",choices);
        var values = new HashMap<>(state.values()); var server = object(values.get("serverOnly"));
        server.put("pendingResponse",pending); values.put("serverOnly",server);
        var labels = new ArrayList<Map<String,Object>>();
        choices.forEach((code,value)->labels.add(Map.of("code",code,"label",object(value).get("label"))));
        var publicWindow = Map.<String,Object>of("windowId",window,"responderPlayerId",target.playerId(),"label",rule.label(),
                "choices",ScopedData.owner(target.playerId(),labels),
                "subject",ScopedData.owner(target.playerId(),Map.of("unitId",target.unitId(),"unitCode",target.unitCode())));
        values.put("responseWindow",publicWindow);
        var events = new ArrayList<>(state.events());
        events.add(new GameEvent(events.size()+1L,"response_opened",action.actorPlayerId(),Map.of("responseWindow",publicWindow)));
        return new ActionResult(true,"RESPONSE_REQUIRED","Waiting for the designated player's response",new GameState(state.sessionId(),state.definitionVersionId(),state.status(),state.round(),
                state.phaseCode(),state.activePlayerId(),state.stateVersion()+1,state.players(),state.units(),state.board(),events,values));
    }
    public static ActionResult resolve(GameState state, Action response, BiFunction<GameState,Action,ActionResult> resume) {
        var pending = pending(state);
        if(pending.isEmpty()) return reject("NO_RESPONSE_WINDOW","No response is pending");
        if(((Number)pending.get("responderPlayerId")).longValue()!=response.actorPlayerId()) return reject("NOT_RESPONDER","Only the designated player may respond");
        if(!pending.get("windowId").equals(response.parameters().get("windowId"))) return reject("STALE_RESPONSE","This response window is no longer current");
        var choice = object(object(pending.get("choices")).get(String.valueOf(response.parameters().get("choice"))));
        if(choice.isEmpty()) return reject("RESPONSE_INVALID","Choose an available response");
        var parameters = object(pending.get("parameters")); parameters.putAll(object(choice.get("parameters")));
        var values = new HashMap<>(state.values()); var server = object(values.get("serverOnly"));
        server.remove("pendingResponse"); values.put("serverOnly",server); values.remove("responseWindow");
        var resumed = new GameState(state.sessionId(),state.definitionVersionId(),state.status(),state.round(),state.phaseCode(),state.activePlayerId(),state.stateVersion(),
                state.players(),state.units(),state.board(),state.events(),values);
        var result = resume.apply(resumed,new Action(pending.get("actionType").toString(),((Number)pending.get("actorPlayerId")).longValue(),parameters));
        if (!result.accepted()) return result;
        var next = result.resultingState(); var events = new ArrayList<>(next.events());
        var last = events.getLast(); var payload = new HashMap<>(last.payload());
        payload.put("response", ScopedData.owner(response.actorPlayerId(), Map.of("windowId", pending.get("windowId"), "choice", response.parameters().get("choice"))));
        events.set(events.size()-1,new GameEvent(last.sequence(),last.type(),last.actorPlayerId(),payload));
        return new ActionResult(true,result.code(),result.message(),new GameState(next.sessionId(),next.definitionVersionId(),next.status(),next.round(),next.phaseCode(),
                next.activePlayerId(),next.stateVersion(),next.players(),next.units(),next.board(),events,next.values()));
    }
    private static ActionResult reject(String code,String message) { return new ActionResult(false,code,message,null); }
}
