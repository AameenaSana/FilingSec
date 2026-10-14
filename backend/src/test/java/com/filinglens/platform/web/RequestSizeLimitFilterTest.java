package com.filinglens.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeLimitFilterTest {

	final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

	@Test
	void rejectsDeclaredOversizedBodies() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/findings");
		request.setContent(new byte[(int) RequestSizeLimitFilter.MAX_BYTES + 1]);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentAsString()).contains("payload-too-large");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void passesSmallBodiesAndNonApiPaths() throws Exception {
		MockHttpServletRequest small = new MockHttpServletRequest("POST", "/api/v1/findings");
		small.setContent("{}".getBytes());
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(small, new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isSameAs(small);

		MockHttpServletRequest page = new MockHttpServletRequest("POST", "/actuator/health");
		page.setContent(new byte[(int) RequestSizeLimitFilter.MAX_BYTES + 1]);
		MockFilterChain pageChain = new MockFilterChain();
		filter.doFilter(page, new MockHttpServletResponse(), pageChain);
		assertThat(pageChain.getRequest()).isSameAs(page);
	}

	@Test
	void stopsReadingUndeclaredBodiesPastTheLimit() throws Exception {
		MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/v1/findings") {
			@Override
			public long getContentLengthLong() {
				return -1;
			}
		};
		chunked.setContent(new byte[(int) RequestSizeLimitFilter.MAX_BYTES + 10]);
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(chunked, new MockHttpServletResponse(), chain);

		var wrapped = (HttpServletRequest) chain.getRequest();
		assertThatThrownBy(() -> wrapped.getInputStream().readAllBytes()).isInstanceOf(IOException.class)
			.hasMessageContaining("exceeds");
	}

}
