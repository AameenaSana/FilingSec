package com.filinglens.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.identity.UserDirectory;

@Component
class UserDirectoryAdapter implements UserDirectory {

	private final JdbcClient jdbc;

	UserDirectoryAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<UserContact> contact(UUID userId) {
		return jdbc.sql("""
				SELECT id, email, display_name, status = 'ACTIVE' AND email_verified_at IS NOT NULL AS reachable
				FROM app_user WHERE id = :id
				""")
			.param("id", userId)
			.query((rs, n) -> new UserContact(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getString("display_name"), rs.getBoolean("reachable")))
			.optional();
	}

}
