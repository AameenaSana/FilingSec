package com.filinglens.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.error.NotFoundException;

class WatchlistServiceTest {

	static final UUID USER = UUID.randomUUID();

	final WatchlistRepository repository = mock(WatchlistRepository.class);

	final CompanyService companies = mock(CompanyService.class);

	final WatchlistService service = new WatchlistService(repository, companies,
			Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC));

	@Test
	void formTypesAreDeduplicatedAndOrdered() {
		assertThat(WatchlistService.canonical(List.of("8-K", "10-K", "8-K"))).containsExactly("10-K", "8-K");
		assertThat(WatchlistService.canonical(null)).containsExactly("10-K", "10-Q", "8-K");
		assertThat(WatchlistService.canonical(Set.of())).containsExactly("10-K", "10-Q", "8-K");
	}

	@Test
	void addingTwiceIsAConflict() {
		when(companies.resolveId("320193")).thenReturn(7L);
		when(repository.insert(eq(USER), eq(7L), anyList(), anyBoolean(), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.add(USER, "320193", List.of(), true)).isInstanceOf(ConflictException.class)
			.extracting("code")
			.isEqualTo("already-watching");
	}

	@Test
	void fullWatchlistIsRejectedBeforeAnySecLookup() {
		when(repository.count(USER)).thenReturn(WatchlistService.MAX_ITEMS);

		assertThatThrownBy(() -> service.add(USER, "320193", List.of(), true)).extracting("code")
			.isEqualTo("watchlist-full");
		verifyNoInteractions(companies);
	}

	@Test
	void updatingOrRemovingAnotherUsersItemIsNotFound() {
		when(repository.update(eq(USER), anyLong(), anyList(), anyBoolean(), any())).thenReturn(false);
		when(repository.delete(USER, 99)).thenReturn(false);

		assertThatThrownBy(() -> service.update(USER, 99, List.of("10-K"), true)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.remove(USER, 99)).isInstanceOf(NotFoundException.class);
		verify(repository).delete(USER, 99);
	}

}
