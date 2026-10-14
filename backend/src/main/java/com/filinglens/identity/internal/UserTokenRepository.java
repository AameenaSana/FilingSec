package com.filinglens.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UserTokenRepository extends JpaRepository<UserToken, Long> {

	Optional<UserToken> findByTokenHashAndType(String tokenHash, UserToken.Type type);

	/** Invalidates outstanding tokens so only the most recently issued link works. */
	@Modifying
	@Query("DELETE FROM UserToken t WHERE t.userId = :userId AND t.type = :type AND t.usedAt IS NULL")
	void deleteUnused(UUID userId, UserToken.Type type);

}
