package com.filinglens.identity.internal;

import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.filinglens.platform.config.FilingLensProperties;
import com.filinglens.platform.mail.EmailMessage;
import com.filinglens.platform.mail.EmailSender;

/**
 * Account emails. Messages are sent asynchronously after the surrounding
 * transaction commits, so a rolled-back operation never sends a link and request
 * latency does not reveal whether an email address is registered.
 * <p>
 * Links put the token in the URL fragment, which browsers never send to servers,
 * keeping tokens out of access logs and Referer headers.
 */
@Component
class IdentityMailer {

	private static final Logger log = LoggerFactory.getLogger(IdentityMailer.class);

	private final EmailSender sender;

	private final TaskExecutor executor;

	private final URI publicUrl;

	private final IdentityProperties identity;

	IdentityMailer(EmailSender sender, @Qualifier("applicationTaskExecutor") TaskExecutor executor,
			FilingLensProperties properties, IdentityProperties identity) {
		this.sender = sender;
		this.executor = executor;
		this.publicUrl = properties.app().publicUrl();
		this.identity = identity;
	}

	void sendVerification(String to, String displayName, String token) {
		send(new EmailMessage(to, "Confirm your FilingLens email address", """
				Hi %s,

				Confirm your email address to finish setting up your FilingLens account:

				%s

				This link expires in %s. If you did not create an account, you can ignore this email.
				""".formatted(displayName, link("/verify-email", token), describe(identity.emailVerificationTtl()))));
	}

	void sendAlreadyRegistered(String to) {
		send(new EmailMessage(to, "FilingLens sign-up attempt", """
				Someone tried to create a FilingLens account with this email address, but an account already exists.

				If this was you, sign in at %s/login or reset your password at %s/forgot-password.
				If it was not you, no action is needed.
				""".formatted(publicUrl, publicUrl)));
	}

	void sendPasswordReset(String to, String token) {
		send(new EmailMessage(to, "Reset your FilingLens password", """
				A password reset was requested for your FilingLens account:

				%s

				This link expires in %s. If you did not request a reset, you can ignore this email.
				""".formatted(link("/reset-password", token), describe(identity.passwordResetTtl()))));
	}

	void sendPasswordChanged(String to) {
		send(new EmailMessage(to, "Your FilingLens password was changed", """
				The password for your FilingLens account was just changed and other sessions were signed out.

				If you did not do this, reset your password immediately at %s/forgot-password.
				""".formatted(publicUrl)));
	}

	static String describe(Duration duration) {
		long hours = duration.toHours();
		if (hours >= 1 && duration.equals(Duration.ofHours(hours))) {
			return hours == 1 ? "1 hour" : hours + " hours";
		}
		long minutes = duration.toMinutes();
		return minutes == 1 ? "1 minute" : minutes + " minutes";
	}

	private String link(String path, String token) {
		return publicUrl + path + "#token=" + token;
	}

	private void send(EmailMessage message) {
		Runnable task = () -> executor.execute(() -> {
			try {
				sender.send(message);
			}
			catch (RuntimeException ex) {
				log.error("Failed to send email \"{}\"", message.subject(), ex);
			}
		});
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}

}
