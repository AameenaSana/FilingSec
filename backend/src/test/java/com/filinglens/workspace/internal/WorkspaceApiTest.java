package com.filinglens.workspace.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;
import com.filinglens.workspace.internal.WorkspaceService.SourceReference;

@WebMvcTest(controllers = WorkspaceController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class WorkspaceApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	WorkspaceService workspace;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/findings")).andExpect(status().isUnauthorized());
	}

	@Test
	void validatesRequests() throws Exception {
		mvc.perform(post("/api/v1/research-sessions").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\" \"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/findings").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"source\":{\"type\":\"FREE_TEXT\"}}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/findings").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"source\":{\"type\":\"PASSAGE\",\"accessionNumber\":\"x\",\"blockOrdinal\":3}}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(workspace);
	}

	@Test
	void savesFindingFromReference() throws Exception {
		when(workspace.saveFinding(eq(USER.id()), eq(4L), any(), eq("note"), eq(List.of("ai"))))
			.thenReturn(WorkspaceServiceTest.finding("note", null));

		mvc.perform(post("/api/v1/findings").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"sessionId":4,"source":{"type":"CITATION","citationId":3},"note":"note","tags":["ai"],
					 "quotedText":"ignored"}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.quotedText").value("AI features\nmay not be successful."))
			.andExpect(jsonPath("$.filing.accessionNumber").value(WorkspaceServiceTest.ACCESSION));
		verify(workspace).saveFinding(USER.id(), 4L, new SourceReference("CITATION", 3L, null, null, null, null, null),
				"note", List.of("ai"));
	}

	@Test
	void exportsMarkdownAttachment() throws Exception {
		when(workspace.exportMarkdown(USER.id(), 4L)).thenReturn("# AI risk\n");

		mvc.perform(get("/api/v1/research-sessions/4/export").with(user()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"research-session-4.md\""))
			.andExpect(content().contentTypeCompatibleWith("text/markdown"))
			.andExpect(content().string("# AI risk\n"));
	}

	@Test
	void missingSessionIsNotFound() throws Exception {
		when(workspace.session(USER.id(), 9L)).thenThrow(new NotFoundException("Session", 9L));

		mvc.perform(get("/api/v1/research-sessions/9").with(user())).andExpect(status().isNotFound());
	}

}
