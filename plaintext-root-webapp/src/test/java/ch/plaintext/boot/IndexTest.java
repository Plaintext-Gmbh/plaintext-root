/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.mockito.Mockito.*;

/**
 * Tests for Index - root URL redirect controller.
 */
@ExtendWith(MockitoExtension.class)
class IndexTest {

    @Mock
    private HttpServletResponse response;

    /**
     * Card 1331: {@code /} only redirects to pages that exist. The servlet context of these tests
     * knows every page EXCEPT the ones the card is about ({@code Index.html}, {@code gibtesnicht.html}).
     */
    private final ServletContext servletContext = mock(ServletContext.class, invocation -> {
        if ("getResource".equals(invocation.getMethod().getName())) {
            String view = invocation.getArgument(0);
            return view.equals("/Index.xhtml") || view.equals("/gibtesnicht.xhtml")
                    ? null : new java.net.URL("file:" + view);
        }
        return null;
    });

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void servletContext() {
        lenient().when(request.getServletContext()).thenReturn(servletContext);
    }

    @InjectMocks
    private Index index;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setupAuthentication(java.util.List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void getIndex_shouldRedirectToIndexHtml_whenNoStartpage() throws IOException {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        index.getIndex(request, response);

        verify(response).sendRedirect("index.html");
    }

    @Test
    void getIndex_shouldRedirectToStartpage_whenHtmlAuthority() throws IOException {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")
        ));

        index.getIndex(request, response);

        verify(response).sendRedirect("dashboard.html");
    }

    @Test
    void getIndex_shouldHandleStartpageWithPropertyPrefix() throws IOException {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_custom.html")
        ));

        index.getIndex(request, response);

        verify(response).sendRedirect("custom.html");
    }

    @Test
    void getIndex_shouldUseFirstHtmlAuthority() throws IOException {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_first.html"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_second.html")
        ));

        index.getIndex(request, response);

        // With several configured start pages the first one wins (consistent with the login redirect).
        verify(response).sendRedirect("first.html");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Index.html", "gibtesnicht.html"})
    void getIndex_nonExistingStartpage_fallsBackToIndex(String startseite) throws IOException {
        // Card 1331: "Index.html" (capital I) passed the form check and looped / -> /Index.html -> /.
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_" + startseite)));

        index.getIndex(request, response);

        verify(response).sendRedirect("index.html");
    }

    @Test
    void getIndex_existingIndividualStartpage_isKept() throws IOException {
        // Positive control of card 1331: a valid, existing individual start page stays.
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_auszahlungen.html")));

        index.getIndex(request, response);

        verify(response).sendRedirect("auszahlungen.html");
    }
}
