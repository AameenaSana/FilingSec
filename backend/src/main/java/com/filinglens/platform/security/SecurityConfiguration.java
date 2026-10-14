package com.filinglens.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * HTTP security: deny by default, server-side sessions (persisted by Spring
 * Session), CSRF protection for the browser SPA (cookie-to-header token),
 * ProblemDetail responses for 401/403, and strict security headers.
 * <p>
 * Credentials are verified by the identity module's login endpoint, which stores
 * the authenticated context through {@link #securityContextRepository()}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	static final String LOGOUT_ENDPOINT = "/api/v1/auth/logout";

	static final String[] PUBLIC_AUTH_ENDPOINTS = { "/api/v1/auth/register", "/api/v1/auth/login",
			"/api/v1/auth/csrf", "/api/v1/auth/verify-email", "/api/v1/auth/verify-email/resend",
			"/api/v1/auth/password/forgot", "/api/v1/auth/password/reset" };

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailSecurityHandler problemHandler,
			SecurityContextRepository securityContextRepository, CookieCsrfTokenRepository csrfTokenRepository)
			throws Exception {
		http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/api/v1/system/**", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
			.permitAll()
			.requestMatchers(PUBLIC_AUTH_ENDPOINTS)
			.permitAll()
			.requestMatchers("/api/v1/admin/**")
			.hasRole("ADMIN")
			.anyRequest()
			.authenticated())
			.securityContext(context -> context.securityContextRepository(securityContextRepository))
			// The SPA reads the XSRF-TOKEN cookie and echoes it verbatim in X-XSRF-TOKEN.
			// The token is never rendered into HTML, so BREACH masking is unnecessary.
			.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
			.logout(logout -> logout
				.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOGOUT_ENDPOINT))
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.exceptionHandling(ex -> ex.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
		return http.build();
	}

	/** Stores the security context in the HTTP session (backed by Spring Session JDBC). */
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/**
	 * Double-submit CSRF token in a JS-readable {@code XSRF-TOKEN} cookie, echoed by
	 * the frontend as the {@code X-XSRF-TOKEN} header. Exposed as a bean so the login
	 * flow can rotate the token.
	 */
	@Bean
	CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
		return repository;
	}

	/**
	 * No form or basic login exists; this bean only stops Spring Boot from creating
	 * a default user with a generated password.
	 */
	@Bean
	UserDetailsService noUsersUserDetailsService() {
		return username -> {
			throw new UsernameNotFoundException("Interactive login is handled by the identity module");
		};
	}

}
