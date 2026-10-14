package com.filinglens.document.internal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.filinglens.document.internal.DocumentViews.DocumentStructure;
import com.filinglens.document.internal.DocumentViews.ParseStatus;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = DocumentController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class })
class DocumentApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	static final String ACCESSION = "0000320193-25-000079";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	DocumentService documents;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void viewerIsFramableOnlyBySameOriginAndSandboxed() throws Exception {
		when(documents.viewerHtml(ACCESSION)).thenReturn("<html><body>doc</body></html>".getBytes());

		mvc.perform(get("/api/v1/filings/{a}/source", ACCESSION).with(user()))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/html"))
			.andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
			.andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'self'")))
			.andExpect(header().string("Content-Security-Policy", containsString("sandbox allow-same-origin")))
			.andExpect(header().string("Content-Security-Policy", not(containsString("allow-scripts"))))
			.andExpect(header().string("Content-Security-Policy", not(containsString("script-src"))));
	}

	@Test
	void otherEndpointsCannotBeFramed() throws Exception {
		when(documents.structure(ACCESSION))
			.thenReturn(new DocumentStructure(ParseStatus.PARSING, null, 1, null, null, List.of()));

		mvc.perform(get("/api/v1/filings/{a}/structure", ACCESSION).with(user()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PARSING"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
	}

	@Test
	void viewerRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/filings/{a}/source", ACCESSION)).andExpect(status().isUnauthorized());
		verifyNoInteractions(documents);
	}

	@Test
	void validatesBlockReferences() throws Exception {
		mvc.perform(get("/api/v1/filings/{a}/blocks/by-ref", ACCESSION).param("ref", "../../etc").with(user()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/filings/{a}/blocks", ACCESSION).param("section", "drop table;").with(user()))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(documents);
	}

}
