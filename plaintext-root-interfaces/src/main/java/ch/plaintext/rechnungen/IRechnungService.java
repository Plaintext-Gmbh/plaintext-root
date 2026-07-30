/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rechnungen;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Service-Interface für Rechnungs-Operationen.
 * Ermöglicht die Erstellung von Rechnungen aus der Zeiterfassung.
 */
public interface IRechnungService {

    /**
     * Prüft ob der Service verfügbar ist
     */
    boolean isAvailable();

    /**
     * Erstellt eine neue Rechnung
     *
     * @param kontaktId ID des Kontakts (Rechnungsempfänger)
     * @param betreff Betreffzeile der Rechnung
     * @param preisProStunde Preis pro Stunde in CHF
     * @param gesamtStunden Gesamtanzahl der Stunden
     * @param zeitraum Zeitraum-Beschreibung (z.B. "Januar 2026")
     * @return Die ID der erstellten Rechnung
     */
    Long createRechnung(Long kontaktId, String betreff, double preisProStunde, double gesamtStunden, String zeitraum);

    /**
     * Erstellt eine neue Rechnung mit Kopftext
     *
     * @param kontaktId ID des Kontakts (Rechnungsempfänger)
     * @param betreff Betreffzeile der Rechnung
     * @param preisProStunde Preis pro Stunde in CHF
     * @param gesamtStunden Gesamtanzahl der Stunden
     * @param zeitraum Zeitraum-Beschreibung (z.B. "Januar 2026")
     * @param kopftext Zusätzlicher Kopftext für die Rechnung
     * @return Die ID der erstellten Rechnung
     */
    Long createRechnung(Long kontaktId, String betreff, double preisProStunde, double gesamtStunden, String zeitraum, String kopftext);

    /**
     * Gibt den zuletzt verwendeten Preis pro Stunde für einen Kontakt zurück
     *
     * @param kontaktId ID des Kontakts
     * @return Preis pro Stunde oder empty wenn keine vorherige Rechnung existiert
     */
    Optional<Double> getLastPreisProStunde(Long kontaktId);

    /**
     * Gibt die zuletzt verwendete Betreffzeile für einen Kontakt zurück
     *
     * @param kontaktId ID des Kontakts
     * @return Betreffzeile oder empty wenn keine vorherige Rechnung existiert
     */
    Optional<String> getLastBetreff(Long kontaktId);

    /**
     * Gibt alle Rechnungen für einen bestimmten Kontakt zurück
     *
     * @param kontaktId ID des Kontakts
     * @return Liste der Rechnungen
     */
    List<? extends IRechnung> findByKontakt(Long kontaktId);

    /**
     * Gibt eine Rechnung anhand ihrer ID zurück
     *
     * @param rechnungId ID der Rechnung
     * @return Rechnung oder empty
     */
    Optional<? extends IRechnung> findById(Long rechnungId);

    /**
     * Gibt alle verfügbaren Kontakte für Rechnungen zurück
     *
     * @return Liste der Kontakte
     */
    List<? extends IKontaktOption> getAvailableKontakte();

    /**
     * Gibt alle bisher verwendeten Kopftexte zurück (für Autocomplete)
     *
     * @return Liste der Kopftexte
     */
    List<String> getAvailableKopftexte();

    /**
     * Erstellt eine Pauschalrechnung (ohne MwSt, Menge 1, Einheit Pauschal)
     *
     * @param kontaktId ID des Kontakts (Rechnungsempfänger)
     * @param beschreibung Beschreibung der Position
     * @param betrag Pauschalbetrag in CHF
     * @return Die ID der erstellten Rechnung
     */
    default Long createPauschalRechnung(Long kontaktId, String beschreibung, double betrag) {
        return createRechnung(kontaktId, beschreibung, betrag, 1.0, "");
    }

    /**
     * Generiert ein Rechnungs-PDF mit individuellem Absender, Beleg-Bild und Empfänger-IBAN für QR-Bill
     *
     * @param rechnungId ID der Rechnung
     * @param options Optionen für die PDF-Generierung
     * @return PDF als byte-Array
     */
    default byte[] generateInvoicePDFWithOptions(Long rechnungId, InvoicePDFOptions options) {
        throw new UnsupportedOperationException("PDF generation not supported");
    }

    /**
     * Gibt alle offenen Rechnungen (Status OFFEN oder MAHNUNG) des aktuellen Mandanten zurück
     *
     * @return Liste der offenen Rechnungen
     */
    List<? extends IRechnung> findOffene();

    /**
     * Gibt eine Rechnung anhand ihrer QR-Referenz zurück (aktueller Mandant)
     *
     * @param qrReferenz die QR-Referenz (QRR, 27 Stellen)
     * @return Rechnung oder empty
     */
    Optional<? extends IRechnung> findByQrReferenz(String qrReferenz);

    /**
     * Setzt eine Rechnung auf BEZAHLT, z.B. nach einem verbuchten Zahlungseingang
     *
     * @param rechnungId ID der Rechnung
     * @return true wenn gefunden und gesetzt, false wenn nicht gefunden
     *         oder nicht zum aktuellen Mandanten gehörend
     */
    boolean markBezahlt(Long rechnungId);

    /**
     * Erstellt eine OFFENE Pauschal-Rechnung (Forderung), z.B. für Jahresbeiträge:
     * Status OFFEN, Zahlungsfrist gemäss Einstellungen, QR-Referenz wird beim Speichern
     * generiert (Zahlungsabgleich-fähig). Abgrenzung: {@link #createPauschalRechnung}
     * erstellt AUSBEZAHLT-Belege für Auszahlungen.
     *
     * @param kontaktId    ID des Kontakts (Rechnungsempfänger)
     * @param beschreibung Positions-/Rechnungstext
     * @param betrag       Betrag in CHF
     * @return die ID der erstellten Rechnung
     */
    Long createOffeneRechnung(Long kontaktId, String beschreibung, double betrag);
}
