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
 * Stores {@link SimpleStorable} objects as text in a database column.
 * <p>
 * <b>JSON is written, JSON and XML are read.</b> This is a migration without a
 * cut-off date: existing data sits in the column as XStream XML and stays
 * readable, while every write converts one record to JSON. After a while in
 * operation the stock has converted itself, without a migration script and
 * without a maintenance window.
 * <p>
 * The reason for the switch is XStream itself: the library has a long series of
 * deserialization CVEs behind it, and the way it is secured is by allowing types
 * through an allowlist. Jackson is the smaller attack surface here - the same
 * allowlist exists below nevertheless, see {@link #typeValidator()}.
 * <p>
 * <b>Careful with a rollback:</b> once this version has run, JSON values sit in
 * the column. An older program version reads XStream only and cannot do anything
 * with them. The way back therefore leads through restoring the backup, not
 * through merely rolling the version back.
 */
@Slf4j
@Converter(autoApply = true)
public class SimpleStorableConverter implements AttributeConverter<SimpleStorable, String> {

    /** Reads the legacy data. Goes away as soon as no XML values occur any more. */
    private final XstreamBaseJPAConverter<SimpleStorable> legacy = new XstreamBaseJPAConverter<>();

    private final ObjectMapper mapper = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // The concrete type is part of the JSON, otherwise the interface cannot be
        // reconstructed. activateDefaultTyping instead of an annotation on the
        // interface: SimpleStorable is meant to stay free of Jackson references.
        m.activateDefaultTyping(typeValidator(), ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return m;
    }

    /**
     * The same restriction the XStream path had via {@code allowTypesByWildcard}:
     * only types of the product line and of the applications built on it, plus the
     * collections from {@code java.util}.
     * <p>
     * Without that barrier a manipulated column value could trigger the creation of
     * arbitrary classes - that is exactly the class of vulnerability that earned
     * XStream its reputation, and it would not be fixed automatically by moving to
     * Jackson.
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
        } catch (Exception e) { // NOSONAR - a serialization failure must not blow up the transaction
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

        // Telling them apart by the first character is enough and costs nothing: JSON
        // starts with '{', XStream XML with '<'. Guessing by way of a trial
        // deserialization would be more expensive and ambiguous when it fails.
        if (trimmed.startsWith("<")) {
            log.debug("[SimpleStorableConverter] Altbestand im XML-Format gelesen");
            return legacy.convertToEntityAttribute(trimmed);
        }

        try {
            return mapper.readValue(trimmed, SimpleStorable.class);
        } catch (Exception e) { // NOSONAR - a broken value must not blow up the query
            log.error("[SimpleStorableConverter] Deserialisierung fehlgeschlagen | fehler={}", e.getMessage());
            return null;
        }
    }
}
