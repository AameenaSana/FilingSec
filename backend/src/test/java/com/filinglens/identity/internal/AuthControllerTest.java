package com.filinglens.identity.internal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = { AuthController.class, AccountController.class })
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, SessionEstablisher.class,
		RateLimiter.class })
class AuthControllerTest {

	static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AccountService accounts;

	@MockitoBean
	LoginService login;

	@Test
	void loginWithoutCsrfTokenIsRejected() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"x\"}")).andExpect(status().isForbidden());
		verifyNoInteractions(login);
	}

	@Test
	void successfulLoginStartsSessionAndRotatesCsrfToken() throws Exception {
		UserPrincipal principal = new UserPrincipal(USER_ID, "a@example.com", "USER");
		when(login.authenticate("a@example.com", "secret passphrase"))
			.thenReturn(new LoginService.Outcome.Success(principal));
		when(accounts.get(USER_ID)).thenReturn(new AccountView(USER_ID, "a@example.com", "Analyst", "USER", true,
				Instant.parse("2026-01-01T00:00:00Z")));

		mvc.perform(post("/api/v1/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"secret passphrase\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Analyst"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist())
			.andExpect(cookie().exists("XSRF-TOKEN"));
	}

	@Test
	void invalidCredentialsReturnGeneric401() throws Exception {
		when(login.authenticate(anyString(), anyString())).thenReturn(new LoginService.Outcome.InvalidCredentials());

		mvc.perform(post("/api/v1/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"nope\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:invalid-credentials"));
	}

	@Test
	void unverifiedEmailReturns403WithCode() throws Exception {
		when(login.authenticate(anyString(), anyString())).thenReturn(new LoginService.Outcome.EmailNotVerified());

		mvc.perform(post("/api/v1/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"secret passphrase\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:email-not-verified"));
	}

	@Test
	void registrationValidatesInput() throws Exception {
		mvc.perform(post("/api/v1/auth/register").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"not-an-email\",\"password\":\"short\",\"displayName\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:validation"))
			.andExpect(jsonPath("$.errors.length()").value(3));
		verifyNoInteractions(accounts);
	}

	@Test
	void registrationAlwaysReturnsTheSameAcceptedResponse() throws Exception {
		mvc.perform(post("/api/v1/auth/register").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@example.com\",\"password\":\"a good long passphrase\",\"displayName\":\"A\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.message").value(containsString("If the address can be used")));
		verify(accounts).register("a@example.com", "a good long passphrase", "A");
	}

	@Test
	void emailSendingEndpointsAreRateLimitedPerClient() throws Exception {
		for (int i = 0; i < AuthController.EMAIL_SENDING.capacity(); i++) {
			mvc.perform(post("/api/v1/auth/password/forgot").with(csrf())
				.with(request -> {
					request.setRemoteAddr("203.0.113.7");
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"a@example.com\"}")).andExpect(status().isAccepted());
		}
		mvc.perform(post("/api/v1/auth/password/forgot").with(csrf()).with(request -> {
			request.setRemoteAddr("203.0.113.7");
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.com\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:too-many-requests"));
	}

	@Test
	void meRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/account")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutReturnsNoContent() throws Exception {
		mvc.perform(post("/api/v1/auth/logout").with(csrf()))
			.andExpect(status().isNoContent())
			.andExpect(header().doesNotExist("Location"));
	}

	@Test
	void accountUpdateIsValidated() throws Exception {
		when(accounts.rename(any(), anyString())).thenThrow(new AssertionError("should not be called"));
		var principal = new UserPrincipal(USER_ID, "a@example.com", "USER");
		mvc.perform(patch("/api/v1/account").with(csrf())
			.with(authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"\"}")).andExpect(status().isBadRequest());
	}

}
