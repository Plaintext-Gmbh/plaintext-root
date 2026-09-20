/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1290 — die Anmeldung des {@link SessionTrackingFilter}. Vorbild:
 * {@code WatchTokenFilterRegistrierungTest} aus Karte 1280.
 *
 * <p><b>Warum das keine Formalie ist.</b> Bis zum 20.09.2026 war der Filter allein ueber
 * {@code @Component} und {@code @Order(100)} angemeldet. Spring Boot leitet die Dispatcher-Typen
 * einer solchen Bohne ab ({@code AbstractFilterRegistrationBean#determineDispatcherTypes}) —
 * fuer einen einfachen {@code Filter} ist das {@code EnumSet.of(REQUEST)}. Mit {@code order=100}
 * sitzt er hinter dem {@code HtmlToXhtmlRewriteFilter} ({@code HIGHEST_PRECEDENCE + 30}), der
 * jede {@code .html}-Seite per {@code forward()} erreicht: der Filter lief damit auf keiner
 * einzigen Seite, sondern nur auf statischen Dateien und REST-Adressen.</p>
 *
 * <p>Alle Unit-Tests des Filters waren dabei durchgehend gruen — einen Dispatcher-Typ gibt es
 * dort gar nicht. Gemessen hat es {@code FilterLaufMessungTest} in plaintext-root-webapp an
 * einem laufenden Tomcat; diese Klasse ist die schnelle Wache an der Stelle, an der der Fehler
 * gemacht wird.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class SessionTrackingFilterRegistrierungTest {

    private FilterRegistrationBean<SessionTrackingFilter> registrierung() {
        return new SessionTrackingFilterConfig()
                .sessionTrackingFilterRegistration(mock(SessionTrackingFilter.class));
    }

    @Test
    @DisplayName("FORWARD ist angemeldet — ohne ihn sieht die Aufzeichnung keine einzige Seite")
    void forwardIstAngemeldet() {
        EnumSet<DispatcherType> typen = registrierung().determineDispatcherTypes();

        assertTrue(typen.contains(DispatcherType.REQUEST),
                "REQUEST fehlt — dann werden Adressen ohne .html nicht mehr erfasst: " + typen);
        assertTrue(typen.contains(DispatcherType.FORWARD),
                "FORWARD fehlt. Jede Seite wird als .html aufgerufen und erreicht ihre Sicht "
                        + "ueber den forward() des HtmlToXhtmlRewriteFilter. Ohne FORWARD sieht "
                        + "die Sitzungsaufzeichnung keine Seite, und das Sitzungsregister — die "
                        + "Grundlage fuer das zwangsweise Beenden einer Sitzung — bekommt eine "
                        + "Sitzung nur zufaellig zu Gesicht (Karte 1290). Angemeldet: " + typen);
    }

    /**
     * Die Gegenrichtung, damit der Test oben nicht zu „melde einfach alles an" verkommt:
     * {@code ERROR} bleibt draussen. Der Fehlerdurchgang ist der des Containers und traegt
     * keinen Anrufer (Karte 652) — eine Aufzeichnung dort schriebe Sitzungen ohne Anmeldung
     * mit.
     */
    @Test
    @DisplayName("ERROR bleibt draussen — der Fehlerdurchgang traegt keinen Anrufer")
    void errorBleibtDraussen() {
        EnumSet<DispatcherType> typen = registrierung().determineDispatcherTypes();
        assertFalse(typen.contains(DispatcherType.ERROR), "ERROR ist angemeldet: " + typen);
    }

    @Test
    @DisplayName("Reihenfolge 100 — hinter der Sicherheitskette (-100), sonst ist die Anmeldung unsichtbar")
    void ordnungLiegtHinterDerSicherheitskette() {
        assertEquals(SessionTrackingFilterConfig.SESSION_TRACKING_FILTER_ORDER,
                registrierung().getOrder());
        assertTrue(SessionTrackingFilterConfig.SESSION_TRACKING_FILTER_ORDER > -100,
                "Vor der Sicherheitskette waere der SecurityContextHolder leer und der Filter "
                        + "stiege bei jeder Anfrage als 'nicht angemeldet' aus.");
    }

    /**
     * Mit {@code FORWARD} kommen REQUEST- und FORWARD-Durchgang derselben Anfrage beide hier an,
     * sobald eine Adresse <b>ohne</b> {@code .html} intern weitergeleitet wird. Ohne Sperre
     * schriebe das jede solche Anfrage doppelt ins Protokoll.
     */
    @Test
    @DisplayName("Dieselbe Anfrage wird nur einmal gezaehlt, auch wenn REQUEST und FORWARD ankommen")
    void zweiDurchgaengeZaehlenEinmal() throws Exception {
        SessionTrackingFilter filter = new SessionTrackingFilter(
                mock(ch.plaintext.sessions.service.SessionAuditWriter.class),
                mock(ch.plaintext.PlaintextSecurity.class),
                mock(ch.plaintext.sessions.service.HttpSessionRegistry.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        // Erster Durchgang: Attribut noch nicht gesetzt. Danach liefert der Mock es zurueck.
        when(request.getAttribute(SessionTrackingFilter.ATTRIBUT_GEZAEHLT)).thenReturn(null);
        filter.doFilter(request, response, chain);
        verify(request).setAttribute(eq(SessionTrackingFilter.ATTRIBUT_GEZAEHLT), any());

        when(request.getAttribute(SessionTrackingFilter.ATTRIBUT_GEZAEHLT)).thenReturn(Boolean.TRUE);
        filter.doFilter(request, response, chain);

        // Das Attribut wird genau einmal gesetzt — der zweite Durchgang steigt davor aus.
        verify(request).setAttribute(eq(SessionTrackingFilter.ATTRIBUT_GEZAEHLT), any());
        // Weitergereicht wird trotzdem jedes Mal.
        verify(chain, org.mockito.Mockito.times(2)).doFilter(request, response);
    }
}
