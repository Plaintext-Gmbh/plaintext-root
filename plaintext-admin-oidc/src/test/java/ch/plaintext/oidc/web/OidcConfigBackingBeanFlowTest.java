/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.service.OidcConfigService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status report 29.08.2026, measure 13 (JaCoCo gate): supplements
 * {@link OidcConfigBackingBeanTest} (session-expired guard) with the normal flows —
 * loading, selection, connection test, JSON export/import, scopes list.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OidcConfigBackingBean Ablaeufe")
class OidcConfigBackingBeanFlowTest {

    @Mock
    private OidcConfigService service;

    @Mock
    private PlaintextSecurity security;

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    private MockedStatic<FacesContext> facesStatic;
    private OidcConfigBackingBean bean;

    @BeforeEach
    void setUp() {
        facesStatic = mockStatic(FacesContext.class);
        facesStatic.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
        when(facesContext.getExternalContext()).thenReturn(externalContext);
        bean = new OidcConfigBackingBean();
        ReflectionTestUtils.setField(bean, "oidcConfigService", service);
        ReflectionTestUtils.setField(bean, "plaintextSecurity", security);
    }

    @AfterEach
    void tearDown() {
        facesStatic.close();
    }

    private static OidcConfig config(String name) {
        OidcConfig c = new OidcConfig();
        c.setName(name);
        c.setIssuerUrl("https://sso.example.org/realms/x");
        c.setClientId("client");
        return c;
    }

    private FacesMessage letzteMeldung() {
        ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
        verify(facesContext).addMessage(isNull(), captor.capture());
        return captor.getValue();
    }

    @Test
    void initLaedtNurFuerRoot() {
        when(security.ifGranted("ROLE_root")).thenReturn(false);
        bean.init();
        verify(service, never()).findAll();

        when(security.ifGranted("ROLE_root")).thenReturn(true);
        OidcConfig erste = config("A");
        when(service.findAll()).thenReturn(List.of(erste, config("B")));
        bean.init();
        assertTrue(bean.isRoot());
        assertSame(erste, bean.getSelected(), "erste Konfiguration wird vorausgewaehlt");
    }

    @Test
    void checkAccessLeitetNichtRootAufAccessDeniedUm() throws Exception {
        when(security.ifGranted("ROLE_root")).thenReturn(false);
        bean.checkAccess();
        verify(externalContext).redirect("access-denied.xhtml");
    }

    @Test
    void newConfigUndSelect() {
        bean.newConfig();
        assertNotNull(bean.getSelected());

        OidcConfig c = config("Keycloak");
        bean.setTestResult("alt");
        bean.select(c);
        assertSame(c, bean.getSelected());
        assertNull(bean.getTestResult(), "Testergebnis gehoert zur vorherigen Auswahl");
    }

    @Test
    void saveVerlangtClientId() {
        OidcConfig c = config("Keycloak");
        c.setClientId("");
        bean.setSelected(c);

        bean.save();

        assertEquals(FacesMessage.SEVERITY_ERROR, letzteMeldung().getSeverity());
        verify(facesContext).validationFailed();
        verify(service, never()).save(c);
    }

    @Test
    void deleteMitAuswahlLoeschtUndLaedtNeu() {
        OidcConfig c = config("Keycloak");
        bean.setSelected(c);
        when(service.findAll()).thenReturn(List.of());

        bean.delete();

        verify(service).delete(c);
        assertNull(bean.getSelected());
        assertEquals(FacesMessage.SEVERITY_INFO, letzteMeldung().getSeverity());
    }

    @Test
    void testConnectionOhneAuswahlUndMitErgebnis() {
        bean.testConnection();
        assertEquals("Keine Konfiguration ausgewählt", bean.getTestResult());

        OidcConfig c = config("Keycloak");
        bean.setSelected(c);
        when(service.testConnection(c)).thenReturn("OK");
        bean.testConnection();
        assertEquals("OK", bean.getTestResult());
        assertEquals(FacesMessage.SEVERITY_INFO, letzteMeldung().getSeverity());
    }

    @Test
    void testConnectionMeldetFehlerAlsError() {
        OidcConfig c = config("Keycloak");
        bean.setSelected(c);
        when(service.testConnection(c)).thenReturn("HTTP 404 - Unerwartete Antwort");

        bean.testConnection();

        assertEquals(FacesMessage.SEVERITY_ERROR, letzteMeldung().getSeverity());
    }

