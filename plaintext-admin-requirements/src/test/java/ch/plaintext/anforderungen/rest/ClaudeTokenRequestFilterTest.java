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
 * Header-Auth für {@code /nosec/api/claude/**}: {@code Authorization: Bearer} bzw.
 * {@code X-Claude-Token} werden als {@code token}-Parameter an den Controller
 * durchgereicht; der Legacy-URL-Token bleibt in der Übergangsphase funktionsfähig.
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

    /** Führt den Filter aus und liefert den Request, der beim Controller ankommen würde. */
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
    void xClaudeTokenHeaderIsExposedAsTokenParameter() throws Exception {
        request.addHeader("X-Claude-Token", "secret-token-456");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream.getParameter("token")).isEqualTo("secret-token-456");
    }

    @Test
    void bearerHeaderWinsOverXClaudeTokenHeader() throws Exception {
        request.addHeader("Authorization", "Bearer from-bearer");
        request.addHeader("X-Claude-Token", "from-x-header");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream.getParameter("token")).isEqualTo("from-bearer");
    }

    @Test
    void headerTokenOverridesLegacyUrlToken() throws Exception {
        request.setParameter("token", "legacy-url-token");
        request.addHeader("Authorization", "Bearer header-token");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream.getParameter("token")).isEqualTo("header-token");
        assertThat(downstream.getParameterValues("token")).containsExactly("header-token");
    }

    @Test
    void legacyUrlTokenStillWorksWithoutHeader() throws Exception {
        // Übergangsphase: bestehende Clients (?token=...) dürfen NICHT brechen.
        request.setParameter("token", "legacy-url-token");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream).isSameAs(request);
        assertThat(downstream.getParameter("token")).isEqualTo("legacy-url-token");
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
        request.setParameter("token", "legacy-url-token");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream.getParameter("token")).isEqualTo("legacy-url-token");
    }

    @Test
    void nonBearerAuthorizationHeaderIsIgnored() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwdw==");

        ServletRequest downstream = filteredRequest();

        assertThat(downstream).isSameAs(request);
        assertThat(downstream.getParameter("token")).isNull();
    }
}
