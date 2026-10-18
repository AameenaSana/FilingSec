package com.filinglens.comparison.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.filinglens.ai.AiModel;
import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiModel.Cite;
import com.filinglens.ai.AiModel.Passage;
import com.filinglens.ai.AiModel.Segment;
import com.filinglens.ai.AiNotConfiguredException;
import com.filinglens.ai.AiUsage;
import com.filinglens.comparison.internal.ComparisonViews.BlockSide;
import com.filinglens.comparison.internal.ComparisonViews.ChangeView;
import com.filinglens.comparison.internal.ComparisonViews.CompanyInfo;
import com.filinglens.comparison.internal.ComparisonViews.ComparisonView;
import com.filinglens.comparison.internal.ComparisonViews.FilingInfo;
import com.filinglens.comparison.internal.ComparisonViews.Status;
import com.filinglens.comparison.internal.InterpretationService.InterpretationRepository;
import com.filinglens.comparison.internal.InterpretationService.InterpretationView;
import com.filinglens.comparison.internal.InterpretationService.ParsedInterpretation;
import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobQueue;

import tools.jackson.databind.json.JsonMapper;

class InterpretationServiceTest {

	static final ChangeView MODIFIED = new ChangeView(1, "MODIFIED", false, 0.8,
			new BlockSide(40, "10K.ITEM_1A/p3", "We use AI features."),
			new BlockSide(42, "10K.ITEM_1A/p3", "We rely heavily on generative AI features."), List.of());

	static final ChangeView ADDED = new ChangeView(2, "ADDED", false, null, null,
			new BlockSide(43, "10K.ITEM_1A/p4", "New tariffs could increase costs."), null);

	static final ChangeView REMOVED = new ChangeView(3, "REMOVED", false, null,
			new BlockSide(41, "10K.ITEM_1A/p4", "The COVID-19 pandemic affected operations."), null, null);

	@Test
	void eachChangeBecomesALabelledPassage() {
		List<Passage> passages = InterpretationService.passages(List.of(MODIFIED, ADDED, REMOVED));

		assertThat(passages).extracting(Passage::title)
			.containsExactly("Change 1: modified (first block: earlier text; second block: later text)",
					"Change 2: added (text in the later filing only)", "Change 3: removed (text in the earlier filing only)");
		assertThat(passages.getFirst().blocks()).containsExactly("We use AI features.",
				"We rely heavily on generative AI features.");
		assertThat(passages.get(2).blocks()).containsExactly("The COVID-19 pandemic affected operations.");
	}

	@Test
	void passagesAreBoundedBySize() {
		ChangeView big = new ChangeView(9, "ADDED", false, null, null,
				new BlockSide(1, "r", "x".repeat(InterpretationService.MAX_CHARS)), null);

		assertThat(InterpretationService.passages(List.of(big, ADDED))).hasSize(1);
	}

	@Test
	void parsesSignificanceAndMapsCitationsToChanges() {
		String first = "Significance: HIGH\n\n";
		String claim = "Reliance on generative AI increased";
		AiResponse response = new AiResponse(List.of(new Segment(first + "The company now says ", List.of()),
				new Segment(claim, List.of(new Cite(0, 1, 2, "We rely heavily on generative AI features."))),
				new Segment(" and drops pandemic language.", List.of(new Cite(2, 0, 1, "Invented text")))), false,
				"end_turn", "claude-opus-5-5", 100, 50, 0);

		ParsedInterpretation parsed = InterpretationService.parse(response, List.of(MODIFIED, ADDED, REMOVED));

		assertThat(parsed.significance()).isEqualTo("HIGH");
		assertThat(parsed.summary()).startsWith("The company now says");
		assertThat(parsed.citations()).hasSize(2);
		var citation = parsed.citations().getFirst();
		assertThat(citation.changeOrdinal()).isEqualTo(1);
		assertThat(citation.marker()).isEqualTo(1);
		assertThat(parsed.summary().substring(citation.answerStart(), citation.answerEnd())).isEqualTo(claim);
		assertThat(citation.verified()).isTrue();
		assertThat(parsed.citations().get(1).changeOrdinal()).isEqualTo(3);
		assertThat(parsed.citations().get(1).verified()).isFalse();
	}

