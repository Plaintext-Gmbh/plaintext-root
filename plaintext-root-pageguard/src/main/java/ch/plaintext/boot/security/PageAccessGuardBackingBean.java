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
 * JSF backing bean for the page access guard.
 * Invoked in template.xhtml via f:event preRenderView.
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
     * Invoked on every page request via f:event type="preRenderView".
     * Checks whether the current user has access to the page based on:
     * - roles (MenuItem.roles)
     * - tenants (MenuVisibilityProvider)
     *
     * @throws IOException if the redirect fails
     */
    public void checkPageAccess() throws IOException {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getViewRoot() == null) {
            log.trace("FacesContext or ViewRoot is null, skipping access check");
            return;
        }

        String viewId = context.getViewRoot().getViewId();

        // Check access
        boolean hasAccess = pageAccessGuardService.hasAccessToView(viewId);

        if (!hasAccess) {
            log.warn("SECURITY: User attempted to access restricted page: {}", viewId);
            pageAccessGuardService.redirectToAccessDenied();
        } else {
            log.trace("Access check passed for view: {}", viewId);
        }
    }
}
