/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UseExistingIdOtherwiseGenerateUsingIdentityTest {

    @Mock
    private SharedSessionContractImplementor session;

    @Mock
    private EntityPersister persister;

    private UseExistingIdOtherwiseGenerateUsingIdentity generator;

    private RepoMaster savedInstance;

    @BeforeEach
    void setUp() {
        generator = new UseExistingIdOtherwiseGenerateUsingIdentity();
        // Save the current static instance so we can restore it
        savedInstance = RepoMaster.instance;
    }

    @AfterEach
    void tearDown() {
        // Restore the original RepoMaster.instance
        RepoMaster.instance = savedInstance;
    }

    @Test
    void generate_returnsExistingId_whenEntityAlreadyHasId() {
        Object entity = new Object();
        when(session.getEntityPersister(null, entity)).thenReturn(persister);
        when(persister.getIdentifier(entity, session)).thenReturn(99L);

        Serializable result = generator.generate(session, entity);

        assertEquals(99L, result);
    }

    @Test
    void generate_returnsIncrementedIdb_whenRepoMasterInstanceIsNull() {
        RepoMaster.instance = null;

        Object entity = new Object();
        when(session.getEntityPersister(null, entity)).thenReturn(persister);
        when(persister.getIdentifier(entity, session)).thenReturn(null);

        Serializable result = generator.generate(session, entity);

        // idb starts at 1000000L, first call increments to 1000001L
        assertEquals(1000001L, result);
    }

    @Test
    void generate_incrementsIdbOnEachCall_whenRepoMasterInstanceIsNull() {
        RepoMaster.instance = null;

        Object entity = new Object();
        when(session.getEntityPersister(null, entity)).thenReturn(persister);
        when(persister.getIdentifier(entity, session)).thenReturn(null);

        Serializable first = generator.generate(session, entity);
        Serializable second = generator.generate(session, entity);
        Serializable third = generator.generate(session, entity);

        assertEquals(1000001L, first);
        assertEquals(1000002L, second);
        assertEquals(1000003L, third);
    }

    @Test
    void generate_delegatesToRepoMaster_whenInstanceIsAvailable() {
        RepoMaster repoMaster = mock(RepoMaster.class);
        RepoMaster.instance = repoMaster;

        Object entity = new Object();
        when(session.getEntityPersister(null, entity)).thenReturn(persister);
        when(persister.getIdentifier(entity, session)).thenReturn(null);
        when(repoMaster.getNextID(entity)).thenReturn(42L);

        Serializable result = generator.generate(session, entity);

        assertEquals(42L, result);
        verify(repoMaster).getNextID(entity);
    }

    @Test
    void generate_threadSafety_doesNotThrowUnderParallelCalls() throws Exception {
        RepoMaster.instance = null;

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Serializable>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                Object entity = new Object();
                SharedSessionContractImplementor localSession = mock(SharedSessionContractImplementor.class);
                EntityPersister localPersister = mock(EntityPersister.class);
                when(localSession.getEntityPersister(null, entity)).thenReturn(localPersister);
                when(localPersister.getIdentifier(entity, localSession)).thenReturn(null);

                return generator.generate(localSession, entity);
            }));
        }

        executor.shutdown();

        // Collect all generated IDs - they should all be unique and non-null
        List<Serializable> ids = new ArrayList<>();
        for (Future<Serializable> future : futures) {
            Serializable id = future.get();
            assertNotNull(id);
            ids.add(id);
        }

        // All IDs should be unique (synchronized block ensures no duplicates)
        assertEquals(threadCount, ids.stream().distinct().count(),
                "All generated IDs should be unique");
    }

    @Test
    void generate_returnsExistingId_evenWhenRepoMasterIsAvailable() {
        // Even with RepoMaster available, existing ID should take precedence
        RepoMaster repoMaster = mock(RepoMaster.class);
        RepoMaster.instance = repoMaster;

        Object entity = new Object();
        when(session.getEntityPersister(null, entity)).thenReturn(persister);
        when(persister.getIdentifier(entity, session)).thenReturn(777L);

        Serializable result = generator.generate(session, entity);

        assertEquals(777L, result);
        // RepoMaster should NOT be consulted when entity already has an ID
        verify(repoMaster, never()).getNextID(any());
    }
}
