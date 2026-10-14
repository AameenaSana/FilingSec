package com.filinglens.dashboard.internal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.dashboard.internal.DashboardViews.Dashboard;
import com.filinglens.identity.UserPrincipal;

@RestController
class DashboardController {

	private final DashboardService dashboard;

	DashboardController(DashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/api/v1/dashboard")
	Dashboard dashboard(@AuthenticationPrincipal UserPrincipal user) {
		return dashboard.dashboard(user.id());
	}

}
