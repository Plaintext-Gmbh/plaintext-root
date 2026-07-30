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
 * Persistierter Ein-/Aus-Zustand eines Feature-Moduls (Task #016). App-weit pro {@code moduleId}
 * genau eine Zeile; {@code enabled=false} blendet das Modul aus. {@code mandat}/{@code createdDate}
 * kommen aus {@link SuperModel}.
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
