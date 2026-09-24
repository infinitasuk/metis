package com.metis.engine.api;

import com.metis.engine.core.LegalAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CpuResponseTest {
    @Test
    void usesAnAvailableResourceOtherwiseDeclines() {
        var decline = new LegalAction("respond", 20, "Decline", Map.of(), Map.of("windowId", "1:2", "choice", "decline"));
        var resource = new LegalAction("respond", 20, "Use resource", Map.of(), Map.of("windowId", "1:2", "choice", "resource:guard"));
        assertEquals(resource, CpuBattleRunner.chooseResponse(List.of(decline, resource)).orElseThrow());
        assertEquals(decline, CpuBattleRunner.chooseResponse(List.of(decline)).orElseThrow());
    }
}
