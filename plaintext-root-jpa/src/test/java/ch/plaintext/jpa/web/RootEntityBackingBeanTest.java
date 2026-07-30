/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.jpa.model.EntityDescriptor;
import ch.plaintext.jpa.model.FieldMetadata;
import ch.plaintext.jpa.service.EntityRegistryService;
import ch.plaintext.jpa.service.JpaEntityService;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RootEntityBackingBeanTest {

    @Mock
    private EntityRegistryService registryService;

    @Mock
    private JpaEntityService entityService;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @InjectMocks
    private RootEntityBackingBean bean;

    private FacesContext facesContext;
    private EntityDescriptor descriptor;
    private MockedStatic<FacesContext> facesContextMock;

    @BeforeEach
    void setUp() {
        facesContext = mock(FacesContext.class);
        facesContextMock = mockStatic(FacesContext.class);
        facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

        descriptor = new EntityDescriptor();
        descriptor.setEntityName("TestEntity");
        descriptor.setDisplayName("Test Entity");
        descriptor.setEntityClass(String.class);

        FieldMetadata idField = new FieldMetadata();
        idField.setFieldName("id");
        idField.setId(true);
        idField.setReadOnly(true);

        FieldMetadata nameField = new FieldMetadata();
        nameField.setFieldName("name");
        nameField.setFieldType(String.class);

        FieldMetadata mandatField = new FieldMetadata();
        mandatField.setFieldName("mandat");
        mandatField.setMandat(true);

        descriptor.setFields(List.of(idField, nameField, mandatField));
    }

    @AfterEach
    void tearDown() {
        facesContextMock.close();
    }

    @Test
    void loadAvailableEntities_loadsAllEntities() {
        List<EntityDescriptor> entities = List.of(descriptor);
        when(registryService.getAllEntities()).thenReturn(entities);

        bean.loadAvailableEntities();

        assertEquals(entities, bean.getAvailableEntities());
        verify(registryService).getAllEntities();
    }

    @Test
    void loadEntities_clearsWhenNoEntityTypeSelected() {
        bean.setSelectedEntityType(null);

        bean.loadEntities();

        assertTrue(bean.getEntities().isEmpty());
    }

    @Test
    void loadEntities_loadsAllEntitiesForSelectedType() {
        bean.setSelectedEntityType(descriptor);
        List<Object> expected = List.of("entity1", "entity2", "entity3");
        doReturn(expected).when(entityService).findAll("TestEntity");

        bean.loadEntities();

        assertEquals(expected, bean.getEntities());
    }

    @Test
    void loadEntities_handlesException() {
        bean.setSelectedEntityType(descriptor);
        when(entityService.findAll("TestEntity")).thenThrow(new RuntimeException("DB error"));

        bean.loadEntities();

        assertTrue(bean.getEntities().isEmpty());
    }

    @Test
    void newEntity_doesNothingWhenNoEntityTypeSelected() {
        bean.setSelectedEntityType(null);

        bean.newEntity();

        assertNull(bean.getSelectedEntity());
        assertFalse(bean.isEditMode());
    }

    @Test
    void newEntity_createsEntityAndEntersEditMode() throws Exception {
        bean.setSelectedEntityType(descriptor);
        Object newEntity = new Object();
        when(entityService.createNew("TestEntity")).thenReturn(newEntity);

        bean.newEntity();

        assertSame(newEntity, bean.getSelectedEntity());
        assertTrue(bean.isEditMode());
    }

    @Test
    void newEntity_handlesException() throws Exception {
        bean.setSelectedEntityType(descriptor);
        when(entityService.createNew("TestEntity")).thenThrow(new RuntimeException("Creation failed"));

        bean.newEntity();

        assertNull(bean.getSelectedEntity());
    }

    @Test
    void selectEntity_setsEditModeWhenEntitySelected() {
        Object entity = new Object();
        bean.setSelectedEntity(entity);
        bean.setSelectedEntityType(descriptor);

        bean.selectEntity();

        assertTrue(bean.isEditMode());
    }

    @Test
    void selectEntity_doesNothingWhenEntityNull() {
        bean.setSelectedEntity(null);

        bean.selectEntity();

        assertFalse(bean.isEditMode());
    }

    @Test
    void clearSelection_resetsState() {
        bean.setSelectedEntity(new Object());
        bean.setEditMode(true);
        bean.getFieldValues().put("key", "value");

        bean.clearSelection();

        assertNull(bean.getSelectedEntity());
        assertFalse(bean.isEditMode());
        assertTrue(bean.getFieldValues().isEmpty());
    }

    @Test
    void saveEntity_doesNothingWhenNoEntitySelected() {
        bean.setSelectedEntity(null);
        bean.setSelectedEntityType(null);

        bean.saveEntity();

        verify(entityService, never()).save(any(), any());
    }

    @Test
    void saveEntity_savesEntityWithFieldValues() {
        bean.setSelectedEntityType(descriptor);
        Object entity = new Object();
        bean.setSelectedEntity(entity);
        bean.getFieldValues().put("name", "newValue");
        bean.getFieldValues().put("mandat", "m1");

        when(entityService.save("TestEntity", entity)).thenReturn(entity);
        doReturn(new ArrayList<>()).when(entityService).findAll("TestEntity");

        bean.saveEntity();

        verify(entityService).setFieldValue(entity, "name", "newValue");
        verify(entityService).setFieldValue(entity, "mandat", "m1");
        verify(entityService).save("TestEntity", entity);
    }

    @Test
    void saveEntity_handlesException() {
        bean.setSelectedEntityType(descriptor);
        Object entity = new Object();
        bean.setSelectedEntity(entity);
        when(entityService.save(eq("TestEntity"), any())).thenThrow(new RuntimeException("Save failed"));

        bean.saveEntity();

        verify(facesContext).addMessage(isNull(), any());
    }

    @Test
    void deleteEntity_doesNothingWhenNoEntitySelected() {
        bean.setSelectedEntity(null);
        bean.setSelectedEntityType(null);

        bean.deleteEntity();

        verify(entityService, never()).delete(any(), any());
    }

    @Test
    void deleteEntity_deletesEntityById() {
        bean.setSelectedEntityType(descriptor);
        Object entity = new Object();
        bean.setSelectedEntity(entity);

        when(entityService.getFieldValue(entity, "id")).thenReturn(42L);
        doReturn(new ArrayList<>()).when(entityService).findAll("TestEntity");

        bean.deleteEntity();

        verify(entityService).delete("TestEntity", 42L);
    }

    @Test
    void deleteEntity_handlesNullId() {
        bean.setSelectedEntityType(descriptor);
        Object entity = new Object();
        bean.setSelectedEntity(entity);

        when(entityService.getFieldValue(entity, "id")).thenReturn(null);

        bean.deleteEntity();

        verify(entityService, never()).delete(any(), any());
    }

    @Test
    void deleteEntity_handlesException() {
        bean.setSelectedEntityType(descriptor);
        Object entity = new Object();
        bean.setSelectedEntity(entity);

        when(entityService.getFieldValue(entity, "id")).thenReturn(42L);
        doThrow(new RuntimeException("Delete failed")).when(entityService).delete("TestEntity", 42L);

        bean.deleteEntity();

        verify(facesContext).addMessage(isNull(), any());
    }

    @Test
    void getFieldValue_delegatesToService() {
        Object entity = new Object();
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("name");

        when(entityService.getFieldValueAsString(entity, field)).thenReturn("testValue");

        String result = bean.getFieldValue(entity, field);
        assertEquals("testValue", result);
    }

    @Test
    void getFieldValueForEdit_returnsNullWhenNoEntity() {
        bean.setSelectedEntity(null);
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("name");

        assertNull(bean.getFieldValueForEdit(field));
    }

    @Test
    void getFieldValueForEdit_delegatesToService() {
        Object entity = new Object();
        bean.setSelectedEntity(entity);
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("name");

        when(entityService.getFieldValue(entity, "name")).thenReturn("testValue");

        Object result = bean.getFieldValueForEdit(field);
        assertEquals("testValue", result);
    }

    @Test
    void setFieldValueForEdit_doesNothingWhenNoEntity() {
        bean.setSelectedEntity(null);
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("name");

        bean.setFieldValueForEdit(field, "value");

        verify(entityService, never()).setFieldValue(any(), any(), any());
    }

    @Test
    void setFieldValueForEdit_delegatesToService() {
        Object entity = new Object();
        bean.setSelectedEntity(entity);
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("name");

        bean.setFieldValueForEdit(field, "newValue");

        verify(entityService).setFieldValue(entity, "name", "newValue");
    }

    @Test
    void getDisplayFields_returnsEmptyWhenNoEntityType() {
        bean.setSelectedEntityType(null);

        List<FieldMetadata> result = bean.getDisplayFields();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getDisplayFields_delegatesToDescriptor() {
        bean.setSelectedEntityType(descriptor);

        List<FieldMetadata> result = bean.getDisplayFields();

        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    void getEditableFields_returnsEmptyWhenNoEntityType() {
        bean.setSelectedEntityType(null);

        List<FieldMetadata> result = bean.getEditableFields();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getEditableFields_delegatesToDescriptor() {
        bean.setSelectedEntityType(descriptor);

        List<FieldMetadata> result = bean.getEditableFields();

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    void getEntityId_returnsNullWhenEntityNull() {
        bean.setSelectedEntityType(descriptor);

        Object result = bean.getEntityId(null);

        assertNull(result);
    }

    @Test
    void getEntityId_returnsNullWhenNoEntityType() {
        bean.setSelectedEntityType(null);

        Object result = bean.getEntityId(new Object());

        assertNull(result);
    }

    @Test
    void getEntityId_delegatesToService() {
        bean.setSelectedEntityType(descriptor);
        Object entity = new Object();

        when(entityService.getFieldValue(entity, "id")).thenReturn(99L);

        Object result = bean.getEntityId(entity);
        assertEquals(99L, result);
    }

    @Test
    void exportEntities_doesNothingWhenNoEntityTypeSelected() {
        bean.setSelectedEntityType(null);

        bean.exportEntities();

        verify(entityService, never()).findAll(any());
    }

    @Test
    void exportEntities_handlesEmptyEntityList() {
        bean.setSelectedEntityType(descriptor);
        doReturn(new ArrayList<>()).when(entityService).findAll("TestEntity");

        bean.exportEntities();

        verify(facesContext).addMessage(isNull(), any());
    }

    @Test
    void importEntities_doesNothingWhenNoEntityTypeSelected() {
        bean.setSelectedEntityType(null);

        bean.importEntities();

        verify(facesContext).addMessage(isNull(), any());
    }

    @Test
    void importEntities_doesNothingWhenNoUploadedFile() {
        bean.setSelectedEntityType(descriptor);
        bean.setUploadedFile(null);

        bean.importEntities();

        verify(facesContext).addMessage(isNull(), any());
    }

    @Test
    void defaultState_hasEmptyCollections() {
        RootEntityBackingBean fresh = new RootEntityBackingBean();
        assertNotNull(fresh.getAvailableEntities());
        assertTrue(fresh.getAvailableEntities().isEmpty());
        assertNotNull(fresh.getEntities());
        assertTrue(fresh.getEntities().isEmpty());
        assertNotNull(fresh.getFieldValues());
        assertTrue(fresh.getFieldValues().isEmpty());
        assertNotNull(fresh.getAllMandate());
        assertTrue(fresh.getAllMandate().isEmpty());
        assertFalse(fresh.isEditMode());
        assertNull(fresh.getSelectedEntity());
        assertNull(fresh.getSelectedEntityType());
        assertNull(fresh.getUploadedFile());
        assertNull(fresh.getExportFile());
    }

    @Test
    void initThis_loadsEntitiesAndMandateAndInitializesMapper() {
        List<EntityDescriptor> entities = List.of(descriptor);
        when(registryService.getAllEntities()).thenReturn(entities);
        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("m1", "m2"));

        bean.initThis();

        assertEquals(entities, bean.getAvailableEntities());
        assertEquals(2, bean.getAllMandate().size());
        assertNotNull(bean.getObjectMapper());
    }
}
