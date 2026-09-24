package com.metis.engine.core.rules;

public final class RuleEvaluationException extends RuntimeException {
    private final String code;
    private final String path;
    public RuleEvaluationException(String code, String path, String message) {
        super(message); this.code = code; this.path = path;
    }
    public String code() { return code; }
    public String path() { return path; }
}
