package com.metis.engine.core.rules;

import java.util.*;

/** Compiler capability contracts. M2 will supply execution handlers separately. */
public final class OperationRegistry {
    public enum Effect { PURE, STATE, DECISION, EVENT, TERMINAL, RANDOM }
    public enum Preview { PURE, READ_ONLY, NONE }
    public record Operation(String name,Set<String> required,Set<String> optional,
                            Map<String,RuleType> arguments,RuleType result,Set<Effect> effects,Preview preview) {
        public Operation {
            Objects.requireNonNull(name);Objects.requireNonNull(preview);
            required=Set.copyOf(required);optional=Set.copyOf(optional);arguments=Map.copyOf(arguments);effects=Set.copyOf(effects);
            if (!Collections.disjoint(required,optional) || required.contains("op") || optional.contains("op")) throw new IllegalArgumentException("Invalid operation fields");
            for(String argument:arguments.keySet())if(!argument.matches("[a-z][a-zA-Z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid argument name");
        }
        public static Operation mechanical(String name,Map<String,RuleType> arguments,RuleType result,Set<Effect> effects,Preview preview) {
            return new Operation(name,result==null?Set.of("args"):Set.of("args","as"),Set.of(),arguments,result,effects,preview);
        }
    }
    public record Module(String id,String version,List<Operation> operations) {
        public Module {
            if (id==null || !id.matches("[a-z][a-z0-9_-]{0,63}") || version==null || !version.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid module identity");
            operations=List.copyOf(operations);
            for (var operation:operations) if (!operation.name().matches(java.util.regex.Pattern.quote(id)+"\\.[a-zA-Z][a-zA-Z0-9]*")) throw new IllegalArgumentException("Operation must use its module namespace");
        }
    }
    private final Map<String,Module> modules;
    private final Map<String,Operation> operations;
    public OperationRegistry(List<Module> extensions) {
        var modules=new TreeMap<String,Module>();var operations=new TreeMap<String,Operation>();
        var all=new ArrayList<Module>();all.add(core());all.addAll(mechanics());all.addAll(spatial());all.add(maneuver());all.addAll(extensions);
        for(var module:all) {
            if(modules.putIfAbsent(module.id(),module)!=null)throw new IllegalArgumentException("Duplicate module "+module.id());
            for(var operation:module.operations()) {
                if(!module.id().equals("core") && (!operation.optional().isEmpty() || !operation.required().equals(operation.result()==null?Set.of("args"):Set.of("args","as"))))
                    throw new IllegalArgumentException("Mechanical operations must use the args/as contract");
                if(operations.putIfAbsent(operation.name(),operation)!=null)throw new IllegalArgumentException("Duplicate operation "+operation.name());
            }
        }
        this.modules=Collections.unmodifiableMap(modules);this.operations=Collections.unmodifiableMap(operations);
    }
    public static OperationRegistry standard() { return new OperationRegistry(List.of()); }
    public Map<String,Module> modules() { return modules; }
    public Map<String,Operation> operations() { return operations; }
    public Operation operation(String name) { return operations.get(name); }
    /** Stable contract material: a changed signature changes the compiled fingerprint. */
    public List<String> contracts(Set<String> selected) {
        var result=new ArrayList<String>();
        for(var module:modules.values())if(selected.contains(module.id())) {
            result.add(module.id()+"@"+module.version());
            module.operations().stream().sorted(Comparator.comparing(Operation::name)).forEach(op->result.add(op.name()+"|"
                    +new TreeSet<>(op.required())+"|"+new TreeSet<>(op.optional())+"|"
                    +op.arguments().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+":"+e.getValue().display()).toList()
                    +"|"+(op.result()==null?"void":op.result().display())+"|"+new TreeSet<>(op.effects())+"|"+op.preview()));
        }
        return List.copyOf(result);
    }
    private static Operation coreOp(String name,Set<String> required,Set<String> optional,Effect...effects) {
        return new Operation("core."+name,required,optional,Map.of(),null,Set.of(effects),Preview.NONE);
    }
    private static Module core() {
        return new Module("core","1",List.of(
                coreOp("let",Set.of("name","value"),Set.of(),Effect.PURE),
                coreOp("if",Set.of("condition","then","else"),Set.of(),Effect.PURE),
                coreOp("forEach",Set.of("source","as","maxItems","steps"),Set.of(),Effect.PURE),
                coreOp("call",Set.of("procedure","args"),Set.of(),Effect.PURE),
                coreOp("window",Set.of("window","args"),Set.of(),Effect.DECISION),
                coreOp("choose",Set.of("player","options","as","visibility"),Set.of(),Effect.DECISION),
                coreOp("setState",Set.of("field","value"),Set.of(),Effect.STATE),
                coreOp("setAttribute",Set.of("entity","field","value"),Set.of(),Effect.STATE),
                coreOp("modifyResource",Set.of("entity","resource","delta"),Set.of(),Effect.STATE),
                coreOp("createEntity",Set.of("type","id","attributes","resources","as"),Set.of("owner"),Effect.STATE),
                coreOp("removeEntity",Set.of("entity"),Set.of(),Effect.STATE),
                coreOp("emit",Set.of("event","values"),Set.of("to"),Effect.EVENT),
                coreOp("endGame",Set.of("outcome","winners"),Set.of(),Effect.TERMINAL)));
    }
    private static Operation op(String name,RuleType result,Effect effect,Object... signature) {
        var args=new TreeMap<String,RuleType>();for(int i=0;i<signature.length;i+=2)args.put((String)signature[i],(RuleType)signature[i+1]);
        return Operation.mechanical(name,args,result,Set.of(effect),effect==Effect.PURE?Preview.READ_ONLY:Preview.NONE);
    }
    private static List<Module> mechanics() {
        var s=RuleType.STRING;var i=RuleType.INTEGER;var e=RuleType.ENTITY;var p=RuleType.PLAYER;var strings=RuleType.list(s);var indices=RuleType.list(i);
        return List.of(
            new Module("dice","1",List.of(
                op("dice.roll",s,Effect.RANDOM,"dice",strings,"owner",p,"visibility",s),
                op("dice.add",null,Effect.RANDOM,"roll",s,"dice",strings),
                op("dice.remove",null,Effect.STATE,"roll",s,"indices",indices),
                op("dice.reroll",null,Effect.RANDOM,"roll",s,"indices",indices,"allowed",indices,"maximum",i),
                op("dice.cancel",null,Effect.STATE,"roll",s,"symbols",strings,"maximum",i),
                op("dice.close",null,Effect.STATE,"roll",s),op("dice.clear",null,Effect.STATE,"roll",s),
                op("dice.indices",indices,Effect.PURE,"roll",s),op("dice.symbols",strings,Effect.PURE,"roll",s),
                op("dice.value",i,Effect.PURE,"roll",s,"index",i),op("dice.symbolsAt",strings,Effect.PURE,"roll",s,"index",i),
                op("dice.type",s,Effect.PURE,"roll",s,"index",i),op("dice.resolve",i,Effect.PURE,"roll",s,"table",s))),
            new Module("cards","1",List.of(
                op("cards.create",s,Effect.RANDOM,"deck",s,"owner",p,"visibility",s),op("cards.hand",s,Effect.STATE,"owner",p,"visibility",s),
                op("cards.draw",strings,Effect.RANDOM,"deck",s,"to",s,"count",i),op("cards.discard",null,Effect.STATE,"cards",strings,"from",s,"deck",s),
                op("cards.move",null,Effect.STATE,"cards",strings,"from",s,"to",s),op("cards.shuffle",null,Effect.RANDOM,"deck",s),
                op("cards.pile",strings,Effect.PURE,"pile",s),op("cards.type",s,Effect.PURE,"card",s))),
            new Module("resources","1",List.of(
                op("resources.transfer",null,Effect.STATE,"from",e,"fromResource",s,"to",e,"toResource",s,"amount",i),
                op("resources.convert",null,Effect.STATE,"entity",e,"from",s,"to",s,"spend",i,"gain",i),
                op("resources.reset",null,Effect.STATE,"entity",e,"resource",s,"value",i),
                op("resources.once",null,Effect.STATE,"entity",e,"key",s,"period",s),op("resources.used",RuleType.BOOLEAN,Effect.PURE,"entity",e,"key",s,"period",s))),
            new Module("effects","1",List.of(
                op("effects.apply",s,Effect.STATE,"entity",e,"key",s,"value",i),op("effects.remove",null,Effect.STATE,"id",s),
                op("effects.total",i,Effect.PURE,"entity",e,"key",s),op("effects.ids",strings,Effect.PURE,"entity",e,"key",s))),
            new Module("queues","1",List.of(
                op("queues.plan",null,Effect.STATE,"entity",e,"field",s,"capacity",i,"orders",strings,"allowed",strings),
                op("queues.commit",null,Effect.STATE,"entity",e,"field",s),op("queues.reveal",s,Effect.STATE,"entity",e,"field",s)))
        );
    }
    private static List<Module> spatial() {
        var e=RuleType.ENTITY;var s=RuleType.STRING;var d=RuleType.DECIMAL;var path=RuleType.list(RuleType.list(d));
        return List.of(new Module("space","1",List.of(
                op("space.move",null,Effect.STATE,"entity",e,"policy",s,"path",path,"heading",d),
                op("space.deploy",null,Effect.STATE,"entity",e,"zone",s,"x",d,"y",d,"heading",d),
                op("space.canMove",RuleType.BOOLEAN,Effect.PURE,"entity",e,"policy",s,"path",path,"heading",d),
                op("space.distance",d,Effect.PURE,"from",e,"to",e,"mode",s),
                op("space.lineOfSight",RuleType.BOOLEAN,Effect.PURE,"from",e,"to",e),
                op("space.inArc",RuleType.BOOLEAN,Effect.PURE,"from",e,"to",e,"start",d,"sweep",d),
                op("space.inZone",RuleType.BOOLEAN,Effect.PURE,"entity",e,"zone",s),
                op("space.paths",RuleType.list(path),Effect.PURE,"entity",e,"policy",s,"targets",RuleType.list(e)))),
            new Module("scenario","1",List.of(
                op("scenario.require",null,Effect.STATE,"condition",RuleType.BOOLEAN,"code",s),
                op("scenario.roster",null,Effect.STATE,"owner",RuleType.PLAYER,"entities",RuleType.list(e),"policy",s),
                op("scenario.damage",RuleType.INTEGER,Effect.STATE,"target",e,"policy",s,"amount",RuleType.INTEGER))));
    }
    private static Module maneuver() {
        var e=RuleType.ENTITY;var i=RuleType.INTEGER;var s=RuleType.STRING;var d=RuleType.DECIMAL;var ints=RuleType.list(i);var decimals=RuleType.list(d);var chart=RuleType.list(ints);
        return new Module("maneuver","1",List.of(
                op("maneuver.move",null,Effect.STATE,"entity",e,"speed",i,"yaw",ints,"side",s,"chart",chart,"segment",d,"angle",d,"anchor",decimals,"toolWidth",d,"collisions",RuleType.BOOLEAN),
                op("maneuver.canMove",RuleType.BOOLEAN,Effect.PURE,"entity",e,"speed",i,"yaw",ints,"side",s,"chart",chart,"segment",d,"angle",d,"anchor",decimals,"toolWidth",d,"collisions",RuleType.BOOLEAN),
                op("maneuver.options",chart,Effect.PURE,"entity",e,"speed",i,"side",s,"chart",chart,"segment",d,"angle",d,"anchor",decimals,"toolWidth",d,"collisions",RuleType.BOOLEAN)));
    }
}
