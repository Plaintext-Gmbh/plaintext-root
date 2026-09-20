/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.maintenance;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1290 — die Anmeldung des {@link MaintenanceModeFilter}, in Millisekunden statt in einem
 * Browserlauf. Vorbild: {@code WatchTokenFilterRegistrierungTest} aus Karte 1280.
 *
 * <p><b>Warum das keine Formalie ist.</b> Am 20.09.2026 war der Filter nur fuer
 * {@code DispatcherType.REQUEST} angemeldet und sitzt mit {@code LOWEST_PRECEDENCE - 100} weit
 * hinter dem {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter}
 * ({@code HIGHEST_PRECEDENCE + 30}). Jede Seite wird als {@code .html} adressiert und erreicht
 * ihre Sicht ueber dessen {@code RequestDispatcher.forward()} — der Filter lief damit auf keiner
 * einzigen Seite. Ein eingeschalteter Wartungsmodus haette nur REST-Endpunkte, statische Dateien
 * und Adressen ohne Endung gesperrt und jede Seite weiter ausgeliefert.</p>
 *
 * <p>Gemessen hat das {@code FilterLaufMessungTest} an einem laufenden Tomcat. Diese Klasse ist
 * die Wache, die in der naechsten Sekunde rot wird, wenn jemand die Anmeldung wieder
 * zurechtstutzt.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class MaintenanceModeFilterRegistrierungTest {

    private FilterRegistrationBean<MaintenanceModeFilter> registrierung() {
        MaintenanceModeConfig config = new MaintenanceModeConfig();
        MaintenanceModeProperties properties = new MaintenanceModeProperties();
        return config.maintenanceModeFilterRegistration(config.maintenanceModeFilter(properties));
    }

    @Test
    @DisplayName("FORWARD ist angemeldet — ohne ihn sperrt der Wartungsmodus keine .html-Seite")
    void forwardIstAngemeldet() {
        EnumSet<DispatcherType> typen = registrierung().determineDispatcherTypes();

        assertTrue(typen.contains(DispatcherType.REQUEST),
                "REQUEST fehlt — dann bleiben Adressen ohne .html ungesperrt: " + typen);
        assertTrue(typen.contains(DispatcherType.FORWARD),
                "FORWARD fehlt. Jede Seite wird als .html aufgerufen und erreicht ihre Sicht "
                        + "ueber den forward() des HtmlToXhtmlRewriteFilter. Ohne FORWARD sperrt "
                        + "der eingeschaltete Wartungsmodus keine einzige Seite (Karte 1290). "
                        + "Angemeldet: " + typen);
    }

    /**
     * Die Gegenrichtung, damit der Test oben nicht zu „melde einfach alles an" verkommt:
     * {@code ERROR} gehoert nicht dazu. Der Fehlerdurchgang ist der des Containers; eine Antwort
     * dort verwandelte jede 404 in eine 503 (dieselbe Falle wie in Karte 652).
     */
    @Test
    @DisplayName("ERROR bleibt draussen — sonst wird aus jeder 404 eine 503")
    void errorBleibtDraussen() {
        EnumSet<DispatcherType> typen = registrierung().determineDispatcherTypes();
        assertFalse(typen.contains(DispatcherType.ERROR),
                "ERROR ist angemeldet — dann antwortet der Wartungsmodus auch auf dem "
                        + "Fehlerdurchgang und macht aus jeder 404 eine 503: " + typen);
    }

    /**
     * Die zweite Bedingung: der Filter muss hinter der Sicherheitskette laufen, sonst ist der
     * {@code SecurityContextHolder} leer und {@code hasBypassRole()} immer falsch — dann sperrt
     * der Wartungsmodus auch die Administratoren aus, die ihn wieder abschalten sollen.
     */
    @Test
    @DisplayName("Reihenfolge ganz am Ende — davor waere der SecurityContextHolder leer")
    void ordnungLiegtHinterDerSicherheitskette() {
        assertEquals(MaintenanceModeConfig.MAINTENANCE_FILTER_ORDER, registrierung().getOrder(),
                "Der Filter muss hinter der Sicherheitskette (-100) stehen, damit die "
                        + "Bypass-Rollen ueberhaupt sichtbar sind.");
        assertTrue(MaintenanceModeConfig.MAINTENANCE_FILTER_ORDER > -100,
                "Die Reihenfolge liegt vor der Sicherheitskette — dann greift keine Bypass-Rolle.");
    }
}
