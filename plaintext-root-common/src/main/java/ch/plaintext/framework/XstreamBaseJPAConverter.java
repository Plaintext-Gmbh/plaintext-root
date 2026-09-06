/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.NullPermission;
import com.thoughtworks.xstream.security.PrimitiveTypePermission;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Einzige XStream-JPA-Konverter-Basisklasse (Karte 1069, A-02). Bis 06.09.2026 gab es drei
 * inhaltsgleiche Kopien (hier, {@code ch.plaintext.boot.plugins.jpa.XstreamBaseJPAConverter} und
 * {@code ch.plaintext.boot.plugins.security.helpers.MyUserXstreamBaseJPAConverter} in
 * plaintext-root-webapp) — beide webapp-Kopien erben jetzt von hier.
 *
 * <p>Allowlist auf {@code ch.plaintext.**} verengt (Karte 1069, S-04): das alte {@code ch.**}
 * schloss auch {@code ch.qos.logback.**} ein, das auf dem Klassenpfad jeder Anwendung liegt und
 * mit {@code JNDIConnectionSource}/{@code DriverManagerConnectionSource} bekannte
 * Deserialisierungs-Gadget-Klassen enthält. Dazu die konkreten {@code java.util}-Typen, die die
 * fünf Nutzer dieser Basisklasse tatsächlich serialisieren ({@code ArrayList} für
 * {@code List<String>}, {@code HashSet} für {@code Set<String>}; {@code LinkedHashSet} zusätzlich
 * erlaubt, weil XStream beim Lesen älterer, ausserhalb dieses Konverters erzeugter Spaltenwerte
 * darauf treffen kann).
 *
 * <p>Der frühere stille Rückfall auf {@code text.split(",")} bei jedem Lesefehler ist entfernt
 * (Karte 1069, S-04) — er stammte nur aus der root-common-Kopie, war nirgends dokumentiert
 * begründet ("todo can go in 2021") und hätte auch eine durch die Allowlist abgewiesene,
 * manipulierte Spalte klaglos in eine plausibel aussehende Liste verwandelt statt den Fehler
 * sichtbar zu machen. Wie die beiden bisherigen webapp-Kopien es schon taten: bei jedem
 * Lesefehler (inklusive {@code ForbiddenClassException} der Allowlist) wird laut geloggt und
 * {@code null} zurückgegeben — das lässt das betroffene Feld leer, statt den Entity-Ladevorgang
 * hart abzubrechen oder einen erratenen Ersatzwert einzusetzen.
 */
@Slf4j
public class XstreamBaseJPAConverter<T> implements AttributeConverter<T, String> {

    private final XStream xstream = createXStream();

    static XStream createXStream() {
        XStream x = new XStream();
        x.allowTypesByWildcard(new String[]{
                "ch.plaintext.**"
        });
        x.allowTypes(new Class[]{
                ArrayList.class, HashSet.class, LinkedHashSet.class, List.class, Set.class,
        });
        x.addPermission(NullPermission.NULL);
        x.addPermission(PrimitiveTypePermission.PRIMITIVES);
        x.allowTypeHierarchy(Collection.class);
        return x;
    }

    @Override
    public String convertToDatabaseColumn(T object) {
        return xstream.toXML(object);
    }

    @Override
    public T convertToEntityAttribute(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return (T) xstream.fromXML(text);
        } catch (Exception e) {
            log.error("XStream-Spalte nicht lesbar: {}", e.getMessage());
        }
        return null;
    }

}
