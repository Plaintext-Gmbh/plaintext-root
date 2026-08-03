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
 */
@Component("menuBean")
@Scope("view")
@Slf4j
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
