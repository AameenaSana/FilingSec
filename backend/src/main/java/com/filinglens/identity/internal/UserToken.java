package com.filinglens.identity.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single-use token sent by email. Only its SHA-256 hash is persisted. */
@Entity
@Table(name = "user_token")
class UserToken {

	enum Type {

		EMAIL_VERIFICATION, PASSWORD_RESET

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Type type;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserToken() {
	}

	UserToken(UUID userId, Type type, String tokenHash, Instant expiresAt, Instant now) {
		this.userId = userId;
		this.type = type;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = now;
	}

	boolean isUsable(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

	void markUsed(Instant now) {
		usedAt = now;
	}

	UUID userId() {
		return userId;
	}

}
