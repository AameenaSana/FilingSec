package com.filinglens.workspace.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.workspace.internal.WorkspaceRepository.FindingSource;

@IntegrationTest
class WorkspaceIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	WorkspaceRepository workspace;

	@Autowired
	JdbcClient jdbc;

	long filingId;

	UUID alice;

	UUID bob;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM app_user").update();
		long companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
		filingId = jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, report_date, created_at, updated_at)
				VALUES (:c, '0000320193-25-000079', '10-K', DATE '2025-10-31', DATE '2025-09-27', now(), now()) RETURNING id
				""").param("c", companyId).query(Long.class).single();
		alice = user("alice@example.com");
		bob = user("bob@example.com");
	}

	@Test
	void findingsAreSearchableTaggedAndPrivate() {
		long session = workspace.createSession(alice, "AI risk", null, NOW);
		long filed = workspace.insertFinding(alice, session, source("Generative AI may not succeed."), "Watch 100%",
				List.of("ai", "risk"), NOW);
		workspace.insertFinding(alice, null, source("Supply chain depends on partners."), null, List.of(), NOW);

		assertThat(workspace.sessions(alice)).singleElement()
			.satisfies(s -> assertThat(s.findingCount()).isEqualTo(1));
		assertThat(workspace.sessions(bob)).isEmpty();
		assertThat(workspace.session(bob, session)).isEmpty();
		assertThat(workspace.finding(bob, filed)).isEmpty();
		assertThat(workspace.deleteFinding(bob, filed)).isFalse();

		var found = workspace.finding(alice, filed).orElseThrow();
		assertThat(found.filing().cik()).isEqualTo("0000320193");
		assertThat(found.blockRefs()).containsExactly("10K.ITEM_1A/p4");
		assertThat(found.tags()).containsExactly("ai", "risk");

		assertThat(workspace.findings(alice, null, false, null, null)).hasSize(2);
		assertThat(workspace.findings(alice, session, false, null, null)).extracting(f -> f.id()).containsExactly(filed);
		assertThat(workspace.findings(alice, null, true, null, null)).hasSize(1);
		assertThat(workspace.findings(alice, null, false, "generative", null)).hasSize(1);
		assertThat(workspace.findings(alice, null, false, "100%", null)).hasSize(1);
		assertThat(workspace.findings(alice, null, false, "_", null)).isEmpty();
		assertThat(workspace.findings(alice, null, false, null, "risk")).hasSize(1);
		assertThat(workspace.findings(bob, null, false, null, null)).isEmpty();
		assertThat(workspace.countFindings(alice)).isEqualTo(2);
	}

	@Test
	void deletingASessionKeepsItsFindingsUnfiled() {
		long session = workspace.createSession(alice, "AI risk", "notes", NOW);
		long filed = workspace.insertFinding(alice, session, source("Quote."), null, List.of(), NOW);

		assertThat(workspace.updateSession(bob, session, "Hijack", null, NOW)).isFalse();
		assertThat(workspace.updateFinding(alice, filed, null, "moved out", List.of("x"), NOW)).isTrue();
		assertThat(workspace.finding(alice, filed).orElseThrow().sessionId()).isNull();
		assertThat(workspace.updateFinding(alice, filed, session, null, List.of(), NOW)).isTrue();

		assertThat(workspace.deleteSession(alice, session)).isTrue();
		var kept = workspace.finding(alice, filed).orElseThrow();
		assertThat(kept.sessionId()).isNull();
		assertThat(kept.quotedText()).isEqualTo("Quote.");
	}

	private FindingSource source(String quote) {
		return new FindingSource("PASSAGE", filingId, "0000320193-25-000079", "Apple Inc.", "10-K",
				LocalDate.of(2025, 9, 27), "10K.ITEM_1A", "Item 1A. Risk Factors", List.of("10K.ITEM_1A/p4"), 4, quote,
				null, null, null, null, null, null, null);
	}

	private UUID user(String email) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, password_hash, display_name, status, password_changed_at, created_at, updated_at)
				VALUES (:id, :email, 'x', 'Test', 'ACTIVE', now(), now(), now())
				""").param("id", id).param("email", email).update();
		return id;
	}

}
