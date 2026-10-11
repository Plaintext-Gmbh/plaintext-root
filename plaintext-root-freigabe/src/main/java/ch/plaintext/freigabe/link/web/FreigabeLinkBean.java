/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.freigabe.FreigabeRecht;
import ch.plaintext.freigabe.link.service.FreigabeLinkService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Java-Seite des Tags {@code pt:freigabeLinks} und der Seite „Freigabe-Links“ (Karte 1476). Alle Rechte prüft der
 * {@link FreigabeLinkService}; das Tag blendet nur aus. Die Adresse eines neuen Links steht nur bis zum nächsten
 * Anlegen oder {@link #verwerfeUrl()} hier.
 */
@Component("freigabeLinkBean")
@Scope("session")
@SuppressWarnings("java:S6813") // Feldinjektion mit transient: Session-Bean muss serialisierbar bleiben
public class FreigabeLinkBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Autowired
    private transient FreigabeLinkService service;

    @Getter
    @Setter
    private String recht = FreigabeRecht.LESEN.kurz();
    @Getter
    @Setter
    private Integer tage = FreigabeLinkService.STANDARD_TAGE;
    @Getter
    @Setter
    private String zweck;
    @Getter
    private String neueUrl;

    /** @param typ {@code null}/leer = alle Typen; @param objektId {@code null} = alle Objekte */
    public List<FreigabeLinkService.Link> liste(String typ, Long objektId) {
        return service.liste(typ == null || typ.isBlank() ? null : typ, objektId);
    }

    public void erzeuge(String typ, Long objektId, String teil) {
        try {
            neueUrl = service.erzeuge(typ, objektId, teil, FreigabeRecht.von(recht), tage, zweck).url();
            zweck = null;
            FacesMessages.info("Link angelegt", "Die Adresse wird nur jetzt angezeigt.");
        } catch (NoSuchElementException | IllegalArgumentException e) {
            FacesMessages.error(FacesMessages.TITEL_FEHLER, e.getMessage());
        }
    }

    public void widerrufe(Long linkId) {
        try {
            service.widerrufe(linkId);
            FacesMessages.info("Link widerrufen");
        } catch (NoSuchElementException e) {
            FacesMessages.error(FacesMessages.TITEL_FEHLER, e.getMessage());
        }
    }

    public void verwerfeUrl() {
        neueUrl = null;
    }
}
