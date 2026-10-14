package com.filinglens.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline HTTP security: deny by default, CSRF protection for the browser SPA
 * (cookie-to-header token), ProblemDetail responses for 401/403, and strict
 * security headers. Authentication itself is added by the identity module.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailSecurityHandler problemHandler)
			throws Exception {
		http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/api/v1/system/**", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
			.permitAll()
			.anyRequest()
			.authenticated())
			.csrf(csrf -> csrf.spa())
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.exceptionHandling(ex -> ex.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
		return http.build();
	}

	/**
	 * Placeholder that knows no users, so Spring Boot does not generate a default
	 * user with a logged password. Replaced by the identity module in Phase 1.
	 */
	@Bean
	UserDetailsService noUsersUserDetailsService() {
		return username -> {
			throw new UsernameNotFoundException("No users configured");
		};
	}

}
