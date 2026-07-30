/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component("versionBean")
@Data
@Scope("application")
@ConditionalOnWebApplication
@Slf4j
public class VersionBean {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${plaintext.version:${plaintext.root.version:dev-SNAPSHOT}}")
    private String version;

    @Value("${plaintext.root.version:dev-SNAPSHOT}")
    private String rootVersion;

    private String buildTimestamp;

    @PostConstruct
    public void init() {
        log.info("Version: {}, Root: {}", version, rootVersion);
        buildTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"));
    }

    public String getVersion() {
        return version;
    }

    public String getBuildTimestamp() {
        return buildTimestamp;
    }

    public String getRootVersion() {
        return rootVersion;
    }

    public String getFullVersion() {
        if (buildTimestamp != null && !buildTimestamp.isEmpty()) {
            return version + " (Build: " + buildTimestamp + ")";
        }
        return version;
    }
}
