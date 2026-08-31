/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.NullPermission;
import com.thoughtworks.xstream.security.PrimitiveTypePermission;
import jakarta.persistence.AttributeConverter;

import java.util.Arrays;
import java.util.Collection;

public class XstreamBaseJPAConverter<T> implements AttributeConverter<T, String> {

    // Allowlist (analogous to ch.plaintext.boot.plugins.jpa.XstreamBaseJPAConverter in the webapp
    // module) -- without it XStream 1.4.21 does apply its default security framework, but that one
    // is more permissive than necessary; restrict it explicitly to the types actually used.
    private XStream xstream = createXStream();

    private static XStream createXStream() {
        XStream x = new XStream();
        x.allowTypesByWildcard(new String[]{
                "ch.**", "java.util.**"
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
            // todo can go in 2021
            if (text != null && !text.isEmpty()) {
                return (T) Arrays.asList(text.split(","));
            }
        }
        return null;
    }

}