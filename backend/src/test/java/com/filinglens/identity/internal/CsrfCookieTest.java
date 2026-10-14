package com.filinglens.identity.internal;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

/**
 * Exercises the real cookie-to-header CSRF mechanism. Deliberately avoids the
 * {@code csrf()} MockMvc post-processor, which replaces the token repository.
 */
@WebMvcTest(controllers = AuthController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, SessionEstablisher.class,
		RateLimiter.class })
class CsrfCookieTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AccountService accounts;

	@MockitoBean
	LoginService login;

	@Test
	void csrfEndpointIssuesReadableSameSiteCookie() throws Exception {
		mvc.perform(get("/api/v1/auth/csrf"))
			.andExpect(status().isNoContent())
			.andExpect(cookie().value("XSRF-TOKEN", not(emptyString())))
			.andExpect(cookie().httpOnly("XSRF-TOKEN", false))
			.andExpect(cookie().sameSite("XSRF-TOKEN", "Lax"));
	}

	@Test
	void echoingTheCookieAsHeaderPassesCsrfCheck() throws Exception {
		Cookie token = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		String body = "{\"email\":\"a@example.com\"}";

		mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/password/forgot").cookie(token)
			.header("X-XSRF-TOKEN", "wrong-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/password/forgot").cookie(token)
			.header("X-XSRF-TOKEN", token.getValue())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isAccepted());
	}

}
