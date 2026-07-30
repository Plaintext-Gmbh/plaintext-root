/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.NullPermission;
import com.thoughtworks.xstream.security.PrimitiveTypePermission;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

@Slf4j
public abstract class MyUserXstreamBaseJPAConverter<T> implements AttributeConverter<T, String> {

    private XStream xstream = null;

    public MyUserXstreamBaseJPAConverter() {
        if (xstream != null) {
            return;
        }
        xstream = new XStream();
        xstream.allowTypesByWildcard(new String[]{
                "ch.**", "java.util.**"
        });
        xstream.addPermission(NullPermission.NULL);
        xstream.addPermission(PrimitiveTypePermission.PRIMITIVES);
        xstream.allowTypeHierarchy(Collection.class);
    }

    @Override
    public String convertToDatabaseColumn(T object) {
        return xstream.toXML(object);
    }

    @Override
    public T convertToEntityAttribute(String text) {
        try {
            if (text == null) {
                return null;
            }
            return (T) xstream.fromXML(text);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return null;
    }

}