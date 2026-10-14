package com.filinglens.document.internal;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.filinglens.document.DocumentContent;
import com.filinglens.document.SectionText;
import com.filinglens.document.internal.DocumentRepository.ParseState;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;

@Component
class DocumentContentAdapter implements DocumentContent {

	private final DocumentRepository documents;

	private final DocumentService service;

	private final FilingCatalog filings;

	DocumentContentAdapter(DocumentRepository documents, DocumentService service, FilingCatalog filings) {
		this.documents = documents;
		this.service = service;
		this.filings = filings;
	}

	@Override
	public State state(long filingId) {
		Optional<ParseState> parse = documents.state(filingId);
		if (parse.isPresent() && parse.get().parserVersion() >= DocumentParseJob.PARSER_VERSION) {
			return switch (parse.get().status()) {
				case "PARSED" -> State.PARSED;
				case "UNSUPPORTED", "FAILED" -> State.UNAVAILABLE;
				default -> State.PENDING;
			};
		}
		boolean fetchFailed = filings.findById(filingId).map(FilingRef::fetchFailed).orElse(true);
		return fetchFailed ? State.UNAVAILABLE : State.PENDING;
	}

	@Override
	public void prepare(long filingId) {
		FilingRef filing = filings.findById(filingId).orElse(null);
		if (filing == null) {
			return;
		}
		if (!filing.isFetched()) {
			filings.requestDocuments(filingId);
		}
		else {
			service.requestParse(filingId);
		}
	}

	@Override
	public List<SectionText> read(long filingId) {
		return documents.readAll(filingId);
	}

}
