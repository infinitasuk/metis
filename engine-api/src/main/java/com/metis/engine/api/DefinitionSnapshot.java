package com.metis.engine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.GameDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** An exact, checksummed definition captured before a session initializes units. */
public record DefinitionSnapshot(int schemaVersion, String rulesVersion, String json, String checksum) {
    public static DefinitionSnapshot capture(ObjectMapper mapper, GameDefinition definition) {
        try {
            String json = mapper.writeValueAsString(definition);
            return new DefinitionSnapshot(1, definition.turnStructure() == null ? "legacy-v1" : "activation-v1", json, hash(json));
        } catch (Exception error) { throw new IllegalStateException("Could not capture definition", error); }
    }
    public GameDefinition resolve(ObjectMapper mapper, long expectedRevision) {
        if (schemaVersion != 1 || !Set.of("legacy-v1", "activation-v1").contains(rulesVersion)) {
            throw new IllegalStateException("Unsupported battle snapshot/rules version");
        }
        if (!hash(json).equals(checksum)) throw new IllegalStateException("Battle definition checksum does not match");
        try {
            GameDefinition definition = mapper.readValue(json, GameDefinition.class);
            if (definition.definitionVersionId() != expectedRevision) throw new IllegalStateException("Battle definition revision does not match");
            if ((definition.turnStructure() != null) != "activation-v1".equals(rulesVersion)) throw new IllegalStateException("Battle rules contract does not match");
            return definition;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Invalid captured definition", error); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
