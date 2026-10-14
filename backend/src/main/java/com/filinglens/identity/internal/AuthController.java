package com.filinglens.identity.internal;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.error.ForbiddenException;
import com.filinglens.platform.error.UnauthorizedException;
import com.filinglens.platform.ratelimit.RateLimiter;

/**
 * Registration, email verification, login and password reset. Logout is handled
 * by Spring Security at {@code POST /api/v1/auth/logout}.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	static final RateLimiter.Limit LOGIN = new RateLimiter.Limit("login", 10, Duration.ofMinutes(1));

	static final RateLimiter.Limit EMAIL_SENDING = new RateLimiter.Limit("email", 5, Duration.ofMinutes(15));

	static final RateLimiter.Limit TOKEN_REDEMPTION = new RateLimiter.Limit("token", 10, Duration.ofMinutes(1));

	private static final Map<String, String> CHECK_EMAIL = Map.of("message",
			"If the address can be used, we have sent an email with the next steps.");

	private final AccountService accounts;

	private final LoginService login;

	private final SessionEstablisher sessions;

	private final RateLimiter rateLimiter;

	AuthController(AccountService accounts, LoginService login, SessionEstablisher sessions, RateLimiter rateLimiter) {
		this.accounts = accounts;
		this.login = login;
		this.sessions = sessions;
		this.rateLimiter = rateLimiter;
	}

	/** Ensures the XSRF-TOKEN cookie is set; call before the first state-changing request. */
	@GetMapping("/csrf")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void csrf(CsrfToken token) {
		token.getToken();
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, String> register(@Valid @RequestBody IdentityRequests.Register body, HttpServletRequest request) {
		rateLimiter.acquireOrThrow(EMAIL_SENDING, request.getRemoteAddr());
		accounts.register(body.email(), body.password(), body.displayName());
		return CHECK_EMAIL;
	}

	@PostMapping("/verify-email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void verifyEmail(@Valid @RequestBody IdentityRequests.Token body, HttpServletRequest request) {
		rateLimiter.acquireOrThrow(TOKEN_REDEMPTION, request.getRemoteAddr());
		accounts.verifyEmail(body.token());
	}

	@PostMapping("/verify-email/resend")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, String> resendVerification(@Valid @RequestBody IdentityRequests.EmailOnly body,
			HttpServletRequest request) {
		rateLimiter.acquireOrThrow(EMAIL_SENDING, request.getRemoteAddr());
		accounts.resendVerification(body.email());
		return CHECK_EMAIL;
	}

	@PostMapping("/login")
	AccountView login(@Valid @RequestBody IdentityRequests.Login body, HttpServletRequest request,
			HttpServletResponse response) {
		rateLimiter.acquireOrThrow(LOGIN, request.getRemoteAddr());
		return switch (login.authenticate(body.email(), body.password())) {
			case LoginService.Outcome.Success success -> {
				sessions.start(success.principal(), request, response);
				yield accounts.get(success.principal().id());
			}
			case LoginService.Outcome.InvalidCredentials ignored -> throw new UnauthorizedException(
					"invalid-credentials",
					"Invalid email or password. After repeated failures, sign-in is temporarily blocked.");
			case LoginService.Outcome.EmailNotVerified ignored -> throw new ForbiddenException("email-not-verified",
					"Confirm your email address before signing in. Check your inbox for the link.");
			case LoginService.Outcome.Disabled ignored ->
				throw new ForbiddenException("account-disabled", "This account has been disabled.");
		};
	}

	@GetMapping("/me")
	AccountView me(@AuthenticationPrincipal UserPrincipal principal) {
		return accounts.get(principal.id());
	}

	@PostMapping("/password/forgot")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, String> forgotPassword(@Valid @RequestBody IdentityRequests.EmailOnly body,
			HttpServletRequest request) {
		rateLimiter.acquireOrThrow(EMAIL_SENDING, request.getRemoteAddr());
		accounts.requestPasswordReset(body.email());
		return CHECK_EMAIL;
	}

	@PostMapping("/password/reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@Valid @RequestBody IdentityRequests.ResetPassword body, HttpServletRequest request) {
		rateLimiter.acquireOrThrow(TOKEN_REDEMPTION, request.getRemoteAddr());
		accounts.resetPassword(body.token(), body.newPassword());
	}

}
