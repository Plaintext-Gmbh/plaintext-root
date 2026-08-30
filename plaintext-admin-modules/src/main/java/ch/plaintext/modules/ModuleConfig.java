/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Persisted on/off state of a feature module (Task #016). Exactly one row per {@code moduleId}
 * application-wide; {@code enabled=false} hides the module. {@code mandat}/{@code createdDate}
 * come from {@link SuperModel}.
 */
@Entity
@Table(name = "module_config")
@Data
@EqualsAndHashCode(callSuper = true)
public class ModuleConfig extends SuperModel {

    @Column(name = "module_id", length = 100, nullable = false)
    private String moduleId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
}
