package com.filinglens.filing.internal;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.filinglens.edgar.EdgarClient;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Imports a company's older filings from EDGAR submissions history pages. */
@Component
class FilingHistoryImportJob implements JobHandler {

	static final String TYPE = "filing.history-import";

	private static final Logger log = LoggerFactory.getLogger(FilingHistoryImportJob.class);

	private final EdgarClient edgar;

	private final FilingRepository filings;

	private final JsonMapper json;

	private final Clock clock;

	FilingHistoryImportJob(EdgarClient edgar, FilingRepository filings, JsonMapper json, Clock clock) {
		this.edgar = edgar;
		this.filings = filings;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void handle(Job job) {
		JsonNode payload = json.readTree(job.payload());
		long companyId = payload.path("companyId").asLong();
		var imported = filings.syncState(companyId).importedHistoryFiles();
		for (JsonNode fileNode : payload.path("files")) {
			String file = fileNode.asString();
			if (imported.contains(file)) {
				continue;
			}
			int added = filings.insertNew(companyId,
					FilingDiscoveryService.tracked(edgar.fetchSubmissionsHistory(file)), clock.instant());
			filings.markHistoryImported(companyId, file);
			log.info("Imported {} older filings from {}", added, file);
		}
	}

}
