/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications;

import java.util.Map;

/**
 * Zentrales In-App-Benachrichtigungssystem. Implementiert in {@code plaintext-admin-notifications}
 * (root); dort transitiv verfuegbar, in anderen Apps (boot/guild) optional als
 * {@code @Autowired(required = false)}-Bean (analog {@code IMailTemplateProvider}).
 *
 * <p>Rendering von Titel/Text laeuft ueber denselben Mechanismus wie Mailtexte
 * ({@code IMailTemplateProvider}, Key-Namespace {@code notif.*}): der Aufrufer liefert
 * Default-Titel/-Text, ein mandantengescoped Admin-Override in der DB hat Vorrang.</p>
 */
public interface NotificationService {

    /**
     * Erzeugt eine In-App-Benachrichtigung fuer einen einzelnen Benutzer.
     *
     * @param empfaengerUsername Username (Login-Name) des Empfaengers
     * @param mandat             Mandant, unter dem gerendert/gespeichert wird
     * @param typ                Benachrichtigungstyp (wird zum Template-Key {@code notif.<typ>})
     * @param defaultTitel       Default-Titel, falls kein Admin-Override existiert
     * @param defaultText        Default-Text, falls kein Admin-Override existiert
     * @param platzhalter        {@code {key}}-Platzhalter fuer Titel/Text
     * @param link               optionale Ziel-URL in der App, oder {@code null}
     */
    void notify(String empfaengerUsername, String mandat, String typ, String defaultTitel, String defaultText,
                Map<String, String> platzhalter, String link);

    /**
     * Erzeugt dieselbe Benachrichtigung fuer alle Benutzer eines Mandanten (Broadcast).
     */
    void notifyMandant(String mandat, String typ, String defaultTitel, String defaultText,
                        Map<String, String> platzhalter, String link);
}
