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
 * <p>Die gesamte Verwaltungslogik steht in {@link AbstractEntityBackingBean}; hier nur, was den
 * Admin vom Root unterscheidet: nur mandantenfaehige Entitaeten, nur der eigene Mandant, und der
 * Mandant wird beim Speichern serverseitig erzwungen.
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
     * SECURITY (Karte 307, MITTEL): ein Nicht-ROOT-Admin darf Datensaetze NUR im EIGENEN Mandanten
     * speichern. Der Mandant wird serverseitig auf den eigenen erzwungen — unabhaengig davon, was
     * das (aus getAllMandate befuellte) Dropdown geliefert hat. So kann niemand in fremde
     * Mandanten schreiben. Nur ROOT darf den Mandanten frei setzen.
     */
    @Override
    protected void vorSpeichern(Object entity) {
        if (getSelectedEntityType().isHasMandatField() && !plaintextSecurity.ifGranted("ROLE_root")) {
            entityService.setFieldValue(entity, "mandat", getMandat());
            log.debug("Non-root: mandat serverseitig auf {} erzwungen", getMandat());
        }
    }
}
