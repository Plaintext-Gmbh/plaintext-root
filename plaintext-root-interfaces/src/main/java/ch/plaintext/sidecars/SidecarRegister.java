/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import java.util.List;
import java.util.Optional;

/**
 * Registry der Sidecars (Karte 1400, Protokoll: {@code docs/SIDECAR_PROTOKOLL.md}).
 *
 * <p>Ein Modul, das eine Zusatzfunktion eines Containers braucht (Bildumrechnung, Erkennung,
 * Messenger), fragt hier nach der <b>Fähigkeit</b> und nicht nach einem Containernamen. Welcher
 * Dienst sie gerade anbietet, Basis-URL und Token: das weiss die Registry. So kann ein anderer
 * Dienst mit derselben Fähigkeit einspringen, ohne dass das Modul es merkt.</p>
 *
 * <p>Umgesetzt im Modul {@code plaintext-admin-sidecars}. Nicht jede Anwendung bindet es ein, deshalb
 * den Bezug optional halten ({@code @Autowired(required = false)} bzw. {@code ObjectProvider}).</p>
 */
public interface SidecarRegister {

    /** @return alle bekannten Sidecars mit ihrem letzten Stand, nach Name */
    List<SidecarStand> alle();

    /**
     * @param name Name des Sidecars, z. B. {@code whatsapp}
     * @return die Verbindung, wenn der Sidecar bekannt ist (auch wenn er gerade nicht antwortet)
     */
    Optional<SidecarVerbindung> verbindung(String name);

    /**
     * Ein erreichbarer Sidecar, der die Fähigkeit anbietet; bei mehreren der mit Status {@code ok}
     * und der kürzesten Antwortzeit.
     *
     * @param faehigkeitId z. B. {@code bild.vorschau}
     */
    Optional<SidecarVerbindung> fuer(String faehigkeitId);
}
