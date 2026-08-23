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
 * Meldet beim Start, welche in den Mandanten-Listen gespeicherten Eintraege im aktuellen
 * Menuebaum ins Leere zeigen — pro Mandant, mit Nennung der toten Eintraege.
 *
 * <p><b>Warum.</b> Die Mandanten-Listen speichern Menuepunkte unter ihrem <i>vollen Titel</i>
 * ({@code "Parent | Titel"}). Wird ein Menuepunkt umbenannt, passt der gespeicherte Titel auf
 * nichts mehr: im Blacklist-Modus blendet er nichts mehr aus, im Whitelist-Modus blendet er den
 * umbenannten Punkt still <i>aus</i> — beides ohne jede Rueckmeldung. Erhoben auf
 * app.plaintext.ch: 19 von 123 gespeicherten Titeln zeigten ins Leere.</p>
 *
 * <p>Das Vorbild ist die bestehende Meldung des {@link ModuleRoleService}
 * („Modul-Rolle konfiguriert fuer unbekannten Modul-Key"): eine Fehlkonfiguration bricht den Start
 * nicht ab, wird aber beim Boot als WARN sichtbar.</p>
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
     * @param repository        Quelle der Mandanten-Konfigurationen
     * @param menuRegistry      liefert die aktuell vorhandenen Menue-Titel
     * @param moduleRoleService liefert die erkannten Modul-Keys, darf {@code null} sein
     */
    public MandateMenuStartupReport(MandateMenuConfigRepository repository,
                                    MenuRegistry menuRegistry,
                                    @Nullable ModuleRoleService moduleRoleService) {
        this.repository = repository;
        this.menuRegistry = menuRegistry;
        this.moduleRoleService = moduleRoleService;
    }

    /**
     * Prueft nach dem Start jede Mandanten-Konfiguration gegen den Menuebaum und meldet die toten
     * Eintraege. Best effort: ein Fehler beim Lesen darf den Start nicht gefaehrden.
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
