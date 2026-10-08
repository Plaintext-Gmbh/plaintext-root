/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.ablagen;

import ch.plaintext.modules.ModulApi;

import java.util.List;
import java.util.Optional;

/**
 * The configured file stores (card 1406), implemented in {@code plaintext-admin-sidecars}. Inject it
 * as optional ({@code @Autowired(required = false)}), not every application contains that module.
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface DateiAblagenRegister {

    /** @return names of all configured stores */
    List<String> namen();

    /** @return the store with this name, if it is configured */
    Optional<DateiAblage> ablage(String name);
}
