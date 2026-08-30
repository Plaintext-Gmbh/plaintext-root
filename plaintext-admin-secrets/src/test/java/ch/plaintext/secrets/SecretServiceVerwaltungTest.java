/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zustandsbericht 29.08.2026, Massnahme 13 (JaCoCo-Gate): Ergaenzung zu
 * {@link SecretServiceResolveTest} (Aufloesung) um die Verwaltung — Anlegen/Setzen je Backend,
 * Soft-Delete nur im eigenen Mandanten, aktives Backend, Health und die Backend-Migration.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecretService: Verwaltung")
class SecretServiceVerwaltungTest {

    @Mock private SecretEntryRepository entryRepo;
    @Mock private SecretBackendConfigRepository configRepo;
    @Mock private SecretCrypto crypto;
    @Mock private VaultwardenSecretBackend vaultwarden;
    @Mock private HashiCorpVaultBackend hashicorp;
    @Mock private PasswordGenerator generator;
    @InjectMocks private SecretService service;

    private MockedStatic<PlaintextSecurityHolder> holder;

    @BeforeEach
    void setUp() {
        holder = mockStatic(PlaintextSecurityHolder.class);
        holder.when(PlaintextSecurityHolder::getMandat).thenReturn("plaintext");
        when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(crypto.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(crypto.decrypt(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s.substring(4, s.length() - 1);
        });
    }

    @AfterEach
    void tearDown() {
        holder.close();
    }

    private static SecretEntry eintrag(String name, SecretBackendType backend, String wertEncrypted) {
        SecretEntry e = new SecretEntry();
        e.setId(1L);
        e.setName(name);
        e.setMandat("plaintext");
        e.setBackendType(backend);
        e.setDeleted(false);
        e.setWertEncrypted(wertEncrypted);
        return e;
    }

    private static SecretBackendConfig konfig(SecretBackendType typ, boolean aktiv) {
        SecretBackendConfig c = new SecretBackendConfig();
        c.setMandat("plaintext");
        c.setBackendType(typ);
        c.setAktiv(aktiv);
        c.setDeleted(false);
        return c;
    }

    @Nested
    class Liste {

        @Test
        void ergaenztVaultwardenKommentareUndTolerierFehler() {
            SecretEntry vw = eintrag("a", SecretBackendType.VAULTWARDEN, null);
            SecretEntry vwKaputt = eintrag("b", SecretBackendType.VAULTWARDEN, null);
            SecretEntry lokal = eintrag("c", SecretBackendType.LOCAL_DB, "enc(x)");
            when(entryRepo.findByMandatAndDeletedOrderByNameAsc("plaintext", false))
                    .thenReturn(List.of(vw, vwKaputt, lokal));
            when(vaultwarden.comment("a")).thenReturn("Notiz aus dem Tresor");
            when(vaultwarden.comment("b")).thenThrow(new IllegalStateException("Tresor zu"));

            List<SecretEntry> liste = service.list();

            assertEquals(3, liste.size());
            assertEquals("Notiz aus dem Tresor", vw.getComment());
            assertNull(vwKaputt.getComment());
            assertNull(lokal.getComment());
        }
    }

    @Nested
    class Setzen {

        @Test
        void nameIstPflicht() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.set(" ", SecretBackendType.LOCAL_DB, "v", null));
        }

        @Test
        void lokalWirdVerschluesseltAbgelegt() {
            when(entryRepo.findByMandatAndName("plaintext", "jira")).thenReturn(Optional.empty());

            SecretEntry e = service.set("jira", SecretBackendType.LOCAL_DB, "geheim", "Notiz");

            assertEquals("jira", e.getName());
            assertEquals("plaintext", e.getMandat());
            assertEquals("Notiz", e.getNote());
            assertEquals("enc(geheim)", e.getWertEncrypted());
            assertEquals(Boolean.FALSE, e.getDeleted());
        }

        @Test
        void lokalOhneWertBehaeltDenAltenWert() {
            SecretEntry alt = eintrag("jira", SecretBackendType.LOCAL_DB, "enc(alt)");
            when(entryRepo.findByMandatAndName("plaintext", "jira")).thenReturn(Optional.of(alt));

            SecretEntry e = service.set("jira", SecretBackendType.LOCAL_DB, null, null);

            assertSame(alt, e);
            assertEquals("enc(alt)", e.getWertEncrypted());
            verify(crypto, never()).encrypt(anyString());
        }

