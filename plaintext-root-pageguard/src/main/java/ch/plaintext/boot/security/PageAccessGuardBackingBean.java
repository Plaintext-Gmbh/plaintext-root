/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

/**
 * JSF Backing Bean für Page Access Guard.
 * Wird in template.xhtml via f:event preRenderView aufgerufen.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@Named
@RequestScoped
@Slf4j
public class PageAccessGuardBackingBean {

    @Autowired
    private PageAccessGuardService pageAccessGuardService;

    /**
     * Wird bei jedem Seitenaufruf über f:event type="preRenderView" aufgerufen.
     * Prüft ob der aktuelle Benutzer Zugriff auf die Seite hat basierend auf:
     * - Rollen (MenuItem.roles)
     * - Mandate (MenuVisibilityProvider)
     *
     * @throws IOException wenn Redirect fehlschlägt
     */
    public void checkPageAccess() throws IOException {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getViewRoot() == null) {
            log.trace("FacesContext or ViewRoot is null, skipping access check");
            return;
        }

        String viewId = context.getViewRoot().getViewId();

        // Prüfe Zugriff
        boolean hasAccess = pageAccessGuardService.hasAccessToView(viewId);

        if (!hasAccess) {
            log.warn("SECURITY: User attempted to access restricted page: {}", viewId);
            pageAccessGuardService.redirectToAccessDenied();
        } else {
            log.trace("Access check passed for view: {}", viewId);
        }
    }
}
