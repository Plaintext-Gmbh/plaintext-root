/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.MenuModelBuilder;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.model.menu.MenuModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Backing bean for the programmatic menu.
 * Builds the PrimeFaces MenuModel from annotated menu items.
 * Uses view scope to rebuild menu on each page view, ensuring dynamic menu changes are reflected.
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>view-scoped and serializable</b>, and with server-side state saving the JSF
 * view state is held in the session and written out with it. On a deserialization <b>no
 * constructor runs</b>: a service field set only through the
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
@Component("menuBean")
@Scope("view")
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class MenuBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private MenuModelBuilder menuModelBuilder;

    @Getter
    private MenuModel model;

    @PostConstruct
    public void init() {
        log.debug("Initializing MenuBean - building menu model");
        model = menuModelBuilder.buildMenuModel();
        log.debug("MenuModel built with {} top-level elements", model.getElements().size());
    }

    /**
     * Rebuild the menu model (useful if menu items change dynamically)
     */
    public void rebuildMenu() {
        log.info("Rebuilding menu model");
        model = menuModelBuilder.buildMenuModel();
    }
}
