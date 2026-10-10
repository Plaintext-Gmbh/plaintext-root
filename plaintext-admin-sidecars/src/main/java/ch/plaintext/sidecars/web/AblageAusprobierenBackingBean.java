/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.web;

import ch.plaintext.PlaintextRoles;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.ablagen.DateiAblagenRegister;
import ch.plaintext.boot.ablage.AblageAuswahl;
import ch.plaintext.boot.ablage.AblageEinsatz;
import ch.plaintext.boot.plugins.jsf.FacesMessages;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Seite Root → Ablage ausprobieren (Karte 1440): {@code pt:dateiAblage} auf allen eingerichteten
 * Ablagen, Lesen und Schreiben nur für ROOT, unter {@code ablage-demo/<mandat>}. Nur ROOT, weil es in
 * app und schuetu ADMINs gibt, die nicht Betreiber sind (Review 1449); die Bean prüft das selbst,
 * nicht nur Menü und Page-Guard.
 */
@Component("ablageAusprobierenBean")
@Scope("session")
@SuppressWarnings("java:S6813") // Feldinjektion in Session-Beans wie SidecarsBackingBean
public class AblageAusprobierenBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    static final Set<String> ROLLEN = Set.of("ROOT");
    static final Set<String> TYPEN = Set.of("txt", "md", "csv", "json", "xml", "drawio", "svg", "png", "jpg", "pdf");
    private static final int VORSCHAU = 10_000;

    @Autowired(required = false)
    private transient DateiAblagenRegister register;
    @Autowired
    private transient PlaintextSecurity security;

    @Getter
    private AblageAuswahl ablage;
    private String mandat;
    @Getter
    @Setter
    private String dateiname = "notiz.txt";
    @Getter
    @Setter
    private String text = "";

    /** preRenderView; baut die Auswahl neu, wenn das Mandat gewechselt hat (läuft auch bei Ajax-Postbacks). */
    public void seitenaufruf() {
        nurRoot();
        String m = security.getMandat();
        if (ablage == null || !Objects.equals(m, mandat)) {
            mandat = m;
            ablage = new AblageAuswahl(new AblageEinsatz(
                    List.of(new AblageEinsatz.Freigabe(AblageEinsatz.ALLE, ROLLEN, ROLLEN)),
                    "ablage-demo/" + mandatsOrdner(m), TYPEN, 5L * 1024 * 1024));
        }
        ablage.init(register, security);
    }

    public void speichern() {
        nurRoot();
        try {
            ablage.speichere(dateiname == null ? "" : dateiname.strip(), (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            FacesMessages.info("«" + dateiname + "» gespeichert.");
        } catch (IOException | RuntimeException e) {
            FacesMessages.error("Speichern nicht möglich", e.getMessage());
        }
    }

    private void nurRoot() {
        if (!PlaintextRoles.isRoot(security)) {
            throw new SecurityException("Ablage ausprobieren ist nur für ROOT.");
        }
    }

    /** @return Anfang der geöffneten Datei als Text */
    public String getVorschau() {
        byte[] b = ablage == null ? null : ablage.getInhalt();
        if (b == null) {
            return "";
        }
        String s = new String(b, StandardCharsets.UTF_8);
        return s.length() > VORSCHAU ? s.substring(0, VORSCHAU) + "…" : s;
    }

    /** Mandat als Ordnername: klein, a-z0-9_-; musste etwas ersetzt werden, hängt ein kurzer Hash an (wie DrawioAblageZugang). */
    static String mandatsOrdner(String mandat) {
        String roh = mandat == null ? "" : mandat.strip();
        String m = roh.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (m.isEmpty() || !m.equals(roh)) {
            try {
                byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(roh.getBytes(StandardCharsets.UTF_8));
                m = (m.isEmpty() ? "standard" : m) + "-" + HexFormat.of().formatHex(h, 0, 3);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return m;
    }
}
