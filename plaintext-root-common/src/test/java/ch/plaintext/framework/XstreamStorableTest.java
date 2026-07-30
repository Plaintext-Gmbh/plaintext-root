/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XstreamStorableTest {

    /**
     * Concrete test implementation with private and non-private fields.
     */
    static class TestStorableImpl implements XstreamStorable {
        private String key;
        private Date lastModifiedDate;
        private String mandat;
        private String lastModifiedBy;
        private String createdBy;
        private String extraField;
        protected String protectedField;

        @Override
        public String getKey() { return key; }

        @Override
        public void setKey(String in) { this.key = in; }

        @Override
        public Date getLastModifiedDate() { return lastModifiedDate; }

        @Override
        public void setLastModifiedDate(Date date) { this.lastModifiedDate = date; }

        @Override
        public String getMandat() { return mandat; }

        @Override
        public void setMandat(String mandat) { this.mandat = mandat; }

        @Override
        public String getLastModifiedBy() { return lastModifiedBy; }

        @Override
        public void setLastModifiedBy(String in) { this.lastModifiedBy = in; }

        @Override
        public String getCreatedBy() { return createdBy; }

        @Override
        public void setCreatedBy(String in) { this.createdBy = in; }
    }

    // -------------------------------------------------------------------------
    // Default method: getDruckmodus
    // -------------------------------------------------------------------------

    @Test
    void getDruckmodus_defaultIsFalse() {
        assertFalse(new TestStorableImpl().getDruckmodus());
    }

    // -------------------------------------------------------------------------
    // Default index methods
    // -------------------------------------------------------------------------

    @Test
    void getIndex_returnsDefault() {
        assertEquals("default", new TestStorableImpl().getIndex());
    }

    @Test
    void getIndex1_returnsDefault() {
        assertEquals("default", new TestStorableImpl().getIndex1());
    }

    @Test
    void getIndex2_returnsDefault() {
        assertEquals("default", new TestStorableImpl().getIndex2());
    }

    @Test
    void getIndex3_returnsDefault() {
        assertEquals("default", new TestStorableImpl().getIndex3());
    }

    @Test
    void getIndex4_returnsDefault() {
        assertEquals("default", new TestStorableImpl().getIndex4());
    }

    // -------------------------------------------------------------------------
    // Default method: getFields
    // -------------------------------------------------------------------------

    @Test
    void getFields_returnsOnlyPrivateFields() {
        TestStorableImpl impl = new TestStorableImpl();
        List<Field> fields = impl.getFields();
        assertNotNull(fields);

        List<String> names = fields.stream().map(Field::getName).toList();
        assertTrue(names.contains("extraField"), "Should contain private field 'extraField'");
        assertFalse(names.contains("protectedField"), "Should not contain protected field");
    }

    @Test
    void getFields_allReturnedFieldsArePrivate() {
        TestStorableImpl impl = new TestStorableImpl();
        for (Field f : impl.getFields()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(f.getModifiers()),
                    "Expected private but got: " + f.getName());
        }
    }
}
