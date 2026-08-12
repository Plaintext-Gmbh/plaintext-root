/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import jakarta.persistence.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Die Id kommt von der Datenbank (Identity-Spalte).
     *
     * <p><b>Karte 687:</b> Hier stand bis zum 12.08.2026 ein eigener Generator
     * {@code UseExistingIdOtherwiseGenerateUsingIdentity}, der zwei Dinge versprach: eine bereits
     * gesetzte Id durchzureichen und andernfalls über {@code RepoMaster} die nächste zu holen. Er
     * hat seit dem Hibernate-7-Umstieg <b>keines von beiden</b> geleistet: seine Methode
     * {@code generate(SharedSessionContractImplementor, Object)} überschrieb nichts —
     * {@code org.hibernate.id.IdentityGenerator} ist über {@code PostInsertIdentifierGenerator}
     * ein {@code OnExecutionGenerator} und kennt die Methode gar nicht, und die einzige Stelle mit
     * diesem Namen ({@code IdentifierGenerator}) hat eine andere Signatur (Rückgabe {@code Object}).
     * Weil {@code @Override} fehlte, fiel das beim Upgrade nicht als Compilerfehler auf.
     *
     * <p>Gelaufen ist die ganze Zeit die geerbte IDENTITY-Strategie. Genau die steht jetzt hier —
     * die Zeile beschreibt also, was ohnehin passiert, statt etwas anderes zu versprechen.
     *
     * <p><b>Wer eine Id vorgeben will, kann das hier nicht.</b> Der Weg dafür ist ein
     * {@code @Id}-Feld <i>ohne</i> {@code @GeneratedValue} an der eigenen Entity — so machen es die
     * Singleton-Konfigurationszeilen {@code MessengerConfig} (app) und {@code SchuetuMysqlConfig}
     * (schuetu), die deshalb bewusst nicht von {@code SuperModel} erben.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
