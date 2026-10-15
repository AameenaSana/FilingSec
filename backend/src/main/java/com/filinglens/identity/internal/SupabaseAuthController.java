package com.filinglens.identity.internal;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.error.ForbiddenException;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.error.UnauthorizedException;
import com.filinglens.platform.ratelimit.RateLimiter;

/**
 * Sign-in through Supabase Auth. The browser completes the provider flow (e.g.
 * Google) with Supabase and sends the resulting access token here once; from then
 * on the user has an ordinary FilingLens session.
 */
@RestController
@RequestMapping("/api/v1/auth/supabase")
class SupabaseAuthController {

	record SignIn(@NotBlank @Size(max = 8192) String accessToken) {
	}

	private final SupabaseLoginService login;

	private final AccountService accounts;

	private final SessionEstablisher sessions;

	private final RateLimiter rateLimiter;

	SupabaseAuthController(SupabaseLoginService login, AccountService accounts, SessionEstablisher sessions,
			RateLimiter rateLimiter) {
		this.login = login;
		this.accounts = accounts;
		this.sessions = sessions;
		this.rateLimiter = rateLimiter;
	}

	/** Whether the backend accepts Supabase sign-in. */
	@GetMapping
	Map<String, Boolean> status() {
		return Map.of("enabled", login.enabled());
	}

	@PostMapping
	AccountView signIn(@Valid @RequestBody SignIn body, HttpServletRequest request, HttpServletResponse response) {
		if (!login.enabled()) {
			throw new NotFoundException("Sign-in provider", "supabase");
		}
		rateLimiter.acquireOrThrow(AuthController.LOGIN, request.getRemoteAddr());
		return switch (login.authenticate(body.accessToken())) {
			case SupabaseLoginService.Outcome.Success success -> {
				sessions.start(success.principal(), request, response);
				yield accounts.get(success.principal().id());
			}
			case SupabaseLoginService.Outcome.Rejected ignored -> throw new UnauthorizedException("invalid-credentials",
					"The sign-in could not be confirmed. Please try again.");
			case SupabaseLoginService.Outcome.Conflict ignored -> throw new ConflictException("account-linked",
					"This email address is already linked to a different sign-in. Use that sign-in or your password.");
			case SupabaseLoginService.Outcome.Disabled ignored ->
				throw new ForbiddenException("account-disabled", "This account has been disabled.");
		};
	}

}
