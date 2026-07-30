/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import java.util.List;

/**
 * Bean-Interface, über das ein Modul seine eigenen Treffer zur globalen Suche (Cmd+K in der Topbar)
 * beisteuert. Spiegelt exakt das Registry-Muster von {@code MenuAnnotation}/{@code MenuRegistry} und
 * {@code DashboardTile}/{@code DashboardTileDataProvider}: <b>root definiert das Interface, jedes Modul
 * registriert einen {@code @Component}, root sammelt alle Beans automatisch ein und fragt sie ab.</b>
 * <p>
 * Kernprinzip: <b>jedes Modul liefert seine Treffer selbst – inklusive des korrekten Ziel-Links.</b>
 * root muss nichts über die Modul-Seiten wissen; der Deep-Link ({@link SearchHit#getLink()}) ist exakt
 * dasselbe wie ein {@code MenuAnnotation.link} (z. B. {@code "korrespondenz.html?id=42"}) und landet
 * garantiert auf der richtigen Detailseite des Moduls.
 * <p>
 * <b>Ein Modul „andocken" =</b> eine einzige {@code @Component}-Klasse, die dieses Interface
 * implementiert – kein Root-Change nötig.
 * <p>
 * <b>Sichtbarkeit/Security:</b> root fragt einen Provider nur ab, wenn dessen {@link #moduleTitle()}
 * für den aktuellen Mandanten/Benutzer sichtbar ist (Abgleich mit
 * {@code MenuRegistry.getAllMenuTitles()}). Zusätzlich sollte jeder Provider seine Treffer selbst über
 * {@code PlaintextSecurity.getMandat()} auf den aktiven Mandanten einschränken – analog zu einem
 * mandantenscoped {@code DashboardTileDataProvider}.
 *
 * @author plaintext.ch
 */
public interface SearchProvider {

    /**
     * Technische, stabile ID dieses Providers (z. B. {@code "korrespondenz"}, {@code "kontakte"}).
     * Dient nur der Diagnose/Logging und muss eindeutig sein.
     *
     * @return die Provider-ID (nie {@code null})
     */
    String providerId();

    /**
     * Anzeige-/Gruppentitel dieses Providers. <b>Muss sich mit einem Menü-Titel decken</b>
     * (Titel oder Voll-Titel aus {@code MenuRegistry}), damit root die Sichtbarkeit an die
     * Menü-Sichtbarkeit koppeln kann: Ist das zugehörige Menü für den Benutzer/Mandanten nicht
     * sichtbar, wird der Provider gar nicht erst abgefragt und taucht in den Ergebnissen nicht auf.
     *
     * @return der Gruppentitel (nie {@code null})
     */
    String moduleTitle();

    /**
     * Sucht im Fachmodul nach {@code query} und liefert bis zu {@code limit} Treffer. Wird im
     * Sicherheits-/Mandantenkontext des aktuellen Benutzers aufgerufen; der Provider filtert selbst
     * auf den aktiven Mandanten.
     * <p>
     * Die Implementierung sollte robust und schnell sein: root ruft die Provider timeboxed auf und
     * fängt Fehler ab (ein langsamer/fehlerhafter Provider darf die Gesamtsuche nicht blockieren),
     * dennoch gilt: keine Exceptions für den Normalfall, {@code null} nie zurückgeben (leere Liste).
     *
     * @param query der Suchbegriff (bereits getrimmt; mindestens 2 Zeichen)
     * @param limit maximale Trefferzahl, die dieser Provider liefern soll
     * @return Trefferliste (nie {@code null}, ggf. leer)
     */
    List<SearchHit> search(String query, int limit);

    /**
     * Ob dieser Provider an die Menü-Sichtbarkeit seines {@link #moduleTitle()} gekoppelt ist
     * (Standard: {@code true}). Ist er gekoppelt, fragt root ihn nur ab, wenn ein sichtbares Menü
     * mit passendem Titel existiert – das ist der Regelfall für Fachmodul-Provider.
     * <p>
     * Quer schneidende Root-Provider (z. B. die Menü-/Seiten-Suche oder die rollen-gebundene
     * Benutzer-Suche) gehören zu keinem einzelnen Menü und geben hier {@code false} zurück; sie
     * erzwingen Sichtbarkeit/Rollen dann <b>selbst</b> in {@link #search(String, int)}.
     *
     * @return {@code true}, wenn root die Sichtbarkeit über den Menü-Titel prüfen soll
     */
    default boolean isMenuScoped() {
        return true;
    }

    /**
     * Ein einzelner Suchtreffer. Der <b>Deep-Link</b> ({@link #getLink()}) ist der Schlüssel: er zeigt
     * auf die modul-eigene Zielseite, exakt wie ein {@code MenuAnnotation.link}.
     */
    interface SearchHit {

        /**
         * Haupttext/Bezeichnung des Treffers (z. B. Titel des Dokuments, Name des Kontakts).
         *
         * @return der Titel (nie {@code null})
         */
        String getTitle();

        /**
         * Kontext-Zeile unter dem Titel (z. B. Datum, Mandant, Kurzbeschreibung). Darf {@code null}
         * oder leer sein.
         *
         * @return der Untertitel oder {@code null}
         */
        String getSubtitle();

        /**
         * <b>Deep-Link auf die Zielseite des Moduls</b> – exakt wie ein {@code MenuAnnotation.link},
         * relativ zum Context-Path (z. B. {@code "korrespondenz.html?id=42"}). Das Frontend navigiert
         * per {@code window.location = contextPath + "/" + link}. Nur unbedenkliche, relative Ziele
         * verwenden; keine absoluten/protokoll-relativen URLs.
         *
         * @return der relative Ziel-Link (nie {@code null})
         */
        String getLink();

        /**
         * PrimeFaces-Icon-Klasse für den Treffer (z. B. {@code "pi pi-envelope"}). Darf {@code null}
         * sein.
         *
         * @return die Icon-Klasse oder {@code null}
         */
        String getIcon();

        /**
         * Ranking innerhalb der Modul-Gruppe: höhere Werte erscheinen weiter oben.
         *
         * @return der Score
         */
        int getScore();
    }
}
