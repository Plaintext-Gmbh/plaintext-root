/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway-Versionsschema (Karte 1069, A-04): neue Migrationsdateien muessen dem Unix-Epoch-Schema
 * folgen (CLAUDE.md: {@code date +%s}), nicht dem alten "Sekunden seit 2000".
 *
 * <p><b>Der Befund.</b> 56 Migrationsdateien im Reactor (Stand 06.09.2026: 29 in root, 27 in app,
 * 0 in guild — geprueft per {@code find} ueber alle drei Repos, nicht aus dem Sicherheitsbericht
 * vom 05.09.2026 uebernommen, der 40 nannte und damit die Zahl unterschaetzte) tragen noch die
 * alte, kuerzere Nummer ("Sekunden seit 2000", 9 Ziffern, Bereich V820... bis V839...). Drei davon
 * sind aus Juli/August 2026 — jünger als die ersten Epoch-Migrationen, aber wegen der kuerzeren
 * Zahl vor ihnen in der Flyway-Reihenfolge. Das erzwingt {@code out-of-order: true} in allen drei
 * {@code application.yml}, was Flyways Reihenfolge-Garantie insgesamt aufweicht (A-04 im Bericht).
 *
 * <p><b>Was diese Regel tut.</b> Jede Migrationsdatei im Reactor, die NICHT auf der festen Liste
 * der 56 bekannten Altdateien steht, muss dem Muster {@code V17\d{8}__...} folgen (10-stellige
 * Unix-Epoch-Sekunden, Stand 2026 beginnt das mit "17"). Die Liste ist in dieser Klasse fest
 * einprogrammiert statt ueber {@link ArchAllowlist} (Datei {@code plaintext-arch-allowlist.txt}):
 * root fuehrt selbst keine solche Datei (siehe deren Klassenkommentar — "das Framework muss die
 * eigenen Regeln ohne Ausnahme bestehen"), diese Regel braucht die Ausnahme aber genau dort fuer
 * die 29 root-eigenen Altdateien. Eine gemeinsame, im Jar mitgelieferte Liste (nach dem Vorbild
 * von {@link PlaintextTableSettingsDriftTest}, das Hashes statt eine Datei mitfuehrt) loest beides:
 * root, app und guild pruefen dieselbe Liste, jede findet nur ihre eigenen Migrationsdateien (per
 * {@link ReactorLayout#sourceRoots}, das ausschliesslich im aktuellen Reactor sucht).
 *
 * <p><b>Was sie nicht tut (bewusst, Karte 1069):</b> {@code out-of-order} bleibt vorerst auf
 * {@code true} — das auf {@code false} zurueckzunehmen ist ein eigener Folge-PR, NACHDEM diese
 * Regel in root, app und guild gruen ist (AGENT-BRIEFING Paket 2). Die beiden leeren
 * Testmigrationen in {@code plaintext-z-running} ({@code V821064957__test_empty.sql},
 * {@code V821065106__test_empty_2.sql}) werden hier nur auf der Allowlist gefuehrt, nicht mit
 * einem Kommentar in der Datei versehen — das ist eine separate Aufraeumarbeit, nicht Teil dieser
 * Regel.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextFlywayVersionSchemaTest {

    private static final String MIGRATION_SUFFIX = "src/main/resources/db/migration";

    /** Neues Schema: 10-stellige Unix-Epoch-Sekunden, Stand 2026 beginnt das mit "17". */
    private static final Pattern NEUES_SCHEMA = Pattern.compile("V17\\d{8}__.*\\.sql");

    /**
     * Feste Liste der 56 bereits vorhandenen Altdateien (Stand 06.09.2026, Karte 1069) —
     * Pfade relativ zur Reactor-Wurzel, wie {@link ReactorLayout#relativ(Path)} sie liefert.
     * Neue Eintraege kommen hier NICHT mehr hinzu: eine neue Migration muss das neue Schema
     * tragen, das ist der Zweck der Regel.
     */
    private static final Set<String> ALTDATEIEN_ALLOWLIST = Set.of(
            "plaintext-admin-cron/src/main/resources/db/migration/V820503545__create_cron_tables.sql",
            "plaintext-admin-cron/src/main/resources/db/migration/V821465986__fix_invalid_cron_patterns.sql",
            "plaintext-admin-cron/src/main/resources/db/migration/V821516397__fix_cron_expressions.sql",
            "plaintext-admin-cron/src/main/resources/db/migration/V821518200__add_cron_statistics_columns.sql",
            "plaintext-admin-modules/src/main/resources/db/migration/V837712929__create_module_config.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820503544__create_anforderung.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820595160__add_claude_automation.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820611675__extend_claude_automation.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820625502__remove_cron_and_template.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820629636__create_api_settings_and_cleanup_anforderung.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820661894__fix_lockfile_references.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820662288__refactor_anforderung_fields.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820671802__create_howto_and_add_howto_ids.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820708164__fix_missing_howto_table.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820751400__increase_beschreibung_length.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820751865__remove_menu_name_from_api_settings.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820752808__increase_beschreibung_length_fix.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820774656__add_mandat_to_anforderungen_entities.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V820887908__refactor_howto_table_fields.sql",
            "plaintext-admin-requirements/src/main/resources/db/migration/V821036618__add_wiederkehrend_tage.sql",
            "plaintext-admin-secrets/src/main/resources/db/migration/V837672022__create_secret_store.sql",
            "plaintext-admin-sessions/src/main/resources/db/migration/V820503551__create_user_session.sql",
            "plaintext-admin-settings/src/main/resources/db/migration/V820503552__create_setting.sql",
            "plaintext-admin-settings/src/main/resources/db/migration/V828995680__add_password_management_to_setup_config.sql",
            "plaintext-admin-settings/src/main/resources/db/migration/V829317139__add_root_user_enabled_to_setup_config.sql",
            "plaintext-gear/src/main/resources/db/migration/V822788475__create_gear_tables.sql",
            "plaintext-root-menu-visibility/src/main/resources/db/migration/V820503558__create_menu_tables.sql",
            "plaintext-root-role-assignment/src/main/resources/db/migration/V820503550__create_rollenzuteilung.sql",
            "plaintext-root-webapp/src/main/resources/db/migration/V820503559__create_webapp_tables.sql",
            "plaintext-root-webapp/src/main/resources/db/migration/V827340596__add_version_to_simple_storable_entity.sql",
            "plaintext-z-bielerlauftage/src/main/resources/db/migration/V833356941__create_bieler_tables.sql",
            "plaintext-z-bielerlauftage/src/main/resources/db/migration/V833396356__bieler_tracking_toggle.sql",
            "plaintext-z-bielerlauftage/src/main/resources/db/migration/V833399566__bieler_rennen_bibliothek.sql",
            "plaintext-z-bielerlauftage/src/main/resources/db/migration/V833402997__bieler_prognose.sql",
            "plaintext-z-bielerlauftage/src/main/resources/db/migration/V833452998__bieler_auf_karte_sichtbar.sql",
            "plaintext-z-bielerlauftage/src/main/resources/db/migration/V834469755__bieler_lauf_archiv.sql",
            "plaintext-z-kontakte/src/main/resources/db/migration/V820503548__create_kontakt_tables.sql",
            "plaintext-z-korrespondenz/src/main/resources/db/migration/V822129835__create_korrespondenz_tables.sql",
            "plaintext-z-rechnungen/src/main/resources/db/migration/V820503549__create_rechnung_tables.sql",
            "plaintext-z-running/src/main/resources/db/migration/V820862044__create_running_event.sql",
            "plaintext-z-running/src/main/resources/db/migration/V821061802__add_training_metrics_corrected.sql",
            "plaintext-z-running/src/main/resources/db/migration/V821062516__fix_training_metrics_column_names.sql",
            "plaintext-z-running/src/main/resources/db/migration/V821064957__test_empty.sql",
            "plaintext-z-running/src/main/resources/db/migration/V821065106__test_empty_2.sql",
            "plaintext-z-running/src/main/resources/db/migration/V821066175__drop_uppercase_flyway_table.sql",
            "plaintext-z-strom/src/main/resources/db/migration/V820503553__create_stromverbraucher.sql",
            "plaintext-z-strom/src/main/resources/db/migration/V821568323__create_strom_eauto_tables.sql",
            "plaintext-z-strom/src/main/resources/db/migration/V821575002__add_mqtt_fields_to_strom_eauto_settings.sql",
            "plaintext-z-verrechnung/src/main/resources/db/migration/V820503555__create_verrechnung_tables.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V820503556__create_zeiterfassung_tables.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V821108310__create_zeiterfassung_planung_table.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V821304672__add_kontakt_id_to_zeiterfassung_settings.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V822129836__create_zeiterfassung_calendar_token.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V822129851__add_export_token_to_zeiterfassung_settings.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V835866626__zeiterfassung_kalender_export_via_hosting.sql",
            "plaintext-z-zeiterfassung/src/main/resources/db/migration/V839357632__schliesse_offene_alt_zaehlungen.sql"
    );

    @Test
    @DisplayName("Neue Flyway-Migrationen tragen Unix-Epoch-Nummern (V17..........__...), keine der 56 bekannten Altdateien ausgenommen")
    void neueMigrationenFolgenDemEpochSchema() {
        List<String> fehler = new ArrayList<>();
        int geprueft = 0;
        for (Path wurzel : ReactorLayout.sourceRoots(MIGRATION_SUFFIX)) {
            List<Path> dateien = sqlDateien(wurzel);
            for (Path datei : dateien) {
                String relativ = ReactorLayout.relativ(datei);
                String dateiname = datei.getFileName().toString();
                geprueft++;
                if (ALTDATEIEN_ALLOWLIST.contains(relativ)) {
                    continue;
                }
                if (!NEUES_SCHEMA.matcher(dateiname).matches()) {
                    fehler.add(relativ);
                }
            }
        }
        // Positivkontrolle: eine Suche, die nichts findet, ist kein bestandener Test (Hausregel).
        assertTrue(geprueft > 0, "Keine Migrationsdatei gefunden -- sucht ReactorLayout.sourceRoots "
                + "am richtigen Ort? (\"" + MIGRATION_SUFFIX + "\")");
        int gesamt = geprueft;
        assertTrue(fehler.isEmpty(), () -> fehler.size() + " von " + gesamt + " Migrationsdatei(en) "
                + "folgen weder dem alten Schema (bekannt und auf der Allowlist) noch dem neuen "
                + "Unix-Epoch-Schema V17..........__... (CLAUDE.md: `date +%s`, NICHT \"Sekunden seit "
                + "2000\"):\n  " + String.join("\n  ", fehler)
                + "\n\nNeue Migration: mit `./getflywaynr` im Projekt-Root eine gueltige Nummer ziehen. "
                + "Falls diese Datei tatsaechlich alt ist und nur vom Scan noch nicht erfasst wurde: "
                + "in ALTDATEIEN_ALLOWLIST aufnehmen und begruenden, statt sie unsichtbar zu lassen.");
    }

    private static List<Path> sqlDateien(Path wurzel) {
        if (!Files.isDirectory(wurzel)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(wurzel)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Migrationsverzeichnis nicht lesbar: " + wurzel, e);
        }
    }
}
