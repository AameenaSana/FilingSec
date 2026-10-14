package com.filinglens.edgar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CikTest {

	@Test
	void normalizesToTenDigits() {
		assertThat(Cik.normalize("320193")).isEqualTo("0000320193");
		assertThat(Cik.normalize(" 0000320193 ")).isEqualTo("0000320193");
		assertThat(Cik.normalize(789019L)).isEqualTo("0000789019");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "0", "0000000000", "abc", "12345678901", "-5", "32 0193" })
	void rejectsInvalidValues(String value) {
		assertThat(Cik.isValid(value)).isFalse();
		assertThatIllegalArgumentException().isThrownBy(() -> Cik.normalize(value));
	}

}
