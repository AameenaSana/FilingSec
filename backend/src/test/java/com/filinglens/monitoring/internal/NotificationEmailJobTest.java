package com.filinglens.monitoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.filinglens.identity.UserDirectory;
import com.filinglens.identity.UserDirectory.UserContact;
import com.filinglens.monitoring.internal.NotificationRepository.EmailContent;
import com.filinglens.platform.config.FilingLensProperties;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.mail.EmailMessage;
import com.filinglens.platform.mail.EmailSender;

import tools.jackson.databind.json.JsonMapper;

class NotificationEmailJobTest {

	static final UUID USER = UUID.randomUUID();

	static final Job JOB = new Job(1, NotificationEmailJob.TYPE, "{\"notificationId\":9}", 1, 5);

	NotificationRepository notifications = mock(NotificationRepository.class);

	UserDirectory users = mock(UserDirectory.class);

	EmailSender mail = mock(EmailSender.class);

	NotificationEmailJob job;

	@BeforeEach
	void setUp() {
		job = new NotificationEmailJob(notifications, users, mail, JsonMapper.builder().build(),
				new FilingLensProperties(new FilingLensProperties.App(URI.create("https://lens.example/")), null, null,
						null, null));
		when(notifications.emailContent(9)).thenReturn(Optional.of(new EmailContent(USER, "Apple Inc. filed a 10-K",
				"Apple Inc. filed a 10-K on Oct 31, 2025.", "/filings/0000320193-25-000079", "Apple Inc.")));
		when(notifications.emailEnabled(USER)).thenReturn(true);
	}

	@Test
	void sendsTheNotificationWithLinks() {
		when(users.contact(USER)).thenReturn(Optional.of(new UserContact(USER, "a@example.com", "Ada", true)));

		job.handle(JOB);

		var message = ArgumentCaptor.forClass(EmailMessage.class);
		verify(mail).send(message.capture());
		assertThat(message.getValue().to()).isEqualTo("a@example.com");
		assertThat(message.getValue().subject()).isEqualTo("Apple Inc. filed a 10-K");
		assertThat(message.getValue().body()).startsWith("Hi Ada,")
			.contains("https://lens.example/filings/0000320193-25-000079")
			.contains("https://lens.example/settings");
	}

	@Test
	void skipsUnverifiedAccountsAndUsersWhoTurnedEmailOff() {
		when(users.contact(USER)).thenReturn(Optional.of(new UserContact(USER, "a@example.com", "Ada", false)));
		job.handle(JOB);

		when(users.contact(USER)).thenReturn(Optional.of(new UserContact(USER, "a@example.com", "Ada", true)));
		when(notifications.emailEnabled(USER)).thenReturn(false);
		job.handle(JOB);

		verify(mail, never()).send(any());
	}

}
