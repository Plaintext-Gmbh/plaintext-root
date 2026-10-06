/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.ablagen;

import ch.plaintext.modules.ModulApi;

import java.util.List;
import java.util.Optional;

/**
 * Die eingerichteten Speicher-Ablagen (Karte 1406), umgesetzt in {@code plaintext-admin-sidecars}.
 * Optional einbinden ({@code @Autowired(required = false)}), nicht jede Anwendung hat das Modul.
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface DateiAblagenRegister {

    /** @return Namen aller eingerichteten Ablagen */
    List<String> namen();

    /** @return die Ablage dieses Namens, wenn eingerichtet */
    Optional<DateiAblage> ablage(String name);
}
