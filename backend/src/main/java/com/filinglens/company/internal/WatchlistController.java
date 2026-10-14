package com.filinglens.company.internal;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.company.internal.CompanyViews.WatchlistEntry;
import com.filinglens.identity.UserPrincipal;

@RestController
@RequestMapping("/api/v1/watchlist")
class WatchlistController {

	private static final String FORM_TYPE = "10-K|10-Q|8-K";

	private final WatchlistService watchlist;

	WatchlistController(WatchlistService watchlist) {
		this.watchlist = watchlist;
	}

	record AddRequest(@NotNull @Pattern(regexp = "\\d{1,10}", message = "must be a CIK of 1-10 digits") String cik,
			@Size(max = 3) List<@Pattern(regexp = FORM_TYPE) String> formTypes, Boolean notificationsEnabled) {
	}

	record UpdateRequest(@NotNull @Size(min = 1, max = 3) List<@Pattern(regexp = FORM_TYPE) String> formTypes,
			@NotNull Boolean notificationsEnabled) {
	}

	@GetMapping
	List<WatchlistEntry> list(@AuthenticationPrincipal UserPrincipal user) {
		return watchlist.list(user.id());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	WatchlistEntry add(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody AddRequest body) {
		boolean notifications = body.notificationsEnabled() == null || body.notificationsEnabled();
		return watchlist.add(user.id(), body.cik(), body.formTypes(), notifications);
	}

	@PatchMapping("/{id}")
	WatchlistEntry update(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
			@Valid @RequestBody UpdateRequest body) {
		return watchlist.update(user.id(), id, body.formTypes(), body.notificationsEnabled());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		watchlist.remove(user.id(), id);
	}

}
