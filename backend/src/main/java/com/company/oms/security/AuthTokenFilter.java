package com.company.oms.security;

import com.company.oms.system.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class AuthTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final SysUserRepository users;
    private final MenuCatalog catalog;
    public AuthTokenFilter(TokenService tokens, SysUserRepository users, MenuCatalog catalog) {
        this.tokens = tokens; this.users = users; this.catalog = catalog;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var session = tokens.parseSession(header.substring(7));
                var user = users.findById(session.userId).orElse(null);
                if (user != null && !Boolean.TRUE.equals(user.deleted) && Boolean.TRUE.equals(user.enabled)) {
                    var codes = catalog.effective(user);
                    var principal = new AccountPrincipal(user.id, user.username, session.id, codes, UserAccountService.orderPricesVisible(user));
                    var authorities = codes.stream().map(code -> new SimpleGrantedAuthority("MENU_" + code)).toList();
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
                }
            } catch (Exception ignored) { SecurityContextHolder.clearContext(); }
        }
        chain.doFilter(request, response);
    }
}
