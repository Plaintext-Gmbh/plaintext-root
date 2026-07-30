/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import jakarta.persistence.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Mandat und Auditing provider, als Mapped Superclass
 *
 * @author Plaintext GmbH
 * @since 2017
 */
@Data
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Slf4j
public class SuperModel implements XstreamStorable {

    @Id
    @GenericGenerator(name = "UseExistingIdOtherwiseGenerateUsingIdentity", strategy = "ch.plaintext.framework.UseExistingIdOtherwiseGenerateUsingIdentity")
    @GeneratedValue(generator = "UseExistingIdOtherwiseGenerateUsingIdentity")
    private Long id;

    private Boolean deleted = Boolean.FALSE;

    @CreatedBy
    private String createdBy;

    @CreatedDate
    private Date createdDate;

    @LastModifiedBy
    private String lastModifiedBy;

    @LastModifiedDate
    private Date lastModifiedDate;

    private String mandat;

    @Column(length = 5000)
    @Convert(converter = StringArrayJPAConverter.class)
    private List<String> tags = new ArrayList<String>();

    @PrePersist
    public void setMandat() {
            if (mandat == null || mandat.isEmpty()) {
                mandat = PlaintextSecurityHolder.getMandat();
                log.info("mandat set from security context: " + mandat);
            } else {
                log.info("mandat already set: " + mandat);
            }
    }

    public List<Field> getFields() {
        Set<Field> all = new HashSet<>();
        all.addAll(Arrays.asList(this.getClass().getDeclaredFields()));

        if (this.getClass().getSuperclass() != null) {
            all.addAll(Arrays.asList(this.getClass().getSuperclass().getDeclaredFields()));
        }
        return new ArrayList<>(all);
    }

    // fuer Emad Form
    public List<Field> getFieldsOhneSuper() {
        List<Field> privateFields = new ArrayList<>();
        Field[] allFields = this.getClass().getDeclaredFields();
        for (Field field : allFields) {
            if (Modifier.isPrivate(field.getModifiers())) {
                privateFields.add(field);
            }
        }
        return privateFields;
    }

    public boolean isFiledEmty(String field) {
        for (Field f : getFields()) {
            if (f.getName().toLowerCase().equals(field.toLowerCase())) {
                f.setAccessible(true);
                try {
                    Object obj = f.get(this);
                    if (obj == null || obj.toString().isEmpty() || obj.toString().equals("[]")) {
                        return true;
                    }
                    if (f.getType().equals(boolean.class)) {
                        log.debug("little boolean ...");
                    }
                } catch (IllegalAccessException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
        return false;
    }

    public String getKey() {
        return "" + id;
    }

    @Override
    public void setKey(String in) {
        setId(Long.parseLong(in));
    }

}
