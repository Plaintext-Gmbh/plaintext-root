/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.config;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.watch.web.WatchTokenSitzungFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Card 1280: the registration of {@link WatchTokenSitzungFilter} — the two properties on which
 * the whole confinement stands or falls.
 *
 * <h2>Why this test is not a formality</h2>
 *
 * <p>On 20.09.2026 the filter was registered for {@code DispatcherType.REQUEST} only, and
 * therefore ran on <b>no page of the application</b>: every page is addressed as {@code .html},
 * and {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} — at {@code HIGHEST_PRECEDENCE + 30},
 * far ahead of the security chain — reaches the view with a {@code RequestDispatcher.forward()}.
 * A token session thereby got {@code /index.html} with HTTP 200, and the per-request revocation
 * check never fired on the watch pages either.</p>
 *
 * <p><b>Every unit test of the filter itself was green through all of that</b>, because a unit
 * test hands the filter a path and a dispatch type does not exist there. It took the browser run
 * {@code WatchHandyLinkPlaywrightIT} to see it — ten minutes of CI for a statement this class
 * makes in a few milliseconds, at the place where the mistake is made. The browser test stays
 * (it is the one that proves the whole way end to end); this one is the guard that goes red in
 * the next second when somebody trims the registration again.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchTokenFilterRegistrierungTest {

    @SuppressWarnings("unchecked")
    private FilterRegistrationBean<WatchTokenSitzungFilter> registrierung() {
        return new WatchTokenFilterConfig()
                .watchTokenSitzungFilterRegistration(mock(ObjectProvider.class));
    }

    @Test
    @DisplayName("FORWARD ist angemeldet — ohne ihn greift die Einsperrung auf keiner .html-Seite")
    void forwardIstAngemeldet() {
        EnumSet<DispatcherType> typen = registrierung().determineDispatcherTypes();

        assertTrue(typen.contains(DispatcherType.REQUEST),
                "REQUEST fehlt — dann laeuft der Filter auf Adressen ohne .html nicht mit: " + typen);
        assertTrue(typen.contains(DispatcherType.FORWARD),
                "FORWARD fehlt. Jede Seite dieses Hauses wird als .html aufgerufen und erreicht "
                        + "ihre Sicht ueber den forward() des HtmlToXhtmlRewriteFilter. Ohne "
                        + "FORWARD sieht dieser Filter keine einzige Seite: eine Token-Sitzung "
                        + "kommt auf /index.html und auf /watch-einstellungen.html durch, und die "
                        + "Widerrufspruefung je Anfrage findet nirgends statt (Karte 1280). "
                        + "Angemeldet: " + typen);
    }

    /**
     * Die Gegenrichtung, damit der Test oben nicht zu „melde einfach alles an" verkommt:
     * {@code ERROR} gehoert weiterhin NICHT dazu. Der Fehlerdurchgang ist der des Containers und
     * traegt keinen Anrufer; ein Filter, der dort 403 antwortet, macht aus jeder 404 eine 403
     * (Karte 652).
     */
    @Test
    @DisplayName("ERROR bleibt draussen — sonst wird aus jeder 404 eine 403 (Karte 652)")
    void errorBleibtDraussen() {
        EnumSet<DispatcherType> typen = registrierung().determineDispatcherTypes();
        assertFalse(typen.contains(DispatcherType.ERROR),
                "ERROR ist angemeldet — der Fehlerdurchgang traegt keinen Anrufer, und eine "
                        + "Antwort dort verwandelt jede 404 in eine 403 (Karte 652): " + typen);
    }

    /**
     * Die zweite Bedingung: der Filter muss INNERHALB der Sicherheitskette laufen, sonst ist der
     * {@code SecurityContextHolder} leer und {@code istTokenSitzung()} immer falsch — der Filter
     * liesse dann alles durch, ohne dass irgendetwas rot wuerde.
     */
    @Test
    @DisplayName("Reihenfolge -99: eine Stelle hinter der Sicherheitskette, also in ihr")
    void ordnungLiegtInnerhalbDerSicherheitskette() {
        assertEquals(WatchTokenFilterConfig.SICHERHEITSKETTE_ORDER + 1,
                registrierung().getOrder(),
                "Der Filter muss hinter der Sicherheitskette (-100) angemeldet sein, damit der "
                        + "SecurityContextHolder gefuellt ist. Davor waere er wirkungslos.");
    }
}
