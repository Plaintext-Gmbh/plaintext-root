/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.sidecars.entity.AuthZustand;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.entity.SpeicherAblage;
import ch.plaintext.sidecars.service.SidecarBeschreibung;
import ch.plaintext.sidecars.service.SidecarService;
import ch.plaintext.sidecars.service.SpeicherAblageService;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Seite Root → Sidecars (Karte 1400): alle bekannten Sidecars mit Status, Version, Teilen,
 * Fähigkeiten und Token-Zustand; Token hinterlegen, von Hand ergänzen, neu abfragen.
 *
 * <p>Ein Token wird nur geschrieben, nie angezeigt: das Eingabefeld ist nach dem Speichern leer.</p>
 *
 * <p>Karte 1413: die Seite ist eine Tabelle mit Aufklappen in zwei Reitern. Die Bean liefert dafür
 * nur Ansichtswerte dazu (Zähler, Kurztexte, welche Zeilen vorab offen sind, Dialogzustand); die
 * Abläufe zum Abfragen, Token setzen und Ablagen prüfen sind unverändert.</p>
 */
@Component("sidecarsBean")
@Scope("session")
@SuppressWarnings("java:S6813") // Feldinjektion in Session-Beans wie SecretsBackingBean (Karte 1273)
public class SidecarsBackingBean implements Serializable {

    /** Anfang der Meldungen zum Token (Karte 1416, Sonar java:S1192). */
    private static final String TOKEN_ANFANG = "Token für «";

    /** Anfang der Meldungen zu einer Ablage (Karte 1416, Sonar java:S1192). */
    private static final String ABLAGE_ANFANG = "Ablage «";

    private static final long serialVersionUID = 1L;
    static final String PROTOKOLL_DOKU = "https://github.com/Plaintext-Gmbh/plaintext-root/blob/master/docs/SIDECAR_PROTOKOLL.md";
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Zurich"));
    private static final String AMPEL_FEHLER = "fehler";
    private static final String AMPEL_AUS = "aus";

    @Autowired
    private transient SidecarService service;
    @Autowired
    private transient SpeicherAblageService ablagen;

    /**
     * Eine Zeile der Übersicht. Bewusst eine Klasse und kein Record: der EL-Resolver für Records
     * kennt nur die Record-Komponenten, nicht zusätzliche Getter wie {@code getAmpel()} (PROD-Fehler
     * auf sidecars.html am 02.10.2026, «does not have a readable property 'ampel'»).
     */
    @SuppressWarnings("java:S6206") // muss Klasse bleiben, siehe oben (EL-Resolver kennt bei Records nur Komponenten)
    public static final class Zeile {

        private final Sidecar sidecar;
        private final SidecarBeschreibung beschreibung;

        public Zeile(Sidecar sidecar, SidecarBeschreibung beschreibung) {
            this.sidecar = sidecar;
            this.beschreibung = beschreibung;
        }

        public Sidecar getSidecar() {
            return sidecar;
        }

        public SidecarBeschreibung getBeschreibung() {
            return beschreibung;
        }

        public List<SidecarBeschreibung.Faehigkeit> getFaehigkeiten() {
            return beschreibung == null ? List.of() : beschreibung.faehigkeiten();
        }

        public List<SidecarBeschreibung.Teil> getTeile() {
            return beschreibung == null ? List.of() : beschreibung.teile();
        }

        /** @return {@code ok}, {@code eingeschraenkt}, {@code fehler} oder {@code aus} (nicht erreichbar) */
        public String getAmpel() {
            return !sidecar.isErreichbar() ? AMPEL_AUS : sidecar.getStatus();
        }

        /** @return die Ampel als Wort, für Tooltip und Screenreader */
        public String getAmpelText() {
            String a = getAmpel();
            if (a == null) {
                return "Status unbekannt";
            }
            return switch (a) {
                case "ok" -> "in Ordnung";
                case "eingeschraenkt" -> "eingeschränkt";
                case AMPEL_FEHLER -> "Fehler";
                case AMPEL_AUS -> "nicht erreichbar";
                default -> a;
            };
        }

        public boolean isTokenNoetig() {
            return beschreibung == null || !beschreibung.ohneAuth();
        }

        /** Fehlerampel, nicht erreichbar oder ein Hinweis aus der letzten Abfrage. */
        public boolean isFehlerhaft() {
            String a = getAmpel();
            return AMPEL_FEHLER.equals(a) || AMPEL_AUS.equals(a)
                    || (sidecar.getFehler() != null && !sidecar.getFehler().isBlank());
        }

