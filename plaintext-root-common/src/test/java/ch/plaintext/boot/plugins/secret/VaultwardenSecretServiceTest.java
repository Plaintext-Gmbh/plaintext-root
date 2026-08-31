/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VaultwardenSecretService}: fail-safe item matching (exact before
 * contains), deactivation, null/blank inputs, the getter filter on non-empty values, exception
 * encapsulation and the rotation preconditions. The {@link VaultwardenClient} is mocked (no network).
 */
class VaultwardenSecretServiceTest {

    private static VaultwardenProperties props(boolean enabled) {
        VaultwardenProperties p = new VaultwardenProperties();
        p.setEnabled(enabled);
        return p;
    }

    private static VaultwardenItem item(String id, String name, String user, String pw, Map<String, String> fields) {
        return new VaultwardenItem(id, name, user, pw, fields);
    }

    // ── isEnabled ─────────────────────────────────────────────────────────────

    @Test
    void isEnabledSpiegeltProperties() {
        assertThat(new VaultwardenSecretService(props(true), mock(VaultwardenClient.class)).isEnabled()).isTrue();
        assertThat(new VaultwardenSecretService(props(false), mock(VaultwardenClient.class)).isEnabled()).isFalse();
    }

    // ── Item matching / getter ────────────────────────────────────────────────

    @Test
    void getPasswordExakterNameGewinntVorEnthaelt() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(
                item("1", "Paperless ngx Admin", "u1", "enthaelt-pw", Map.of()),
                item("2", "Paperless", "u2", "exakt-pw", Map.of())));

        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        // "paperless" matches item 2 exactly (case-insensitive) despite an earlier contains candidate.
        assertThat(svc.getPassword("paperless")).contains("exakt-pw");
    }

    @Test
    void getPasswordFaelltAufEnthaeltZurueck() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(
                item("1", "Paperless ngx Admin (example.invalid)", "u1", "geheim", Map.of())));

        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getPassword("Paperless")).contains("geheim");
    }

    @Test
    void getUsernameLiefertBenutzernamen() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(item("1", "Item", "admin", "pw", Map.of())));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getUsername("Item")).contains("admin");
    }

    @Test
    void getFieldCaseInsensitiv() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(
                item("1", "Item", "u", "pw", Map.of("API-Token", "tok-123"))));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getField("Item", "api-token")).contains("tok-123");
        assertThat(svc.getField("Item", "unbekannt")).isEmpty();
    }

    @Test
    void getSecretLiefertGesamtesItem() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(item("42", "Item", "u", "pw", Map.of())));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        Optional<VaultwardenItem> found = svc.getSecret("Item");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("42");
    }

    @Test
    void leererWertWirdHerausgefiltert() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(item("1", "Item", "", "", Map.of())));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getPassword("Item")).isEmpty();
        assertThat(svc.getUsername("Item")).isEmpty();
    }

    @Test
    void keinTrefferLiefertEmpty() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenReturn(List.of(item("1", "Anderes", "u", "pw", Map.of())));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getPassword("Gibtsnicht")).isEmpty();
    }

    // ── Deactivated / invalid inputs ──────────────────────────────────────────

    @Test
    void deaktivierterVaultLiefertEmptyUndFragtClientNie() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        VaultwardenSecretService svc = new VaultwardenSecretService(props(false), client);
        assertThat(svc.getPassword("Item")).isEmpty();
        verify(client, never()).getItems();
    }

    @Test
    void nullOderBlankItemNameLiefertEmpty() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getPassword(null)).isEmpty();
        assertThat(svc.getPassword("   ")).isEmpty();
        verify(client, never()).getItems();
    }

    @Test
    void clientFehlerWirdGekapseltNichtGeworfen() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.getItems()).thenThrow(new RuntimeException("boom"));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.getPassword("Item")).isEmpty();
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    @Test
    void rotationBeiDeaktiviertemVaultFalseOhneClient() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        VaultwardenSecretService svc = new VaultwardenSecretService(props(false), client);
        assertThat(svc.rotatePassword("Item", "neu")).isFalse();
        verify(client, never()).rotatePassword("Item", "neu");
    }

    @Test
    void rotationMitFehlendenEingabenFalse() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.rotatePassword(null, "neu")).isFalse();
        assertThat(svc.rotatePassword("  ", "neu")).isFalse();
        assertThat(svc.rotatePassword("Item", null)).isFalse();
        verify(client, never()).rotatePassword(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rotationErfolgDelegiertAnClient() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.rotatePassword("Item", "neu")).thenReturn(true);
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.rotatePassword("Item", "neu")).isTrue();
        verify(client).rotatePassword("Item", "neu");
    }

    @Test
    void rotationClientFehlerWirdGekapseltFalse() {
        VaultwardenClient client = mock(VaultwardenClient.class);
        when(client.rotatePassword("Item", "neu")).thenThrow(new RuntimeException("io"));
        VaultwardenSecretService svc = new VaultwardenSecretService(props(true), client);
        assertThat(svc.rotatePassword("Item", "neu")).isFalse();
    }
}
