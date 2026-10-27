/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.audit.DestructiveActionAuditService;
import ch.plaintext.lader.ModulAuswahl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Karte 1608: ADMIN sieht, nur ROOT schaltet; Auswahl nur aus dem Angebot; Neustart per Exit-Code. */
class ModulAuswahlServiceTest {

    @TempDir
    Path module;
    @TempDir
    Path konfig;

    private final PlaintextSecurity security = mock(PlaintextSecurity.class);
    private final DestructiveActionAuditService audit = mock(DestructiveActionAuditService.class);
    private final AtomicInteger beendet = new AtomicInteger();
    private ModulAuswahlService service;
    private HttpServer server;
    private final List<String> index = new ArrayList<>();
    private KeyPair schluessel;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            byte[] b = String.join("\n", index).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
            ex.close();
        });
        server.start();
        schluessel = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        index.add("plaintext-modul-index 1");
        zeile("plaintext-z-a", "1.0.0", schluessel);
        zeile("plaintext-z-a", "1.10.0", schluessel);
        zeile("plaintext-z-b", "2.0.0", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        Files.writeString(konfig.resolve(ModulAuswahl.QUELLEN), "schluessel ed25519 "
                + Base64.getEncoder().encodeToString(schluessel.getPublic().getEncoded()) + "\nregistry test http://127.0.0.1:"
                + server.getAddress().getPort() + "/index.txt\n");
        when(security.getUser()).thenReturn("root@plaintext.ch");
        service = new ModulAuswahlService(security, audit, mock(ApplicationContext.class), module.toString(), konfig.toString()) {
            @Override
            void beenden() {
                beendet.incrementAndGet();
            }
        };
    }

    @AfterEach
    void stoppen() {
        server.stop(0);
    }

    private void zeile(String name, String version, KeyPair k) throws Exception {
        ModulAuswahl.Zeile z = new ModulAuswahl.Zeile("modul", name, version, "1.770.0", "a".repeat(64), List.of(), List.of(),
                "x.jar", "x", "test", null);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(z.nachricht().getBytes(StandardCharsets.UTF_8));
        index.add(z.alsIndexZeile().replace("signatur=x", "signatur=" + Base64.getEncoder().encodeToString(s.sign())));
    }

    private void als(String rolle) {
        when(security.ifGranted("ROLE_ROOT")).thenReturn("ROOT".equals(rolle));
        when(security.ifGranted("ROLE_ADMIN")).thenReturn("ADMIN".equals(rolle));
    }

    @Test
    void adminSiehtAlleModuleNeuesteZuerstMitSignaturstatus() throws Exception {
        als("ADMIN");
        ModulAuswahlService.Ansicht a = service.ansicht();

        assertThat(a.darfSchalten()).isFalse();
        assertThat(a.module()).extracting(ModulAuswahlService.Modul::name).containsExactly("plaintext-z-a", "plaintext-z-b");
        assertThat(a.module().get(0).versionen()).extracting(ModulAuswahlService.Version::version)
                .containsExactly("1.10.0", "1.0.0");
        assertThat(a.module().get(0).versionen().get(0).signiert()).isTrue();
        assertThat(a.module().get(1).versionen().get(0).signiert()).isFalse();
        assertThat(a.registryFehler()).isEmpty();
    }

    @Test
    void userSiehtNichts() {
        als("USER");
        assertThatThrownBy(service::ansicht).isInstanceOf(SecurityException.class);
    }

    @Test
    void rootSpeichertAuswahlMitVorherUndAudit() throws Exception {
        als("ROOT");
        service.speichern(List.of(new ModulAuswahl.Wahl("plaintext-z-a", "latest")));
        service.speichern(List.of(new ModulAuswahl.Wahl("plaintext-z-a", "1.0.0")));

        assertThat(ModulAuswahl.auswahlParsen(Files.readString(module.resolve(ModulAuswahl.AUSWAHL)), "t"))
                .containsExactly(new ModulAuswahl.Wahl("plaintext-z-a", "1.0.0"));
        assertThat(Files.readString(module.resolve(ModulAuswahl.AUSWAHL_VORHER))).contains("version: latest");
        verify(audit).logDestructiveAction(eq("UI"), eq("MODUL_AUSWAHL_SPEICHERN"), eq("auswahl.yaml"),
                eq("plaintext-z-a:1.0.0"), anyString());
        assertThat(service.ansicht().module().get(0).gewaehlt()).isEqualTo("1.0.0");
    }

    @Test
    void adminDarfNichtSpeichernUndNichtNeuStarten() {
        als("ADMIN");
        assertThatThrownBy(() -> service.speichern(List.of(new ModulAuswahl.Wahl("plaintext-z-a", "latest"))))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(service::neustart).isInstanceOf(SecurityException.class);

        assertThat(module.resolve(ModulAuswahl.AUSWAHL)).doesNotExist();
        assertThat(beendet).hasValue(0);
        verify(audit, never()).logDestructiveAction(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nurAngeboteneModuleUndVersionen() {
        als("ROOT");
        assertThatThrownBy(() -> service.speichern(List.of(new ModulAuswahl.Wahl("plaintext-z-gibtsnicht", "latest"))))
                .hasMessageContaining("steht in keiner erreichbaren Registry");
        assertThatThrownBy(() -> service.speichern(List.of(new ModulAuswahl.Wahl("plaintext-z-a", "9.9.9"))))
                .hasMessageContaining("9.9.9 steht in keiner Registry");
        assertThat(module.resolve(ModulAuswahl.AUSWAHL)).doesNotExist();
    }

    @Test
    void rootStartetNeuMitAudit() {
        als("ROOT");
        service.neustart();

        assertThat(beendet).hasValue(1);
        verify(audit).logDestructiveAction(eq("UI"), eq("MODUL_AUSWAHL_NEUSTART"), eq("auswahl.yaml"),
                eq("Neustart mit Exit 75"), anyString());
    }

    @Test
    void beanVerweigertOhneRootOhneDenDienstZuRufen() throws Exception {
        ModulAuswahlService s = mock(ModulAuswahlService.class);
        when(s.darfSchalten()).thenReturn(false);
        ModulAuswahlBackingBean bean = new ModulAuswahlBackingBean();
        bean.setService(s);

        bean.speichern();
        bean.neustart();

        verify(s, never()).speichern(org.mockito.ArgumentMatchers.any());
        verify(s, never()).neustart();
        assertThat(bean.verweigert()).isTrue();
    }

    @Test
    void beanBautZeilenAusDerAnsicht() throws Exception {
        als("ROOT");
        service.speichern(List.of(new ModulAuswahl.Wahl("plaintext-z-a", "1.0.0")));
        ModulAuswahlBackingBean bean = new ModulAuswahlBackingBean();
        bean.setService(service);

        bean.load();

        assertThat(bean.getZeilen()).extracting(ModulAuswahlBackingBean.Zeile::getName).containsExactly("plaintext-z-a", "plaintext-z-b");
        assertThat(bean.getZeilen().get(0).isAktiv()).isTrue();
        assertThat(bean.getZeilen().get(0).getOptionen()).containsExactly("latest", "1.10.0", "1.0.0");
        assertThat(bean.getZeilen().get(1).getHinweis()).contains("Signatur ungültig");
    }
}
