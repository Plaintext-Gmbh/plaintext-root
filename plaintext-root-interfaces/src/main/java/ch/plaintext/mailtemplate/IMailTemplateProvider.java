/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate;

import java.util.Map;

/**
 * Rendert einen (Betreff+Body)-Mailtext für einen {@code templateKey}: DB-Override des Mandanten,
 * falls vorhanden ({@code plaintext-admin-mailtemplate}), sonst der vom Aufrufer übergebene
 * Default-Text. Platzhalter-Syntax {@code {name}}, ersetzt in beiden Fällen einheitlich.
 *
 * <p>Konsumenten (Module ohne Abhängigkeit zu {@code plaintext-admin-mailtemplate}) injizieren dies
 * als {@code @Autowired(required = false)} und fallen bei fehlender Bean auf den unveränderten
 * Default zurück (analog {@code I18nProvider}/{@code I18nEL}).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface IMailTemplateProvider {

    /**
     * @param mandat        Mandant, dessen DB-Override gesucht wird (kein Override-Konzept über
     *                      Mandanten hinweg — jeder Mandant kann seinen eigenen Text hinterlegen)
     * @param templateKey   eindeutiger Schlüssel, z. B. {@code auth.registration}
     * @param defaultBetreff Default-Betreff (Platzhalter-Syntax {@code {name}}), falls kein Override existiert
     * @param defaultBody    Default-Body (Platzhalter-Syntax {@code {name}}), falls kein Override existiert
     * @param platzhalter    Ersetzungswerte, angewandt auf Betreff UND Body (egal ob Default oder Override)
     * @return gerenderter Betreff+Body mit ersetzten Platzhaltern
     */
    RenderedMail render(String mandat, String templateKey, String defaultBetreff, String defaultBody,
                        Map<String, String> platzhalter);

    record RenderedMail(String betreff, String body) {}
}
