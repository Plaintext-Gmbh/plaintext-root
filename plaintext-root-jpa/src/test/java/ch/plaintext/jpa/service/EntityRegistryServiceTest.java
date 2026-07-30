/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.service;

import ch.plaintext.jpa.model.EntityDescriptor;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityRegistryServiceTest {

    @Mock
    private EntityMetadataService metadataService;

    @Mock
    private ApplicationContext applicationContext;

    private EntityRegistryService service;

    @Entity
    public static class SampleEntity {
        @Id
        private Long id;
        private String name;
        private String mandat;
    }

    @Entity
    public static class AnotherEntity {
        @Id
        private Long id;
        private String title;
    }

    public interface SampleRepository extends JpaRepository<SampleEntity, Long> {}
    public interface AnotherRepository extends JpaRepository<AnotherEntity, Long> {}

    @BeforeEach
    void setUp() {
        service = new EntityRegistryService(metadataService, applicationContext);
    }

    @Test
    void getAllEntities_returnsEmptyWhenNoEntitiesRegistered() {
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(Map.of());
        service.init();

        List<EntityDescriptor> result = service.getAllEntities();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getEntity_returnsNullForUnknownEntity() {
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(Map.of());
        service.init();

        EntityDescriptor result = service.getEntity("NonExistent");
        assertNull(result);
    }

    @Test
    void getRepository_returnsNullForUnknownEntity() {
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(Map.of());
        service.init();

        Object result = service.getRepository("NonExistent");
        assertNull(result);
    }

    @Test
    void getMandatAwareEntities_filtersCorrectly() {
        EntityDescriptor withMandat = new EntityDescriptor();
        withMandat.setEntityName("WithMandat");
        withMandat.setHasMandatField(true);
        withMandat.setEntityClass(SampleEntity.class);

        EntityDescriptor withoutMandat = new EntityDescriptor();
        withoutMandat.setEntityName("WithoutMandat");
        withoutMandat.setHasMandatField(false);
        withoutMandat.setEntityClass(AnotherEntity.class);

        // Manually register by calling init with mock repos
        SampleRepository sampleRepo = mock(SampleRepository.class);
        AnotherRepository anotherRepo = mock(AnotherRepository.class);

        Map<String, JpaRepository> repos = new LinkedHashMap<>();
        repos.put("sampleRepository", sampleRepo);
        repos.put("anotherRepository", anotherRepo);
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(repos);
        when(metadataService.analyzeEntity(SampleEntity.class)).thenReturn(withMandat);
        when(metadataService.analyzeEntity(AnotherEntity.class)).thenReturn(withoutMandat);

        service.init();

        List<EntityDescriptor> mandatEntities = service.getMandatAwareEntities();
        assertEquals(1, mandatEntities.size());
        assertEquals("WithMandat", mandatEntities.get(0).getEntityName());
    }

    @Test
    void getAllEntities_returnsAllRegistered() {
        EntityDescriptor desc1 = new EntityDescriptor();
        desc1.setEntityName("SampleEntity");
        desc1.setEntityClass(SampleEntity.class);

        EntityDescriptor desc2 = new EntityDescriptor();
        desc2.setEntityName("AnotherEntity");
        desc2.setEntityClass(AnotherEntity.class);

        SampleRepository sampleRepo = mock(SampleRepository.class);
        AnotherRepository anotherRepo = mock(AnotherRepository.class);

        Map<String, JpaRepository> repos = new LinkedHashMap<>();
        repos.put("sampleRepository", sampleRepo);
        repos.put("anotherRepository", anotherRepo);
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(repos);
        when(metadataService.analyzeEntity(SampleEntity.class)).thenReturn(desc1);
        when(metadataService.analyzeEntity(AnotherEntity.class)).thenReturn(desc2);

        service.init();

        List<EntityDescriptor> all = service.getAllEntities();
        assertEquals(2, all.size());
    }

    @Test
    void getEntity_returnsRegisteredEntity() {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityName("SampleEntity");
        desc.setEntityClass(SampleEntity.class);

        SampleRepository sampleRepo = mock(SampleRepository.class);
        Map<String, JpaRepository> repos = new LinkedHashMap<>();
        repos.put("sampleRepository", sampleRepo);
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(repos);
        when(metadataService.analyzeEntity(SampleEntity.class)).thenReturn(desc);

        service.init();

        EntityDescriptor result = service.getEntity("SampleEntity");
        assertNotNull(result);
        assertEquals("SampleEntity", result.getEntityName());
    }

    @Test
    void getRepository_returnsRegisteredRepository() {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityName("SampleEntity");
        desc.setEntityClass(SampleEntity.class);

        SampleRepository sampleRepo = mock(SampleRepository.class);
        Map<String, JpaRepository> repos = new LinkedHashMap<>();
        repos.put("sampleRepository", sampleRepo);
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(repos);
        when(metadataService.analyzeEntity(SampleEntity.class)).thenReturn(desc);

        service.init();

        Object result = service.getRepository("SampleEntity");
        assertNotNull(result);
        assertSame(sampleRepo, result);
    }

    @Test
    void init_handlesAnalyzeEntityException() {
        SampleRepository sampleRepo = mock(SampleRepository.class);
        Map<String, JpaRepository> repos = new LinkedHashMap<>();
        repos.put("sampleRepository", sampleRepo);
        when(applicationContext.getBeansOfType(JpaRepository.class)).thenReturn(repos);
        when(metadataService.analyzeEntity(SampleEntity.class)).thenThrow(new RuntimeException("analysis failed"));

        // Should not throw
        assertDoesNotThrow(() -> service.init());

        // Entity should not be registered
        assertNull(service.getEntity("SampleEntity"));
    }
}