        /**
         * Der Sidecar verlangt laut Beschreibung einen Token, und es ist keiner hinterlegt oder er wird
         * abgelehnt. Ohne Beschreibung (nie erreicht) ist das unbekannt und zählt nicht.
         */
        public boolean isTokenFehlt() {
            return beschreibung != null && isTokenNoetig()
                    && (!sidecar.hatToken() || sidecar.getAuthZustand() == AuthZustand.UNGUELTIG);
        }

        /** Karte 1413: Zeilen, bei denen etwas zu tun ist, stehen beim Laden offen. */
        public boolean isAufgeklappt() {
            return isFehlerhaft() || isTokenFehlt();
        }

        /** @return Kurztext für die Spalte «Zugang» */
        public String getZugang() {
            if (!isTokenNoetig() || sidecar.getAuthZustand() == AuthZustand.NICHT_NOETIG) {
                return "nicht nötig";
            }
            if (sidecar.getAuthZustand() == AuthZustand.UNGUELTIG) {
                return "Token ungültig";
            }
            if (beschreibung == null && !sidecar.hatToken()) {
                return "unbekannt";
            }
            if (!sidecar.hatToken()) {
                return "Token fehlt";
            }
            return sidecar.getAuthZustand() == AuthZustand.GUELTIG ? "Token ✓" : "Token ungeprüft";
        }

        /** @return Schweregrad für {@code p:tag} ({@code success}, {@code warning}, {@code danger}, {@code info}) */
        public String getZugangSchwere() {
            if (!isTokenNoetig() || sidecar.getAuthZustand() == AuthZustand.NICHT_NOETIG) {
                return "info";
            }
            if (isTokenFehlt()) {
                return "danger";
            }
            if (beschreibung == null && !sidecar.hatToken()) {
                return "warning";
            }
            return sidecar.getAuthZustand() == AuthZustand.GUELTIG ? "success" : "warning";
        }
    }

    @Getter private transient List<Zeile> zeilen = List.of();
    /** Name → eingegebener Token (wird nach dem Speichern geleert). */
    @Getter private Map<String, String> tokenEingabe = new HashMap<>();
    @Getter @Setter private String neueUrl;

    // Karte 1406: Speicher-Ablagen (zweiter Abschnitt)
    @Getter private transient List<SpeicherAblage> speicherAblagen = List.of();
    /** Formular «Ablage einrichten/ändern»; das Passwort wird nie zurückgegeben. */
    @Getter @Setter private String abName;
    @Getter @Setter private String abUrl;
    @Getter @Setter private String abBenutzer;
    @Getter @Setter private String abPasswort;
    @Getter @Setter private String abPfad;
    /** Karte 1413: der Dialog ändert eine bestehende Ablage (Name fest) statt eine neue einzurichten. */
    @Getter private boolean ablageBestehend;

    public String getProtokollDoku() {
        return PROTOKOLL_DOKU;
    }

    public void seitenaufruf() {
        service.aktualisiereVeraltete();
        laden();
    }

    void laden() {
        zeilen = service.liste().stream().map(s -> new Zeile(s, service.beschreibung(s))).toList();
        speicherAblagen = ablagen.liste();
    }

    // ---------- Karte 1413: Kennzahlen für die Kopfzeilen ----------

    public long getAnzahlOk() {
        return zeilen.stream().filter(z -> "ok".equals(z.getAmpel())).count();
    }

    public long getAnzahlEingeschraenkt() {
        return zeilen.stream().filter(z -> "eingeschraenkt".equals(z.getAmpel())).count();
    }

    /** Fehlerampel oder nicht erreichbar. */
    public long getAnzahlFehler() {
        return zeilen.stream().filter(z -> AMPEL_FEHLER.equals(z.getAmpel()) || AMPEL_AUS.equals(z.getAmpel())).count();
    }

    public long getAnzahlTokenFehlt() {
        return zeilen.stream().filter(Zeile::isTokenFehlt).count();
    }

    public long getAnzahlAblagenOk() {
        return speicherAblagen.stream().filter(a -> Boolean.TRUE.equals(a.getOk())).count();
    }

    public long getAnzahlAblagenFehler() {
        return speicherAblagen.stream().filter(a -> Boolean.FALSE.equals(a.getOk())).count();
    }

    /** @return Zeitpunkt als {@code dd.MM.yyyy HH:mm} (Europe/Zurich), leer bei {@code null} */
    public String zeit(Instant i) {
        return i == null ? "" : ZEIT.format(i);
    }

    /** Leert den Dialog «Sidecar ergänzen» vor dem Öffnen. */
    public void ergaenzenVorbereiten() {
        neueUrl = null;
    }

