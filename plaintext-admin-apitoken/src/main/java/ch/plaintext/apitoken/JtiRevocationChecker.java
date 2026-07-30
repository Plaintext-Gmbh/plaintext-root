/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

/**
 * Optionaler Collaborator für {@link McpBearerTokenFilter}: prüft, ob ein Token anhand seiner
 * {@code jti} (JWT-ID-Claim) gesperrt wurde. Root selbst führt keine Blocklist-Tabelle — Apps, die
 * Revocation wollen (z.B. schuetu), registrieren eine eigene Spring-Bean, die dieses Interface
 * implementiert; der Filter holt sie sich optional per {@code ObjectProvider} (siehe
 * {@link McpBearerTokenFilterConfig}). Ist keine Bean vorhanden, gilt kein Token als revoked —
 * 100% verhaltensgleich zum bisherigen Filter für Apps, die (noch) keine Blocklist betreiben.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@FunctionalInterface
public interface JtiRevocationChecker {

    /**
     * @param jti JWT-ID-Claim des zu prüfenden Tokens (nie {@code null} — der Filter ruft dies nur
     *            auf, wenn das Token einen {@code jti}-Claim trägt)
     * @return {@code true}, wenn das Token gesperrt wurde und die Anfrage mit 401 abgelehnt werden soll
     */
    boolean isRevoked(String jti);
}
