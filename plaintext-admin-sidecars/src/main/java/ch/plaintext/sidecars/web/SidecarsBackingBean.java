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
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Seite Root → Sidecars (Karte 1400): alle bekannten Sidecars mit Status, Version, Teilen,
 * Fähigkeiten und Token-Zustand; Token hinterlegen, von Hand ergänzen, neu abfragen.
 *
 * <p>Ein Token wird nur geschrieben, nie angezeigt: das Eingabefeld ist nach dem Speichern leer.</p>
 */
@Component("sidecarsBean")
@Scope("session")
@SuppressWarnings("java:S6813") // Feldinjektion in Session-Beans wie SecretsBackingBean (Karte 1273)
public class SidecarsBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;
    static final String PROTOKOLL_DOKU = "https://github.com/Plaintext-Gmbh/plaintext-root/blob/master/docs/SIDECAR_PROTOKOLL.md";

    @Autowired
    private transient SidecarService service;
    @Autowired
    private transient SpeicherAblageService ablagen;

    /** Eine Zeile der Übersicht. */
    public record Zeile(Sidecar sidecar, SidecarBeschreibung beschreibung) {

        public List<SidecarBeschreibung.Faehigkeit> getFaehigkeiten() {
            return beschreibung == null ? List.of() : beschreibung.faehigkeiten();
        }

        public List<SidecarBeschreibung.Teil> getTeile() {
            return beschreibung == null ? List.of() : beschreibung.teile();
        }

        /** @return {@code ok}, {@code eingeschraenkt}, {@code fehler} oder {@code aus} (nicht erreichbar) */
        public String getAmpel() {
            return !sidecar.isErreichbar() ? "aus" : sidecar.getStatus();
        }

        public boolean isTokenNoetig() {
            return beschreibung == null || !beschreibung.ohneAuth();
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

    // ---------- Karte 1406: Speicher-Ablagen ----------

    public void ablageBearbeiten(String name) {
        SpeicherAblage a = ablagen.eintrag(name);
        abName = a.getName();
        abUrl = a.getUrl();
        abBenutzer = a.getBenutzer();
        abPfad = a.getPfad();
        abPasswort = null;
    }

    public void ablageNeu() {
        abName = null;
        abUrl = null;
        abBenutzer = null;
        abPasswort = null;
        abPfad = null;
    }

    public void ablageSpeichern() {
        try {
            SpeicherAblage a = ablagen.speichere(abName, abUrl, abBenutzer, abPasswort, abPfad);
            if (Boolean.TRUE.equals(a.getOk())) {
                FacesMessages.info("Ablage «" + a.getName() + "» gespeichert. " + a.getMeldung());
            } else {
                FacesMessages.warn("Ablage «" + a.getName() + "» gespeichert, aber nicht erreichbar: " + a.getMeldung());
            }
            ablageNeu();
        } catch (IllegalArgumentException e) {
            FacesMessages.error(e.getMessage());
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
            FacesMessages.info("Ablage «" + name + "» entfernt. Die Dateien in der Nextcloud bleiben.");
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
            case GUELTIG -> FacesMessages.info("Token für «" + name + "» hinterlegt und vom Sidecar bestätigt.");
            case UNGUELTIG -> FacesMessages.warn("Token für «" + name + "» hinterlegt, aber der Sidecar lehnt ihn ab.");
            case NICHT_NOETIG -> FacesMessages.info("Token für «" + name + "» hinterlegt; der Sidecar verlangt keinen.");
            default -> FacesMessages.warn("Token für «" + name + "» hinterlegt, Prüfung nicht möglich (Sidecar nicht erreichbar?).");
        }
    }

    public void registrieren() {
        try {
            Sidecar s = service.registriere(neueUrl);
            FacesMessages.info("Sidecar «" + s.getName() + "» ergänzt.");
            neueUrl = null;
        } catch (IllegalArgumentException e) {
            FacesMessages.error(e.getMessage());
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
