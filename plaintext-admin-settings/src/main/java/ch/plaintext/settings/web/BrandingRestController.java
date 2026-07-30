/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.entity.BrandingLogo;
import ch.plaintext.settings.service.BrandingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/branding")
public class BrandingRestController {

    private final BrandingService brandingService;
    private final PlaintextSecurity security;

    public BrandingRestController(BrandingService brandingService, PlaintextSecurity security) {
        this.brandingService = brandingService;
        this.security = security;
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> getLogo(@RequestParam(defaultValue = "light") String theme) {
        String mandat = security.getMandat();
        return brandingService.getLogo(mandat, theme)
                .map(this::buildLogoResponse)
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> buildLogoResponse(BrandingLogo logo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.getContentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).mustRevalidate())
                .header(HttpHeaders.ETAG, String.valueOf(logo.getLastModifiedDate() != null
                        ? logo.getLastModifiedDate().hashCode()
                        : logo.getCreatedDate().hashCode()))
                .body(brandingService.getLogoBytes(logo));
    }
}
