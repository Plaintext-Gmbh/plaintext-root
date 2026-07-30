/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.bus.ExecutionScope;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.Date;

@Entity
@Table(name = "cron_config", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cronName", "mandat"})
})
@Data
@EntityListeners(AuditingEntityListener.class)
public class CronConfigEntity {

    @Transient
    private SuperCron cron;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String cronName;

    @Column(nullable = false)
    private String mandat;

    private String cronExpression;

    private boolean enabled = true;

    private boolean startup = false;

    // Persistent fields for cron execution tracking
    private Integer counter = 0;

    @Temporal(TemporalType.TIMESTAMP)
    private Date lastRun;

    private Integer lastSeconds = 0;

    @SuppressWarnings("deprecation")
    public boolean isGlobal(){
        if(cron == null){
            return false;
        }
        return cron.isGlobal();
    }

    /** Delegiert an {@link SuperCron#getScope()} (Task 005) — {@code MANDAT} als Fallback ohne Cron. */
    public ExecutionScope getScope() {
        if (cron == null) {
            return ExecutionScope.MANDAT;
        }
        return cron.getScope();
    }

    public int getPercente() {
        if(cron == null){
            return 0;
        }
        return cron.getPercente();
    }

    public String getDisplayName() {
        if(cron == null){
            return cronName;
        }
        return cron.getDisplayName();
    }

    /**
     * Synchronizes the execution statistics from the transient SuperCron instance
     * to the persistent fields in this entity.
     */
    public void syncFromCron() {
        if (cron != null) {
            this.counter = cron.getCounter();
            this.lastRun = cron.getLastRun();
            this.lastSeconds = cron.getLastSeconds();
        }
    }

    @CreatedBy
    private String createdBy;

    @CreatedDate
    private Date createdDate;

    @LastModifiedBy
    private String lastModifiedBy;

    @LastModifiedDate
    private Date lastModifiedDate;

}
