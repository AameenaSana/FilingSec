package com.filinglens.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.filinglens.identity.UserPrincipal;

/** Starts and ends authenticated sessions for the identity endpoints. */
@Component
class SessionEstablisher {

	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	private final SecurityContextRepository securityContextRepository;

	private final CookieCsrfTokenRepository csrfTokenRepository;

	SessionEstablisher(SecurityContextRepository securityContextRepository,
			CookieCsrfTokenRepository csrfTokenRepository) {
		this.securityContextRepository = securityContextRepository;
		this.csrfTokenRepository = csrfTokenRepository;
	}

	/**
	 * Authenticates the session. The session id is rotated (session fixation
	 * protection) and a fresh CSRF token is issued.
	 */
	void start(UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		request.getSession(true);
		request.changeSessionId();
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
				principal.authorities()));
		contextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
	}

	/** Invalidates the current session and clears the security context. */
	void end(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		contextHolder.clearContext();
	}

	static String currentSessionId(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return session != null ? session.getId() : null;
	}

}
