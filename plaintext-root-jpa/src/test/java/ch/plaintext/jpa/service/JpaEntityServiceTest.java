/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.jpa.model.EntityDescriptor;
import ch.plaintext.jpa.model.FieldMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaEntityServiceTest {

    @Mock
    private EntityRegistryService registryService;

    @Mock
    private PlaintextSecurity security;

    @Mock
    private JpaRepository<Object, Long> repository;

    @InjectMocks
    private JpaEntityService service;

    @BeforeEach
    void setUp() {
        lenient().when(registryService.getRepository("TestEntity")).thenReturn(repository);
    }

    @Test
    void findAll_delegatesToRepository() {
        List<Object> expected = List.of("a", "b");
        when(repository.findAll()).thenReturn(expected);

        List<?> result = service.findAll("TestEntity");
        assertEquals(expected, result);
    }

    @Test
    void findById_returnsEntity() {
        Object entity = new Object();
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        Object result = service.findById("TestEntity", 1L);
        assertSame(entity, result);
    }

    @Test
    void findById_returnsNull_whenNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        Object result = service.findById("TestEntity", 999L);
        assertNull(result);
    }

    @Test
    void save_delegatesToRepository() {
        Object entity = new Object();
        when(repository.save(entity)).thenReturn(entity);

        Object result = service.save("TestEntity", entity);
        assertSame(entity, result);
        verify(repository).save(entity);
    }

    @Test
    void delete_delegatesToRepository() {
        service.delete("TestEntity", 1L);
        verify(repository).deleteById(1L);
    }

    @Test
    void getRepository_throwsForNull() {
        when(registryService.getRepository("Unknown")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.findAll("Unknown"));
    }

    @Test
    void getRepository_throwsForNonJpaRepository() {
        when(registryService.getRepository("BadRepo")).thenReturn("not a repo");

        assertThrows(IllegalArgumentException.class,
                () -> service.findAll("BadRepo"));
    }

    @Test
    void getFieldValue_returnsFieldValue() {
        TestPojo pojo = new TestPojo();
        pojo.name = "hello";

        Object result = service.getFieldValue(pojo, "name");
        assertEquals("hello", result);
    }

    @Test
    void getFieldValue_returnsNull_forUnknownField() {
        TestPojo pojo = new TestPojo();
        Object result = service.getFieldValue(pojo, "nonexistent");
        assertNull(result);
    }

    @Test
    void setFieldValue_setsFieldValue() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "name", "world");
        assertEquals("world", pojo.name);
    }

    @Test
    void setFieldValue_convertsStringToLong() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "count", "42");
        assertEquals(42L, pojo.count);
    }

    @Test
    void setFieldValue_convertsStringToInteger() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "intValue", "7");
        assertEquals(7, pojo.intValue);
    }

    @Test
    void setFieldValue_convertsStringToBoolean() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "active", "true");
        assertTrue(pojo.active);
    }

    @Test
    void setFieldValue_convertsStringToDouble() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "price", "3.14");
        assertEquals(3.14, pojo.price, 0.001);
    }

    @Test
    void setFieldValue_convertsStringToFloat() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "ratio", "1.5");
        assertEquals(1.5f, pojo.ratio, 0.001f);
    }

    @Test
    void setFieldValue_emptyString_setsNull() {
        TestPojo pojo = new TestPojo();
        pojo.count = 42L;
        service.setFieldValue(pojo, "count", "");
        assertNull(pojo.count);
    }

    @Test
    void setFieldValue_nullValue_setsNull() {
        TestPojo pojo = new TestPojo();
        pojo.name = "old";
        service.setFieldValue(pojo, "name", null);
        assertNull(pojo.name);
    }

    @Test
    void getFieldValueAsString_nullValue_returnsEmptyString() {
        TestPojo pojo = new TestPojo();
        pojo.name = null;

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("name");

        String result = service.getFieldValueAsString(pojo, fm);
        assertEquals("", result);
    }

    @Test
    void getFieldValueAsString_stringValue_returnsToString() {
        TestPojo pojo = new TestPojo();
        pojo.name = "hello";

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("name");

        String result = service.getFieldValueAsString(pojo, fm);
        assertEquals("hello", result);
    }

    @Test
    void getFieldValueAsString_dateValue_formatsCorrectly() {
        TestPojo pojo = new TestPojo();
        pojo.dateField = new Date(1704067200000L); // 2024-01-01 in UTC

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("dateField");

        String result = service.getFieldValueAsString(pojo, fm);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void getFieldValueAsString_localDateTime_formatsCorrectly() {
        TestPojo pojo = new TestPojo();
        pojo.localDateTimeField = LocalDateTime.of(2024, 3, 15, 10, 30);

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("localDateTimeField");

        String result = service.getFieldValueAsString(pojo, fm);
        assertEquals("15.03.2024 10:30", result);
    }

    @Test
    void getFieldValueAsString_localDate_formatsCorrectly() {
        TestPojo pojo = new TestPojo();
        pojo.localDateField = LocalDate.of(2024, 6, 25);

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("localDateField");

        String result = service.getFieldValueAsString(pojo, fm);
        assertEquals("25.06.2024", result);
    }

    @Test
    void createNew_createsInstanceAndSetsMandatIfApplicable() throws Exception {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityClass(TestPojo.class);
        desc.setHasMandatField(true);

        when(registryService.getEntity("TestPojo")).thenReturn(desc);
        when(security.getMandat()).thenReturn("test-mandat");

        Object result = service.createNew("TestPojo");
        assertNotNull(result);
        assertInstanceOf(TestPojo.class, result);
        assertEquals("test-mandat", ((TestPojo) result).mandat);
    }

    @Test
    void createNew_skipsMandat_whenNoMandatField() throws Exception {
        EntityDescriptor desc = new EntityDescriptor();
        desc.setEntityClass(TestPojo.class);
        desc.setHasMandatField(false);

        when(registryService.getEntity("TestPojo")).thenReturn(desc);

        Object result = service.createNew("TestPojo");
        assertNotNull(result);
        assertNull(((TestPojo) result).mandat);
    }

    @Test
    void getFieldValue_findsInheritedField() {
        SubPojo sub = new SubPojo();
        sub.name = "parent-value";
        sub.subField = "sub-value";

        assertEquals("parent-value", service.getFieldValue(sub, "name"));
        assertEquals("sub-value", service.getFieldValue(sub, "subField"));
    }

    @Test
    void findByMandat_usesPlaintextRepository() {
        ch.plaintext.framework.PlaintextRepository<Object> ptRepo = mock(ch.plaintext.framework.PlaintextRepository.class);
        when(registryService.getRepository("MandatEntity")).thenReturn(ptRepo);
        List<Object> expected = List.of("a", "b");
        when(ptRepo.findByMandat("m1")).thenReturn(expected);

        List<?> result = service.findByMandat("MandatEntity", "m1");
        assertEquals(expected, result);
    }

    @Test
    void findByMandat_failsClosed_whenNoFindByMandatMethod() {
        // SECURITY (Karte 307, K2.1): Fehlt eine findByMandat-Methode, darf NICHT auf findAll()
        // zurueckgefallen werden (das lieferte alle Mandanten). Fail-closed: Exception statt Dump.
        Object repoWithoutFindByMandat = mock(JpaRepository.class);
        when(registryService.getRepository("NoMandatEntity")).thenReturn(repoWithoutFindByMandat);

        assertThrows(IllegalStateException.class,
                () -> service.findByMandat("NoMandatEntity", "m1"),
                "Ohne findByMandat muss fail-closed geworfen werden, kein findAll-Fallback");
        // findAll darf in diesem Pfad NICHT aufgerufen werden (kein Cross-Mandant-Leak).
        verify((JpaRepository) repoWithoutFindByMandat, never()).findAll();
    }

    @Test
    void setFieldValue_withAlreadyCorrectType_setsDirectly() {
        TestPojo pojo = new TestPojo();
        service.setFieldValue(pojo, "name", "directValue");
        assertEquals("directValue", pojo.name);
    }

    @Test
    void setFieldValue_handlesNonexistentField() {
        TestPojo pojo = new TestPojo();
        // Should not throw
        assertDoesNotThrow(() -> service.setFieldValue(pojo, "nonexistent", "value"));
    }

    @Test
    void getFieldValueAsString_numericValue_returnsString() {
        TestPojo pojo = new TestPojo();
        pojo.count = 42L;

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("count");

        String result = service.getFieldValueAsString(pojo, fm);
        assertEquals("42", result);
    }

    @Test
    void getFieldValueAsString_booleanValue_returnsString() {
        TestPojo pojo = new TestPojo();
        pojo.active = true;

        FieldMetadata fm = new FieldMetadata();
        fm.setFieldName("active");

        String result = service.getFieldValueAsString(pojo, fm);
        assertEquals("true", result);
    }

    // Test POJOs
    public static class TestPojo {
        String name;
        Long count;
        Integer intValue;
        boolean active;
        double price;
        float ratio;
        String mandat;
        Date dateField;
        LocalDateTime localDateTimeField;
        LocalDate localDateField;

        public TestPojo() {}
    }

    public static class SubPojo extends TestPojo {
        String subField;
    }
}
