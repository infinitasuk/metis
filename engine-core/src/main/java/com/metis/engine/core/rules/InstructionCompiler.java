package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class InstructionCompiler {
    private final CompileScope scope;
    private final ExpressionCompiler expressions;
    private final OperationRegistry registry;
    private final Set<String> modules;
    private int sourceInstructions;
    InstructionCompiler(CompileScope scope,OperationRegistry registry,Set<String> modules) {
        this.scope=scope;this.registry=registry;this.modules=modules;expressions=new ExpressionCompiler(scope,registry,modules);
    }
    Expression expression(JsonNode node,Map<String,RuleType> env,String path) { return expressions.compile(node,env,path); }
    List<CompiledRules.Instruction> steps(JsonNode nodes,Map<String,RuleType> env,String path) {
        scope.expect(nodes!=null&&nodes.isArray(),"SHAPE_INVALID",path,"Expected instruction array");
        var result=new ArrayList<CompiledRules.Instruction>();
        for(int i=0;i<nodes.size();i++) {
            scope.expect(++sourceInstructions<=10000,"SOURCE_LIMIT",path,"Too many source instructions");
            result.add(instruction(nodes.get(i),env,path+"/"+i));
        }
        return List.copyOf(result);
    }
    Map<String,Expression> arguments(JsonNode node,Map<String,RuleType> signature,Map<String,RuleType> env,String path) {
        scope.shape(node,signature.keySet(),Set.of(),path);var result=new TreeMap<String,Expression>();
        for(var parameter:signature.entrySet()) {
            var argument=expression(node.get(parameter.getKey()),env,path+"/"+parameter.getKey());
            scope.type(parameter.getValue(),argument,argument.path());result.put(parameter.getKey(),argument);
        }
        return result;
    }
    Map<String,RuleType> procedure(String name,String path) {
        var signature=scope.procedures.get(name);scope.expect(signature!=null,"PROCEDURE_UNKNOWN",path,"Unknown procedure "+name);return signature;
    }
    private CompiledRules.Instruction instruction(JsonNode node,Map<String,RuleType> env,String path) {
        scope.expect(node.isObject()&&node.path("op").isTextual(),"SHAPE_INVALID",path,"Instruction requires op");
        String name=node.get("op").textValue();var operation=registry.operation(name);
        scope.expect(operation!=null,"OPERATION_UNKNOWN",path+"/op","Unknown operation "+name);
        scope.expect(modules.contains(name.split("\\.")[0]),"MODULE_NOT_DECLARED",path+"/op","Operation module is not pinned in definition");
        var required=new HashSet<>(operation.required());required.add("op");scope.shape(node,required,operation.optional(),path);
        var inputs=new TreeMap<String,Expression>();var refs=new TreeMap<String,String>();var blocks=new TreeMap<String,List<CompiledRules.Instruction>>();String binding=null;
        switch(name) {
            case "core.let" -> {
                var value=put(inputs,"value",node,env,path);binding=id(node,"name",path);scope.bind(env,binding,value.type(),path+"/name");
            }
            case "core.if" -> {
                scope.type(RuleType.BOOLEAN,put(inputs,"condition",node,env,path),path+"/condition");
                blocks.put("then",steps(node.get("then"),new TreeMap<>(env),path+"/then"));blocks.put("else",steps(node.get("else"),new TreeMap<>(env),path+"/else"));
            }
            case "core.forEach" -> {
                var source=put(inputs,"source",node,env,path);scope.expect(source.type().kind()==RuleType.Kind.LIST,"TYPE_MISMATCH",path+"/source","forEach requires list");
                var max=node.get("maxItems");scope.expect(max.isIntegralNumber()&&max.canConvertToInt()&&max.intValue()>0&&max.intValue()<=scope.limits.maxCollectionSize(),"LOOP_BOUND_INVALID",path+"/maxItems","Explicit positive collection bound required");
                refs.put("maxItems",max.asText());binding=id(node,"as",path);var nested=new TreeMap<>(env);scope.bind(nested,binding,source.type().element(),path+"/as");
                blocks.put("steps",steps(node.get("steps"),nested,path+"/steps"));
            }
            case "core.call" -> {
                String target=id(node,"procedure",path);refs.put("procedure",target);
                inputs.putAll(arguments(node.get("args"),procedure(target,path+"/procedure"),env,path+"/args"));
            }
            case "core.window" -> {
                String target=id(node,"window",path);var signature=scope.windows.get(target);
                scope.expect(signature!=null,"WINDOW_UNKNOWN",path+"/window","Unknown reaction window "+target);
                refs.put("window",target);inputs.putAll(arguments(node.get("args"),signature,env,path+"/args"));
            }
            case "core.choose" -> {
                scope.type(RuleType.PLAYER,put(inputs,"player",node,env,path),path+"/player");
                var options=put(inputs,"options",node,env,path);scope.expect(options.type().kind()==RuleType.Kind.LIST,"TYPE_MISMATCH",path+"/options","Choice options must be a list");
                String visibility=node.get("visibility").asText();scope.expect(Set.of("PUBLIC","OWNER").contains(visibility),"VISIBILITY_INVALID",path+"/visibility","Player decisions must be PUBLIC or OWNER");
                refs.put("visibility",visibility);binding=id(node,"as",path);scope.bind(env,binding,options.type().element(),path+"/as");
            }
            case "core.setState" -> {
                String field=id(node,"field",path);var definition=scope.state.get(field);scope.expect(definition!=null,"STATE_UNKNOWN",path+"/field","Unknown state field "+field);
                refs.put("field",field);scope.type(definition.type(),put(inputs,"value",node,env,path),path+"/value");
            }
            case "core.setAttribute","core.modifyResource" -> {
                var entity=put(inputs,"entity",node,env,path);boolean attribute=name.equals("core.setAttribute");String key=attribute?"field":"resource";
                String field=id(node,key,path);var read=expressions.field(entity,attribute?Expression.Access.ATTRIBUTE:Expression.Access.RESOURCE,field,path+"/"+key);
                refs.put(key,field);scope.type(read.type(),put(inputs,attribute?"value":"delta",node,env,path),path);
            }
            case "core.createEntity" -> {
                String type=id(node,"type",path);var definition=scope.entities.get(type);scope.expect(definition!=null,"ENTITY_TYPE_UNKNOWN",path+"/type","Unknown entity type "+type);refs.put("type",type);
                scope.type(RuleType.STRING,put(inputs,"id",node,env,path),path+"/id");
                scope.expect(node.has("owner")== (definition.ownership()==CompiledRules.Ownership.PLAYER),"OWNER_INVALID",path+"/owner","Owner must be specified exactly for player-owned entity types");
                if(node.has("owner"))scope.type(RuleType.PLAYER,put(inputs,"owner",node,env,path),path+"/owner");
                scope.shape(node.get("attributes"),Set.of(),definition.attributes().keySet(),path+"/attributes");
                for(String field:CompileScope.keys(node.get("attributes"))) {
                    var value=expression(node.get("attributes").get(field),env,path+"/attributes/"+field);scope.type(definition.attributes().get(field).type(),value,value.path());inputs.put("attribute:"+field,value);
                }
                scope.shape(node.get("resources"),Set.of(),definition.resources().keySet(),path+"/resources");
                for(String resource:CompileScope.keys(node.get("resources"))) {
                    var value=expression(node.get("resources").get(resource),env,path+"/resources/"+resource);scope.type(RuleType.INTEGER,value,value.path());inputs.put("resource:"+resource,value);
                }
                binding=id(node,"as",path);scope.bind(env,binding,RuleType.entity(type),path+"/as");
            }
            case "core.removeEntity" -> {
                var entity=put(inputs,"entity",node,env,path);scope.expect(entity.type().kind()==RuleType.Kind.ENTITY,"TYPE_MISMATCH",path+"/entity","Expected entity");
            }
            case "core.emit" -> {
                String event=id(node,"event",path);var definition=scope.events.get(event);scope.expect(definition!=null,"EVENT_UNKNOWN",path+"/event","Unknown event "+event);refs.put("event",event);
                scope.expect(node.has("to")== (definition.visibility()==CompiledRules.Audience.OWNER),"AUDIENCE_INVALID",path+"/to","An OWNER event requires exactly one recipient");
                if(node.has("to"))scope.type(RuleType.PLAYER,put(inputs,"to",node,env,path),path+"/to");
                arguments(node.get("values"),definition.fields(),env,path+"/values").forEach((field,value)->inputs.put("value:"+field,value));
            }
            case "core.endGame" -> {
                scope.type(RuleType.STRING,put(inputs,"outcome",node,env,path),path+"/outcome");scope.type(RuleType.list(RuleType.PLAYER),put(inputs,"winners",node,env,path),path+"/winners");
            }
            default -> {
                inputs.putAll(arguments(node.get("args"),operation.arguments(),env,path+"/args"));
                if(operation.result()!=null) { binding=id(node,"as",path);scope.bind(env,binding,operation.result(),path+"/as"); }
            }
        }
        return new CompiledRules.Instruction(name,inputs,refs,blocks,binding,path);
    }
    private Expression put(Map<String,Expression> values,String key,JsonNode node,Map<String,RuleType> env,String path) {
        var value=expression(node.get(key),env,path+"/"+key);values.put(key,value);return value;
    }
    private String id(JsonNode node,String key,String path) { return scope.identifier(node.get(key),path+"/"+key); }
}
