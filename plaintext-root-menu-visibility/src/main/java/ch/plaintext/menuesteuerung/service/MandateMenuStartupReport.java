/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.service;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reports at startup which entries stored in the tenant lists point nowhere in the current menu
 * tree — per tenant, naming the dead entries.
 *
 * <p><b>Why.</b> The tenant lists store menu items under their <i>full title</i>
 * ({@code "Parent | Titel"}). When a menu item is renamed, the stored title no longer matches
 * anything: in blacklist mode it stops hiding anything, in whitelist mode it silently hides the
 * renamed item — both without any feedback whatsoever. Measured on app.plaintext.ch: 19 of 123
 * stored titles pointed nowhere.</p>
 *
 * <p>The model for this is the existing message from the {@link ModuleRoleService}
 * ("Modul-Rolle konfiguriert fuer unbekannten Modul-Key"): a misconfiguration does not abort the
 * startup, but becomes visible as a WARN at boot time.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
@Component
@Slf4j
public class MandateMenuStartupReport {

    private final MandateMenuConfigRepository repository;
    private final MenuRegistry menuRegistry;
    private final ModuleRoleService moduleRoleService;

    /**
     * @param repository        source of the tenant configurations
     * @param menuRegistry      supplies the menu titles that currently exist
     * @param moduleRoleService supplies the detected module keys, may be {@code null}
     */
    public MandateMenuStartupReport(MandateMenuConfigRepository repository,
                                    MenuRegistry menuRegistry,
                                    @Nullable ModuleRoleService moduleRoleService) {
        this.repository = repository;
        this.menuRegistry = menuRegistry;
        this.moduleRoleService = moduleRoleService;
    }

    /**
     * After startup, checks every tenant configuration against the menu tree and reports the dead
     * entries. Best effort: a read error must not endanger the startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void berichte() {
        try {
            pruefeAlleMandanten();
        } catch (Exception e) {
            log.warn("Mandanten-Menuelisten konnten beim Start nicht geprueft werden: {}", e.getMessage());
        }
    }

    private void pruefeAlleMandanten() {
        List<MandateMenuConfig> configs = repository.findAll();
        if (configs.isEmpty()) {
            log.info("Menuesteuerung: keine Mandanten-Konfiguration vorhanden — alle Menuepunkte sichtbar");
            return;
        }

        Set<String> titel = new TreeSet<>(menuRegistry.getAllMenuTitles());
        Set<String> keys = moduleRoleService == null ? Set.of() : moduleRoleService.getKnownModuleKeys();

        int betroffene = 0;
        List<String> ohneBefund = new ArrayList<>();
        for (MandateMenuConfig config : configs) {
            Set<String> tot = MandateMenuVisibilityService.deadEntries(config, titel, keys);
            if (tot.isEmpty()) {
                ohneBefund.add(config.getMandateName());
                continue;
            }
            betroffene++;
            meldeToteEintraege(config, tot);
        }

        log.info("Menuesteuerung: {} Mandanten-Konfigurationen geprueft gegen {} Menue-Titel und "
                        + "{} Modul-Keys — {} ohne Befund: {}",
                configs.size(), titel.size(), keys.size(), ohneBefund.size(), ohneBefund);
        if (betroffene > 0) {
            log.warn("Menuesteuerung: {} von {} Mandanten haben Listen-Eintraege, die ins Leere zeigen "
                            + "(siehe Meldungen oben). Bereinigen unter 'Root | Menuesteuerung'; die "
                            + "Diagnose-Ansicht 'Root | Menue-Diagnose' zeigt die Wirkung pro Menuepunkt.",
                    betroffene, configs.size());
        }
    }

    private void meldeToteEintraege(MandateMenuConfig config, Set<String> tot) {
        boolean whitelist = Boolean.TRUE.equals(config.getWhitelistMode());
        log.warn("Menuesteuerung: Mandant '{}' ({}-Modus) hat {} Listen-Eintraege ohne Entsprechung im "
                        + "Menuebaum — sie wirken nicht (umbenannter Menuepunkt?): {}",
                config.getMandateName(), whitelist ? "Whitelist" : "Blacklist", tot.size(), tot);
    }
}
