package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.metis.engine.core.rules.*;
import java.io.IOException;
import java.util.ArrayList;

/** Versioned checkpoint/commit JSON with strict typed-value reconstruction. */
public final class RulesStateCodec {
    private static final int MAX_CHARS=16_000_000;
    private static final ObjectMapper JSON=createMapper();
    private RulesStateCodec() { }
    private static ObjectMapper createMapper() {
        var mapper=com.fasterxml.jackson.databind.json.JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).maxStringLength(4096).maxNumberLength(256).build()).build())
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var module=new SimpleModule();module.addDeserializer(RuleValue.class,new JsonDeserializer<>() {
            @Override public RuleValue deserialize(JsonParser parser,DeserializationContext context) throws IOException {
                JsonNode node=context.readTree(parser);
                if(!node.isObject()||node.size()!=2||!node.hasNonNull("type")||!node.hasNonNull("value"))throw new IOException("Invalid typed value");
                RuleType type=parser.getCodec().treeToValue(node.get("type"),RuleType.class);JsonNode value=node.get("value");
                try {
                    if(type.kind()==RuleType.Kind.LIST) {
                        if(!value.isArray())throw new IOException("Expected typed list");
                        var items=new ArrayList<RuleValue>();for(var item:value)items.add(parser.getCodec().treeToValue(item,RuleValue.class));
                        return new RuleValue(type,items);
                    }
                    if(type.kind()==RuleType.Kind.ENTITY)return new RuleValue(type,parser.getCodec().treeToValue(value,RuleValue.EntityRef.class));
                    return literal(type,value);
                } catch(IllegalArgumentException error) { throw new IOException("Invalid typed value",error); }
            }
        });mapper.registerModule(module);return mapper;
    }
    /** Raw action parameter conversion; the runtime still checks references and eligibility. */
    public static RuleValue literal(RuleType type,JsonNode value) {
        if(value==null||value.isNull())throw new IllegalArgumentException("Null is not a rules value");
        return switch(type.kind()) {
            case INTEGER -> { if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalArgumentException("Expected integer");yield RuleValue.integer(value.longValue()); }
            case DECIMAL -> { if(!value.isNumber())throw new IllegalArgumentException("Expected decimal");yield RuleValue.decimal(value.decimalValue()); }
            case BOOLEAN -> { if(!value.isBoolean())throw new IllegalArgumentException("Expected boolean");yield RuleValue.bool(value.booleanValue()); }
            case STRING,PLAYER,ENTITY -> {
                if(!value.isTextual())throw new IllegalArgumentException("Expected string identity/value");
                yield type.kind()==RuleType.Kind.STRING?RuleValue.text(value.textValue()):type.kind()==RuleType.Kind.PLAYER?RuleValue.player(value.textValue()):RuleValue.entity(type.entityType(),value.textValue());
            }
            case LIST -> {
                if(!value.isArray())throw new IllegalArgumentException("Expected list");
                var items=new ArrayList<RuleValue>();value.forEach(item->items.add(literal(type.element(),item)));yield RuleValue.list(type.element(),items);
            }
        };
    }
    public static String encode(Object value) {
        try {
            var writer=new java.io.Writer() {
                final StringBuilder text=new StringBuilder();
                public void write(char[] chars,int offset,int length) throws IOException {
                    if((long)text.length()+length>MAX_CHARS)throw new IOException("Checkpoint exceeds size limit");text.append(chars,offset,length);
                }
                public void flush() { }
                public void close() { }
            };
            JSON.writeValue(writer,value);return writer.text.toString();
        } catch(IOException error) { throw new IllegalArgumentException("Cannot encode checkpoint: "+error.getMessage(),error); }
    }
    public static RulesState decode(String source) { return decode(source,RulesState.class); }
    public static RulesState.Commit decodeCommit(String source) { return decode(source,RulesState.Commit.class); }
    private static <T> T decode(String source,Class<T> type) {
        if(source==null||source.length()>MAX_CHARS)throw new IllegalArgumentException("Checkpoint exceeds size limit");
        try {
            var node=JSON.readTree(source);
            if(node!=null&&node.path("control").isObject()&&!node.get("control").has("mechanics"))((com.fasterxml.jackson.databind.node.ObjectNode)node.get("control")).putNull("mechanics");
            return JSON.treeToValue(node,type);
        }catch(JsonProcessingException error){throw new IllegalArgumentException("Invalid checkpoint JSON",error);}
    }
}
