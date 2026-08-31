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
 * Translates a {@code vault:} property value into the corresponding secret from
 * Vaultwarden. Encapsulates the parsing of the three syntax forms, the naming
 * convention check, the per-boot caching and the fail-fast behaviour.
 *
 * <p>The {@link VaultwardenSecretService} is obtained LAZILY through a
 * {@link Supplier} and only instantiated on the first value to be resolved (exactly
 * once, cached afterwards). That makes the resolver testable independently of the
 * Spring context (mocked service).</p>
 *
 * <h2>Boot retry on a transient disturbance</h2>
 * <p>If the resolution fails because the vault access itself failed (login/sync error,
 * timeout, HTTP 429), the resolver waits and retries several times BEFORE failing fast — after
 * a 429 deliberately for a long time (the rate limit only refills over time). Only when the
 * sync SUCCEEDED and the item is still missing does the boot abort immediately: the empty
 * answer is definitive then (the typo case schuetu.remember-me-keyn, 18.08.2026). What
 * prompted the retry: 21.08.2026 — a crash-looping neighbouring container kept the shared
 * login bucket empty, and guild 1.372.0 as well as app-snapshot failed on their first and
 * only attempt.</p>
 */
@Slf4j
class VaultwardenValueResolver {

    /** Value prefix that marks a vault reference. */
    static final String VAULT_PREFIX = "vault:";

    /**
     * Second value prefix: the same mechanism, but against OpenBao instead of Vaultwarden
     * (card 995). Syntax: {@code bao:<path>} or {@code bao:<path>#<field>}; without a field
     * {@code value} applies.
     *
     * <p><b>Additive, and deliberately so:</b> every existing {@code vault:} value stays
     * untouched and keeps going through Vaultwarden. A property migrates one at a time by
     * rewriting its value from {@code vault:} to {@code bao:} — no cut-off date, no big bang,
     * and the way back is the same line in reverse.</p>
     */
    static final String BAO_PREFIX = "bao:";

    /** Field a {@code bao:} reference reads when it carries no selector. */
    static final String BAO_DEFAULT_FELD = "value";

    /** Selector separator between the item name and the field selector. */
    private static final char SELECTOR_SEP = '#';

    /** Prefix of the custom field selector ({@code #field:<name>}). */
    private static final String FIELD_SELECTOR = "field:";

    /**
     * Naming convention {@code <app>.<key>} (e.g. {@code app.jira-bit-admin}).
     * If the item name does not match, only a WARN is logged, but it is resolved anyway.
     */
    private static final Pattern NAME_CONVENTION = Pattern.compile("^[a-z0-9-]+\\.[a-z0-9-]+");

    /** Boot retry: total number of read attempts per reference on a transient disturbance. */
    static final int BOOT_MAX_VERSUCHE = 4;

    /** Waiting time before the first boot retry; doubles with every further attempt (5s, 10s, 20s). */
    static final long BOOT_WARTE_START_MS = 5_000;

    /**
     * Waiting time after an HTTP 429: just ABOVE the default refill rate of the Vaultwarden login
     * limiter (1 token per 60s). Waiting any less would mean sending the next attempt straight
     * back into the empty bucket.
     */
    static final long BOOT_WARTE_RATE_LIMIT_MS = 65_000;

    /** Sleep hook — {@link Thread#sleep(long)} in production, a recorder without real time in the test. */
    @FunctionalInterface
    interface Schlaefer {
        void schlafe(long millis) throws InterruptedException;
    }

    private final Supplier<VaultwardenSecretService> serviceSupplier;
    /** Supplies the OpenBao client — lazy like the Vaultwarden service, and for the same reason. */
    private final Supplier<OpenBaoClient> baoSupplier;
    private final Schlaefer schlaefer;
    /** Values resolved per boot (key = the complete raw value including the {@code vault:} prefix). */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile VaultwardenSecretService service;

    VaultwardenValueResolver(Supplier<VaultwardenSecretService> serviceSupplier) {
        this(serviceSupplier, () -> null, Thread::sleep);
    }

