package com.filinglens.document.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.document.internal.DocumentRepository.ParseState;
import com.filinglens.document.internal.DocumentViews.BlockView;
import com.filinglens.document.internal.DocumentViews.DocumentStructure;
import com.filinglens.document.internal.DocumentViews.ParseStatus;
import com.filinglens.document.internal.DocumentViews.SectionView;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingFetched;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.jobs.JobQueue;
import com.filinglens.platform.storage.BlobStore;

@Service
class DocumentService {

	private final FilingCatalog filings;

	private final DocumentRepository documents;

	private final JobQueue jobs;

	private final BlobStore blobs;

	private final Clock clock;

	DocumentService(FilingCatalog filings, DocumentRepository documents, JobQueue jobs,
			BlobStore blobs, Clock clock) {
		this.filings = filings;
		this.documents = documents;
		this.jobs = jobs;
		this.blobs = blobs;
		this.clock = clock;
	}

	/** Parses every newly fetched filing. Runs after the fetch has committed. */
	@ApplicationModuleListener
	void on(FilingFetched event) {
		requestParse(event.filingId());
	}

	@Transactional
	void requestParse(long filingId) {
		if (documents.startParse(filingId, DocumentParseJob.PARSER_VERSION, clock.instant())) {
			jobs.enqueueUnique(DocumentParseJob.TYPE, Map.of("filingId", filingId), DocumentParseJob.TYPE + ":" + filingId);
		}
	}

	/**
	 * The document's structure. A fetched filing that was never parsed, or was
	 * parsed by an older parser version, is queued for parsing.
	 */
	DocumentStructure structure(String accession) {
		FilingRef filing = filing(accession);
		Optional<ParseState> state = documents.state(filing.id());
		if (!filing.isFetched()) {
			return new DocumentStructure(ParseStatus.NOT_AVAILABLE, null, null, null, null, List.of());
		}
		if (state.isEmpty() || state.get().parserVersion() < DocumentParseJob.PARSER_VERSION) {
			requestParse(filing.id());
			state = documents.state(filing.id());
		}
		ParseState s = state.orElseThrow();
		ParseStatus status = ParseStatus.valueOf(s.status());
		return new DocumentStructure(status, s.detail(), s.parserVersion(), s.parsedAt(), s.blockCount(),
				status == ParseStatus.PARSED ? documents.sections(filing.id()) : List.of());
	}

	/** Re-queues parsing, e.g. after a failure. */
	DocumentStructure reparse(String accession) {
		requestParse(filing(accession).id());
		return structure(accession);
	}

	/** @throws NotFoundException if the document is parsed but has no section {@code sectionKey} */
	List<BlockView> blocks(String accession, String sectionKey, int fromOrdinal, int limit) {
		long filingId = filing(accession).id();
		if (sectionKey != null) {
			List<SectionView> sections = documents.sections(filingId);
			if (!sections.isEmpty() && sections.stream().noneMatch(s -> s.key().equals(sectionKey))) {
				throw new NotFoundException("Section", sectionKey);
			}
		}
		return documents.blocks(filingId, sectionKey, fromOrdinal, limit);
	}

	BlockView block(String accession, String ref) {
		return documents.blockByRef(filing(accession).id(), ref).orElseThrow(() -> new NotFoundException("Block", ref));
	}

	/** The sanitized, annotated viewer copy. */
	byte[] viewerHtml(String accession) {
		FilingRef filing = filing(accession);
		String key = documents.state(filing.id())
			.filter(s -> "PARSED".equals(s.status()) && s.annotatedBlobKey() != null)
			.map(ParseState::annotatedBlobKey)
			.orElseThrow(() -> new NotFoundException("Document view", accession));
		try (InputStream in = blobs.open(key).orElseThrow(() -> new NotFoundException("Document view", accession))) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private FilingRef filing(String accession) {
		return filings.findByAccession(accession).orElseThrow(() -> new NotFoundException("Filing", accession));
	}

}
