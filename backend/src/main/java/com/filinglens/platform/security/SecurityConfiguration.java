package com.filinglens.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

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

	private static final String CSP = "Content-Security-Policy";

	/** API responses are data, never documents: nothing may load or frame them. */
	static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

	/**
	 * The filing source viewer: sanitized SEC HTML shown in a same-origin frame. No
	 * scripts can run (no script-src, CSP sandbox without allow-scripts); only inline
	 * styles and images from sec.gov load; links open in a new tab.
	 */
	static final String VIEWER_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src https://www.sec.gov data:; "
			+ "font-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; "
			+ "sandbox allow-same-origin allow-popups allow-popups-to-escape-sandbox";

	static final RequestMatcher VIEWER = PathPatternRequestMatcher.withDefaults()
		.matcher(HttpMethod.GET, "/api/v1/filings/{accession}/source");

	private static final RequestMatcher NOT_VIEWER = new NegatedRequestMatcher(VIEWER);

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
			.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
				.addHeaderWriter(only(NOT_VIEWER, new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY)))
				.addHeaderWriter(only(NOT_VIEWER, new StaticHeadersWriter(CSP, API_CSP)))
				.addHeaderWriter(only(VIEWER, new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
				.addHeaderWriter(only(VIEWER, new StaticHeadersWriter(CSP, VIEWER_CSP)))
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
		return http.build();
	}

	private static HeaderWriter only(RequestMatcher matcher, HeaderWriter writer) {
		return new DelegatingRequestMatcherHeaderWriter(matcher, writer);
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
