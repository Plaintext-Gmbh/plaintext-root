/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.service.RollenzuteilungService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Separation of privileges in the role assignment: an admin hands out <b>module roles</b> (that is
 * exactly what the page is open to them for), but no administrative privileges.
 *
 * <p>Without this check the role assignment would be a way around the allowlist of the user
 * administration: an admin could have granted themselves {@code ROLE_ROOT} here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Rollenzuteilung: Rechtetrennung root/admin")
class RollenzuteilungRechtetrennungTest {

    private static final String BENUTZER = "anna@example.com";
    private static final String MANDANT = "butscher";
    private static final String MODUL_ROLLE = "ROLE_WIKI";

    @Mock
    private RollenzuteilungService service;

    @Mock
    private PlaintextSecurity security;

    @Mock
    private FacesContext facesContext;

    private RollenzuteilungBackingBean bean;
    private MockedStatic<FacesContext> facesContextMock;

    @BeforeEach
    void setUp() {
        bean = new RollenzuteilungBackingBean(service, security);
        facesContextMock = mockStatic(FacesContext.class);
        facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
    }

    @AfterEach
    void tearDown() {
        facesContextMock.close();
    }

    private Rollenzuteilung zuteilung(String rolle) {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setUsername(BENUTZER);
        rz.setMandat(MANDANT);
        rz.setRoleName(rolle);
        rz.setActive(true);
        bean.setSelected(rz);
        return rz;
    }

    private void akteurIstRoot(boolean root) {
        lenient().when(security.ifGranted("ROLE_ROOT")).thenReturn(root);
    }

    private void verifyFehler() {
        verify(facesContext).addMessage(isNull(),
                argThat(msg -> msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
    }

    @Test
    @DisplayName("admin DARF eine Modul-Rolle vergeben")
    void adminDarfModulRolleVergeben() {
        akteurIstRoot(false);
        Rollenzuteilung rz = zuteilung(MODUL_ROLLE);
        when(service.save(rz)).thenReturn(rz);

        bean.save();

        verify(service).save(rz);
    }

    @Test
    @DisplayName("admin darf NICHT root vergeben")
    void adminDarfNichtRootVergeben() {
        akteurIstRoot(false);
        zuteilung("ROLE_ROOT");
        when(service.findByUsernameAndMandatAndRole(BENUTZER, MANDANT, "ROLE_ROOT"))
                .thenReturn(Optional.empty());

        bean.save();

        verify(service, never()).save(org.mockito.ArgumentMatchers.any());
        verifyFehler();
    }

    @Test
    @DisplayName("admin darf NICHT admin vergeben")
    void adminDarfNichtAdminVergeben() {
        akteurIstRoot(false);
        zuteilung("ROLE_ADMIN");
        when(service.findByUsernameAndMandatAndRole(BENUTZER, MANDANT, "ROLE_ADMIN"))
                .thenReturn(Optional.empty());

        bean.save();

        verify(service, never()).save(org.mockito.ArgumentMatchers.any());
        verifyFehler();
    }

    @Test
    @DisplayName("root darf alles vergeben")
    void rootDarfAllesVergeben() {
        akteurIstRoot(true);
        Rollenzuteilung rz = zuteilung("ROLE_ROOT");
        when(service.save(rz)).thenReturn(rz);

        bean.save();

        verify(service).save(rz);
    }

    @Test
    @DisplayName("Bestand bleibt editierbar: eine bereits vergebene privilegierte Rolle darf admin speichern")
    void bestandBleibtEditierbar() {
        akteurIstRoot(false);
        Rollenzuteilung rz = zuteilung("ROLE_ADMIN");
        when(service.findByUsernameAndMandatAndRole(BENUTZER, MANDANT, "ROLE_ADMIN"))
                .thenReturn(Optional.of(rz));
        when(service.save(rz)).thenReturn(rz);

        bean.save();

        verify(service).save(rz);
    }
}
