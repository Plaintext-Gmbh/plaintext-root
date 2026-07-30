/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.kontakte;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IKontaktDefaultMethodsTest {

    @Test
    void getKonto_defaultReturnsNull() {
        IKontakt kontakt = new IKontakt() {
            @Override public Long getId() { return 1L; }
            @Override public String getDisplayName() { return "Test"; }
            @Override public String getVorname() { return "Max"; }
            @Override public String getNachname() { return "Muster"; }
            @Override public String getFirma() { return null; }
            @Override public String getStrasse() { return "Str 1"; }
            @Override public String getPlz() { return "8000"; }
            @Override public String getOrt() { return "Zurich"; }
            @Override public String getLand() { return "CH"; }
            @Override public String getEmail() { return "test@test.ch"; }
            @Override public String getTelefon() { return "123"; }
        };

        assertNull(kontakt.getKonto());
    }

    @Test
    void iKontaktService_isAvailable_defaultTrue() {
        IKontaktService service = new IKontaktService() {
            @Override public java.util.List<IKontakt> findAll() { return java.util.List.of(); }
            @Override public java.util.List<IKontakt> findByMandat(Long mandatId) { return java.util.List.of(); }
            @Override public Optional<IKontakt> findById(Long id) { return Optional.empty(); }
            @Override public java.util.List<IKontakt> searchByName(String searchTerm) { return java.util.List.of(); }
        };

        assertTrue(service.isAvailable());
    }

    @Test
    void iKontaktService_findByEmail_defaultReturnsEmpty() {
        IKontaktService service = new IKontaktService() {
            @Override public java.util.List<IKontakt> findAll() { return java.util.List.of(); }
            @Override public java.util.List<IKontakt> findByMandat(Long mandatId) { return java.util.List.of(); }
            @Override public Optional<IKontakt> findById(Long id) { return Optional.empty(); }
            @Override public java.util.List<IKontakt> searchByName(String searchTerm) { return java.util.List.of(); }
        };

        assertTrue(service.findByEmail("test@test.ch").isEmpty());
    }

    @Test
    void iKontaktService_updateKontakt_defaultReturnsEmpty() {
        IKontaktService service = new IKontaktService() {
            @Override public java.util.List<IKontakt> findAll() { return java.util.List.of(); }
            @Override public java.util.List<IKontakt> findByMandat(Long mandatId) { return java.util.List.of(); }
            @Override public Optional<IKontakt> findById(Long id) { return Optional.empty(); }
            @Override public java.util.List<IKontakt> searchByName(String searchTerm) { return java.util.List.of(); }
        };

        assertTrue(service.updateKontakt(1L, "a", "b", "c", "d", "e", "f", "g", "h", "i").isEmpty());
    }

    @Test
    void iKontaktService_createKontakt_defaultThrowsUnsupported() {
        IKontaktService service = new IKontaktService() {
            @Override public java.util.List<IKontakt> findAll() { return java.util.List.of(); }
            @Override public java.util.List<IKontakt> findByMandat(Long mandatId) { return java.util.List.of(); }
            @Override public Optional<IKontakt> findById(Long id) { return Optional.empty(); }
            @Override public java.util.List<IKontakt> searchByName(String searchTerm) { return java.util.List.of(); }
        };

        assertThrows(UnsupportedOperationException.class,
                () -> service.createKontakt("a", "b", "c", "d", "e", "f", "g", "h", "i"));
    }
}
