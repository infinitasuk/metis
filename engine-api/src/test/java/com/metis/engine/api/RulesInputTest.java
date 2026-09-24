package com.metis.engine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RulesInputTest {
    @Test void transportPreservesDecimalLiteralKindsAndExactPrecision() {
        var repository=new Drafts();var service=new RulesService(repository,new ObjectMapper());
        var source=service.parse("{\"id\":\"precise\",\"revision\":\"1\",\"value\":1e0,\"fraction\":0.1234567890123456789012345678901234}");
        var draft=service.createDefinition(source,new MetisUser(5,"admin","Admin","admin"));
        assertTrue(draft.source().contains("\"value\":1.0"));assertTrue(draft.source().contains("0.1234567890123456789012345678901234"));
        assertEquals(draft.source(),service.definition(1,new MetisUser(5,"admin","Admin","admin")).source());
    }
    @Test void duplicateKeysTrailingValuesAndImplicitVersionsCannotEnterCommands() {
        var service=new RulesService(null,new ObjectMapper());
        for(String invalid:new String[]{"{\"x\":1,\"x\":2}","{} {}","[]","null"})assertThrows(RulesService.Failure.class,()->service.parse(invalid));
        for(String invalid:new String[]{"{}","{\"expectedVersion\":null}","{\"expectedVersion\":1.0}","{\"expectedVersion\":\"1\"}","{\"expectedVersion\":-1}","{\"expectedVersion\":0,\"actor\":\"forged\"}"})
            assertThrows(RulesService.Failure.class,()->service.editVersion(service.parse(invalid),false));
        assertEquals(0,service.editVersion(service.parse("{\"expectedVersion\":0}"),false));
        assertThrows(RulesService.Failure.class,()->service.parse("{\"x\":1e1000000000}"));
    }
    @Test void definitionPublicationPermissionsAreAlsoEnforcedBelowTheWebInterceptor() {
        var service=new RulesService(null,new ObjectMapper());var user=new MetisUser(1,"alice","Alice","player");
        assertEquals(403,assertThrows(RulesService.Failure.class,()->service.publish(1,0,user)).status());
        assertEquals(403,assertThrows(RulesService.Failure.class,()->service.createDefinition(service.parse("{\"id\":\"x\",\"revision\":\"1\"}"),user)).status());
    }
    private static final class Drafts extends RulesRepository {
        private String source;
        Drafts() { super(null); }
        @Override public long createDefinition(String source,long user) { this.source=source;return 1; }
        @Override public Optional<Definition> definition(long id,boolean lock) { return Optional.of(new Definition(id,"precise","1",0,"draft",source,null,null,null,null,null,null)); }
    }
}
