/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slowness detection (card 430).
 *
 * <p>What is checked is what the card demands: the slow request has to become visible, the log
 * must not overflow, and NO payload data may end up in it.
 *
 * @author plaintext.ch
 */
class SlowRequestFilterTest {

    private SlowRequestProperties props;
    private PerformanceService performance;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        props = new SlowRequestProperties();
        props.setSchwelle(Duration.ofMillis(50));
        props.setMeldeabstand(Duration.ofMinutes(1));
        performance = mock(PerformanceService.class);
        logger = (Logger) LoggerFactory.getLogger(SlowRequestFilter.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    /** Request dummy; the chain sleeps for the desired duration. */
    private void lauf(SlowRequestFilter filter, String methode, String pfad, String query, long dauerMs)
            throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(pfad);
        when(req.getMethod()).thenReturn(methode);
        lenientQuery(req, query);
        FilterChain chain = mock(FilterChain.class);
        doAnswer(a -> { Thread.sleep(dauerMs); return null; })
                .when(chain).doFilter(req, null);
        filter.doFilter(req, null, chain);
    }

    private void lenientQuery(HttpServletRequest req, String query) {
        org.mockito.Mockito.lenient().when(req.getQueryString()).thenReturn(query);
    }

    private List<String> meldungen() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("Langsamer Request wird mit Methode, Pfad und Dauer gemeldet")
    void langsamWirdGemeldet() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "POST", "/wiki.xhtml", null, 120);

        assertThat(meldungen()).hasSize(1);
        assertThat(meldungen().get(0))
                .contains("POST")
                .contains("/wiki.xhtml")
                .contains("Schwelle 50 ms");
    }

    @Test
    @DisplayName("Schneller Request wird nicht gemeldet")
    void schnellSchweigt() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "GET", "/wiki-projekte.html", null, 0);

        assertThat(meldungen()).isEmpty();
    }

    @Test
    @DisplayName("SICHERHEIT: der Query-String landet NICHT im Log")
    void keineNutzdatenImLog() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "GET", "/nosec/wiki/p/seite.xhtml",
                "token=GEHEIM123&titel=Vertrauliche+Seite", 120);

        assertThat(meldungen()).hasSize(1);
        assertThat(meldungen().get(0))
                .doesNotContain("GEHEIM123")
                .doesNotContain("token=")
                .doesNotContain("Vertrauliche")
                .contains("/nosec/wiki/p/seite.xhtml");
    }

    @Test
    @DisplayName("Drosselung: zweiter langsamer Request desselben Pfades meldet nicht erneut")
    void drosselung() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "POST", "/wiki.xhtml", null, 120);
        lauf(filter, "POST", "/wiki.xhtml", null, 120);
        lauf(filter, "POST", "/wiki.xhtml", null, 120);

        assertThat(meldungen()).as("nur die erste Meldung").hasSize(1);
    }

    @Test
    @DisplayName("Nach Ablauf des Meldeabstands wird wieder gemeldet — mit Anzahl der unterdrueckten")
    void unterdrueckteWerdenNachgemeldet() throws Exception {
        // The reporting interval has to be markedly LARGER than the request duration, otherwise it has
        // already elapsed by the second run anyway and three messages are emitted (first version of the
        // test: 1 ms interval with 60 ms requests — the test measured itself, not the throttling).
        props.setMeldeabstand(Duration.ofMillis(300));
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "POST", "/wiki.xhtml", null, 60);   // reports
        lauf(filter, "POST", "/wiki.xhtml", null, 60);   // suppressed (60 ms < 300 ms)
        Thread.sleep(350);
        lauf(filter, "POST", "/wiki.xhtml", null, 60);   // interval elapsed -> reports again

        assertThat(meldungen()).hasSize(2);
        assertThat(meldungen().get(1)).contains("unterdrueckt");
    }

    @Test
    @DisplayName("Verschiedene Pfade drosseln sich nicht gegenseitig")
    void pfadeUnabhaengig() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "POST", "/wiki.xhtml", null, 60);
        lauf(filter, "GET", "/wiki-projekte.html", null, 60);

        assertThat(meldungen()).hasSize(2);
    }

    @Test
    @DisplayName("Auch schnelle Requests fliessen in den PerformanceService")
    void performanceServiceBekommtAlles() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "GET", "/index.xhtml", null, 0);

        verify(performance).record(anyString(), anyLong());
    }

    @Test
    @DisplayName("Abgeschaltet: keine Messung, keine Meldung")
    void abgeschaltet() throws Exception {
        props.setEnabled(false);
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);

        lauf(filter, "POST", "/wiki.xhtml", null, 120);

        assertThat(meldungen()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(performance);
    }

    @Test
    @DisplayName("Eine Ausnahme der Kette wird durchgereicht, nicht verschluckt")
    void ausnahmeWirdDurchgereicht() {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/kaputt.xhtml");
        when(req.getMethod()).thenReturn("GET");
        FilterChain chain = mock(FilterChain.class);

        try {
            doAnswer(a -> { throw new IllegalStateException("kaputt"); }).when(chain).doFilter(req, null);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(req, null, chain))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("kaputt");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("Nicht-HTTP-Request wird einfach durchgereicht")
    void nichtHttpDurchgereicht() throws Exception {
        SlowRequestFilter filter = new SlowRequestFilter(props, performance);
        jakarta.servlet.ServletRequest req = mock(jakarta.servlet.ServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(meldungen()).isEmpty();
    }
}
