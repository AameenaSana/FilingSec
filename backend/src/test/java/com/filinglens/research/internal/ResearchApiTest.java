package com.filinglens.research.internal;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.filinglens.ai.AiNotConfiguredException;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = ResearchController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class ResearchApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ResearchService research;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void validatesConversationScopeAndQuestions() throws Exception {
		mvc.perform(post("/api/v1/conversations").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"filings\":[]}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/conversations").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"filings\":[\"not-an-accession\"]}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/conversations/1/messages").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + "x".repeat(2001) + "\"}")).andExpect(status().isBadRequest());
		verifyNoInteractions(research);
	}

	@Test
	void unconfiguredAiIsReportedAsProblemDetail() throws Exception {
		when(research.ask(eq(USER.id()), eq(1L), anyString())).thenThrow(new AiNotConfiguredException());

		mvc.perform(post("/api/v1/conversations/1/messages").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
			.content("{\"content\":\"What AI risks?\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:ai-not-configured"));
	}

	@Test
	void otherUsersConversationsAreNotFound() throws Exception {
		when(research.get(eq(USER.id()), anyLong())).thenThrow(new NotFoundException("Conversation", 9));

		mvc.perform(get("/api/v1/conversations/9").with(user())).andExpect(status().isNotFound());
	}

}
