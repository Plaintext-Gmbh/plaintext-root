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
 * Protokolliert Requests, die laenger dauern als eine konfigurierte Schwelle (Karte 430).
 *
 * <p><b>Warum es diesen Filter gibt.</b> „Die Anwendung ist langsam" liess sich bisher nicht
 * nachweisen: Der einzige Filter, der Wiki-Wege protokolliert, greift nur bei {@code .html}-URLs,
 * und ein Ajax-Postback auf {@code /wiki.xhtml} tauchte in keinem Log auf. Es gab schlicht keine
 * Stelle, an der die Dauer eines Requests festgehalten wird — eine Meldung wie „das Rädchen dreht
 * lange" war damit grundsätzlich nicht überprüfbar.
 *
 * <p><b>Warum kein Ausbau des {@link TimingAspect}.</b> Der misst per AOP einzelne
 * <i>Methodenaufrufe</i> und kennt weder HTTP-Pfad noch -Methode. Ein Request durchläuft Dutzende
 * Methoden; aus ihren Einzelzeiten lässt sich die Dauer des Requests nicht rekonstruieren, und ein
 * Pfad kommt darin gar nicht vor. Die Frage „wie lange dauerte der POST auf /wiki.xhtml" kann er
 * deshalb nicht beantworten. Beide ergänzen sich: der Aspect sagt <i>welche Methode</i>, der
 * Filter <i>welcher Request</i>.
 *
 * <p><b>Was NICHT ins Log geht:</b> kein Query-String, kein Request-Body, keine Parameter, keine
 * Session-Kennung, kein Benutzername. Ein Timing-Log, das nebenbei Inhalte mitschreibt, wäre ein
 * Datenleck — und ausgerechnet die Wiki-Pfade führen Titel und Tokens in der Query.
 *
 * <p><b>Drosselung:</b> Je Pfad höchstens eine Meldung pro {@code meldeabstand}. Unterdrückte
 * Treffer werden gezählt und bei der nächsten Meldung mit ausgewiesen, damit die Drosselung nichts
 * verschweigt. Ohne sie schriebe eine dauerhaft langsame Anwendung bei jedem Request eine Zeile.
 *
 * <p>Die Messung fliesst zusätzlich in den {@link PerformanceService} und erscheint damit in der
 * bestehenden Performance-Ansicht — auch unterhalb der Schwelle, wo nichts protokolliert wird.
 *
 * @author plaintext.ch
 */
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SlowRequestFilter implements Filter {

    private final SlowRequestProperties properties;

    private final PerformanceService performanceService;

    /** Zeitpunkt der letzten Meldung je Pfad (Millisekunden). */
    private final ConcurrentMap<String, AtomicLong> letzteMeldung = new ConcurrentHashMap<>();

    /** Seit der letzten Meldung unterdrueckte Treffer je Pfad. */
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
                // Die Messung darf den Request NIE beeintraechtigen: Sie laeuft im finally, also
                // auch im Fehlerfall. Eine Ausnahme hier wuerde die urspruengliche verdecken.
                log.debug("Langsam-Erkennung uebersprungen", e);
            }
        }
    }

    private void bewerte(HttpServletRequest http, long dauerMs) {
        // Nur Methode und Pfad — bewusst OHNE Query-String (siehe Klassenkommentar).
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
     * Darf fuer diesen Pfad jetzt gemeldet werden? Beim ersten Mal ja; danach erst wieder nach
     * Ablauf des Meldeabstands. Ist die Pfad-Obergrenze erreicht, wird gemeldet ohne zu drosseln —
     * lieber ein paar Zeilen zu viel als eine unbegrenzt wachsende Map.
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
