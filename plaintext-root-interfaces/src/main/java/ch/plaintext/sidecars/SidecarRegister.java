/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import ch.plaintext.modules.ModulApi;

import java.util.List;
import java.util.Optional;

/**
 * Registry of the sidecars (card 1400, protocol: {@code docs/SIDECAR_PROTOKOLL.md}).
 *
 * <p>A module that needs an additional function of a container (image conversion, recognition,
 * messenger) asks here for the <b>capability</b>, not for a container name. Which service offers it
 * right now, its base URL and token: the registry knows. Another service with the same capability can
 * thus step in without the module noticing.</p>
 *
 * <p>Implemented in the module {@code plaintext-admin-sidecars}. Not every application includes it, so
 * keep the dependency optional ({@code @Autowired(required = false)} or {@code ObjectProvider}).</p>
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface SidecarRegister {

    /** @return all known sidecars with their latest state, by name */
    List<SidecarStand> alle();

    /**
     * @param name name of the sidecar, e.g. {@code whatsapp}
     * @return the connection if the sidecar is known (even if it does not answer right now)
     */
    Optional<SidecarVerbindung> verbindung(String name);

    /**
     * A reachable sidecar that offers the capability; sidecars with status {@code fehler} are skipped,
     * among several the one with status {@code ok} and the shortest response time wins.
     *
     * @param faehigkeitId e.g. {@code bild.vorschau}
     */
    Optional<SidecarVerbindung> fuer(String faehigkeitId);
}
