/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.flyway;

import lombok.Data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Date;

/**
 * Entity representing Flyway schema history table
 *
 * @author plaintext.ch
 * @since 1.108.0
 */
@Data
@Entity
@Table(name = "flyway_schema_history")
public class FlywaySchemaHistory {

    @Id
    @Column(name = "installed_rank")
    private Integer installedRank;

    @Column(name = "version")
    private String version;

    @Column(name = "description")
    private String description = "";

    @Column(name = "type")
    private String type;

    @Column(name = "script")
    private String script;

    @Column(name = "checksum")
    private Integer checksum;

    @Column(name = "installed_by")
    private String installedBy;

    @Column(name = "installed_on")
    private Date installedOn;

    @Column(name = "execution_time")
    private Integer executionTime;

    @Column(name = "success")
    private Boolean success;

}
