package com.company.oms.config;

import com.company.oms.security.AuthTokenFilter;
import com.company.oms.system.MenuPermissionService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final AuthTokenFilter authTokenFilter;
    private final MenuPermissionService menuPermissions;

    public SecurityConfig(AuthTokenFilter authTokenFilter, MenuPermissionService menuPermissions) {
        this.authTokenFilter = authTokenFilter;
        this.menuPermissions = menuPermissions;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, ex) -> {
                        response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"success\":false,\"message\":\"登录已失效，请重新登录\"}");
                    })
                    .accessDeniedHandler((request, response, ex) -> {
                        response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"success\":false,\"message\":\"无权限访问该功能\"}");
                    }))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login").permitAll()
                        .requestMatchers("/production/records/worker-entry/**").permitAll()
                        .requestMatchers("/material-entry/**").authenticated()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().access((authentication, context) ->
                            new AuthorizationDecision(menuPermissions.allows(context.getRequest())))
                );
        http.addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