	@Test
	void missingSignificanceLineIsTolerated() {
		AiResponse response = new AiResponse(List.of(new Segment("Minor wording edits.", List.of())), false,
				"end_turn", "m", 1, 1, 0);

		ParsedInterpretation parsed = InterpretationService.parse(response, List.of(MODIFIED));

		assertThat(parsed.significance()).isNull();
		assertThat(parsed.summary()).isEqualTo("Minor wording edits.");
	}

	@Test
	void requestQueuesOnceAndRequiresAReadyComparison() {
		ComparisonService comparisons = mock(ComparisonService.class);
		ComparisonRepository repository = mock(ComparisonRepository.class);
		InterpretationRepository interpretations = mock(InterpretationRepository.class);
		AiModel model = mock(AiModel.class);
		JobQueue jobs = mock(JobQueue.class);
		InterpretationService service = new InterpretationService(comparisons, repository, interpretations, model,
				mock(AiUsage.class), jobs);
		UUID user = UUID.randomUUID();
		FilingInfo filing = new FilingInfo("0000320193-25-000079", "10-K", LocalDate.of(2025, 10, 31), null);
		when(comparisons.get(9)).thenReturn(view(Status.COMPUTING, filing));

		assertThatThrownBy(() -> service.request(user, 9, "10K.ITEM_1A")).isInstanceOf(ConflictException.class);

		when(comparisons.get(9)).thenReturn(view(Status.READY, filing));
		when(repository.sectionId(9, "10K.ITEM_1A")).thenReturn(Optional.of(5L));
		InterpretationView pending = new InterpretationView("PENDING", null, null, List.of(), null, null, null, null,
				Instant.now());
		when(interpretations.find(5, InterpretationService.PROMPT_VERSION)).thenReturn(Optional.empty(),
				Optional.of(pending));
		when(model.available()).thenReturn(false);
		assertThatThrownBy(() -> service.request(user, 9, "10K.ITEM_1A")).isInstanceOf(AiNotConfiguredException.class);

		when(model.available()).thenReturn(true);
		when(interpretations.find(5, InterpretationService.PROMPT_VERSION)).thenReturn(Optional.empty(),
				Optional.of(pending));
		when(interpretations.startPending(5, InterpretationService.PROMPT_VERSION, user)).thenReturn(77L);
		assertThat(service.request(user, 9, "10K.ITEM_1A").status()).isEqualTo("PENDING");
		verify(jobs).enqueueUnique(eq(InterpretationService.InterpretationJob.TYPE), anyMap(),
				eq(InterpretationService.InterpretationJob.TYPE + ":77"));

		// An existing interpretation is returned without queuing another.
		when(interpretations.find(5, InterpretationService.PROMPT_VERSION)).thenReturn(Optional.of(pending));
		service.request(user, 9, "10K.ITEM_1A");
		verify(interpretations, never()).startPending(5, InterpretationService.PROMPT_VERSION, null);
		verify(jobs).enqueueUnique(anyString(), anyMap(), anyString());
	}

	@Test
	void permanentFailureDoesNotShowTheProviderError() {
		InterpretationRepository interpretations = mock(InterpretationRepository.class);
		var job = new InterpretationService.InterpretationJob(mock(ComparisonService.class),
				mock(ComparisonRepository.class), interpretations, mock(AiModel.class), mock(AiUsage.class),
				JsonMapper.builder().build());

		job.onPermanentFailure(new Job(1, InterpretationService.InterpretationJob.TYPE, "{\"interpretationId\":77}", 5, 5),
				new IllegalStateException("400: {\"error\":{\"message\":\"Your credit balance is too low\"}}"));

		verify(interpretations).finish(77, "FAILED", null, null, List.of(), null, null, null, 0, 0,
				"The interpretation could not be generated. Please try again later.");
	}

	static ComparisonView view(Status status, FilingInfo filing) {
		return new ComparisonView(9, status, null, new CompanyInfo("0000320193", "Apple Inc."), filing, filing, null,
				null, List.of());
	}

}
