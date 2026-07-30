/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for GenericEntityService - generic object storage service.
 */
@ExtendWith(MockitoExtension.class)
class GenericEntityServiceTest {

    @Mock
    private SimpleStorableEntityRepository repository;

    @InjectMocks
    private GenericEntityService<UserPreference> service;

    @Test
    void save_shouldCreateNewEntity_whenNotExists() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("user1");
        when(repository.findByUniqueId("user1")).thenReturn(null);

        service.save(pref);

        ArgumentCaptor<SimpleStorableEntity> captor = ArgumentCaptor.forClass(SimpleStorableEntity.class);
        verify(repository).save(captor.capture());

        SimpleStorableEntity savedEntity = captor.getValue();
        assertNotNull(savedEntity.getMyObject());
    }

    @Test
    void save_shouldUpdateExistingEntity_whenExists() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("user1");

        SimpleStorableEntity existingEntity = new SimpleStorableEntity();
        when(repository.findByUniqueId("user1")).thenReturn(existingEntity);

        service.save(pref);

        ArgumentCaptor<SimpleStorableEntity> captor = ArgumentCaptor.forClass(SimpleStorableEntity.class);
        verify(repository).save(captor.capture());

        assertSame(existingEntity, captor.getValue());
    }

    @Test
    void save_shouldHandleException() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("user1");
        when(repository.findByUniqueId("user1")).thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(() -> service.save(pref));
    }

    @Test
    void findByUniqueId_shouldReturnObject_whenExists() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("user1");
        pref.setDarkMode("dark");

        SimpleStorableEntity entity = new SimpleStorableEntity();
        entity.setMyObject(pref);
        when(repository.findByUniqueId("user1")).thenReturn(entity);

        UserPreference result = service.findByUniqueId("user1");

        assertNotNull(result);
        assertEquals("dark", result.getDarkMode());
    }

    @Test
    void findByUniqueId_shouldReturnNull_whenNotExists() {
        when(repository.findByUniqueId("nonexistent")).thenReturn(null);

        UserPreference result = service.findByUniqueId("nonexistent");

        assertNull(result);
    }

    @Test
    void deleteByUniqueId_shouldDeleteEntity() {
        SimpleStorableEntity entity = new SimpleStorableEntity();
        when(repository.findByUniqueId("user1")).thenReturn(entity);

        service.deleteByUniqueId("user1");

        verify(repository).delete(entity);
    }
}
