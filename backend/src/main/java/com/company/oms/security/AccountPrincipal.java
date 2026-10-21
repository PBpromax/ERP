package com.company.oms.security;

import java.security.Principal;
import java.util.Set;

public record AccountPrincipal(Long userId, String username, String sessionId, Set<String> menuCodes, boolean orderPricesVisible) implements Principal {
    public AccountPrincipal(Long userId, String username, String sessionId, Set<String> menuCodes) {
        this(userId, username, sessionId, menuCodes, true);
    }
    @Override public String getName() { return username; }
}
