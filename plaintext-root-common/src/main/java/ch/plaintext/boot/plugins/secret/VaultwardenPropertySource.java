/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * {@link PropertySource}, die AN ERSTER STELLE im Environment liegt und
 * {@code vault:}-Werte transparent aus Vaultwarden aufloest (analog zu Spring
 * Cloud Vault).
 *
 * <p>{@link #getProperty(String)} fragt die uebrigen Property-Sources nach dem
 * Roh-Wert (die eigene Source wird per Identitaet uebersprungen). Beginnt der
 * Roh-Wert mit {@code vault:}, wird er aufgeloest; sonst wird {@code null}
 * geliefert, sodass der normale Resolver-Durchlauf zur echten Source
 * weiterlaeuft (Durchfall).</p>
 *
 * <p><b>Re-Entranz-Schutz:</b> Ein {@link ThreadLocal}-Guard verhindert, dass die
 * Lazy-Initialisierung des Vault-Clients (die per {@link Binder} selbst wieder
 * {@code plaintext.vault.*} aus dem Environment liest und damit erneut durch
 * diese Source laeuft) in eine Endlosrekursion kippt: waehrend eine Aufloesung
 * laeuft, liefert diese Source fuer verschachtelte Zugriffe sofort {@code null}.</p>
 */
class VaultwardenPropertySource extends PropertySource<Object> {

    /** Name dieser Source im Environment. */
    static final String SOURCE_NAME = "vaultwardenSecrets";

    /** Guard gegen Re-Entranz waehrend der Lazy-Client-Initialisierung / Aufloesung. */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ConfigurableEnvironment environment;
    private final VaultwardenValueResolver resolver;

    VaultwardenPropertySource(ConfigurableEnvironment environment) {
        this(environment, new VaultwardenValueResolver(() -> buildService(environment)));
    }

    /** Test-Konstruktor mit injizierbarem Resolver (gemockter Service). */
    VaultwardenPropertySource(ConfigurableEnvironment environment, VaultwardenValueResolver resolver) {
        super(SOURCE_NAME);
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public Object getProperty(String name) {
        if (name == null || Boolean.TRUE.equals(RESOLVING.get())) {
            // Re-Entranz (z.B. Binder-Zugriff waehrend der Client-Init): nicht erneut auf-
            // loesen, Roh-Wert soll aus den echten Sources kommen.
            return null;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            Object raw = rawFromOtherSources(name);
            if (!VaultwardenValueResolver.isVaultReference(raw)) {
                return null; // Durchfall: normaler Wert -> echte Source uebernimmt
            }
            return resolver.resolve(name, (String) raw);
        } finally {
            RESOLVING.remove();
        }
    }

    /**
     * Diese Source fuehrt keine eigenen Keys ein — sie transformiert nur Werte von
     * Keys, die bereits in anderen Sources existieren. Ein {@code contains}-Check
     * darf daher KEINEN Vault-Login ausloesen: die Existenz beantwortet die echte
     * Source.
     */
    @Override
    public boolean containsProperty(String name) {
        return false;
    }

    /** Roh-Wert des Properties aus allen uebrigen Sources (eigene per Identitaet uebersprungen). */
    private Object rawFromOtherSources(String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source == this) {
                continue;
            }
            Object value = source.getProperty(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Baut den {@link VaultwardenSecretService} Spring-frei aus den
     * {@code plaintext.vault.*}-Werten des Environments (relaxed binding via
     * {@link Binder}). Wird nur waehrend einer laufenden Aufloesung aufgerufen
     * (Guard aktiv), daher liest der Binder die Roh-Werte aus den echten Sources.
     */
    static VaultwardenSecretService buildService(ConfigurableEnvironment environment) {
        VaultwardenProperties props = Binder.get(environment)
                .bind("plaintext.vault", VaultwardenProperties.class)
                .orElseGet(VaultwardenProperties::new);
        String appName = environment.getProperty("spring.application.name", "plaintext");
        VaultwardenClient client = new VaultwardenClient(props, appName);
        return new VaultwardenSecretService(props, client);
    }
}
