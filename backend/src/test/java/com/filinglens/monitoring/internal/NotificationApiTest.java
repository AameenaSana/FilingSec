package com.filinglens.monitoring.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.filinglens.identity.UserPrincipal;
import com.filinglens.monitoring.internal.NotificationViews.NotificationPage;
import com.filinglens.monitoring.internal.NotificationViews.NotificationView;
import com.filinglens.monitoring.internal.NotificationViews.Preferences;
import com.filinglens.monitoring.internal.NotificationViews.Type;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = NotificationController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class NotificationApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	NotificationService notifications;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void listsNotificationsWithUnreadCount() throws Exception {
		when(notifications.list(USER.id(), true, 10)).thenReturn(new NotificationPage(1, List.of(new NotificationView(
				9, Type.NEW_FILING, "Apple Inc. filed a 10-K", "b", "/filings/x", Instant.EPOCH, false))));

		mvc.perform(get("/api/v1/notifications?unreadOnly=true&limit=10").with(user()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(1))
			.andExpect(jsonPath("$.items[0].type").value("NEW_FILING"));
	}

	@Test
	void validatesLimitAndPreferences() throws Exception {
		mvc.perform(get("/api/v1/notifications?limit=1000").with(user())).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/notification-preferences").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest());
		verifyNoInteractions(notifications);
	}

	@Test
	void updatesPreferences() throws Exception {
		when(notifications.updatePreferences(USER.id(), false)).thenReturn(new Preferences(false));

		mvc.perform(put("/api/v1/notification-preferences").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"emailEnabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.emailEnabled").value(false));
		verify(notifications).updatePreferences(USER.id(), false);
	}

	@Test
	void markingUnknownNotificationIsNotFound() throws Exception {
		doThrow(new NotFoundException("Notification", 5L)).when(notifications).markRead(USER.id(), 5);

		mvc.perform(post("/api/v1/notifications/5/read").with(user()).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/notifications/read-all").with(user()).with(csrf()))
			.andExpect(status().isNoContent());
	}

	@Test
	void streamRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isUnauthorized());
		verify(notifications, never()).stream(any());
	}

}
