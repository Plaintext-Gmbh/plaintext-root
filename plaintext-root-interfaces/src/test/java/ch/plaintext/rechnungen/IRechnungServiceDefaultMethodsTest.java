/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rechnungen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IRechnungServiceDefaultMethodsTest {

    @Test
    void createPauschalRechnung_delegatesToCreateRechnung() {
        IRechnungService service = spy(new IRechnungService() {
            @Override public boolean isAvailable() { return true; }
            @Override public Long createRechnung(Long kontaktId, String betreff, double preisProStunde, double gesamtStunden, String zeitraum) {
                return 42L;
            }
            @Override public Long createRechnung(Long kontaktId, String betreff, double preisProStunde, double gesamtStunden, String zeitraum, String kopftext) {
                return null;
            }
            @Override public Optional<Double> getLastPreisProStunde(Long kontaktId) { return Optional.empty(); }
            @Override public Optional<String> getLastBetreff(Long kontaktId) { return Optional.empty(); }
            @Override public List<? extends IRechnung> findByKontakt(Long kontaktId) { return List.of(); }
            @Override public Optional<? extends IRechnung> findById(Long rechnungId) { return Optional.empty(); }
            @Override public List<? extends IRechnung> findOffene() { return List.of(); }
            @Override public Optional<? extends IRechnung> findByQrReferenz(String qrReferenz) { return Optional.empty(); }
            @Override public boolean markBezahlt(Long rechnungId) { return false; }
            @Override public Long createOffeneRechnung(Long kontaktId, String beschreibung, double betrag) { return null; }
            @Override public List<? extends IKontaktOption> getAvailableKontakte() { return List.of(); }
            @Override public List<String> getAvailableKopftexte() { return List.of(); }
        });

        Long result = service.createPauschalRechnung(1L, "Pauschal", 500.0);
        assertEquals(42L, result);
    }

    @Test
    void generateInvoicePDFWithOptions_defaultThrowsUnsupported() {
        IRechnungService service = new IRechnungService() {
            @Override public boolean isAvailable() { return true; }
            @Override public Long createRechnung(Long kontaktId, String betreff, double preisProStunde, double gesamtStunden, String zeitraum) { return null; }
            @Override public Long createRechnung(Long kontaktId, String betreff, double preisProStunde, double gesamtStunden, String zeitraum, String kopftext) { return null; }
            @Override public Optional<Double> getLastPreisProStunde(Long kontaktId) { return Optional.empty(); }
            @Override public Optional<String> getLastBetreff(Long kontaktId) { return Optional.empty(); }
            @Override public List<? extends IRechnung> findByKontakt(Long kontaktId) { return List.of(); }
            @Override public Optional<? extends IRechnung> findById(Long rechnungId) { return Optional.empty(); }
            @Override public List<? extends IRechnung> findOffene() { return List.of(); }
            @Override public Optional<? extends IRechnung> findByQrReferenz(String qrReferenz) { return Optional.empty(); }
            @Override public boolean markBezahlt(Long rechnungId) { return false; }
            @Override public Long createOffeneRechnung(Long kontaktId, String beschreibung, double betrag) { return null; }
            @Override public List<? extends IKontaktOption> getAvailableKontakte() { return List.of(); }
            @Override public List<String> getAvailableKopftexte() { return List.of(); }
        };

        InvoicePDFOptions options = new InvoicePDFOptions(
                "Firma", null, null, null,
                null, null, null, null,
                null, null, null, null,
                false, null
        );

        assertThrows(UnsupportedOperationException.class,
                () -> service.generateInvoicePDFWithOptions(1L, options));
    }
}
