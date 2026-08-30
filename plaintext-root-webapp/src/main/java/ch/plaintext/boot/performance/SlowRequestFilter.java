/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs requests that take longer than a configured threshold (card 430).
 *
 * <p><b>Why this filter exists.</b> "The application is slow" could not be proven so far:
 * the only filter that logs wiki paths applies to {@code .html} URLs only,
 * and an Ajax postback on {@code /wiki.xhtml} showed up in no log at all. There simply was no
 * place where the duration of a request is recorded — a report such as "the spinner keeps
 * turning for a long time" was therefore fundamentally unverifiable.
 *
 * <p><b>Why not an extension of the {@link TimingAspect}.</b> That one measures individual
 * <i>method calls</i> via AOP and knows neither the HTTP path nor the HTTP method. A request runs
 * through dozens of methods; the duration of the request cannot be reconstructed from their
 * individual times, and a path does not appear in them at all. The question "how long did the POST
 * on /wiki.xhtml take" is therefore one it cannot answer. The two complement each other: the aspect
 * says <i>which method</i>, the filter <i>which request</i>.
 *
 * <p><b>What does NOT go into the log:</b> no query string, no request body, no parameters, no
 * session identifier, no user name. A timing log that writes down content along the way would be a
 * data leak — and of all things the wiki paths carry titles and tokens in the query.
 *
 * <p><b>Throttling:</b> at most one message per path per {@code meldeabstand}. Suppressed
 * hits are counted and reported along with the next message, so that the throttling hides
 * nothing. Without it a permanently slow application would write a line on every request.
 *
 * <p>The measurement additionally flows into the {@link PerformanceService} and thereby appears in
 * the existing performance view — also below the threshold, where nothing is logged.
 *
 * @author plaintext.ch
 */
@Slf4j
@RequiredArgsConstructor
// Card 497 (requirement from the review of card 430): NOT +10 — there
// RateLimitFilterConfig registers the ForwardedHeaderFilter for security reasons (card 303).
// Two filters on the same order value have NO defined order in Spring.
// +9 instead of +11, because this filter only measures the time and logs method/path —
// it does not need the corrected forwarded headers, but should cover as much of the
// chain as possible.
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
public class SlowRequestFilter implements Filter {

    private final SlowRequestProperties properties;

    private final PerformanceService performanceService;

    /** Time of the last message per path (milliseconds). */
    private final ConcurrentMap<String, AtomicLong> letzteMeldung = new ConcurrentHashMap<>();

    /** Hits suppressed per path since the last message. */
    private final ConcurrentMap<String, AtomicInteger> unterdrueckt = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!properties.isEnabled() || !(req instanceof HttpServletRequest http)) {
            chain.doFilter(req, res);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long dauerMs = (System.nanoTime() - start) / 1_000_000L;
            try {
                bewerte(http, dauerMs);
            } catch (Exception e) {
                // The measurement must NEVER affect the request: it runs in the finally block, hence
                // also in the error case. An exception here would mask the original one.
                log.debug("Langsam-Erkennung uebersprungen", e);
            }
        }
    }

    private void bewerte(HttpServletRequest http, long dauerMs) {
        // Method and path only — deliberately WITHOUT the query string (see the class comment).
        String pfad = http.getRequestURI();
        String methode = http.getMethod();
        performanceService.record(methode + " " + pfad, dauerMs * 1_000_000L);

        if (dauerMs < properties.getSchwelle().toMillis()) {
            return;
        }
        if (!darfMelden(pfad)) {
            unterdrueckt.computeIfAbsent(pfad, k -> new AtomicInteger()).incrementAndGet();
            return;
        }
        int stumm = unterdrueckt.computeIfAbsent(pfad, k -> new AtomicInteger()).getAndSet(0);
        if (stumm > 0) {
            log.warn("Langsamer Request: {} {} dauerte {} ms (Schwelle {} ms; {} weitere Treffer "
                            + "seit der letzten Meldung unterdrueckt)",
                    methode, pfad, dauerMs, properties.getSchwelle().toMillis(), stumm);
        } else {
            log.warn("Langsamer Request: {} {} dauerte {} ms (Schwelle {} ms)",
                    methode, pfad, dauerMs, properties.getSchwelle().toMillis());
        }
    }

    /**
     * May a message be emitted for this path right now? The first time yes; after that only once the
     * reporting interval has elapsed. If the path limit is reached, a message is emitted without
     * throttling — better a few lines too many than a map growing without bound.
     */
    private boolean darfMelden(String pfad) {
        long jetzt = System.currentTimeMillis();
        long abstand = properties.getMeldeabstand().toMillis();
        AtomicLong letzte = letzteMeldung.get(pfad);
        if (letzte == null) {
            if (letzteMeldung.size() >= properties.getMaxPfade()) {
                return true;
            }
            letzteMeldung.putIfAbsent(pfad, new AtomicLong(jetzt));
            return true;
        }
        long vorher = letzte.get();
        return jetzt - vorher >= abstand && letzte.compareAndSet(vorher, jetzt);
    }
}
