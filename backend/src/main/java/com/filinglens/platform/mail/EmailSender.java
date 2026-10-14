package com.filinglens.platform.mail;

/** Port for sending email. Implementations may throw on delivery failure. */
public interface EmailSender {

	void send(EmailMessage message);

}
