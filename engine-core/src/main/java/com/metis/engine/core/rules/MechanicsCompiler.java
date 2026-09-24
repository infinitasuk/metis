package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class MechanicsCompiler {
    private MechanicsCompiler() { }
    static MechanicsRules compile(JsonNode root,CompileScope scope,Set<String> modules) {
        for(var entry:Map.of("dice","dice","resultTables","dice","decks","cards","effects","effects").entrySet())
            if(root.has(entry.getKey()))scope.expect(modules.contains(entry.getValue()),"MODULE_REQUIRED","/"+entry.getKey(),"Mechanical configuration requires its pinned module");
        var dice=new TreeMap<String,MechanicsRules.Die>();
        for(String name:CompileScope.keys(root.path("dice"))) {
            var node=root.get("dice").get(name);var faces=new ArrayList<MechanicsRules.Face>();int total=0;
            for(var face:node.get("faces")) {
                int weight=face.get("weight").intValue();total=Math.addExact(total,weight);var symbols=new ArrayList<String>();face.get("symbols").forEach(s->symbols.add(s.textValue()));
                faces.add(new MechanicsRules.Face(weight,face.get("value").longValue(),symbols));
            }
            dice.put(name,new MechanicsRules.Die(faces,node.get("maxRerolls").intValue(),total));
        }
        var tables=new TreeMap<String,MechanicsRules.ResultTable>();
        for(String name:CompileScope.keys(root.path("resultTables"))) {
            var node=root.get("resultTables").get(name);var symbols=new TreeMap<String,Long>();node.get("symbols").fields().forEachRemaining(e->symbols.put(e.getKey(),e.getValue().longValue()));
            tables.put(name,new MechanicsRules.ResultTable(symbols,node.get("valueMultiplier").longValue(),node.get("unmapped").textValue().equals("IGNORE")));
        }
        var decks=new TreeMap<String,MechanicsRules.Deck>();
        for(String name:CompileScope.keys(root.path("decks"))) {
            var node=root.get("decks").get(name);var cards=new ArrayList<String>();node.get("cards").forEach(c->cards.add(c.textValue()));
            scope.expect(cards.size()<=scope.limits.maxCollectionSize(),"COLLECTION_LIMIT","/decks/"+name+"/cards","Deck exceeds configured collection limit");
            decks.put(name,new MechanicsRules.Deck(cards,node.get("shuffle").booleanValue(),MechanicsRules.Exhaustion.valueOf(node.get("exhaustion").textValue()),CompiledRules.Audience.valueOf(node.get("discardVisibility").textValue())));
        }
        var effects=new TreeMap<String,MechanicsRules.Effect>();
        for(String name:CompileScope.keys(root.path("effects"))) {
            var node=root.get("effects").get(name);int stacks=node.get("maximumStacks").intValue();
            scope.expect(stacks<=scope.limits.maxCollectionSize(),"COLLECTION_LIMIT","/effects/"+name,"Effect stack limit exceeds collection limit");
            effects.put(name,new MechanicsRules.Effect(MechanicsRules.Duration.valueOf(node.get("duration").textValue()),MechanicsRules.Stacking.valueOf(node.get("stacking").textValue()),stacks,CompiledRules.Audience.valueOf(node.get("visibility").textValue())));
        }
        return new MechanicsRules(dice,tables,decks,effects);
    }
}
