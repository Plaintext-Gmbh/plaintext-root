/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.rest;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Header auth for {@code /nosec/api/claude/**}: only {@code Authorization: Bearer} is passed on to
 * the controller as the {@code token} parameter; a {@code token} parameter is rejected (card 1460, N-2).
 */
@DisplayName("ClaudeTokenRequestFilter Header-Auth")
class ClaudeTokenRequestFilterTest {

    private ClaudeTokenRequestFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ClaudeTokenRequestFilter();
        request = new MockHttpServletRequest("GET", "/nosec/api/claude/has-work");
        response = new MockHttpServletResponse();
    }

    /** Runs the filter and returns the request that would arrive at the controller. */
    private ServletRequest filteredRequest() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> seen.set(req));
        return seen.get();
    }

    @Test
    void bearerHeaderIsExposedAsTokenParameter() throws Exception {
        request.addHeader("Authorization", "Bearer secret-token-123");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream.getParameter("token")).isEqualTo("secret-token-123");
        assertThat(downstream.getParameterMap()).containsKey("token");
        assertThat(downstream.getParameterValues("token")).containsExactly("secret-token-123");
        assertThat(Collections.list(downstream.getParameterNames())).contains("token");
    }

    @Test
    void xClaudeTokenHeaderWirdIgnoriert() throws Exception {
        // Card 1460 (N-2): only the Authorization header counts.
        request.addHeader("X-Claude-Token", "secret-token-456");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream).isSameAs(request);
        assertThat(downstream.getParameter("token")).isNull();
    }

    @Test
    void urlTokenNebenBearerHeaderWirdAbgelehnt() throws Exception {
        // Card 1460 (N-2): the URL token leaks into the logs even if a header is present as well.
        request.setParameter("token", "legacy-url-token");
        request.addHeader("Authorization", "Bearer header-token");
        jakarta.servlet.FilterChain chainOhneAufruf = (req, res) -> {
            throw new AssertionError("Kette darf bei URL-Token nicht laufen");
        };

        filter.doFilter(request, response, chainOhneAufruf);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void urlTokenWirdAbgelehnt() throws Exception {
        request.setParameter("token", "legacy-url-token");
        jakarta.servlet.FilterChain chainOhneAufruf = (req, res) -> {
            throw new AssertionError("Kette darf bei abgelehntem URL-Token nicht laufen");
        };

        filter.doFilter(request, response, chainOhneAufruf);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void otherParametersPassThroughWrapperUnchanged() throws Exception {
        request.setParameter("summary", "Arbeit erledigt");
        request.addHeader("Authorization", "Bearer header-token");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream.getParameter("summary")).isEqualTo("Arbeit erledigt");
        assertThat(downstream.getParameterMap()).containsKeys("summary", "token");
    }

    @Test
    void noTokenAtAllPassesThroughUntouched() throws Exception {
        ServletRequest downstream = filteredRequest();

        assertThat(downstream).isSameAs(request);
        assertThat(downstream.getParameter("token")).isNull();
    }

    @Test
    void emptyBearerHeaderIsIgnored() throws Exception {
        request.addHeader("Authorization", "Bearer ");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream).isSameAs(request);
        assertThat(downstream.getParameter("token")).isNull();
    }

    @Test
    void nonBearerAuthorizationHeaderIsIgnored() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwdw==");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream).isSameAs(request);
        assertThat(downstream.getParameter("token")).isNull();
    }
}
