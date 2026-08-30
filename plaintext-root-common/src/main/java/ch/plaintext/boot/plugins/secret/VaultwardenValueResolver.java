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
 *
 * <h2>Boot-Retry bei transienter Stoerung</h2>
 * <p>Scheitert die Aufloesung, weil der Vault-Zugriff selbst gescheitert ist (Login/Sync-Fehler,
 * Timeout, HTTP 429), wird VOR dem Fail-fast mehrfach gewartet und erneut versucht — nach einem
 * 429 bewusst lange (das Rate-Limit fuellt sich nur ueber Zeit wieder auf). Nur wenn der Sync
 * ERFOLGREICH war und das Item trotzdem fehlt, bricht der Boot sofort ab: dann ist die leere
 * Antwort definitiv (Tippfehler-Fall schuetu.remember-me-keyn, 18.08.2026). Anlass fuer den
 * Retry: 21.08.2026 — ein crashloopender Nachbar-Container hielt den geteilten Login-Bucket
 * leer, und guild 1.372.0 sowie app-snapshot scheiterten im ersten und einzigen Versuch.</p>
 */
@Slf4j
class VaultwardenValueResolver {

    /** Wert-Prefix, das eine Tresor-Referenz kennzeichnet. */
    static final String VAULT_PREFIX = "vault:";

    /**
     * Zweites Wert-Prefix: derselbe Mechanismus, aber gegen OpenBao statt Vaultwarden
     * (Karte 995). Syntax: {@code bao:<pfad>} oder {@code bao:<pfad>#<feld>}; ohne Feld gilt
     * {@code value}.
     *
     * <p><b>Additiv, und das ist Absicht:</b> Jeder bestehende {@code vault:}-Wert bleibt
     * unveraendert und laeuft weiter ueber Vaultwarden. Eine Property wandert einzeln, indem ihr
     * Wert von {@code vault:} auf {@code bao:} umgeschrieben wird — kein Stichtag, kein
     * Big-Bang, und der Rueckweg ist dieselbe Zeile rueckwaerts.</p>
     */
    static final String BAO_PREFIX = "bao:";

    /** Feld, das eine {@code bao:}-Referenz ohne Selektor liest. */
    static final String BAO_DEFAULT_FELD = "value";

    /** Selektor-Trenner zwischen Item-Name und Feld-Selektor. */
    private static final char SELECTOR_SEP = '#';

    /** Prefix des Custom-Feld-Selektors ({@code #field:<name>}). */
    private static final String FIELD_SELECTOR = "field:";

    /**
     * Namenskonvention {@code <app>.<key>} (z.B. {@code app.jira-bit-admin}).
     * Passt der Item-Name nicht, wird nur ge-WARN-t, aber trotzdem aufgeloest.
     */
    private static final Pattern NAME_CONVENTION = Pattern.compile("^[a-z0-9-]+\\.[a-z0-9-]+");

    /** Boot-Retry: Gesamtzahl der Leseversuche je Referenz bei transienter Stoerung. */
    static final int BOOT_MAX_VERSUCHE = 4;

    /** Wartezeit vor dem ersten Boot-Retry; verdoppelt sich je weiterem Versuch (5s, 10s, 20s). */
    static final long BOOT_WARTE_START_MS = 5_000;

    /**
     * Wartezeit nach einem HTTP 429: knapp UEBER der Default-Refill-Rate des Vaultwarden-Login-
     * Limiters (1 Token je 60s). Kuerzer zu warten hiesse, den naechsten Versuch sicher wieder
     * in den leeren Bucket zu schicken.
     */
    static final long BOOT_WARTE_RATE_LIMIT_MS = 65_000;

    /** Schlaf-Hook — im Betrieb {@link Thread#sleep(long)}, im Test ein Rekorder ohne Echtzeit. */
    @FunctionalInterface
    interface Schlaefer {
        void schlafe(long millis) throws InterruptedException;
    }

    private final Supplier<VaultwardenSecretService> serviceSupplier;
    /** Liefert den OpenBao-Client — lazy wie der Vaultwarden-Service, und aus demselben Grund. */
    private final Supplier<OpenBaoClient> baoSupplier;
    private final Schlaefer schlaefer;
    /** Pro Boot aufgeloeste Werte (Key = kompletter Roh-Wert inkl. {@code vault:}-Prefix). */
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

    /** {@code true}, wenn der Wert eine Tresor-Referenz ist — {@code vault:} ODER {@code bao:}. */
    static boolean isVaultReference(Object value) {
        return value instanceof String s && (s.startsWith(VAULT_PREFIX) || s.startsWith(BAO_PREFIX));
    }

    /** {@code true} nur fuer {@code bao:} — trennt die beiden Quellen im {@link #resolve}. */
    static boolean isBaoReference(Object value) {
        return value instanceof String s && s.startsWith(BAO_PREFIX);
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
     * Loest eine {@code bao:}-Referenz gegen OpenBao auf.
     *
     * <p>Syntax: {@code bao:<pfad>} liest das Feld {@code value}, {@code bao:<pfad>#<feld>} ein
     * anderes. Bewusst schlichter als die Vaultwarden-Syntax: Dort gibt es Passwort, Username und
     * Custom-Felder, weil ein Bitwarden-Item diese Struktur hat. Ein KV-v2-Eintrag ist eine flache
     * Abbildung — ein zweiter Selektor-Dialekt haette nichts abgebildet, was es gibt.</p>
     *
     * <p>Fail-fast wie beim Vaultwarden-Zweig: Ein fehlender Eintrag beendet den Start, statt die
     * Anwendung mit einem leeren Geheimnis weiterlaufen zu lassen. Transiente Stoerungen
     * (Netz, HTTP 5xx, versiegelter Tresor) werden vorher mehrfach wiederholt — ein versiegelter
     * OpenBao ist beim Kaltstart der Normalfall, nicht die Ausnahme.</p>
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            wert = client.lies(pfad, feld);
            versuch++;
        }

        return wert.orElseThrow(() -> new VaultwardenPropertyResolutionException(
                propertyName, pfad, "OpenBao: " + client.letzterFehler()));
    }

    /** Menschliche Bezeichnung des Selektors; wirft bei unbekanntem Selektor (Fail-fast). */
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

    /** Liest den Wert gemaess Selektor (Selektor ist hier bereits validiert). */
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
     * Wiederholt die Leseoperation, solange der leere Wert auf einer TRANSIENTEN Vault-Stoerung
     * beruht (Login/Sync-Fehler, Timeout, HTTP 429) — mit Wartezeit dazwischen, nach einem 429
     * bewusst {@value #BOOT_WARTE_RATE_LIMIT_MS}ms. Eine leere Antwort nach ERFOLGREICHEM Sync
     * ist definitiv (Item fehlt wirklich) und wird nicht wiederholt.
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
                return wert; // unterbrochen -> ohne weiteren Versuch in den Fail-fast
            }
            svc.erzwingeNeuenVersuch();
            wert = leseWert(svc, itemName, selector);
            versuch++;
        }
        return wert;
    }

    /**
     * Fail-fast-Begruendung: unterscheidet die transiente Stoerung (Vault-Zugriff scheitert,
     * Aussage ueber das Item unmoeglich) vom definitiv fehlenden Item nach erfolgreichem Sync.
     * Die Meldung nennt NIE Secret-Werte — nur die secret-freie Fehlermeldung des Clients.
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
