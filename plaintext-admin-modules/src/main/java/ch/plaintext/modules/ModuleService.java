/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Central module management (Task #016): collects all {@link ModuleDescriptor} beans via Spring
 * discovery (like the {@code PlaintextCron} pattern — no central registry) and manages their
 * on/off state in {@link ModuleConfig}. {@link #isEnabled(String)} is the API that feature modules
 * can query in their crons/listeners in order to deactivate themselves functionally (default: enabled).
 */
@Service
@Slf4j
public class ModuleService implements ModuleEnablementProvider {

    @Autowired
    private ModuleConfigRepository configRepository;

    /** All modules that implement the discovery interface (opt-in; empty if there is none). */
    @Autowired(required = false)
    private List<ModuleDescriptor> descriptors = new ArrayList<>();

    /** On/off state of a module (default: enabled if no row exists). */
    @Override
    public boolean isEnabled(String moduleId) {
        return configRepository.findByModuleId(moduleId).map(ModuleConfig::isEnabled).orElse(true);
    }

    @Transactional
    public void setEnabled(String moduleId, boolean enabled) {
        ModuleConfig c = configRepository.findByModuleId(moduleId).orElseGet(() -> {
            ModuleConfig neu = new ModuleConfig();
            neu.setModuleId(moduleId);
            return neu;
        });
        c.setEnabled(enabled);
        configRepository.save(c);
        log.info("Modul '{}' {}", moduleId, enabled ? "aktiviert" : "deaktiviert");
    }

    /** List of all discovered modules (display name, version, state), alphabetically. */
    public List<ModuleView> list() {
        List<ModuleView> result = new ArrayList<>();
        for (ModuleDescriptor d : descriptors) {
            result.add(new ModuleView(d.moduleId(), sicher(d::displayName, d.moduleId()),
                    sicher(d::version, "dev"), isEnabled(d.moduleId())));
        }
        result.sort(Comparator.comparing(ModuleView::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Defensive: a single faulty module must not take down the entire list. */
    private static String sicher(java.util.function.Supplier<String> s, String fallback) {
        try {
            String v = s.get();
            return v != null && !v.isBlank() ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
