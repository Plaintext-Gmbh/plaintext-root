/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.jpa.model.EntityDescriptor;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Admin Entity Management Backing Bean
 * Allows administrators to manage entities for their own mandat.
 *
 * <p>The entire management logic lives in {@link AbstractEntityBackingBean}; only what sets the
 * Admin apart from the Root is here: only tenant-capable entities, only the own tenant, and the
 * tenant is enforced server-side when saving.
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Component
@Named("adminEntityBackingBean")
@Slf4j
@Scope(scopeName = "session")
@MenuAnnotation(
    title = "Datenverwaltung",
    link = "adminentities.html",
    parent = "Admin",
    order = 100,
    icon = "pi pi-database",
    roles = {"ADMIN", "ROOT"}
)
public class AdminEntityBackingBean extends AbstractEntityBackingBean {
    private static final long serialVersionUID = 1L;

    @Override
    protected List<EntityDescriptor> ladeVerfuegbareEntities() {
        log.info("Loading mandat-aware entities for Admin");
        return registryService.getMandatAwareEntities();
    }

    @Override
    protected List<?> ladeEntities(String entityName) {
        String mandat = getMandat();
        log.info("Loading entities for {} and mandat {}", entityName, mandat);
        return entityService.findByMandat(entityName, mandat);
    }

    /**
     * SECURITY (Karte 307, MEDIUM): a non-ROOT admin may save records ONLY within their OWN tenant.
     * The tenant is forced server-side to the admin's own one — regardless of what the dropdown
     * (filled from getAllMandate) delivered. That way nobody can write into a foreign tenant. Only
     * ROOT may set the tenant freely.
     */
    @Override
    protected void vorSpeichern(Object entity) {
        if (getSelectedEntityType().isHasMandatField() && !plaintextSecurity.ifGranted("ROLE_root")) {
            entityService.setFieldValue(entity, "mandat", getMandat());
            log.debug("Non-root: mandat serverseitig auf {} erzwungen", getMandat());
        }
    }
}
