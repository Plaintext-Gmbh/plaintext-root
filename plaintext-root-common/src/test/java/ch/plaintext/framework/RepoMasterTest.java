/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoMasterTest {

    private RepoMaster repoMaster;
    private Map<String, PlaintextRepository> repoMap;

    @BeforeEach
    void setUp() throws Exception {
        repoMaster = new RepoMaster();
        repoMap = new HashMap<>();

        // Inject the map via reflection
        java.lang.reflect.Field mapField = RepoMaster.class.getDeclaredField("map");
        mapField.setAccessible(true);
        mapField.set(repoMaster, repoMap);

        RepoMaster.instance = repoMaster;
    }

    // -------------------------------------------------------------------------
    // getRepo
    // -------------------------------------------------------------------------

    @Test
    void getRepo_existingType_returnsRepo() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        repoMap.put("sampleentity", mockRepo);

        assertSame(mockRepo, repoMaster.getRepo("SampleEntity"));
    }

    @Test
    void getRepo_caseInsensitive() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        repoMap.put("sampleentity", mockRepo);

        assertSame(mockRepo, repoMaster.getRepo("SAMPLEENTITY"));
    }

    @Test
    void getRepo_unknownType_returnsNull() {
        assertNull(repoMaster.getRepo("UnknownType"));
    }

    // -------------------------------------------------------------------------
    // getNextID
    // -------------------------------------------------------------------------

    @Test
    void getNextID_repoNotFound_returnsMinusOne() {
        long id = repoMaster.getNextID(new Object());
        assertEquals(-1, id);
    }

    @Test
    void getNextID_repoExists_maxIdNotNull_returnsMaxIdPlusOne() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        when(mockRepo.getMaxID()).thenReturn(10L);
        repoMap.put("sampleentity", mockRepo);

        SampleEntity obj = new SampleEntity();
        long id = repoMaster.getNextID(obj);
        assertEquals(11L, id);
    }

    @Test
    void getNextID_repoExists_maxIdNull_returnsOne() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        when(mockRepo.getMaxID()).thenReturn(null);
        repoMap.put("sampleentity", mockRepo);

        SampleEntity obj = new SampleEntity();
        long id = repoMaster.getNextID(obj);
        assertEquals(1L, id);
    }

    @Test
    void getNextID_repoExists_exceptionThrown_returnsOne() {
        PlaintextRepository mockRepo = mock(PlaintextRepository.class);
        when(mockRepo.getMaxID()).thenThrow(new RuntimeException("DB error"));
        repoMap.put("sampleentity", mockRepo);

        SampleEntity obj = new SampleEntity();
        long id = repoMaster.getNextID(obj);
        // Exception caught, id is null, so returns 1
        assertEquals(1L, id);
    }

    // -------------------------------------------------------------------------
    // Static instance
    // -------------------------------------------------------------------------

    @Test
    void instance_isSetCorrectly() {
        assertSame(repoMaster, RepoMaster.instance);
    }

    // -------------------------------------------------------------------------
    // Test-only fixture: a minimal SuperModel entity used to exercise the
    // RepoMaster registry (getRepo / getNextID). RepoMaster derives its lookup
    // key from getClass().getSimpleName().toLowerCase(), so the map key is
    // "sampleentity".
    // -------------------------------------------------------------------------
    static class SampleEntity extends SuperModel {
    }
}
