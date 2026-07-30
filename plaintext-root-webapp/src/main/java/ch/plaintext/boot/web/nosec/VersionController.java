/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.nosec;

import ch.plaintext.boot.web.VersionBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/nosec")
@Tag(name = "Version", description = "Public endpoint exposing the application version (no authentication required)")
public class VersionController {

    private final VersionBean versionBean;

    public VersionController(VersionBean versionBean) {
        this.versionBean = versionBean;
    }

    @Operation(summary = "Get application version",
               description = "Returns the current application version string (e.g. Maven project version). "
                           + "This endpoint is publicly accessible without authentication.")
    @ApiResponse(responseCode = "200", description = "Version string returned successfully")
    @GetMapping("/version")
    public String getVersion() {
        return versionBean.getVersion();
    }
}