        @Test
        void vaultwardenSchreibtInDenTresorUndHaeltLokalNichts() {
            when(entryRepo.findByMandatAndName("plaintext", "smtp")).thenReturn(Optional.empty());

            SecretEntry e = service.set("smtp", SecretBackendType.VAULTWARDEN, "pw", "Mail");

            verify(vaultwarden).set("smtp", "pw", "Mail");
            assertNull(e.getWertEncrypted());
            assertEquals(SecretBackendType.VAULTWARDEN, e.getBackendType());
        }

        @Test
        void vaultwardenOhneWertAendertNurMetadaten() {
            when(entryRepo.findByMandatAndName("plaintext", "smtp")).thenReturn(Optional.empty());

            service.set("smtp", SecretBackendType.VAULTWARDEN, "", "Mail");

            verify(vaultwarden, never()).set(anyString(), anyString(), any());
        }

        @Test
        void hashicorpSchreibtInDenTresorUndNichtInDieDatenbank() {
            when(entryRepo.findByMandatAndName("plaintext", "openbao-probe")).thenReturn(Optional.empty());
            when(entryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            SecretEntry e = service.set("openbao-probe", SecretBackendType.HASHICORP, "geheim", "Karte 855");

            verify(hashicorp).set("openbao-probe", "geheim", "Karte 855");
            verify(vaultwarden, never()).set(anyString(), anyString(), any());
            verify(crypto, never()).encrypt(anyString());
            // Der Wert darf NICHT zusaetzlich in der Datenbank landen — sonst haette das Umhaengen
            // eine zweite Kopie erzeugt, die niemand mitrotiert.
            assertNull(e.getWertEncrypted());
            assertEquals(SecretBackendType.HASHICORP, e.getBackendType());
        }

        @Test
        void hashicorpOhneWertAendertNurMetadaten() {
            SecretEntry vorhanden = eintrag("openbao-probe", SecretBackendType.VAULTWARDEN, null);
            when(entryRepo.findByMandatAndName("plaintext", "openbao-probe")).thenReturn(Optional.of(vorhanden));
            when(entryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            SecretEntry e = service.set("openbao-probe", SecretBackendType.HASHICORP, "", "umgehaengt");

            // Der Wert liegt schon im Ziel-Tresor; ein Schreibaufruf mit leerem Wert wuerde ihn
            // ueberschreiben. Nur die Zuordnung wechselt.
            verify(hashicorp, never()).set(anyString(), anyString(), any());
            assertEquals(SecretBackendType.HASHICORP, e.getBackendType());
        }
    }

    @Nested
    class Loeschen {

        @Test
        void softDeleteNurImEigenenMandanten() {
            SecretEntry eigen = eintrag("a", SecretBackendType.LOCAL_DB, null);
            SecretEntry fremd = eintrag("b", SecretBackendType.LOCAL_DB, null);
            fremd.setMandat("guild42");
            when(entryRepo.findById(1L)).thenReturn(Optional.of(eigen));
            when(entryRepo.findById(2L)).thenReturn(Optional.of(fremd));

            service.delete(1L);
            service.delete(2L);
            service.delete(3L);

            assertEquals(Boolean.TRUE, eigen.getDeleted());
            assertEquals(Boolean.FALSE, fremd.getDeleted());
            verify(entryRepo).save(eigen);
            verify(entryRepo, never()).save(fremd);
        }
    }

    @Test
    void resolveLocalValueEntschluesseltNurLokaleEintraege() {
        when(entryRepo.findByMandatAndName("plaintext", "a"))
                .thenReturn(Optional.of(eintrag("a", SecretBackendType.LOCAL_DB, "enc(klar)")));
        when(entryRepo.findByMandatAndName("plaintext", "b"))
                .thenReturn(Optional.of(eintrag("b", SecretBackendType.VAULTWARDEN, null)));

        assertEquals(Optional.of("klar"), service.resolveLocalValue("a"));
        assertTrue(service.resolveLocalValue("b").isEmpty());
    }

    @Nested
    class Backend {

        @Test
        void ohneKonfigurationIstVaultwardenDieVorgabe() {
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false)).thenReturn(Optional.empty());

            assertEquals(SecretBackendType.VAULTWARDEN, service.activeBackend());
            assertFalse(service.isConfigured());
        }

