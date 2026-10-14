package com.filinglens.document.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.document.internal.DocumentViews.BlockView;
import com.filinglens.document.internal.DocumentViews.DocumentStructure;

@RestController
@RequestMapping("/api/v1/filings/{accession}")
class DocumentController {

	private static final String ACCESSION = "\\d{10}-?\\d{2}-?\\d{6}";

	private static final String SECTION_KEY = "[A-Z0-9_.]{1,40}";

	private final DocumentService documents;

	DocumentController(DocumentService documents) {
		this.documents = documents;
	}

	/** Parse status and sections. */
	@GetMapping("/structure")
	DocumentStructure structure(@PathVariable @Pattern(regexp = ACCESSION) String accession) {
		return documents.structure(accession);
	}

	/** Retries parsing a document whose parse failed. */
	@PostMapping("/parse")
	@ResponseStatus(HttpStatus.ACCEPTED)
	DocumentStructure reparse(@PathVariable @Pattern(regexp = ACCESSION) String accession) {
		return documents.reparse(accession);
	}

	/** Text blocks in document order, optionally for one section. */
	@GetMapping("/blocks")
	List<BlockView> blocks(@PathVariable @Pattern(regexp = ACCESSION) String accession,
			@RequestParam(required = false) @Pattern(regexp = SECTION_KEY) String section,
			@RequestParam(defaultValue = "1") @Min(1) int from,
			@RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
		return documents.blocks(accession, section, from, limit);
	}

	/** One block by its stable reference, e.g. {@code 10K.ITEM_1A/p12}. */
	@GetMapping("/blocks/by-ref")
	BlockView block(@PathVariable @Pattern(regexp = ACCESSION) String accession,
			@RequestParam @NotBlank @Size(max = 80) @Pattern(regexp = SECTION_KEY + "/p\\d{1,6}") String ref) {
		return documents.block(accession, ref);
	}

	/**
	 * The sanitized source document with block anchors, for the sandboxed viewer
	 * frame. Security headers for this path are set in the security configuration.
	 */
	@GetMapping(value = "/source", produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<byte[]> source(@PathVariable @Pattern(regexp = ACCESSION) String accession) {
		return ResponseEntity.ok()
			.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(documents.viewerHtml(accession));
	}

}
