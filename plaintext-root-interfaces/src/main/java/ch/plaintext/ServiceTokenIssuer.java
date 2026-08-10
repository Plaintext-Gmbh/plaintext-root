/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.time.Duration;

/**
 * Stellt <b>Maschinen-Ausweise</b> aus: kurzlebige, signierte Tokens, mit denen sich diese Instanz
 * bei einer fremden Gegenstelle ausweist (Karte 635).
 *
 * <p>Das Interface liegt in {@code plaintext-root-interfaces}, damit Anwendungsmodule den Ausweis
 * anfordern können, ohne von {@code plaintext-admin-apitoken} abzuhängen — die Implementierung
 * ({@code JwtTokenService}) hält den privaten Signaturschlüssel, und der hat in einem Fachmodul
 * nichts zu suchen. Konsumiert wird sie wie die übrigen Verträge optional, über
 * {@link org.springframework.beans.factory.ObjectProvider}: ein Modul, das ohne Ausweis auskommt,
 * soll auch ohne ihn starten.
 *
 * <p><b>Wozu überhaupt.</b> Der Normalfall zwischen zwei Diensten ist ein geteiltes Geheimnis: Wer
 * den Wert kennt, gilt als berechtigt. Das hat zwei Kosten — der Wert muss gepflegt, verteilt und
 * rotiert werden, und wer ihn <em>vergisst</em>, ist ausgesperrt. Genau das passiert bei jedem
 * Deploy: Der Label-Drucker aus Karte 556 hält eine exklusive Session, guild verliert beim Neustart
 * den Token dazu, und der einzige Ausweg wäre ein Neustart des Geräts. Mit einem Ausweis signiert
 * der Dienst nach dem Neustart einfach neu.
 *
 * <p><b>Wie die Gegenstelle prüft.</b> Über den öffentlichen Schlüssel dieser Instanz, den sie sich
 * selbst holen kann: {@code /.well-known/jwks.json} (RFC 7517). Der private Schlüssel verlässt die
 * Anwendung nie.
 *
 * <p><b>Was ein Ausweis NICHT ist.</b> Kein API-Token. Die Implementierung kennzeichnet ihn so, dass
 * die eigene Token-Prüfung ihn ablehnt — sonst wäre ein Wert, der als HTTP-Header über die Leitung
 * geht, ein vollprivilegierter Zugang zur eigenen API.
 */
public interface ServiceTokenIssuer {

    /**
     * Signiert einen Ausweis.
     *
     * @param subject     wer sich ausweist, z. B. {@code guild-checkin-desk} (Pflicht) — die
     *                    Gegenstelle erkennt daran denselben Aufrufer über einen Neustart hinweg
     * @param audience    für wen der Ausweis gilt, z. B. {@code guild42-label-printer}; leer lässt
     *                    den {@code aud}-Claim weg, womit der Ausweis überall gilt, wo der Schlüssel
     *                    bekannt ist — deshalb setzen
     * @param gueltigkeit Laufzeit ab jetzt; die Implementierung begrenzt sie nach oben und unten
     * @return signiertes JWT (RS256)
     * @throws IllegalArgumentException wenn {@code subject} fehlt
     * @throws IllegalStateException    wenn die Signaturschlüssel noch nicht geladen sind (der Start
     *                                  wartet ggf. auf den Vault) — <b>kein</b> dauerhafter Fehler,
     *                                  ein späterer Versuch kann gelingen
     */
    String signServiceToken(String subject, String audience, Duration gueltigkeit);
}
