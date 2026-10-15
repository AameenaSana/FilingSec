package com.filinglens.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, UUID> {

	/** @param email an email already normalized with {@link EmailAddresses#normalize} */
	Optional<User> findByEmail(String email);

	Optional<User> findBySupabaseUserId(UUID supabaseUserId);

}
