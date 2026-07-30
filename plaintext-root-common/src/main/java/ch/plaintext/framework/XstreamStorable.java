/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public interface XstreamStorable {

    default boolean getDruckmodus() {
        return false;
    }

    default void setDruckmodus(boolean druckmodus) {
    }

    String getKey();

    void setKey(String in);

    default String getIndex() {
        return "default";
    }

    default String getIndex1() {
        return "default";
    }

    default String getIndex2() {
        return "default";
    }

    default String getIndex3() {
        return "default";
    }

    default String getIndex4() {
        return "default";
    }

    public Date getLastModifiedDate();

    public void setLastModifiedDate(Date date);

    public String getMandat();

    public void setMandat(String mandat);

    public String getLastModifiedBy();

    public void setLastModifiedBy(String in);

    public String getCreatedBy();

    public void setCreatedBy(String in);

    default List<Field> getFields() {
        List<Field> privateFields = new ArrayList<>();
        Field[] allFields = this.getClass().getDeclaredFields();
        for (Field field : allFields) {
            if (Modifier.isPrivate(field.getModifiers())) {
                privateFields.add(field);
            }
        }
        return privateFields;
    }


}
