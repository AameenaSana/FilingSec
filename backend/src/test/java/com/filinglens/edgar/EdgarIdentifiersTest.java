package com.filinglens.edgar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EdgarIdentifiersTest {

	@Test
	void accessionNumbersNormalizeToDashedForm() {
		assertThat(Accession.normalize("000032019325000079")).isEqualTo("0000320193-25-000079");
		assertThat(Accession.normalize("0000320193-25-000079")).isEqualTo("0000320193-25-000079");
		assertThat(Accession.withoutDashes("0000320193-25-000079")).isEqualTo("000032019325000079");
		assertThatIllegalArgumentException().isThrownBy(() -> Accession.normalize("0000320193-25-79"));
	}

	@Test
	void buildsCanonicalSecUrls() {
		assertThat(EdgarUrls.filingIndexUrl("0000320193", "0000320193-25-000079")).isEqualTo(
				"https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/0000320193-25-000079-index.htm");
		assertThat(EdgarUrls.documentUrl("320193", "000032019325000079", "aapl-20250927.htm"))
			.isEqualTo("https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm");
	}

	@ParameterizedTest
	@ValueSource(strings = { "../x.htm", "a/b.htm", "a\b.htm", ".hidden", "", "x..htm", "name with space.htm" })
	void rejectsUnsafeArchiveFileNames(String filename) {
		assertThat(EdgarUrls.isValidFilename(filename)).isFalse();
		assertThatIllegalArgumentException()
			.isThrownBy(() -> EdgarUrls.documentUrl("320193", "0000320193-25-000079", filename));
	}

}
