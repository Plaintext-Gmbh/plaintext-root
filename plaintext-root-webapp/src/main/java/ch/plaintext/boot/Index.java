/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

@org.springframework.web.bind.annotation.RestController
@Slf4j
public class Index {

    @GetMapping("/")
    public void getIndex(HttpServletResponse response) throws IOException {
        // Use the individual start page if it is set validly - otherwise always index.html.
        // This way no user is locked out of the start page by an empty/invalid startpage value.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String redirect = StartpageResolver.resolve(auth == null ? null : auth.getAuthorities());
        response.sendRedirect(redirect);
    }
}