    /** Bricht eine Aktion aus einem Dialog ab, ohne ihn zu schliessen (oncomplete prüft validationFailed). */
    private static void dialogOffenLassen() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null) {
            fc.validationFailed();
        }
    }

    // ---------- Karte 1406: Speicher-Ablagen ----------

    public void ablageBearbeiten(String name) {
        SpeicherAblage a = ablagen.eintrag(name);
        abName = a.getName();
        abUrl = a.getUrl();
        abBenutzer = a.getBenutzer();
        abPfad = a.getPfad();
        abPasswort = null;
        ablageBestehend = true;
    }

    public void ablageNeu() {
        abName = null;
        abUrl = null;
        abBenutzer = null;
        abPasswort = null;
        abPfad = null;
        ablageBestehend = false;
    }

    public void ablageSpeichern() {
        try {
            SpeicherAblage a = ablagen.speichere(abName, abUrl, abBenutzer, abPasswort, abPfad);
            if (Boolean.TRUE.equals(a.getOk())) {
                FacesMessages.info(ABLAGE_ANFANG + a.getName() + "» gespeichert. " + a.getMeldung());
            } else {
                FacesMessages.warn(ABLAGE_ANFANG + a.getName() + "» gespeichert, aber nicht erreichbar: " + a.getMeldung());
            }
            ablageNeu();
        } catch (IllegalArgumentException e) {
            FacesMessages.error(e.getMessage());
            dialogOffenLassen();
        }
        abPasswort = null;
        laden();
    }

    public void ablagePruefen(String name) {
        try {
            SpeicherAblage a = ablagen.pruefe(ablagen.eintrag(name));
            if (Boolean.TRUE.equals(a.getOk())) {
                FacesMessages.info("«" + name + "»: " + a.getMeldung());
            } else {
                FacesMessages.warn("«" + name + "»: " + a.getMeldung());
            }
        } catch (NoSuchElementException e) {
            FacesMessages.error(e.getMessage());
        }
        laden();
    }

    public void ablageEntfernen(String name) {
        try {
            ablagen.entferne(name);
            FacesMessages.info(ABLAGE_ANFANG + name + "» entfernt. Die Dateien in der Nextcloud bleiben.");
        } catch (NoSuchElementException e) {
            FacesMessages.error(e.getMessage());
        }
        laden();
    }

    public void alleAbfragen() {
        service.aktualisiereAlle();
        laden();
        FacesMessages.info("Alle Sidecars neu abgefragt.");
    }

    public void abfragen(String name) {
        try {
            service.aktualisiere(service.sidecar(name));
        } catch (NoSuchElementException e) {
            FacesMessages.error(e.getMessage());
        }
        laden();
    }

    public void tokenSpeichern(String name) {
        String t = tokenEingabe.remove(name);
        if (t == null || t.isBlank()) {
            FacesMessages.warn("Bitte einen Token eingeben.");
            return;
        }
        meldeToken(name, service.setzeToken(name, t));
        laden();
    }

    public void tokenEntfernen(String name) {
        tokenEingabe.remove(name);
        service.setzeToken(name, null);
        FacesMessages.info("Token von «" + name + "» entfernt.");
        laden();
    }

    private static void meldeToken(String name, AuthZustand z) {
        switch (z) {
            case GUELTIG -> FacesMessages.info(TOKEN_ANFANG + name + "» hinterlegt und vom Sidecar bestätigt.");
            case UNGUELTIG -> FacesMessages.warn(TOKEN_ANFANG + name + "» hinterlegt, aber der Sidecar lehnt ihn ab.");
            case NICHT_NOETIG -> FacesMessages.info(TOKEN_ANFANG + name + "» hinterlegt; der Sidecar verlangt keinen.");
            default -> FacesMessages.warn(TOKEN_ANFANG + name + "» hinterlegt, Prüfung nicht möglich (Sidecar nicht erreichbar?).");
        }
    }

    public void registrieren() {
        try {
            Sidecar s = service.registriere(neueUrl);
            FacesMessages.info("Sidecar «" + s.getName() + "» ergänzt.");
            neueUrl = null;
        } catch (IllegalArgumentException e) {
            FacesMessages.error(e.getMessage());
            dialogOffenLassen();
        }
        laden();
    }

    public void entfernen(String name) {
        try {
            service.entferne(name);
            FacesMessages.info("Sidecar «" + name + "» entfernt.");
        } catch (IllegalArgumentException | NoSuchElementException e) {
            FacesMessages.error(e.getMessage());
        }
        laden();
    }

    /** @return Anzeigetext des Token-Zustands */
    public String authText(AuthZustand z) {
        return z == null ? "unbekannt" : switch (z) {
            case NICHT_NOETIG -> "kein Token nötig";
            case KEIN_TOKEN -> "kein Token hinterlegt";
            case GUELTIG -> "Token gültig";
            case UNGUELTIG -> "Token ungültig";
            case UNBEKANNT -> "nicht geprüft";
        };
    }
}
