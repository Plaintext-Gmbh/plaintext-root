/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.jpa.model.EntityDescriptor;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;

/**
 * JSF Converter for EntityDescriptor objects. The list of selectable types comes from the bean
 * that the {@code p:selectOneMenu} passes along via {@code <f:attribute name="backingBean">} —
 * Root or Admin, both are an {@link AbstractEntityBackingBean}.
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@FacesConverter("entityDescriptorConverter")
public class EntityDescriptorConverter implements Converter<EntityDescriptor> {

    @Override
    public EntityDescriptor getAsObject(FacesContext context, UIComponent component, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // Get the list of available entities from the backing bean
        Object backingBean = component.getAttributes().get("backingBean");
        if (backingBean instanceof AbstractEntityBackingBean bean) {
            return bean.getAvailableEntities().stream()
                    .filter(e -> e.getEntityName().equals(value))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    @Override
    public String getAsString(FacesContext context, UIComponent component, EntityDescriptor value) {
        if (value == null) {
            return "";
        }
        return value.getEntityName();
    }
}
