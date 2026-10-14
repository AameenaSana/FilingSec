package com.filinglens.document.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.filinglens.document.DocumentParsed;
import com.filinglens.document.internal.SectionDetector.DetectedSection;
import com.filinglens.edgar.Accession;
import com.filinglens.edgar.EdgarUrls;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;
import com.filinglens.platform.storage.BlobStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * Parses a fetched filing's primary document into sections and text blocks and
 * stores the annotated viewer copy. Re-running replaces earlier results.
 */
@Component
class DocumentParseJob implements JobHandler {

	static final String TYPE = "document.parse";

	/** Bump when parsing changes so stored documents are re-parsed on next view. */
	static final int PARSER_VERSION = 1;

	private static final Logger log = LoggerFactory.getLogger(DocumentParseJob.class);

	private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)");

	private final FilingCatalog filings;

	private final DocumentRepository documents;

	private final BlobStore blobs;

	private final ApplicationEventPublisher events;

	private final JsonMapper json;

	private final Clock clock;

	DocumentParseJob(FilingCatalog filings, DocumentRepository documents, BlobStore blobs,
			ApplicationEventPublisher events, JsonMapper json, Clock clock) {
		this.filings = filings;
		this.documents = documents;
		this.blobs = blobs;
		this.events = events;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void handle(Job job) {
		long filingId = filingId(job);
		FilingRef filing = filings.findById(filingId).orElse(null);
		if (filing == null) {
			return;
		}
		if (!filing.isFetched()) {
			documents.markFinished(filingId, "FAILED", "The filing's document has not been downloaded", clock.instant());
			return;
		}
		String filename = filing.primaryDocumentBlobKey().substring(filing.primaryDocumentBlobKey().lastIndexOf('/') + 1);
		String lower = filename.toLowerCase(Locale.ROOT);
		byte[] content = read(filing.primaryDocumentBlobKey());

		ParseResult result;
		if (lower.endsWith(".htm") || lower.endsWith(".html")) {
			String baseUri = EdgarUrls.WWW + EdgarUrls.archivePath(filing.cik(), filing.accessionNumber()) + "/";
			result = HtmlDocumentParser.parse(decode(content, true), baseUri);
		}
		else if (lower.endsWith(".txt")) {
			result = TextDocumentParser.parse(decode(content, false));
		}
		else {
			documents.markFinished(filingId, "UNSUPPORTED",
					"Documents of type " + lower.substring(Math.max(0, lower.lastIndexOf('.'))) + " cannot be read yet",
					clock.instant());
			return;
		}
		if (result.blocks().isEmpty()) {
			documents.markFinished(filingId, "FAILED", "No text was found in the document", clock.instant());
			return;
		}

		List<DetectedSection> sections = SectionDetector.detect(filing.formType(), result.blocks());
		String annotatedKey = "processed/" + filing.cik() + "/" + Accession.withoutDashes(filing.accessionNumber())
				+ "/v" + PARSER_VERSION + "/annotated.html";
		blobs.put(annotatedKey, new ByteArrayInputStream(result.annotatedHtml().getBytes(StandardCharsets.UTF_8)));
		documents.saveParsed(filingId, PARSER_VERSION, sections, result.blocks(), annotatedKey, clock.instant());
		events.publishEvent(new DocumentParsed(filingId, filing.accessionNumber(), PARSER_VERSION));
		log.info("Parsed filing {}: {} sections, {} blocks", filing.accessionNumber(), sections.size(),
				result.blocks().size());
	}

	@Override
	public void onPermanentFailure(Job job, Throwable cause) {
		documents.markFinished(filingId(job), "FAILED", "The document could not be processed: " + cause.getMessage(),
				clock.instant());
	}

	private long filingId(Job job) {
		return json.readTree(job.payload()).path("filingId").asLong();
	}

	private byte[] read(String key) {
		try (InputStream in = blobs.open(key).orElseThrow(() -> new IllegalStateException("Stored document missing: " + key))) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Decodes using the declared charset if any, otherwise UTF-8, falling back to
	 * Windows-1252 (common in older EDGAR filings) when the bytes are not valid UTF-8.
	 */
	static String decode(byte[] content, boolean html) {
		if (html) {
			String head = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.ISO_8859_1);
			Matcher declared = META_CHARSET.matcher(head);
			if (declared.find() && Charset.isSupported(declared.group(1))) {
				return new String(content, Charset.forName(declared.group(1)));
			}
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(content))
				.toString();
		}
		catch (CharacterCodingException ex) {
			return new String(content, Charset.forName("windows-1252"));
		}
	}

}
