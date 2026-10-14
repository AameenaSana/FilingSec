package com.filinglens.monitoring.internal;

import org.springframework.stereotype.Component;

import com.filinglens.identity.UserDirectory;
import com.filinglens.identity.UserDirectory.UserContact;
import com.filinglens.monitoring.internal.NotificationRepository.EmailContent;
import com.filinglens.platform.config.FilingLensProperties;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;
import com.filinglens.platform.mail.EmailMessage;
import com.filinglens.platform.mail.EmailSender;

import tools.jackson.databind.json.JsonMapper;

/**
 * Emails a notification, unless the user has since turned email off or cannot be
 * reached (unverified or disabled account). Retried with backoff by the job queue.
 */
@Component
class NotificationEmailJob implements JobHandler {

	static final String TYPE = "monitoring.email";

	private final NotificationRepository notifications;

	private final UserDirectory users;

	private final EmailSender mail;

	private final JsonMapper json;

	private final String publicUrl;

	NotificationEmailJob(NotificationRepository notifications, UserDirectory users, EmailSender mail, JsonMapper json,
			FilingLensProperties properties) {
		this.notifications = notifications;
		this.users = users;
		this.mail = mail;
		this.json = json;
		this.publicUrl = properties.app().publicUrl().toString().replaceAll("/+$", "");
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void handle(Job job) {
		long id = json.readTree(job.payload()).path("notificationId").asLong();
		EmailContent content = notifications.emailContent(id).orElse(null);
		if (content == null || !notifications.emailEnabled(content.userId())) {
			return;
		}
		UserContact contact = users.contact(content.userId()).orElse(null);
		if (contact == null || !contact.reachable()) {
			return;
		}
		mail.send(new EmailMessage(contact.email(), content.title(), body(contact, content)));
	}

	String body(UserContact contact, EmailContent content) {
		return """
				Hi %s,

				%s

				Open in FilingLens: %s%s

				You receive this because %s is on your watchlist. To stop these emails, turn off
				email notifications in your settings (%s/settings) or notifications for this
				company on your watchlist (%s/watchlist).
				""".formatted(contact.displayName(), content.body(), publicUrl, content.link(), content.companyName(),
				publicUrl, publicUrl);
	}

}
