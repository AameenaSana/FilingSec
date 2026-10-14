package com.filinglens.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.filinglens.platform.config.FilingLensProperties;

/**
 * Sends through SMTP when Spring Boot has configured a {@link JavaMailSender}
 * (i.e. {@code spring.mail.host} is set); otherwise falls back to logging.
 */
@Configuration(proxyBeanMethods = false)
class MailConfiguration {

	private static final Logger log = LoggerFactory.getLogger(MailConfiguration.class);

	@Bean
	EmailSender emailSender(ObjectProvider<JavaMailSender> javaMailSender, FilingLensProperties properties) {
		JavaMailSender smtp = javaMailSender.getIfAvailable();
		FilingLensProperties.Mail mail = properties.mail();
		if (smtp != null) {
			return message -> {
				SimpleMailMessage mailMessage = new SimpleMailMessage();
				mailMessage.setFrom(mail.from());
				mailMessage.setTo(message.to());
				mailMessage.setSubject(message.subject());
				mailMessage.setText(message.body());
				smtp.send(mailMessage);
			};
		}
		log.warn("No SMTP server configured (spring.mail.host); emails will be logged, not sent");
		return message -> {
			if (mail.logContent()) {
				log.info("Email (not sent) to={} subject=\"{}\"\n{}", message.to(), message.subject(), message.body());
			}
			else {
				log.warn("Email (not sent) to={} subject=\"{}\"", message.to(), message.subject());
			}
		};
	}

}
