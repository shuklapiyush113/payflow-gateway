package dev.payflow.payment.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import dev.payflow.payment.merchant.MerchantService;

/**
 * Stateless API-key security. {@code /v1/**} needs a merchant key, {@code /admin/**} the admin token,
 * and health and metrics stay open for probes and scraping. 401 and 403 are rendered by
 * {@code ApiExceptionHandler}, so they are problem details like every other error.
 */
@Configuration
public class SecurityConfig {

    private static final int MIN_ADMIN_TOKEN_LENGTH = 32;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MerchantService merchants,
                                            @Value("${payflow.admin.token:}") String adminToken,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        if (!adminToken.isBlank() && adminToken.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalStateException("payflow.admin.token must be at least " + MIN_ADMIN_TOKEN_LENGTH
                    + " characters; leave it empty to disable the admin API");
        }
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error")
                        .permitAll()
                        .requestMatchers("/v1/**").hasRole("MERCHANT")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(new ApiKeyAuthenticationFilter(merchants, adminToken), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> resolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) -> resolver.resolveException(request, response, null, ex)))
                .build();
    }
}
