package com.filinglens.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.platform.mail.EmailMessage;
import com.filinglens.platform.mail.EmailSender;

/**
 * End-to-end account flow over real HTTP with real cookies, CSRF tokens, Spring
 * Session persistence and Postgres.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(IdentityFlowIntegrationTest.CapturingMail.class)
class IdentityFlowIntegrationTest {

	private static final Pattern TOKEN_LINK = Pattern.compile("#token=([A-Za-z0-9_-]+)");

	@LocalServerPort
	int port;

	@Autowired
	CapturingEmailSender mail;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		mail.messages.clear();
		jdbc.sql("DELETE FROM spring_session").update();
		jdbc.sql("DELETE FROM app_user").update();
	}

	@Test
	void registerVerifyLoginChangePasswordLogout() throws Exception {
		Browser browser = new Browser();

		assertThat(browser.post("/api/v1/auth/register",
				"{\"email\":\"Jane@Example.com\",\"password\":\"a good long passphrase\",\"displayName\":\"Jane\"}")
			.statusCode()).isEqualTo(202);
		String verificationToken = tokenFrom(mail.next("jane@example.com"));

		// Cannot sign in before verifying.
		assertThat(browser.post("/api/v1/auth/login", login("a good long passphrase")).statusCode()).isEqualTo(403);

		assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + verificationToken + "\"}").statusCode())
			.isEqualTo(204);
		// Tokens are single-use.
		assertThat(browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + verificationToken + "\"}").statusCode())
			.isEqualTo(400);

		assertThat(browser.post("/api/v1/auth/login", login("wrong password here")).statusCode()).isEqualTo(401);
		HttpResponse<String> loggedIn = browser.post("/api/v1/auth/login", login("a good long passphrase"));
		assertThat(loggedIn.statusCode()).isEqualTo(200);
		assertThat(loggedIn.body()).contains("\"email\":\"jane@example.com\"").doesNotContain("password");

		HttpResponse<String> me = browser.get("/api/v1/auth/me");
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"emailVerified\":true");

		// A second device is signed out by a password change; the current one is kept.
		Browser otherDevice = new Browser();
		assertThat(otherDevice.post("/api/v1/auth/login", login("a good long passphrase")).statusCode())
			.isEqualTo(200);
		assertThat(browser
			.put("/api/v1/account/password",
					"{\"currentPassword\":\"a good long passphrase\",\"newPassword\":\"another long passphrase\"}")
			.statusCode()).isEqualTo(204);
		assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
		assertThat(otherDevice.get("/api/v1/auth/me").statusCode()).isEqualTo(401);

		assertThat(browser.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(204);
		assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
	}

	@Test
	void duplicateRegistrationIsIndistinguishable() throws Exception {
		Browser browser = new Browser();
		String body = "{\"email\":\"dup@example.com\",\"password\":\"a good long passphrase\",\"displayName\":\"D\"}";

		HttpResponse<String> first = browser.post("/api/v1/auth/register", body);
		mail.next("dup@example.com");
		HttpResponse<String> second = browser.post("/api/v1/auth/register", body);

		assertThat(second.statusCode()).isEqualTo(first.statusCode());
		assertThat(second.body()).isEqualTo(first.body());
		assertThat(mail.next("dup@example.com").subject()).isEqualTo("FilingLens sign-up attempt");
	}

	@Test
	void passwordResetSignsOutEverywhere() throws Exception {
		Browser browser = new Browser();
		browser.post("/api/v1/auth/register",
				"{\"email\":\"r@example.com\",\"password\":\"a good long passphrase\",\"displayName\":\"R\"}");
		browser.post("/api/v1/auth/verify-email", "{\"token\":\"" + tokenFrom(mail.next("r@example.com")) + "\"}");
		browser.post("/api/v1/auth/login", "{\"email\":\"r@example.com\",\"password\":\"a good long passphrase\"}");

		Browser anonymous = new Browser();
		assertThat(anonymous.post("/api/v1/auth/password/forgot", "{\"email\":\"r@example.com\"}").statusCode())
			.isEqualTo(202);
		String resetToken = tokenFrom(mail.next("r@example.com"));
		assertThat(anonymous
			.post("/api/v1/auth/password/reset",
					"{\"token\":\"" + resetToken + "\",\"newPassword\":\"a fresh long passphrase\"}")
			.statusCode()).isEqualTo(204);

		assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
		assertThat(anonymous
			.post("/api/v1/auth/login", "{\"email\":\"r@example.com\",\"password\":\"a fresh long passphrase\"}")
			.statusCode()).isEqualTo(200);
	}

	private static String login(String password) {
		return "{\"email\":\"jane@example.com\",\"password\":\"" + password + "\"}";
	}

	private static String tokenFrom(EmailMessage message) {
		Matcher matcher = TOKEN_LINK.matcher(message.body());
		assertThat(matcher.find()).as("token link in email").isTrue();
		return matcher.group(1);
	}

	/** An HTTP client that keeps cookies and sends the CSRF header like the frontend does. */
	class Browser {

		private final CookieManager cookies = new CookieManager();

		private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

		HttpResponse<String> get(String path) throws IOException, InterruptedException {
			return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
		}

		HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
			return send("POST", path, json);
		}

		HttpResponse<String> put(String path, String json) throws IOException, InterruptedException {
			return send("PUT", path, json);
		}

		private HttpResponse<String> send(String method, String path, String json)
				throws IOException, InterruptedException {
			String token = csrfToken();
			if (token == null) {
				get("/api/v1/auth/csrf");
				token = csrfToken();
			}
			HttpRequest request = request(path).header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", token)
				.method(method, HttpRequest.BodyPublishers.ofString(json))
				.build();
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}

		private HttpRequest.Builder request(String path) {
			return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		}

		private String csrfToken() {
			return cookies.getCookieStore()
				.getCookies()
				.stream()
				.filter(c -> c.getName().equals("XSRF-TOKEN"))
				.map(HttpCookie::getValue)
				.findFirst()
				.orElse(null);
		}

	}

	static class CapturingEmailSender implements EmailSender {

		final BlockingQueue<EmailMessage> messages = new LinkedBlockingQueue<>();

		@Override
		public void send(EmailMessage message) {
			messages.add(message);
		}

		EmailMessage next(String to) throws InterruptedException {
			EmailMessage message = messages.poll(10, TimeUnit.SECONDS);
			assertThat(message).as("email to " + to).isNotNull();
			assertThat(message.to()).isEqualTo(to);
			return message;
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CapturingMail {

		@Bean
		@Primary
		CapturingEmailSender capturingEmailSender() {
			return new CapturingEmailSender();
		}

	}

}