    VaultwardenValueResolver(Supplier<VaultwardenSecretService> serviceSupplier, Schlaefer schlaefer) {
        this(serviceSupplier, () -> null, schlaefer);
    }

    VaultwardenValueResolver(Supplier<VaultwardenSecretService> serviceSupplier,
                             Supplier<OpenBaoClient> baoSupplier, Schlaefer schlaefer) {
        this.serviceSupplier = serviceSupplier;
        this.baoSupplier = baoSupplier;
        this.schlaefer = schlaefer;
    }

    /** {@code true} when the value is a vault reference — {@code vault:} OR {@code bao:}. */
    static boolean isVaultReference(Object value) {
        return value instanceof String s && (s.startsWith(VAULT_PREFIX) || s.startsWith(BAO_PREFIX));
    }

    /** {@code true} for {@code bao:} only — separates the two sources inside {@link #resolve}. */
    static boolean isBaoReference(Object value) {
        return value instanceof String s && s.startsWith(BAO_PREFIX);
    }

    /**
     * Resolves a {@code vault:} raw value.
     *
     * @param propertyName property key (only for log/exception, not a secret)
     * @param rawValue     raw value with the {@code vault:} prefix
     * @return the resolved secret value (never {@code null})
     * @throws VaultwardenPropertyResolutionException when it cannot be resolved (fail fast)
     */
    String resolve(String propertyName, String rawValue) {
        String cached = cache.get(rawValue);
        if (cached != null) {
            return cached;
        }

        if (isBaoReference(rawValue)) {
            String result = resolveBao(propertyName, rawValue);
            cache.put(rawValue, result);
            return result;
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

        String kind = artDesSelektors(propertyName, itemName, selector);
        Optional<String> value = mitBootRetry(leseWert(svc, itemName, selector),
                svc, propertyName, itemName, selector);

        String result = value.orElseThrow(() -> new VaultwardenPropertyResolutionException(
                propertyName, itemName, fehlertext(kind, svc)));
        cache.put(rawValue, result);
        return result;
    }

    /**
     * Resolves a {@code bao:} reference against OpenBao.
     *
     * <p>Syntax: {@code bao:<path>} reads the field {@code value}, {@code bao:<path>#<field>}
     * reads another one. Deliberately plainer than the Vaultwarden syntax: there we have password,
     * username and custom fields because a Bitwarden item has that structure. A KV v2 entry is a
     * flat map — a second selector dialect would have modelled nothing that exists.</p>
     *
     * <p>Fail-fast like the Vaultwarden branch: a missing entry ends the startup instead of
     * letting the application run on with an empty secret. Transient disturbances (network,
     * HTTP 5xx, sealed vault) are retried beforehand — a sealed OpenBao is the normal case on a
     * cold start, not the exception.</p>
     */
    private String resolveBao(String propertyName, String rawValue) {
        String spec = rawValue.substring(BAO_PREFIX.length()).trim();
        int sep = spec.indexOf(SELECTOR_SEP);
        String pfad = (sep >= 0 ? spec.substring(0, sep) : spec).trim();
        String feld = sep >= 0 ? spec.substring(sep + 1).trim() : BAO_DEFAULT_FELD;

        if (pfad.isEmpty()) {
            throw new VaultwardenPropertyResolutionException(propertyName, pfad, "leerer OpenBao-Pfad");
        }
        if (feld.isEmpty()) {
            feld = BAO_DEFAULT_FELD;
        }

        OpenBaoClient client = baoSupplier.get();
        if (client == null) {
            throw new VaultwardenPropertyResolutionException(propertyName, pfad,
                    "OpenBao ist nicht konfiguriert (plaintext.bao.enabled=false oder token-file fehlt)");
        }

        Optional<String> wert = client.lies(pfad, feld);
        int versuch = 1;
        while (wert.isEmpty() && client.letzterFehlerWarTransient() && versuch < BOOT_MAX_VERSUCHE) {
            long warteMs = BOOT_WARTE_START_MS << (versuch - 1);
            log.warn("OpenBao-Eintrag '{}' (Property '{}') nicht lesbar ({}). Boot-Retry {}/{} in {}s.",
                    pfad, propertyName, client.letzterFehler(), versuch, BOOT_MAX_VERSUCHE - 1,
                    warteMs / 1000);
            try {
                schlaefer.schlafe(warteMs);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
            wert = client.lies(pfad, feld);
            versuch++;
        }

        return wert.orElseThrow(() -> new VaultwardenPropertyResolutionException(
                propertyName, pfad, "OpenBao: " + client.letzterFehler()));
    }

    /** Human-readable designation of the selector; throws on an unknown selector (fail fast). */
    private static String artDesSelektors(String propertyName, String itemName, String selector) {
        if (selector.isEmpty() || selector.equalsIgnoreCase("password")) {
            return "Passwort";
        }
        if (selector.equalsIgnoreCase("username")) {
            return "Username";
        }
        if (selector.regionMatches(true, 0, FIELD_SELECTOR, 0, FIELD_SELECTOR.length())) {
            return "Feld '" + selector.substring(FIELD_SELECTOR.length()).trim() + "'";
        }
        throw new VaultwardenPropertyResolutionException(propertyName, itemName,
                "unbekannter Selektor '#" + selector + "'");
    }

    /** Reads the value according to the selector (the selector is already validated here). */
    private static Optional<String> leseWert(VaultwardenSecretService svc, String itemName, String selector) {
        if (selector.isEmpty() || selector.equalsIgnoreCase("password")) {
            return svc.getPassword(itemName);
        }
        if (selector.equalsIgnoreCase("username")) {
            return svc.getUsername(itemName);
        }
        return svc.getField(itemName, selector.substring(FIELD_SELECTOR.length()).trim());
    }

    /**
     * Repeats the read operation for as long as the empty value is due to a TRANSIENT vault
     * disturbance (login/sync error, timeout, HTTP 429) — with a waiting time in between, after
     * a 429 deliberately {@value #BOOT_WARTE_RATE_LIMIT_MS}ms. An empty answer after a SUCCESSFUL
     * sync is definitive (the item really is missing) and is not repeated.
     */
    private Optional<String> mitBootRetry(Optional<String> wert, VaultwardenSecretService svc,
                                          String propertyName, String itemName, String selector) {
        int versuch = 1;
        while (wert.isEmpty() && svc.istLetzterZugriffTransientGescheitert()
                && versuch < BOOT_MAX_VERSUCHE) {
            long warteMs = svc.warLetzterFehlerRateLimit()
                    ? BOOT_WARTE_RATE_LIMIT_MS
                    : BOOT_WARTE_START_MS << (versuch - 1);
            log.warn("Vault-Item '{}' (Property '{}') wegen transienter Vaultwarden-Stoerung nicht"
                            + " lesbar ({}). Boot-Retry {}/{} in {}s.",
                    itemName, propertyName, svc.letzteVaultFehlermeldung(),
                    versuch, BOOT_MAX_VERSUCHE - 1, warteMs / 1000);
            try {
                schlaefer.schlafe(warteMs);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return wert; // interrupted -> into the fail-fast without a further attempt
            }
            svc.erzwingeNeuenVersuch();
            wert = leseWert(svc, itemName, selector);
            versuch++;
        }
        return wert;
    }

    /**
     * Fail-fast rationale: distinguishes the transient disturbance (vault access fails, no
     * statement about the item possible) from the definitively missing item after a successful
     * sync. The message NEVER names secret values — only the secret-free error message of the
     * client.
     */
    private static String fehlertext(String kind, VaultwardenSecretService svc) {
        if (svc.istLetzterZugriffTransientGescheitert()) {
            return kind + " nicht lesbar: Vaultwarden-Zugriff scheitert transient ("
                    + svc.letzteVaultFehlermeldung() + ") — auch nach "
                    + BOOT_MAX_VERSUCHE + " Versuchen";
        }
        return kind + " nicht im Tresor gefunden (Vault-Sync war erfolgreich — Item oder Feld"
                + " fehlt bzw. heisst anders)";
    }

    /** Lazy singleton: obtain the service exactly once (only on the first vault: value). */
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
