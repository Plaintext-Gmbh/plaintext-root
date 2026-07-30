/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@Slf4j
public class DelegatingMenuVisibilityProvider implements MenuVisibilityProvider {

    private final MandateMenuVisibilityService mandateMenuVisibilityService;

    public DelegatingMenuVisibilityProvider(
            MandateMenuVisibilityService mandateMenuVisibilityService) {
        this.mandateMenuVisibilityService = mandateMenuVisibilityService;
    }

    @Override
    public boolean isMenuVisible(String menuTitle) {
        return mandateMenuVisibilityService.isMenuVisible(menuTitle);
    }

    @Override
    public boolean isMenuVisibleForMandate(String menuTitle, String mandate) {
        return mandateMenuVisibilityService.isMenuVisibleForMandate(menuTitle, mandate);
    }
}
