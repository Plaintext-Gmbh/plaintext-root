/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import java.util.List;
import java.util.Optional;

/**
 * Root-Dienst zum Bauen von Deep-Links (Karte 345). Konsumierende Module bauen ihre Mail-Links
 * ueber diesen Dienst, statt URLs selbst zusammenzusetzen — so bleibt das Format an einer Stelle
 * und der Einstiegspunkt {@code /deeplink} kann die Sicherheitspruefungen zentral durchsetzen.
 *
 * <p>Beispiel aus einem Modul:
 * <pre>{@code
 * String link = deepLinkService.buildAbsoluteLink("auszahlung", auszahlung.getMandat(),
 *                                                 String.valueOf(auszahlung.getId()));
 * }</pre>
 */
public interface DeepLinkService {

    /** Pfad des Root-Einstiegspunkts (ohne Context-Path). */
    String DEEPLINK_PATH = "/deeplink";

    /**
     * Absoluter Link fuer den Versand per Mail. Die Basis-URL kommt aus der Konfiguration
     * ({@code plaintext.baseurl}) — bewusst nicht aus dem aktuellen Request, weil Links auch aus
     * Hintergrund-Jobs ohne Request erzeugt werden.
     *
     * @throws IllegalArgumentException wenn {@code type} nicht registriert ist oder Mandat/Id das
     *                                  erlaubte Zeichenmuster verletzen
     */
    String buildAbsoluteLink(String type, String mandat, String id);

    /** Wie {@link #buildAbsoluteLink}, aber relativ zum Context-Path ({@code /deeplink?...}). */
    String buildRelativeLink(String type, String mandat, String id);

    /** Alle registrierten Ziele, nach {@code type} sortiert — Grundlage der Root-Uebersicht. */
    List<DeepLinkTarget> getTargets();

    /** Registriertes Ziel zu einem {@code type}, oder leer. Unbekannte Typen werden abgelehnt. */
    Optional<DeepLinkTarget> findTarget(String type);
}
