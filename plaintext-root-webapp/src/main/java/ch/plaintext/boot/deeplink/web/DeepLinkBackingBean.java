/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.deeplink.DeepLinkService;
import ch.plaintext.boot.deeplink.DeepLinkTarget;
import jakarta.faces.application.FacesMessage;
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
 * Backing bean of the root overview "deep links" (card 345).
 *
 * <p>Listed are the <b>registered targets</b>, not the links that were sent: the mechanism
 * deliberately persists nothing (a deep link is an address, not a token — it grants no
 * permissions, so there is nothing to administer or revoke either). The overview thereby answers
 * the question one really has while debugging: which module hangs under which
 * {@code type}, which view does it point to, and what does a link for it look like.
 *
 * <p>In addition a generator that builds the finished link for a target + tenant + id, ready to
 * copy. The generator is pure string work and <b>no</b> granting of permissions: whoever opens the
 * generated link runs through the same chain of checks as everybody else.
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>session-scoped and serializable</b> — the one case in which constructor
 * injection is the wrong answer. On a deserialization <b>no constructor runs</b>: a service
 * field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Slf4j
@Scope("session")
@Component
@Named
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
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

    /** All registered targets for the table. */
    public List<DeepLinkTarget> getZiele() {
        return deepLinkService.getTargets();
    }

    /** Type keys for the selection field of the generator. */
    public List<String> getTypen() {
        List<String> typen = new ArrayList<>();
        for (DeepLinkTarget ziel : getZiele()) {
            typen.add(ziel.getType());
        }
        return typen;
    }

    /**
     * Tenants for the selection field — deliberately only those of the logged-in user. For ROOT that
     * is all of them anyway; a selection field that offers more than the user is allowed to enter
     * would only be misleading.
     */
    public List<String> getMandate() {
        return new ArrayList<>(plaintextSecurity.getAllowedMandate());
    }

    /** URL pattern for the table column "link pattern". */
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
        FacesMessages.meldung(severity, titel, detail);
    }
}
