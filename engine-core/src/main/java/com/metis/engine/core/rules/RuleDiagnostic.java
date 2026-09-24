package com.metis.engine.core.rules;

/** JSON Pointer paths are relative to the submitted definition. */
public record RuleDiagnostic(String code, String definitionId, String ruleId, String path, String message) { }
