/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.log;

/**
 * Helper to mask third-party e-mail addresses before they reach a log line (S-08, security/
 * architecture analysis 05.09.2026, card 1104).
 *
 * <p>Log lines that carry a real person's e-mail address in plain text turn every log sink
 * (application logs, Graylog, log shipping) into a place that holds PII outside the database —
 * with its own retention, access and export rules, none of which the mandate/consent model
 * governing the actual person record was designed for. {@link #mail(String)} keeps the address
 * recognisable for support/troubleshooting (first character, full domain) without keeping it
 * intact.</p>
 *
 * <pre>{@code
 * log.info("Self-registration completed for {} on mandat={}", Log.mail(token.getEmail()), mandat);
 * // "vorname.nachname@example.com" -> "v***@example.com"
 * }</pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class Log {

    private static final String MASK = "***";

    private Log() {
    }

    /**
     * Masks an e-mail address for logging: keeps the first character of the local part and the
     * full domain, replaces the rest of the local part with {@code "***"}.
     *
     * <p>{@code null} and blank input pass through unchanged (nothing to leak); a value without
     * an {@code "@"} is not an e-mail address in the first place and is masked in full, so that a
     * caller passing the wrong variable by mistake does not silently defeat the point of this
     * method.</p>
     *
     * @param adresse the address to mask, may be {@code null}
     * @return the masked address, {@code null} if {@code adresse} was {@code null}
     */
    public static String mail(String adresse) {
        if (adresse == null) {
            return null;
        }
        if (adresse.isBlank()) {
            return adresse;
        }
        int at = adresse.lastIndexOf('@');
        if (at <= 0) {
            // No '@' (or it's the very first character, so there is no local part to keep a
            // character of either): not a usable e-mail address, mask it wholesale.
            return MASK;
        }
        String local = adresse.substring(0, at);
        String domain = adresse.substring(at); // includes '@'
        return local.charAt(0) + MASK + domain;
    }
}
