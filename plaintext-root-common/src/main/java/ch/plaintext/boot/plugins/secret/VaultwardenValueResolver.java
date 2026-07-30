/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Uebersetzt einen {@code vault:}-Property-Wert in das entsprechende Secret aus
 * Vaultwarden. Kapselt Parsing der drei Syntaxformen, die
 * Namenskonventions-Pruefung, das Caching pro Boot und das Fail-fast-Verhalten.
 *
 * <p>Der {@link VaultwardenSecretService} wird ueber einen {@link Supplier} LAZY
 * bezogen und erst beim ersten aufzuloesenden Wert instanziiert (ein einziges Mal,
 * danach gecacht). So ist der Resolver unabhaengig vom Spring-Context testbar
 * (gemockter Service).</p>
 */
@Slf4j
class VaultwardenValueResolver {

    /** Wert-Prefix, das eine Tresor-Referenz kennzeichnet. */
    static final String VAULT_PREFIX = "vault:";

    /** Selektor-Trenner zwischen Item-Name und Feld-Selektor. */
    private static final char SELECTOR_SEP = '#';

    /** Prefix des Custom-Feld-Selektors ({@code #field:<name>}). */
    private static final String FIELD_SELECTOR = "field:";

    /**
     * Namenskonvention {@code <app>.<key>} (z.B. {@code app.jira-bit-admin}).
     * Passt der Item-Name nicht, wird nur ge-WARN-t, aber trotzdem aufgeloest.
     */
    private static final Pattern NAME_CONVENTION = Pattern.compile("^[a-z0-9-]+\\.[a-z0-9-]+");

    private final Supplier<VaultwardenSecretService> serviceSupplier;
    /** Pro Boot aufgeloeste Werte (Key = kompletter Roh-Wert inkl. {@code vault:}-Prefix). */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile VaultwardenSecretService service;

    VaultwardenValueResolver(Supplier<VaultwardenSecretService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    /** {@code true}, wenn der Wert eine {@code vault:}-Referenz ist. */
    static boolean isVaultReference(Object value) {
        return value instanceof String s && s.startsWith(VAULT_PREFIX);
    }

    /**
     * Loest einen {@code vault:}-Roh-Wert auf.
     *
     * @param propertyName Property-Schluessel (nur fuer Log/Exception, kein Secret)
     * @param rawValue     Roh-Wert mit {@code vault:}-Prefix
     * @return der aufgeloeste Secret-Wert (nie {@code null})
     * @throws VaultwardenPropertyResolutionException wenn nicht aufloesbar (Fail-fast)
     */
    String resolve(String propertyName, String rawValue) {
        String cached = cache.get(rawValue);
        if (cached != null) {
            return cached;
        }

        String spec = rawValue.substring(VAULT_PREFIX.length()).trim();
        int sep = spec.indexOf(SELECTOR_SEP);
        String itemName = (sep >= 0 ? spec.substring(0, sep) : spec).trim();
        String selector = sep >= 0 ? spec.substring(sep + 1).trim() : "";

        if (itemName.isEmpty()) {
            throw new VaultwardenPropertyResolutionException(propertyName, itemName, "leerer Item-Name");
        }
        if (!NAME_CONVENTION.matcher(itemName).find()) {
            log.warn("Vault-Item '{}' (Property '{}') folgt nicht der Namenskonvention app.key",
                    itemName, propertyName);
        }

        VaultwardenSecretService svc = service();
        if (!svc.isEnabled()) {
            throw new VaultwardenPropertyResolutionException(propertyName, itemName,
                    "Vaultwarden ist deaktiviert (plaintext.vault.enabled=false)");
        }

        Optional<String> value;
        String kind;
        if (selector.isEmpty() || selector.equalsIgnoreCase("password")) {
            value = svc.getPassword(itemName);
            kind = "Passwort";
        } else if (selector.equalsIgnoreCase("username")) {
            value = svc.getUsername(itemName);
            kind = "Username";
        } else if (selector.regionMatches(true, 0, FIELD_SELECTOR, 0, FIELD_SELECTOR.length())) {
            String fieldName = selector.substring(FIELD_SELECTOR.length()).trim();
            value = svc.getField(itemName, fieldName);
            kind = "Feld '" + fieldName + "'";
        } else {
            throw new VaultwardenPropertyResolutionException(propertyName, itemName,
                    "unbekannter Selektor '#" + selector + "'");
        }

        String result = value.orElseThrow(() -> new VaultwardenPropertyResolutionException(
                propertyName, itemName,
                kind + " nicht im Tresor gefunden (oder Vault-Login fehlgeschlagen)"));
        cache.put(rawValue, result);
        return result;
    }

    /** Lazy-Singleton: Service genau einmal beziehen (erst beim ersten vault:-Wert). */
    private VaultwardenSecretService service() {
        VaultwardenSecretService s = service;
        if (s == null) {
            synchronized (this) {
                s = service;
                if (s == null) {
                    s = serviceSupplier.get();
                    service = s;
                }
            }
        }
        return s;
    }
}
