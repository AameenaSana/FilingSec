package com.filinglens.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated user, stored in the server-side session. Contains no
 * credentials. Its {@link #getName() name} is the user id, which Spring Session
 * indexes so all of a user's sessions can be found and revoked.
 * <p>
 * Other modules obtain it with {@code @AuthenticationPrincipal UserPrincipal user}
 * and must scope every query on user-owned data by {@link #id()}.
 */
public record UserPrincipal(UUID id, String email, String role) implements AuthenticatedPrincipal, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	public String getName() {
		return id.toString();
	}

	public Collection<? extends GrantedAuthority> authorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role));
	}

}