    @Test
    void downloadJsonSchreibtDieKonfigurationAlsAnhang() throws Exception {
        OidcConfig c = config("Keycloak Prod");
        c.setClientSecret("s3cret");
        bean.setSelected(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(externalContext.getResponseOutputStream()).thenReturn(out);

        bean.downloadJson();

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"issuerUrl\" : \"https://sso.example.org/realms/x\""), json);
        assertTrue(json.contains("\"clientSecret\" : \"s3cret\""), json);
        verify(externalContext).setResponseContentType("application/json");
        verify(externalContext).setResponseHeader("Content-Disposition",
                "attachment; filename=\"oidc-Keycloak_Prod.json\"");
        verify(facesContext).responseComplete();
    }

    @Test
    void downloadJsonOhneAuswahlTutNichts() throws Exception {
        bean.downloadJson();
        verify(externalContext, never()).getResponseOutputStream();
    }

    @Test
    void uploadJsonUebernimmtFelderInDieAuswahl() throws Exception {
        String json = """
                {"name":"Importiert","enabled":true,"issuerUrl":"https://idp/","clientId":"cid",
                 "clientSecret":"cs","scopes":"openid,email","usernameAttribute":"preferred_username",
                 "buttonLabel":"Los","buttonIcon":"pi pi-bolt","autoCreateUsers":true,
                 "defaultRoles":"user,admin","defaultMandat":"m1"}
                """;
        UploadedFile datei = mock(UploadedFile.class);
        when(datei.getSize()).thenReturn((long) json.length());
        when(datei.getInputStream()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        FileUploadEvent event = mock(FileUploadEvent.class);
        when(event.getFile()).thenReturn(datei);

        bean.uploadJson(event);

        OidcConfig s = bean.getSelected();
        assertEquals("Importiert", s.getName());
        assertTrue(s.isEnabled());
        assertEquals("https://idp/", s.getIssuerUrl());
        assertEquals("cid", s.getClientId());
        assertEquals("cs", s.getClientSecret());
        assertEquals("openid,email", s.getScopes());
        assertEquals("preferred_username", s.getUsernameAttribute());
        assertEquals("Los", s.getButtonLabel());
        assertEquals("pi pi-bolt", s.getButtonIcon());
        assertTrue(s.isAutoCreateUsers());
        assertEquals("user,admin", s.getDefaultRoles());
        assertEquals("m1", s.getDefaultMandat());
        assertEquals(FacesMessage.SEVERITY_INFO, letzteMeldung().getSeverity());
    }

    @Test
    void uploadJsonOhneDateiWarnt() {
        FileUploadEvent event = mock(FileUploadEvent.class);
        when(event.getFile()).thenReturn(null);

        bean.uploadJson(event);

        assertEquals(FacesMessage.SEVERITY_WARN, letzteMeldung().getSeverity());
    }

    @Test
    void uploadJsonMitKaputtemInhaltMeldetFehler() throws Exception {
        UploadedFile datei = mock(UploadedFile.class);
        when(datei.getSize()).thenReturn(3L);
        when(datei.getInputStream()).thenReturn(new ByteArrayInputStream("{x".getBytes(StandardCharsets.UTF_8)));
        FileUploadEvent event = mock(FileUploadEvent.class);
        when(event.getFile()).thenReturn(datei);

        bean.uploadJson(event);

        assertEquals(FacesMessage.SEVERITY_ERROR, letzteMeldung().getSeverity());
    }

    @Test
    void scopesListeHinUndZurueck() {
        assertTrue(bean.getScopesList().isEmpty(), "ohne Auswahl leer");
        bean.setScopesList(List.of("openid"));
        assertNull(bean.getSelected(), "ohne Auswahl wird nichts angelegt");

        OidcConfig c = config("Keycloak");
        c.setScopes("openid,profile");
        bean.setSelected(c);
        assertEquals(List.of("openid", "profile"), bean.getScopesList());

        bean.setScopesList(List.of("openid", "email"));
        assertEquals("openid,email", c.getScopes());
        bean.setScopesList(null);
        assertEquals("", c.getScopes());
        assertFalse(bean.getScopesList().iterator().hasNext());
    }
}
