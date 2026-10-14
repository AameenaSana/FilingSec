package com.filinglens.identity.internal;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;

/** The signed-in user's own account. */
@RestController
@RequestMapping("/api/v1/account")
class AccountController {

	/** Limits guessing of the current password through these endpoints. */
	static final RateLimiter.Limit PASSWORD_CONFIRMATION = new RateLimiter.Limit("password-confirmation", 10,
			Duration.ofMinutes(15));

	private final AccountService accounts;

	private final SessionEstablisher sessions;

	private final RateLimiter rateLimiter;

	AccountController(AccountService accounts, SessionEstablisher sessions, RateLimiter rateLimiter) {
		this.accounts = accounts;
		this.sessions = sessions;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping
	AccountView get(@AuthenticationPrincipal UserPrincipal principal) {
		return accounts.get(principal.id());
	}

	@PatchMapping
	AccountView update(@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody IdentityRequests.UpdateAccount body) {
		return accounts.rename(principal.id(), body.displayName());
	}

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody IdentityRequests.ChangePassword body, HttpServletRequest request) {
		rateLimiter.acquireOrThrow(PASSWORD_CONFIRMATION, principal.getName());
		accounts.changePassword(principal.id(), body.currentPassword(), body.newPassword(),
				SessionEstablisher.currentSessionId(request));
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody IdentityRequests.DeleteAccount body, HttpServletRequest request) {
		rateLimiter.acquireOrThrow(PASSWORD_CONFIRMATION, principal.getName());
		accounts.delete(principal.id(), body.password(), SessionEstablisher.currentSessionId(request));
		sessions.end(request);
	}

}
