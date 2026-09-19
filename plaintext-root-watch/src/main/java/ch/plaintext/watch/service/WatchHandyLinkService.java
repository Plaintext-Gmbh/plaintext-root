/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.framework.EigeneAdresse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Issues, revokes and re-issues the personal phone link of the watch view (card 1257).
 *
 * <p>Daniel, 19.09.2026: „also link erstellen pro person persistierbar … Diesen Link soll man
 * deaktivieren koennen und neu generieren koennen, er soll mit dem Token in URL funktionieren
 * und keine Anmeldung benoetigen."</p>
 *
 * <h2>The model is in the house</h2>
 *
 * <p>{@code ZeiterfassungSettingsBackingBean.generateHandyLink()} does the same for the mobile
 * clock. What is taken over from it: the token is issued through
 * {@link IApiTokenService#createToken}-family methods so that it gets a row in
 * {@code api_token} — without that row there is nothing to revoke — and the base address comes
 * from {@link EigeneAdresse}, never hard-wired (card 1046: a wired address makes every other
 * tenant's link point at a foreign installation).</p>
 *
 * <p>What is done differently, and why:</p>
 * <ul>
 *   <li><b>One link per person, not one per press.</b> The clock mints a new token on every
 *       press and leaves the old ones alive. Here the old one is revoked first
 *       ({@link IApiTokenService#invalidateTokensByName}) — "generate a new one" has to make
 *       the old one worthless on the spot, otherwise a link once handed out can never be taken
 *       back.</li>
 *   <li><b>The token is never stored.</b> Not in {@code watch_user_state}, not anywhere else —
 *       see {@code WatchUserState.getHandyLinkJti()}. It is shown once, right after it is
 *       created.</li>
 *   <li><b>{@code scope} is READ, not WRITE.</b> See {@link #SCOPE}.</li>
 *   <li><b>The name carries {@link IApiTokenService#UI_TOKEN_NAME_PREFIX}</b>, which makes the
 *       token worthless at the API. See {@link #TOKEN_NAME}.</li>
 * </ul>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchHandyLinkService {

    /**
     * Name of the token in {@code api_token} — and at the same time the marker that makes it a
     * browser credential and nothing else ({@link IApiTokenService#UI_TOKEN_NAME_PREFIX}).
     *
     * <p>The name is the key of the whole flow: it is what "the phone link of this user" means,
     * what {@link #widerrufe} revokes and what the token path checks on the way in. It must not
     * change once released — an existing link would otherwise become unrevocable.</p>
     */
    public static final String TOKEN_NAME = IApiTokenService.UI_TOKEN_NAME_PREFIX + "watch-handy-link";

    /**
     * The least the link can carry.
     *
     * <p>The clock's token takes {@code WRITE} because its counterpart evaluates the scope. The
     * watch pages do not: they run as ordinary JSF views, and what limits this token is the
     * path confinement in {@code WatchTokenSitzungFilter}, not the scope ladder. The scope
     * therefore only still matters in the one place that does read it — the MCP bearer filter —
     * and there the minimum is right. {@code READ} instead of an invented value on purpose:
     * an unknown scope falls back to READ anyway, so a made-up one would only pretend to be
     * narrower than it is.</p>
     */
    static final String SCOPE = "READ";

    /**
     * Lifetime of the link in days.
     *
     * <p>{@code MAX_VALIDITY_DAYS} of the JWT service is 365 and the clock uses it. Here it is
     * deliberately shorter: this link hangs on a phone's home screen and is meant to be
     * forgotten about, so the expiry is the last line of defence for a link nobody ever revokes
     * because nobody remembers it exists. 90 days is the house default
     * ({@code JwtTokenService.DEFAULT_VALIDITY_DAYS}) and one quarter — short enough to matter,
     * long enough not to be a nuisance.</p>
     */
    static final int GUELTIG_TAGE = 90;

    /** Path of the token entry point; the strict rate-limit bucket keys on it. */
    public static final String EINSTIEGSPFAD = "/nosec/watch";

    private final WatchStateService zustand;
    private final PlaintextSecurity sicherheit;

    /**
     * Optional on purpose: the watch module has to start in an application that does not carry
     * {@code plaintext-admin-apitoken}. Without it there is no phone link — and the settings
     * page says so instead of throwing.
     */
    private final ObjectProvider<IApiTokenService> tokenDienst;

    private final ObjectProvider<EigeneAdresse> eigeneAdresse;

    /** Whether this installation can issue a phone link at all. */
    public boolean verfuegbar() {
        return tokenDienst.getIfAvailable() != null;
    }

    /**
     * Revokes any existing link of the signed-in user and issues a new one.
     *
     * <p>The order is not interchangeable: revoke first, issue second. The other way round a
     * failure between the two steps would leave two valid links, and the user would believe the
     * old one is dead.</p>
     *
     * @return the complete link, once — it is not recoverable afterwards
     * @throws IllegalStateException if no token service is present or nobody is signed in
     */
    public String erzeuge() {
        IApiTokenService dienst = tokenDienst.getIfAvailable();
        if (dienst == null) {
            throw new IllegalStateException("Kein Token-Dienst vorhanden — Handy-Link nicht moeglich");
        }
        Long userId = sicherheit.getId();
        String mandat = sicherheit.getMandat();
        if (userId == null || userId < 0) {
            throw new IllegalStateException("Kein angemeldeter Benutzer — Handy-Link nicht moeglich");
        }
        String email = sicherheit.getEmailForUser(userId).orElse(sicherheit.getUser());

        int widerrufen = dienst.invalidateTokensByName(userId, mandat, TOKEN_NAME);
        String jwt = dienst.createToken(userId, mandat, TOKEN_NAME, email, GUELTIG_TAGE, SCOPE);
        zustand.merkeHandyLink(jtiAus(jwt));

        // Der Token selbst wird NICHT geloggt — weder ganz noch gekuerzt. Ein Praefix im Log ist
        // kein Schutz, sondern nur ein kleinerer Heuhaufen fuer den, der die Signatur ohnehin
        // nicht faelschen kann, aber den Rest mitliest.
        log.info("Watch-Handy-Link ausgestellt (userId={}, mandat={}, {} Tage, scope={}, "
                        + "{} alte widerrufen)",
                userId, mandat, GUELTIG_TAGE, SCOPE, widerrufen);
        return basis() + EINSTIEGSPFAD + "?t=" + jwt;
    }

    /**
     * Switches the link off: revokes the token <b>and</b> clears the flag.
     *
     * <p>Both, not either. The revocation is what actually stops the link; the flag is the
     * second lock the token path checks anyway, so a revocation that somehow failed to be
     * written still does not leave the link usable.</p>
     *
     * @return how many tokens were revoked
     */
    public int widerrufe() {
        IApiTokenService dienst = tokenDienst.getIfAvailable();
        Long userId = sicherheit.getId();
        int widerrufen = 0;
        if (dienst != null && userId != null && userId >= 0) {
            widerrufen = dienst.invalidateTokensByName(userId, sicherheit.getMandat(), TOKEN_NAME);
        }
        zustand.schalteHandyLinkAb();
        log.info("Watch-Handy-Link abgeschaltet (userId={}, {} Token widerrufen)", userId, widerrufen);
        return widerrufen;
    }

    /**
     * Base address of the link. From {@link EigeneAdresse}, never wired: app runs under
     * {@code app.plaintext.ch}, guild under {@code guild.plaintext.ch}, and a wired value would
     * make one of the two point at the wrong installation (card 1046).
     */
    private String basis() {
        EigeneAdresse adresse = eigeneAdresse.getIfAvailable();
        String basis = adresse == null ? "" : adresse.basis("");
        if (basis.isBlank()) {
            // Karte 1277 (19.09.2026): Bis heute war dieser Fall still. Ist app.ownhost
            // nirgends gepflegt — weder als Einstellung noch als Property —, liefert basis("")
            // den leeren String, und der ausgegebene Link lautet "/nosec/watch?t=<jwt>": ohne
            // Schema, ohne Host, auf einem Telefon kein Link, sondern ein Textschnipsel. Genau
            // so stand es in app-prod, bis der Wert gesetzt wurde.
            //
            // Der Wert wird hier NICHT geraten. Karte 1046 hat EigeneAdresse eingefuehrt, weil
            // app unter app.plaintext.ch laeuft und guild unter app.guild42.ch — was immer hier
            // fest verdrahtet stuende, zeigte bei einer der beiden Installationen auf die
            // falsche. Aber lautlos einen kaputten Link auszugeben ist die schlechtere von zwei
            // Antworten: der Aufrufer sieht nichts, und der Benutzer merkt es erst am Telefon.
            log.warn("⚠️ WATCH-HANDY-LINK ohne Basisadresse: app.ownhost ist weder als Einstellung"
                    + " noch als Property gesetzt. Der Link wird ohne Schema und Host ausgegeben"
                    + " und ist auf einem Telefon nicht anklickbar. In den Einstellungen"
                    + " 'app.ownhost' auf die oeffentliche Adresse dieser Installation setzen,"
                    + " mit https, ohne Schraegstrich am Ende (Karte 1277).");
        }
        return basis;
    }

    /**
     * The {@code jti} out of the token — for the trace back to the row in {@code api_token}.
     * Reads the payload without verifying: nothing is decided on this value, it is issued here
     * and was just signed by us. A token whose middle part is unreadable simply gets no id.
     */
    private static String jtiAus(String jwt) {
        try {
            String[] teile = jwt.split("\\.");
            if (teile.length < 2) {
                return null;
            }
            String payload = new String(java.util.Base64.getUrlDecoder().decode(teile[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of(payload)
                    .map(p -> java.util.regex.Pattern.compile("\"jti\"\\s*:\\s*\"([^\"]+)\"").matcher(p))
                    .filter(java.util.regex.Matcher::find)
                    .map(m -> m.group(1))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Watch: jti des Handy-Links nicht lesbar: {}", e.toString());
            return null;
        }
    }
}
