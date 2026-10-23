/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.files.service.Datei;
import ch.plaintext.files.service.FilesService;
import ch.plaintext.linktoken.LinkToken;
import lombok.Getter;
import lombok.Setter;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serial;
import java.io.Serializable;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;

/**
 * Seite {@code files/ablage.html} (Karte 1559): Hochladen, Liste, Löschen und der Dialog «Link teilen» - dasselbe
 * Formular wie im Inventar ({@code FilesLinkBean}): Gültigkeit, Zweck, erzeugen und kopieren, bestehende Links mit
 * Aufrufen. Nur Lesen - Files haben keinen Editor.
 *
 * <p>Abweichung vom Inventar: die Seite ist eine PrimeFaces-Tabelle statt des Ablage-Widgets {@code ablage.js}
 * (das liegt in den Anwendungen, nicht in root); die REST-Schnittstelle dafür bietet {@link FilesApiController}.</p>
 */
@Component("filesBean")
@Scope("session")
@SuppressWarnings("java:S6813") // Feldinjektion mit transient: Session-Bean muss serialisierbar bleiben
public class FilesBean implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Vorgabe: so lange, wie die Datei höchstens lebt. */
    static final int STANDARD_TAGE = 21;
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(FilesService.ZONE);

    @Autowired
    private transient FilesService filesService;
    @Autowired
    private transient FilesZugriff zugriff;

    @Getter
    private String datei;
    @Getter
    @Setter
    private int linkTage = STANDARD_TAGE;
    @Getter
    @Setter
    private String linkZweck;
    @Getter
    private String neueUrl;

    public List<Datei> getDateien() {
        String b = zugriff.benutzer();
        if (b == null) {
            return List.of();
        }
        try {
            return filesService.liste(b);
        } catch (RuntimeException e) {
            FacesMessages.error(FacesMessages.TITEL_FEHLER, e.getMessage());
            return List.of();
        }
    }

    public void hochladen(FileUploadEvent event) {
        UploadedFile f = event.getFile();
        String b = zugriff.benutzer();
        if (b == null || f == null) {
            FacesMessages.error("Kein Zugriff", "Bitte anmelden.");
            return;
        }
        try {
            String name = filesService.hochladen(b, f.getFileName(), f.getContent());
            FacesMessages.info("Hochgeladen", name + " ist abgelegt und läuft in " + filesService.frist().toDays() + " Tagen ab.");
        } catch (RuntimeException e) {
            FacesMessages.error("Nicht hochgeladen", e.getMessage());
        }
    }

    public void loeschen(String name) {
        String b = zugriff.benutzer();
        if (b == null) {
            return;
        }
        try {
            filesService.loeschen(b, name);
            FacesMessages.info("Gelöscht", name + " und ihre Links sind weg.");
        } catch (RuntimeException e) {
            FacesMessages.error(FacesMessages.TITEL_FEHLER, e.getMessage());
        }
    }

    /** Öffnet den Dialog für eine Datei. */
    public void dialogOeffnen(String name) {
        datei = name;
        linkZweck = null;
        linkTage = STANDARD_TAGE;
        neueUrl = null;
    }

    public List<LinkToken> getLinks() {
        String b = zugriff.benutzer();
        if (b == null || datei == null || datei.isBlank()) {
            return List.of();
        }
        try {
            return filesService.links(b, datei);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** @return die Adresse, wenn der Store das Token kennt; sonst {@code null} (nur beim Anlegen sichtbar) */
    public String url(LinkToken l) {
        return filesService.url(l.token());
    }

    public long getFristTage() {
        return filesService.frist().toDays();
    }

    public String zeit(TemporalAccessor t) {
        return t == null ? "" : ZEIT.format(t);
    }

    public String groesse(long bytes) {
        return FilesApiController.groesse(bytes);
    }

    public void linkErzeugen() {
        try {
            neueUrl = filesService.url(filesService.linkErstellen(zugriff.benutzer(), datei, linkTage, linkZweck).token());
            FacesMessages.info("Link angelegt", "Die Adresse wird nur jetzt angezeigt.");
        } catch (RuntimeException e) {
            FacesMessages.error("Kein Link erzeugt", e.getMessage());
        }
    }

    public void linkZurueckziehen(String id) {
        if (!filesService.linkWiderrufen(zugriff.benutzer(), id)) {
            FacesMessages.warn("Nicht gefunden", "Diesen Link gibt es nicht mehr.");
        }
    }
}
