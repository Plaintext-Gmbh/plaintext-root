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
 * Mandat and auditing provider, as a mapped superclass
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
     * The id comes from the database (identity column).
     *
     * <p><b>Karte 687:</b> until 12.08.2026 a custom generator
     * {@code UseExistingIdOtherwiseGenerateUsingIdentity} stood here, promising two things: to pass
     * an already set id through and otherwise to fetch the next one via {@code RepoMaster}. Since
     * the move to Hibernate 7 it delivered <b>neither of the two</b>: its method
     * {@code generate(SharedSessionContractImplementor, Object)} overrode nothing —
     * {@code org.hibernate.id.IdentityGenerator} is an {@code OnExecutionGenerator} by way of
     * {@code PostInsertIdentifierGenerator} and does not know the method at all, and the only place
     * with that name ({@code IdentifierGenerator}) has a different signature (returning
     * {@code Object}). Because {@code @Override} was missing, this did not surface as a compiler
     * error during the upgrade.
     *
     * <p>What ran all along was the inherited IDENTITY strategy. That is exactly what stands here
     * now — so the line describes what happens anyway instead of promising something else.
     *
     * <p><b>Whoever wants to supply an id cannot do it here.</b> The way to do that is an
     * {@code @Id} field <i>without</i> {@code @GeneratedValue} on one's own entity — that is how
     * the singleton configuration rows {@code MessengerConfig} (app) and {@code SchuetuMysqlConfig}
     * (schuetu) do it, which is why they deliberately do not extend {@code SuperModel}.
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

    // for the Emad form
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
