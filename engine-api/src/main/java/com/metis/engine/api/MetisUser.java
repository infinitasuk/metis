package com.metis.engine.api;

public record MetisUser(long userId, String username, String displayName, String roleCode) {
    public boolean admin() {
        return "admin".equals(roleCode);
    }
}
