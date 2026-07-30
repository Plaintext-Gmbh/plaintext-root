/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.service.OidcConfigService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Fokus: Session-abgelaufen-Härtung — Interaktionen ohne {@code selected}
 * (Session war abgelaufen/neu) müssen eine klare FacesMessage liefern statt
 * still nichts zu tun oder mit NPE zu crashen.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OidcConfigBackingBean Session-abgelaufen-Guard")
class OidcConfigBackingBeanTest {

    @Mock
    private OidcConfigService oidcConfigService;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private FacesContext facesContext;

    private MockedStatic<FacesContext> facesContextStatic;

    private OidcConfigBackingBean bean;

    @BeforeEach
    void setUp() {
        facesContextStatic = mockStatic(FacesContext.class);
        facesContextStatic.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

        bean = new OidcConfigBackingBean();
        ReflectionTestUtils.setField(bean, "oidcConfigService", oidcConfigService);
        ReflectionTestUtils.setField(bean, "plaintextSecurity", plaintextSecurity);
    }

    @AfterEach
    void tearDown() {
        facesContextStatic.close();
    }

    @Test
    void saveWithoutSelectedShowsSessionExpiredMessageInsteadOfNpe() {
        bean.setSelected(null);

        bean.save();

        ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
        verify(facesContext).addMessage(isNull(), captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(FacesMessage.SEVERITY_WARN);
        assertThat(captor.getValue().getSummary()).contains("Sitzung abgelaufen");
        assertThat(captor.getValue().getDetail()).contains("bitte Eintrag erneut auswählen");
        verify(facesContext).validationFailed();
        verify(oidcConfigService, never()).save(any());
    }

    @Test
    void deleteWithoutSelectedShowsSessionExpiredMessage() {
        bean.setSelected(null);

        bean.delete();

        ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
        verify(facesContext).addMessage(isNull(), captor.capture());
        assertThat(captor.getValue().getSummary()).contains("Sitzung abgelaufen");
        verify(oidcConfigService, never()).delete(any());
    }

    @Test
    void saveWithSelectedStillWorks() {
        OidcConfig config = new OidcConfig();
        config.setIssuerUrl("https://issuer.example.org");
        config.setClientId("client-1");
        bean.setSelected(config);
        when(oidcConfigService.save(config)).thenReturn(config);
        when(oidcConfigService.findAll()).thenReturn(List.of(config));

        bean.save();

        verify(oidcConfigService).save(config);
        ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
        verify(facesContext).addMessage(isNull(), captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(FacesMessage.SEVERITY_INFO);
        verify(facesContext, never()).validationFailed();
    }

    @Test
    void saveWithSelectedButMissingIssuerShowsValidationError() {
        OidcConfig config = new OidcConfig();
        config.setClientId("client-1");
        bean.setSelected(config);

        bean.save();

        verify(oidcConfigService, never()).save(any());
        verify(facesContext).validationFailed();
    }
}
