/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.deeplink.DeepLinkService;
import ch.plaintext.boot.deeplink.DeepLinkTarget;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing Bean der Root-Uebersicht „Deep-Links" (Karte 345).
 *
 * <p>Gelistet werden die <b>registrierten Ziele</b>, nicht die verschickten Links: der Mechanismus
 * persistiert bewusst nichts (ein Deep-Link ist eine Adresse, kein Token — er verleiht keine
 * Rechte, also gibt es auch nichts zu verwalten oder zu widerrufen). Die Uebersicht beantwortet
 * damit die Frage, die man beim Debuggen wirklich hat: welches Modul haengt unter welchem
 * {@code type}, auf welche View zeigt es, und wie sieht ein Link dafuer aus.
 *
 * <p>Dazu ein Generator, der zu einem Ziel + Mandat + Id den fertigen Link zum Kopieren baut.
 * Der Generator ist reine Zeichenkettenarbeit und <b>keine</b> Rechtevergabe: wer den erzeugten
 * Link oeffnet, durchlaeuft dieselbe Pruefkette wie jeder andere.
 */
@Slf4j
@Scope("session")
@Component
@Named
public class DeepLinkBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Autowired
    private DeepLinkService deepLinkService;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Getter
    @Setter
    private String gewaehlterTyp;

    @Getter
    @Setter
    private String beispielMandat;

    @Getter
    @Setter
    private String beispielId = "1";

    @Getter
    private String erzeugterLink;

    /** Alle registrierten Ziele fuer die Tabelle. */
    public List<DeepLinkTarget> getZiele() {
        return deepLinkService.getTargets();
    }

    /** Typ-Schluessel fuer das Auswahlfeld des Generators. */
    public List<String> getTypen() {
        List<String> typen = new ArrayList<>();
        for (DeepLinkTarget ziel : getZiele()) {
            typen.add(ziel.getType());
        }
        return typen;
    }

    /**
     * Mandate fuer das Auswahlfeld — bewusst nur die des angemeldeten Benutzers. Bei ROOT sind das
     * ohnehin alle; ein Auswahlfeld, das mehr anbietet als der Benutzer betreten darf, waere nur
     * irrefuehrend.
     */
    public List<String> getMandate() {
        return new ArrayList<>(plaintextSecurity.getAllowedMandate());
    }

    /** URL-Muster fuer die Tabellenspalte „Link-Muster". */
    public String musterFuer(DeepLinkTarget ziel) {
        return DeepLinkService.DEEPLINK_PATH + "?type=" + ziel.getType()
                + "&mandat=<mandat>&id=<" + ziel.getParamName() + ">";
    }

    public void erzeugeLink() {
        erzeugterLink = null;
        try {
            erzeugterLink = deepLinkService.buildAbsoluteLink(gewaehlterTyp, beispielMandat, beispielId);
        } catch (IllegalArgumentException e) {
            meldung(FacesMessage.SEVERITY_WARN, "Link konnte nicht erzeugt werden", e.getMessage());
        }
    }

    private void meldung(FacesMessage.Severity severity, String titel, String detail) {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context != null) {
            context.addMessage(null, new FacesMessage(severity, titel, detail));
        }
    }
}
