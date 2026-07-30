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
 * Zentrale Modul-Verwaltung (Task #016): sammelt per Spring-Discovery alle {@link ModuleDescriptor}-Beans
 * ein (wie das {@code PlaintextCron}-Muster — keine zentrale Registry) und verwaltet ihren Ein-/Aus-Zustand
 * in {@link ModuleConfig}. {@link #isEnabled(String)} ist die API, die Feature-Module in ihren
 * Crons/Listenern abfragen können, um sich funktional zu deaktivieren (Default: aktiviert).
 */
@Service
@Slf4j
public class ModuleService implements ModuleEnablementProvider {

    @Autowired
    private ModuleConfigRepository configRepository;

    /** Alle Module, die das Discovery-Interface implementieren (Opt-in; leer, wenn keins). */
    @Autowired(required = false)
    private List<ModuleDescriptor> descriptors = new ArrayList<>();

    /** Ein-/Aus-Zustand eines Moduls (Default: aktiviert, wenn keine Zeile vorhanden). */
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

    /** Liste aller entdeckten Module (Anzeigename, Version, Zustand), alphabetisch. */
    public List<ModuleView> list() {
        List<ModuleView> result = new ArrayList<>();
        for (ModuleDescriptor d : descriptors) {
            result.add(new ModuleView(d.moduleId(), sicher(d::displayName, d.moduleId()),
                    sicher(d::version, "dev"), isEnabled(d.moduleId())));
        }
        result.sort(Comparator.comparing(ModuleView::getDisplayName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Defensiv: ein einzelnes fehlerhaftes Modul soll die ganze Liste nicht abschiessen. */
    private static String sicher(java.util.function.Supplier<String> s, String fallback) {
        try {
            String v = s.get();
            return v != null && !v.isBlank() ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
