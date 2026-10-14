package com.filinglens.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.filinglens.identity.UserPrincipal;

@Entity
@Table(name = "app_user")
class User {

	enum Role {

		USER, ADMIN

	}

	enum Status {

		PENDING_VERIFICATION, ACTIVE, DISABLED

	}

	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 320)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Status status;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "password_changed_at", nullable = false)
	private Instant passwordChangedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	protected User() {
	}

	static User register(String normalizedEmail, String passwordHash, String displayName, Instant now) {
		User user = new User();
		user.id = UUID.randomUUID();
		user.email = normalizedEmail;
		user.passwordHash = passwordHash;
		user.displayName = displayName;
		user.role = Role.USER;
		user.status = Status.PENDING_VERIFICATION;
		user.passwordChangedAt = now;
		user.createdAt = now;
		user.updatedAt = now;
		return user;
	}

	boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	/** Records a failed password attempt, locking the account once the threshold is reached. */
	void recordFailedLogin(int maxFailedLogins, Duration lockoutDuration, Instant now) {
		failedLoginCount++;
		if (failedLoginCount >= maxFailedLogins) {
			lockedUntil = now.plus(lockoutDuration);
			failedLoginCount = 0;
		}
		updatedAt = now;
	}

	void recordSuccessfulLogin(Instant now) {
		failedLoginCount = 0;
		lockedUntil = null;
		lastLoginAt = now;
		updatedAt = now;
	}

	void markEmailVerified(Instant now) {
		if (emailVerifiedAt == null) {
			emailVerifiedAt = now;
		}
		if (status == Status.PENDING_VERIFICATION) {
			status = Status.ACTIVE;
		}
		updatedAt = now;
	}

	void changePassword(String newPasswordHash, Instant now) {
		passwordHash = newPasswordHash;
		passwordChangedAt = now;
		failedLoginCount = 0;
		lockedUntil = null;
		updatedAt = now;
	}

	/** Transparent re-hash when the password encoder's preferred algorithm changes. */
	void upgradePasswordHash(String newPasswordHash, Instant now) {
		passwordHash = newPasswordHash;
		updatedAt = now;
	}

	void rename(String newDisplayName, Instant now) {
		displayName = newDisplayName;
		updatedAt = now;
	}

	UserPrincipal toPrincipal() {
		return new UserPrincipal(id, email, role.name());
	}

	UUID id() {
		return id;
	}

	String email() {
		return email;
	}

	String passwordHash() {
		return passwordHash;
	}

	String displayName() {
		return displayName;
	}

	Role role() {
		return role;
	}

	Status status() {
		return status;
	}

	boolean emailVerified() {
		return emailVerifiedAt != null;
	}

	Instant createdAt() {
		return createdAt;
	}

	int failedLoginCount() {
		return failedLoginCount;
	}

}
