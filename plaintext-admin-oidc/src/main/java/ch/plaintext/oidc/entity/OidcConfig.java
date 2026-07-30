/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "OIDC_CONFIG")
@Data
@EntityListeners(AuditingEntityListener.class)
public class OidcConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name = "Keycloak";

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "ISSUER_URL", nullable = false, length = 1000)
    private String issuerUrl = "";

    @Column(name = "CLIENT_ID", nullable = false, length = 500)
    private String clientId = "";

    @Column(name = "CLIENT_SECRET", nullable = false, length = 1000)
    private String clientSecret = "";

    @Column(length = 500)
    private String scopes = "openid,profile,email";

    @Column(name = "BUTTON_LABEL", length = 200)
    private String buttonLabel = "Mit Keycloak anmelden";

    @Column(name = "BUTTON_ICON", length = 100)
    private String buttonIcon = "pi pi-sign-in";

    @Column(name = "AUTO_CREATE_USERS")
    private boolean autoCreateUsers = false;

    @Column(name = "DEFAULT_ROLES", length = 500)
    private String defaultRoles = "user";

    @Column(name = "DEFAULT_MANDAT", length = 100)
    private String defaultMandat = "default";

    @Column(name = "USERNAME_ATTRIBUTE", length = 100)
    private String usernameAttribute = "email";

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private LocalDateTime createdDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private LocalDateTime lastModifiedDate;

    @Transient
    public String getRegistrationId() {
        return name != null ? name.toLowerCase().replaceAll("[^a-z0-9]", "-") : "oidc";
    }
}
