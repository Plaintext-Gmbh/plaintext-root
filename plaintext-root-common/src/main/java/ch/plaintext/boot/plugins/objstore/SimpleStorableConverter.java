/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import ch.plaintext.framework.XstreamBaseJPAConverter;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Speichert {@link SimpleStorable}-Objekte als Text in einer Datenbankspalte.
 * <p>
 * <b>Geschrieben wird JSON, gelesen wird JSON und XML.</b> Das ist eine
 * Migration ohne Stichtag: Bestandsdaten liegen als XStream-XML in der Spalte
 * und bleiben lesbar, jeder Schreibvorgang wandelt einen Datensatz nach JSON.
 * Nach einer Weile im Betrieb ist der Bestand von selbst umgestellt, ohne
 * Migrationsskript und ohne Ausfallfenster.
 * <p>
 * Der Grund fuer den Wechsel ist XStream selbst: die Bibliothek hat eine lange
 * Reihe von Deserialisierungs-CVEs hinter sich, und ihre Absicherung besteht
 * darin, Typen per Allowlist zuzulassen. Jackson ist hier die kleinere
 * Angriffsflaeche - dieselbe Allowlist gibt es unten trotzdem, siehe
 * {@link #typeValidator()}.
 * <p>
 * <b>Achtung bei einem Rollback:</b> Sobald diese Fassung gelaufen ist, stehen
 * JSON-Werte in der Spalte. Eine aeltere Programmfassung liest ausschliesslich
 * XStream und kann damit nichts anfangen. Der Weg zurueck fuehrt also ueber ein
 * Einspielen der Datensicherung, nicht ueber ein blosses Zurueckstellen der
 * Version.
 */
@Slf4j
@Converter(autoApply = true)
public class SimpleStorableConverter implements AttributeConverter<SimpleStorable, String> {

    /** Liest den Altbestand. Faellt weg, sobald keine XML-Werte mehr vorkommen. */
    private final XstreamBaseJPAConverter<SimpleStorable> legacy = new XstreamBaseJPAConverter<>();

    private final ObjectMapper mapper = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // Der konkrete Typ steht im JSON, sonst laesst sich das Interface nicht
        // zurueckbauen. activateDefaultTyping statt einer Annotation am
        // Interface: SimpleStorable soll frei von Jackson-Bezuegen bleiben.
        m.activateDefaultTyping(typeValidator(), ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return m;
    }

    /**
     * Dieselbe Einschraenkung, die der XStream-Weg per {@code allowTypesByWildcard}
     * hatte: nur Typen der Produktlinie und der Anwendungen darauf, dazu die
     * Sammlungen aus {@code java.util}.
     * <p>
     * Ohne diese Schranke koennte ein manipulierter Spaltenwert die Erzeugung
     * beliebiger Klassen anstossen - das ist genau die Schwachstellenklasse, die
     * XStream seinen Ruf eingebracht hat, und sie waere mit Jackson nicht
     * automatisch behoben.
     */
    private static PolymorphicTypeValidator typeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("ch.")
                .allowIfSubType("java.util.")
                .build();
    }

    @Override
    public String convertToDatabaseColumn(SimpleStorable object) {
        if (object == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(object);
        } catch (Exception e) { // NOSONAR - ein Serialisierungsfehler darf die Transaktion nicht sprengen
            log.error("[SimpleStorableConverter] Serialisierung fehlgeschlagen | typ={} | fehler={}",
                    object.getClass().getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public SimpleStorable convertToEntityAttribute(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();

        // Die Unterscheidung am ersten Zeichen genuegt und kostet nichts: JSON
        // beginnt mit '{', XStream-XML mit '<'. Ein Rateverfahren ueber
        // Probe-Deserialisierung waere teurer und im Fehlerfall mehrdeutig.
        if (trimmed.startsWith("<")) {
            log.debug("[SimpleStorableConverter] Altbestand im XML-Format gelesen");
            return legacy.convertToEntityAttribute(trimmed);
        }

        try {
            return mapper.readValue(trimmed, SimpleStorable.class);
        } catch (Exception e) { // NOSONAR - ein defekter Wert darf die Abfrage nicht sprengen
            log.error("[SimpleStorableConverter] Deserialisierung fehlgeschlagen | fehler={}", e.getMessage());
            return null;
        }
    }
}
