package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class ExpressionCompiler {
    static final Set<String> OPERATORS=Set.of("add","sub","mul","div","floor","ceil","eq","ne","lt","lte","gt","gte","and","or","not","count","sum","contains","at","append","id","concat");
    private final CompileScope scope;
    private final OperationRegistry registry;
    private final Set<String> modules;
    ExpressionCompiler(CompileScope scope,OperationRegistry registry,Set<String> modules) { this.scope=scope;this.registry=registry;this.modules=Set.copyOf(modules); }

    Expression compile(JsonNode node,Map<String,RuleType> env,String path) {
        scope.expect(node!=null&&!node.isNull(),"EXPRESSION_INVALID",path,"An expression is required; null is not a value");
        if(node.isBoolean())return new Expression.Literal(RuleValue.bool(node.booleanValue()),path);
        if(node.isTextual())return new Expression.Literal(RuleValue.text(node.textValue()),path);
        if(node.isNumber())return new Expression.Literal(scope.initial(node,node.isIntegralNumber()?RuleType.INTEGER:RuleType.DECIMAL,path),path);
        scope.expect(node.isObject(),"EXPRESSION_INVALID",path,"Expected expression object or scalar literal");
        if(node.has("op"))return operator(node,env,path);
        scope.expect(node.size()==1,"EXPRESSION_INVALID",path,"Expression requires exactly one discriminator");
        String kind=node.fieldNames().next();var value=node.get(kind);String at=path+"/"+kind;
        return switch(kind) {
            case "var" -> {
                String name=scope.identifier(value,at);var type=env.get(name);
                scope.expect(type!=null,"BINDING_UNKNOWN",at,"Unknown binding "+name);yield new Expression.Variable(name,type,path);
            }
            case "state" -> {
                String name=scope.identifier(value,at);var field=scope.state.get(name);
                scope.expect(field!=null,"STATE_UNKNOWN",at,"Unknown state field "+name);yield new Expression.State(name,field.type(),path);
            }
            case "entities" -> {
                String name=scope.identifier(value,at);scope.expect(scope.entityNames.contains(name),"ENTITY_TYPE_UNKNOWN",at,"Unknown entity type "+name);
                yield new Expression.Entities(name,path);
            }
            case "owner" -> field(compile(value,env,at),Expression.Access.OWNER,null,path);
            case "get","resource" -> {
                scope.shape(value,Set.of("entity","field"),Set.of(),at);
                yield field(compile(value.get("entity"),env,at+"/entity"),kind.equals("get")?Expression.Access.ATTRIBUTE:Expression.Access.RESOURCE,
                        scope.identifier(value.get("field"),at+"/field"),path);
            }
            case "list" -> {
                scope.shape(value,Set.of("elementType","items"),Set.of(),at);var type=scope.type(value.get("elementType"),at+"/elementType");
                var items=value.get("items");scope.expect(items.isArray()&&items.size()<=scope.limits.maxCollectionSize(),"COLLECTION_LIMIT",at+"/items","Expected bounded expression list");
                var compiled=new ArrayList<Expression>();
                for(int i=0;i<items.size();i++) { var item=compile(items.get(i),env,at+"/items/"+i);scope.type(type,item,item.path());compiled.add(item); }
                yield new Expression.ListValue(RuleType.list(type),compiled,path);
            }
            case "filter","map" -> {
                String bodyName=kind.equals("filter")?"where":"value";
                scope.shape(value,Set.of("source","as",bodyName),Set.of(),at);
                var source=compile(value.get("source"),env,at+"/source");list(source,source.path());
                String binding=scope.identifier(value.get("as"),at+"/as");var nested=new TreeMap<>(env);scope.bind(nested,binding,source.type().element(),at+"/as");
                var body=compile(value.get(bodyName),nested,at+"/"+bodyName);
                if(kind.equals("filter"))scope.type(RuleType.BOOLEAN,body,body.path());
                yield new Expression.Select(source,binding,body,kind.equals("filter"),kind.equals("filter")?source.type():RuleType.list(body.type()),path);
            }
            case "query" -> {
                scope.shape(value,Set.of("op","args"),Set.of(),at);String operation=value.get("op").asText();var contract=registry.operation(operation);
                scope.expect(contract!=null,"OPERATION_UNKNOWN",at+"/op","Unknown query operation "+operation);
                scope.expect(modules.contains(operation.split("\\.")[0]),"MODULE_NOT_DECLARED",at+"/op","Query module must be pinned");
                scope.expect(contract.result()!=null && contract.effects().equals(Set.of(OperationRegistry.Effect.PURE)) && contract.preview()!=OperationRegistry.Preview.NONE,
                        "QUERY_NOT_PURE",at+"/op","Queries require a pure result-producing operation with read-only preview support");
                scope.shape(value.get("args"),contract.arguments().keySet(),Set.of(),at+"/args");var arguments=new TreeMap<String,Expression>();
                for(var parameter:new TreeMap<>(contract.arguments()).entrySet()) {
                    var argument=compile(value.get("args").get(parameter.getKey()),env,at+"/args/"+parameter.getKey());scope.type(parameter.getValue(),argument,argument.path());arguments.put(parameter.getKey(),argument);
                }
                yield new Expression.Query(operation,arguments,contract.arguments(),contract.result(),path);
            }
            default -> throw scope.error("EXPRESSION_UNKNOWN",at,"Unknown expression "+kind);
        };
    }
    Expression.Field field(Expression entity,Expression.Access access,String name,String path) {
        scope.expect(entity.type().kind()==RuleType.Kind.ENTITY,"TYPE_MISMATCH",path,"Expected entity reference");
        var definition=scope.entities.get(entity.type().entityType());
        scope.expect(definition!=null,"ENTITY_TYPE_UNKNOWN",path,"Field access requires a concrete declared entity type");
        RuleType type;
        if(access==Expression.Access.OWNER) {
            scope.expect(definition.ownership()==CompiledRules.Ownership.PLAYER,"OWNER_UNAVAILABLE",path,"Entity type has no player owner");type=RuleType.PLAYER;
        } else if(access==Expression.Access.ATTRIBUTE) {
            scope.expect(definition.attributes().containsKey(name),"ATTRIBUTE_UNKNOWN",path,"Unknown attribute "+name);type=definition.attributes().get(name).type();
        } else {
            scope.expect(definition.resources().containsKey(name),"RESOURCE_UNKNOWN",path,"Unknown resource "+name);type=RuleType.INTEGER;
        }
        return new Expression.Field(entity,access,name,type,path);
    }
    private Expression operator(JsonNode node,Map<String,RuleType> env,String path) {
        scope.shape(node,Set.of("op","args"),Set.of(),path);String op=node.get("op").asText();
        scope.expect(OPERATORS.contains(op),"OPERATOR_UNKNOWN",path+"/op","Unknown expression operator "+op);
        var raw=node.get("args");scope.expect(raw.isArray(),"EXPRESSION_INVALID",path+"/args","Expected arguments array");
        boolean variadic=Set.of("and","or","concat").contains(op);
        int count=Set.of("not","count","sum","floor","ceil","id").contains(op)?1:2;
        scope.expect(raw.size()>=count&&(variadic||raw.size()==count)&&raw.size()<=scope.limits.maxCollectionSize(),"ARGUMENT_COUNT",path+"/args","Incorrect operator arity");
        var args=new ArrayList<Expression>();for(int i=0;i<raw.size();i++)args.add(compile(raw.get(i),env,path+"/args/"+i));
        RuleType type;
        switch(op) {
            case "and","or","not" -> { args.forEach(arg->scope.type(RuleType.BOOLEAN,arg,arg.path()));type=RuleType.BOOLEAN; }
            case "concat" -> { args.forEach(arg->scope.type(RuleType.STRING,arg,arg.path()));type=RuleType.STRING; }
            case "id" -> {
                scope.expect(Set.of(RuleType.Kind.PLAYER,RuleType.Kind.ENTITY).contains(args.getFirst().type().kind()),"TYPE_MISMATCH",path,"id requires a player or entity");type=RuleType.STRING;
            }
            case "eq","ne" -> { compatible(args.get(0).type(),args.get(1).type(),path);type=RuleType.BOOLEAN; }
            case "count","sum","contains","at","append" -> {
                var collection=args.getFirst();list(collection,collection.path());var element=collection.type().element();
                type=switch(op) {
                    case "count" -> RuleType.INTEGER;
                    case "sum" -> { scope.expect(element.numeric(),"TYPE_MISMATCH",path,"sum requires numeric elements");yield element; }
                    case "contains" -> { compatible(element,args.get(1).type(),path);yield RuleType.BOOLEAN; }
                    case "append" -> { scope.type(element,args.get(1),path);yield collection.type(); }
                    default -> { scope.type(RuleType.INTEGER,args.get(1),path);yield element; }
                };
            }
            default -> {
                args.forEach(arg->scope.expect(arg.type().numeric(),"TYPE_MISMATCH",arg.path(),"Expected numeric expression"));
                type=Set.of("lt","lte","gt","gte").contains(op)?RuleType.BOOLEAN:Set.of("floor","ceil").contains(op)?RuleType.INTEGER:
                        op.equals("div")||args.stream().anyMatch(arg->arg.type().equals(RuleType.DECIMAL))?RuleType.DECIMAL:RuleType.INTEGER;
            }
        }
        return new Expression.Apply(op,args,type,path);
    }
    private void compatible(RuleType a,RuleType b,String path) { scope.expect(a.accepts(b)||b.accepts(a),"TYPE_MISMATCH",path,"Values have incompatible types"); }
    private void list(Expression expression,String path) { scope.expect(expression.type().kind()==RuleType.Kind.LIST,"TYPE_MISMATCH",path,"Expected list expression"); }
}
