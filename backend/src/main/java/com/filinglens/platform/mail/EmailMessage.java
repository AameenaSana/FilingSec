package com.filinglens.platform.mail;

/** A plain-text email. */
public record EmailMessage(String to, String subject, String body) {
}
