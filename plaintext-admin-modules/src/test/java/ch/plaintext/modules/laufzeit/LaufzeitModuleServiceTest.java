/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.audit.DestructiveActionAuditService;
import ch.plaintext.lader.ModulLader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Karte 1515: nur ROOT schreibt, Lader-Parser vor dem Schreiben, atomar, Rollback, Audit. */
class LaufzeitModuleServiceTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @TempDir
    Path dir;

    private final PlaintextSecurity security = mock(PlaintextSecurity.class);
    private final DestructiveActionAuditService audit = mock(DestructiveActionAuditService.class);
    private LaufzeitModuleService service;

    @BeforeEach
    void setUp() throws Exception {
        when(security.ifGranted("ROLE_ROOT")).thenReturn(true);
        when(security.getUser()).thenReturn("root@plaintext.ch");
        service = new LaufzeitModuleService(security, audit, dir.toString());
        registry("org.apache.pdfbox:pdfbox:3.0.5", C);
    }

    @AfterEach
    void aufraeumen() {
        System.clearProperty("loader.path");
    }

    private void registry(String lib, String libSha) throws Exception {
        Files.writeString(dir.resolve("registry.yaml"), """
                format: 2
                zug: {name: '2026-10-15', root: 1.780.0, kompatibel-ab: 1.767.0}
                module:
                - name: plaintext-root-common
                  klasse: Kern (root/admin)
                  art: huelle
                - name: plaintext-z-kontakte-api
                  klasse: app
                  art: api
                  versionen:
                  - {version: 2.1.0, root-min: 1.775.0, sha256: %1$s, api: [], pflicht: [], optional: [], bibliotheken: []}
                - name: plaintext-z-kontakte
                  klasse: app
                  art: modul
                  versionen:
                  - {version: 2.1.0, root-min: 1.775.0, sha256: %1$s, api: [plaintext-z-kontakte-api], pflicht: [], optional: [], bibliotheken: []}
                - name: plaintext-z-rechnungen
                  klasse: app
                  art: modul
                  versionen:
                  - version: 2.1.0
                    root-min: 1.775.0
                    sha256: %2$s
                    api: []
                    pflicht: [plaintext-z-kontakte]
                    optional: []
                    bibliotheken: [{gav: '%3$s', sha256: %4$s}]
                - name: plaintext-guild-member
                  klasse: guild
                  art: modul
                """.formatted(A, B, lib, libSha));
    }

    private String konfig() throws Exception {
        return Files.readString(dir.resolve(ModulLader.KONFIG));
    }

    private List<String> temps() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp")).toList();
        }
    }

    // --- nur ROOT ---

    @Test
    void rootSchreibtDieKonfig() throws Exception {
        List<String> neu = service.schreiben(List.of("plaintext-z-rechnungen", "plaintext-z-kontakte"));

        assertThat(neu).containsExactly("plaintext-z-rechnungen:2.1.0", "plaintext-z-kontakte:2.1.0",
                "plaintext-z-kontakte-api:2.1.0", "pdfbox:3.0.5 (Bibliothek)");
        assertThat(ModulLader.konfigPruefen(konfig())).isEqualTo(neu);
        assertThat(dir.resolve(ModulLader.KONFIG_VORHER)).doesNotExist();
        assertThat(temps()).isEmpty();
        verify(audit).logDestructiveAction(eq("UI"), eq("LAUFZEIT_MODULE_SCHREIBEN"), eq("module.yaml"),
                contains("plaintext-z-rechnungen:2.1.0"), contains("root@plaintext.ch"));
    }

    @Test
    void adminWirdAbgewiesenUndNichtsAendertSich() throws Exception {
        service.schreiben(List.of("plaintext-z-kontakte"));
        String vorher = konfig();
        when(security.ifGranted("ROLE_ROOT")).thenReturn(false);
        when(security.ifGranted("ROLE_ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> service.schreiben(List.of("plaintext-z-rechnungen", "plaintext-z-kontakte")))
                .isInstanceOf(SecurityException.class).hasMessageContaining("nur ROOT");
        assertThatThrownBy(service::rollback).isInstanceOf(SecurityException.class);
        assertThatThrownBy(service::stand).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.entwurf(List.of("plaintext-z-kontakte"))).isInstanceOf(SecurityException.class);

        assertThat(konfig()).isEqualTo(vorher);
        assertThat(dir.resolve(ModulLader.KONFIG_VORHER)).doesNotExist();
        verify(audit, never()).logDestructiveAction(anyString(), eq("LAUFZEIT_MODULE_ROLLBACK"), any(), any(), any());
    }

    // --- Rollback ---

    @Test
    void rollbackStelltDenVorigenStandHer() throws Exception {
        service.schreiben(List.of("plaintext-z-kontakte"));
        String erster = konfig();
        service.schreiben(List.of("plaintext-z-rechnungen", "plaintext-z-kontakte"));
        String zweiter = konfig();
        assertThat(Files.readString(dir.resolve(ModulLader.KONFIG_VORHER))).isEqualTo(erster);

        assertThat(service.rollback()).containsExactly("plaintext-z-kontakte:2.1.0", "plaintext-z-kontakte-api:2.1.0");
        assertThat(konfig()).isEqualTo(erster);
        assertThat(Files.readString(dir.resolve(ModulLader.KONFIG_VORHER))).isEqualTo(zweiter);
        verify(audit).logDestructiveAction(eq("UI"), eq("LAUFZEIT_MODULE_ROLLBACK"), eq("module.yaml"),
                contains("plaintext-z-kontakte:2.1.0"), contains("root@plaintext.ch"));

        service.rollback(); // ein zweiter Rollback macht den ersten rueckgaengig
        assertThat(konfig()).isEqualTo(zweiter);
        assertThat(temps()).isEmpty();
    }

    @Test
    void rollbackOhneVorherWirdAbgewiesen() {
        assertThatThrownBy(service::rollback).hasMessageContaining("kein vorheriger Stand");
    }

    // --- ungueltige Konfig vor dem Schreiben abgewiesen (Lader-Parser) ---

    @Test
    void konfigDieDerLaderAblehntWirdNichtGeschrieben() throws Exception {
        service.schreiben(List.of("plaintext-z-kontakte"));
        String vorher = konfig();
        // Grossbuchstaben im Namen: der Lader-Parser lehnt das ab (Muster fuer Name)
        registry("com.zaxxer:HikariCP:6.0.0", C);

        assertThatThrownBy(() -> service.schreiben(List.of("plaintext-z-rechnungen", "plaintext-z-kontakte")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ungueltige Bibliothek: com.zaxxer:HikariCP");
        assertThat(konfig()).isEqualTo(vorher);
        assertThat(dir.resolve(ModulLader.KONFIG_VORHER)).doesNotExist();
        assertThat(temps()).isEmpty();
    }

    @Test
    void falscherHashInDerRegistryWirdNichtGeschrieben() throws Exception {
        registry("org.apache.pdfbox:pdfbox:3.0.5", "kein-hash");
        assertThatThrownBy(() -> service.schreiben(List.of("plaintext-z-rechnungen", "plaintext-z-kontakte")))
                .hasMessageContaining("'sha256'");
        assertThat(dir.resolve(ModulLader.KONFIG)).doesNotExist();
    }

    @Test
    void fehlendeVorbedingungWirdAbgewiesen() {
        assertThatThrownBy(() -> service.schreiben(List.of("plaintext-z-rechnungen")))
                .hasMessageContaining("braucht 'plaintext-z-kontakte'");
        assertThat(dir.resolve(ModulLader.KONFIG)).doesNotExist();
    }

    @Test
    void nurModuleDerRegistryKeinFreierName() {
        for (String n : List.of("../../etc/passwd", "plaintext-root-common", "plaintext-guild-member")) {
            assertThatThrownBy(() -> service.schreiben(List.of(n))).as(n).hasMessageContaining("kein ladbares Modul");
        }
        assertThat(dir.resolve(ModulLader.KONFIG)).doesNotExist();
    }

    @Test
    void kaputteKonfigDarfUeberschriebenWerden() throws Exception {
        Files.writeString(dir.resolve(ModulLader.KONFIG), "voellig kaputt");
        service.schreiben(List.of("plaintext-z-kontakte"));
        assertThat(ModulLader.konfigPruefen(konfig())).hasSize(2);
        assertThat(Files.readString(dir.resolve(ModulLader.KONFIG_VORHER))).isEqualTo("voellig kaputt");
        verify(audit).logDestructiveAction(any(), eq("LAUFZEIT_MODULE_SCHREIBEN"), any(), any(), contains("unlesbar"));
    }

    // --- Stand / Diff ---

    @Test
    void standZeigtWasBeimNaechstenStartAndert() throws Exception {
        service.schreiben(List.of("plaintext-z-kontakte"));
        System.setProperty("loader.path", "/data/module/cache/plaintext-z-kontakte-2.1.0.jar,/data/module/cache/plaintext-z-alt-1.0.0.jar");

        LaufzeitModuleService.Stand s = service.stand();

        assertThat(s.registry()).extracting(LaufzeitModuleService.RegistryModul::name)
                .containsExactly("plaintext-z-kontakte-api", "plaintext-z-kontakte", "plaintext-z-rechnungen");
        assertThat(s.konfig()).containsExactly("plaintext-z-kontakte:2.1.0", "plaintext-z-kontakte-api:2.1.0");
        assertThat(s.neuBeimStart()).containsExactly("plaintext-z-kontakte-api-2.1.0.jar");
        assertThat(s.wegBeimStart()).containsExactly("plaintext-z-alt-1.0.0.jar");
        assertThat(s.registryFehler()).isNull();
    }

    @Test
    void ohneRegistryKeineAuswahl() throws Exception {
        Files.delete(dir.resolve("registry.yaml"));
        LaufzeitModuleService.Stand s = service.stand();
        assertThat(s.registry()).isEmpty();
        assertThat(s.registryFehler()).contains("keine");
        assertThatThrownBy(() -> service.schreiben(List.of("plaintext-z-kontakte"))).hasMessageContaining("keine");
    }
}
