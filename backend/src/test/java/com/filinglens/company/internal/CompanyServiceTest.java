package com.filinglens.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.filinglens.company.internal.CompanyRepository.CompanyRow;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.platform.error.NotFoundException;

class CompanyServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final CompanySubmissions APPLE = new CompanySubmissions("0000320193", "Apple Inc.", "operating", "3571",
			"Electronic Computers", "Large accelerated filer", "0927", "CA", List.of("AAPL"), List.of("Nasdaq"), List.of(),
			List.of());

	final CompanyRepository repository = mock(CompanyRepository.class);

	final EdgarClient edgar = mock(EdgarClient.class);

	final CompanyService service = new CompanyService(repository, edgar,
			new CompanyProperties(Duration.ofHours(24), new CompanyProperties.DirectorySync(false, "0 0 6 * * *")),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void freshProfileIsServedWithoutCallingSec() {
		when(repository.findByCik("0000320193")).thenReturn(Optional.of(new CompanyRow(7, NOW.minusSeconds(60))));

		assertThat(service.resolveId("320193")).isEqualTo(7);
		verifyNoInteractions(edgar);
	}

	@Test
	void staleOrNeverLoadedProfileIsRefreshedFromSec() {
		when(repository.findByCik("0000320193")).thenReturn(Optional.of(new CompanyRow(7, null)));
		when(edgar.fetchSubmissions("0000320193")).thenReturn(Optional.of(APPLE));
		when(repository.upsertProfile(APPLE, NOW)).thenReturn(7L);

		assertThat(service.resolveId("320193")).isEqualTo(7);
		verify(repository).upsertProfile(APPLE, NOW);
	}

	@Test
	void unknownCompanyIsLoadedFromSec() {
		when(repository.findByCik("0000320193")).thenReturn(Optional.empty());
		when(edgar.fetchSubmissions("0000320193")).thenReturn(Optional.of(APPLE));
		when(repository.upsertProfile(APPLE, NOW)).thenReturn(42L);

		assertThat(service.resolveId("0000320193")).isEqualTo(42);
	}

	@Test
	void storedProfileIsServedWhenSecIsUnavailable() {
		when(repository.findByCik("0000320193")).thenReturn(Optional.of(new CompanyRow(7, NOW.minus(Duration.ofDays(3)))));
		when(edgar.fetchSubmissions("0000320193")).thenThrow(new EdgarUnavailableException("down"));

		assertThat(service.resolveId("320193")).isEqualTo(7);
		verify(repository, never()).upsertProfile(any(), any());
	}

	@Test
	void secOutageWithNothingStoredIsReported() {
		when(repository.findByCik("0000320193")).thenReturn(Optional.empty());
		when(edgar.fetchSubmissions("0000320193")).thenThrow(new EdgarUnavailableException("down"));

		assertThatThrownBy(() -> service.resolveId("320193")).isInstanceOf(EdgarUnavailableException.class);
	}

	@Test
	void companyUnknownToSecIsNotFound() {
		when(repository.findByCik("0000000001")).thenReturn(Optional.empty());
		when(edgar.fetchSubmissions("0000000001")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.resolveId("1")).isInstanceOf(NotFoundException.class);
	}

}
