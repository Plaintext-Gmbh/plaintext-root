/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.jpa.model.EntityDescriptor;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityDescriptorConverterTest {

    private EntityDescriptorConverter converter;

    @Mock
    private FacesContext facesContext;

    @Mock
    private UIComponent component;

    @BeforeEach
    void setUp() {
        converter = new EntityDescriptorConverter();
    }

    @Test
    void getAsObject_returnsNullForNullValue() {
        EntityDescriptor result = converter.getAsObject(facesContext, component, null);
        assertNull(result);
    }

    @Test
    void getAsObject_returnsNullForEmptyValue() {
        EntityDescriptor result = converter.getAsObject(facesContext, component, "");
        assertNull(result);
    }

    @Test
    void getAsObject_findsEntityFromAdminBackingBean() {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityName("MyEntity");

        AdminEntityBackingBean adminBean = mock(AdminEntityBackingBean.class);
        when(adminBean.getAvailableEntities()).thenReturn(List.of(desc));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("backingBean", adminBean);
        when(component.getAttributes()).thenReturn(attributes);

        EntityDescriptor result = converter.getAsObject(facesContext, component, "MyEntity");
        assertNotNull(result);
        assertEquals("MyEntity", result.getEntityName());
    }

    @Test
    void getAsObject_returnsNullWhenEntityNotFoundInAdminBean() {
        AdminEntityBackingBean adminBean = mock(AdminEntityBackingBean.class);
        when(adminBean.getAvailableEntities()).thenReturn(List.of());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("backingBean", adminBean);
        when(component.getAttributes()).thenReturn(attributes);

        EntityDescriptor result = converter.getAsObject(facesContext, component, "NonExistent");
        assertNull(result);
    }

    @Test
    void getAsObject_findsEntityFromRootBackingBean() {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityName("RootEntity");

        RootEntityBackingBean rootBean = mock(RootEntityBackingBean.class);
        when(rootBean.getAvailableEntities()).thenReturn(List.of(desc));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("backingBean", rootBean);
        when(component.getAttributes()).thenReturn(attributes);

        EntityDescriptor result = converter.getAsObject(facesContext, component, "RootEntity");
        assertNotNull(result);
        assertEquals("RootEntity", result.getEntityName());
    }

    @Test
    void getAsObject_returnsNullWhenEntityNotFoundInRootBean() {
        RootEntityBackingBean rootBean = mock(RootEntityBackingBean.class);
        when(rootBean.getAvailableEntities()).thenReturn(List.of());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("backingBean", rootBean);
        when(component.getAttributes()).thenReturn(attributes);

        EntityDescriptor result = converter.getAsObject(facesContext, component, "NonExistent");
        assertNull(result);
    }

    @Test
    void getAsObject_returnsNullWhenBackingBeanNotRecognized() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("backingBean", "unknownType");
        when(component.getAttributes()).thenReturn(attributes);

        EntityDescriptor result = converter.getAsObject(facesContext, component, "SomeEntity");
        assertNull(result);
    }

    @Test
    void getAsObject_returnsNullWhenNoBackingBeanAttribute() {
        Map<String, Object> attributes = new HashMap<>();
        when(component.getAttributes()).thenReturn(attributes);

        EntityDescriptor result = converter.getAsObject(facesContext, component, "SomeEntity");
        assertNull(result);
    }

    @Test
    void getAsString_returnsEmptyForNull() {
        String result = converter.getAsString(facesContext, component, null);
        assertEquals("", result);
    }

    @Test
    void getAsString_returnsEntityName() {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityName("TestEntity");

        String result = converter.getAsString(facesContext, component, desc);
        assertEquals("TestEntity", result);
    }
}
