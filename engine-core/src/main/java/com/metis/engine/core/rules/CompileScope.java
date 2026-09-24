package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Shared semantic symbols and location-aware validation helpers. */
final class CompileScope {
    final String id;
    final CompiledRules.Limits limits;
    final Set<String> entityNames;
    final Map<String,CompiledRules.Field> state=new TreeMap<>();
    final Map<String,CompiledRules.EntityType> entities=new TreeMap<>();
    final Map<String,CompiledRules.EventType> events=new TreeMap<>();
    final Map<String,Map<String,RuleType>> procedures=new TreeMap<>();
    final Map<String,Map<String,RuleType>> windows=new TreeMap<>();
    final Map<String,CompiledRules.Window> compiledWindows=new TreeMap<>();
    CompileScope(String id,CompiledRules.Limits limits,Set<String> entityNames) { this.id=id;this.limits=limits;this.entityNames=Set.copyOf(entityNames); }
    static final class Invalid extends RuntimeException {
        final RuleDiagnostic diagnostic;
        Invalid(RuleDiagnostic diagnostic) { super(diagnostic.message());this.diagnostic=diagnostic; }
    }
    Invalid error(String code,String path,String message) {
        String[] parts=path.split("/");String rule=null;
        if(parts.length>2 && Set.of("procedures","actions","events","windows").contains(parts[1]))rule=parts[2];
        if(parts.length>3 && parts[1].equals("flow") && parts[2].equals("phases"))rule=parts[3];
        return new Invalid(new RuleDiagnostic(code,id,rule,path,message));
    }
    void expect(boolean condition,String code,String path,String message) { if(!condition)throw error(code,path,message); }
    String identifier(JsonNode node,String path) {
        expect(node!=null && node.isTextual() && node.textValue().matches("[a-z][a-zA-Z0-9_-]{0,63}"),"IDENTIFIER_INVALID",path,"Expected a language identifier");
        return node.textValue();
    }
    void type(RuleType expected,Expression actual,String path) {
        expect(expected.accepts(actual.type()),"TYPE_MISMATCH",path,"Expected "+expected.display()+", found "+actual.type().display());
    }
    RuleType type(JsonNode node,String path) {
        if(node.isObject() && node.size()==1 && node.has("list"))return RuleType.list(type(node.get("list"),path+"/list"));
        expect(node.isTextual(),"TYPE_UNKNOWN",path,"Expected a type name or list type");
        String name=node.textValue();
        if(name.startsWith("entity:")) {
            String entity=name.substring(7);expect(entityNames.contains(entity),"ENTITY_TYPE_UNKNOWN",path,"Unknown entity type "+entity);
            return RuleType.entity(entity);
        }
        return switch(name) {
            case "integer"->RuleType.INTEGER;case "decimal"->RuleType.DECIMAL;case "boolean"->RuleType.BOOLEAN;
            case "string"->RuleType.STRING;case "player"->RuleType.PLAYER;
            default->throw error("TYPE_UNKNOWN",path,"Unknown type "+name);
        };
    }
    RuleValue initial(JsonNode node,RuleType type,String path) {
        try {
            return switch(type.kind()) {
                case INTEGER -> { expect(node.isIntegralNumber()&&node.canConvertToLong(),"VALUE_TYPE",path,"Expected signed 64-bit integer");yield RuleValue.integer(node.longValue()); }
                case DECIMAL -> { expect(node.isNumber(),"VALUE_TYPE",path,"Expected number");yield RuleValue.decimal(node.decimalValue()); }
                case BOOLEAN -> { expect(node.isBoolean(),"VALUE_TYPE",path,"Expected boolean");yield RuleValue.bool(node.booleanValue()); }
                case STRING,PLAYER,ENTITY -> {
                    expect(node.isTextual(),"VALUE_TYPE",path,"Expected string identity/value");
                    yield type.kind()==RuleType.Kind.STRING?RuleValue.text(node.textValue()):type.kind()==RuleType.Kind.PLAYER?RuleValue.player(node.textValue()):RuleValue.entity(type.entityType(),node.textValue());
                }
                case LIST -> {
                    expect(node.isArray()&&node.size()<=limits.maxCollectionSize(),"COLLECTION_LIMIT",path,"Expected bounded list");
                    var items=new ArrayList<RuleValue>();for(int i=0;i<node.size();i++)items.add(initial(node.get(i),type.element(),path+"/"+i));
                    yield RuleValue.list(type.element(),items);
                }
            };
        } catch(IllegalArgumentException exception) { throw error("VALUE_BOUNDS",path,exception.getMessage()); }
    }
    Map<String,RuleType> parameters(JsonNode node,String path) {
        var result=new TreeMap<String,RuleType>();
        for(String name:keys(node)) { identifier(com.fasterxml.jackson.databind.node.TextNode.valueOf(name),path+"/"+name);result.put(name,type(node.get(name),path+"/"+name)); }
        return result;
    }
    void bind(Map<String,RuleType> env,String name,RuleType type,String path) {
        identifier(com.fasterxml.jackson.databind.node.TextNode.valueOf(name),path);
        expect(!env.containsKey(name),"BINDING_EXISTS",path,"Binding already exists: "+name);env.put(name,type);
    }
    Map<String,RuleType> environment(Map<String,RuleType> parameters,String path) {
        var env=new TreeMap<String,RuleType>();env.put("round",RuleType.INTEGER);env.put("players",RuleType.list(RuleType.PLAYER));
        parameters.forEach((name,type)->bind(env,name,type,path+"/"+name));return env;
    }
    void shape(JsonNode node,Set<String> required,Set<String> optional,String path) {
        expect(node.isObject(),"SHAPE_INVALID",path,"Expected object");
        for(String key:new TreeSet<>(required))expect(node.has(key),"FIELD_REQUIRED",path+"/"+key,"Required field is missing");
        for(String key:keys(node))expect(required.contains(key)||optional.contains(key),"FIELD_UNKNOWN",path+"/"+key,"Unknown field");
    }
    static Set<String> keys(JsonNode node) { var result=new TreeSet<String>();node.fieldNames().forEachRemaining(result::add);return result; }
}