        @Test
        void aktivesBackendKommtAusDerKonfiguration() {
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.LOCAL_DB, true)));

            assertEquals(SecretBackendType.LOCAL_DB, service.activeBackend());
            assertTrue(service.isConfigured());
        }

        @Test
        void setActiveBackendDeaktiviertAlteUndVerschluesseltDieKonfiguration() {
            SecretBackendConfig alt = konfig(SecretBackendType.VAULTWARDEN, true);
            when(configRepo.findByMandatAndDeleted("plaintext", false)).thenReturn(List.of(alt));
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", false, false)).thenReturn(Optional.empty());

            service.setActiveBackend(SecretBackendType.LOCAL_DB, "{\"key\":1}");

            assertFalse(alt.isAktiv());
            ArgumentCaptor<SecretBackendConfig> captor = ArgumentCaptor.forClass(SecretBackendConfig.class);
            verify(configRepo, org.mockito.Mockito.atLeast(2)).save(captor.capture());
            SecretBackendConfig neu = captor.getAllValues().getLast();
            assertTrue(neu.isAktiv());
            assertEquals(SecretBackendType.LOCAL_DB, neu.getBackendType());
            assertEquals("plaintext", neu.getMandat());
            assertEquals("enc({\"key\":1})", neu.getConfigEncrypted());
        }

        @Test
        void healthJeBackend() {
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.LOCAL_DB, true)));
            when(crypto.isDevFallback()).thenReturn(true);
            assertFalse(service.health().ok(), "Dev-Fallback-Schluessel ist kein PROD-Zustand");
            assertTrue(service.isDevFallbackKey());

            when(crypto.isDevFallback()).thenReturn(false);
            assertTrue(service.health().ok());

            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.VAULTWARDEN, true)));
            when(vaultwarden.health()).thenReturn(SecretHealth.down("kein Token"));
            assertEquals("kein Token", service.health().detail());

            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.HASHICORP, true)));
            when(hashicorp.health()).thenReturn(SecretHealth.up("ok"));
            assertTrue(service.health().ok());
        }

        @Test
        void generatePasswordDelegiert() {
            when(generator.generate(12, true, true, true, false)).thenReturn("abc");
            assertEquals("abc", service.generatePassword(12, true, true, true, false));
        }
    }

    @Nested
    class Migration {

        @Test
        void zielBackendIstPflicht() {
            assertThrows(IllegalArgumentException.class, () -> service.migrate(null, null));
        }

        @Test
        void lokalNachVaultwardenLiestAltSchaltetUmUndSchreibtNeu() {
            SecretEntry mitWert = eintrag("a", SecretBackendType.LOCAL_DB, "enc(klar)");
            SecretEntry ohneWert = eintrag("b", SecretBackendType.LOCAL_DB, null);
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.LOCAL_DB, true)))
                    .thenReturn(Optional.of(konfig(SecretBackendType.VAULTWARDEN, true)));
            when(entryRepo.findByMandatAndDeletedOrderByNameAsc("plaintext", false)).thenReturn(List.of(mitWert, ohneWert));
            when(vaultwarden.health()).thenReturn(SecretHealth.up("ok"));

            SecretService.MigrationResult r = service.migrate(SecretBackendType.VAULTWARDEN, null);

            assertEquals(SecretBackendType.LOCAL_DB, r.from());
            assertEquals(SecretBackendType.VAULTWARDEN, r.to());
            assertEquals(1, r.migrated());
            assertEquals(1, r.skipped());
            verify(vaultwarden).set("a", "klar", null);
            assertNull(mitWert.getWertEncrypted());
            assertEquals(SecretBackendType.VAULTWARDEN, ohneWert.getBackendType());
        }

        @Test
        void vaultwardenNachLokalVerschluesseltDieWerte() {
            SecretEntry e = eintrag("a", SecretBackendType.VAULTWARDEN, null);
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.VAULTWARDEN, true)))
                    .thenReturn(Optional.of(konfig(SecretBackendType.LOCAL_DB, true)));
            when(entryRepo.findByMandatAndDeletedOrderByNameAsc("plaintext", false)).thenReturn(List.of(e));
            when(vaultwarden.readValue("a")).thenReturn("klar");
            when(crypto.isDevFallback()).thenReturn(true); // Ziel LOCAL_DB migriert auch bei Dev-Schluessel

            SecretService.MigrationResult r = service.migrate(SecretBackendType.LOCAL_DB, "{}");

            assertEquals(1, r.migrated());
            assertEquals("enc(klar)", e.getWertEncrypted());
            assertEquals(SecretBackendType.LOCAL_DB, e.getBackendType());
        }

        @Test
        void nichtErreichbaresZielBrichtAb() {
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(konfig(SecretBackendType.LOCAL_DB, true)))
                    .thenReturn(Optional.of(konfig(SecretBackendType.HASHICORP, true)));
            when(entryRepo.findByMandatAndDeletedOrderByNameAsc("plaintext", false)).thenReturn(List.of());
            when(hashicorp.health()).thenReturn(SecretHealth.down("kein Token"));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.migrate(SecretBackendType.HASHICORP, null));
            assertTrue(ex.getMessage().contains("kein Token"));
        }
    }
}
