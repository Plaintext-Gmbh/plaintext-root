/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.web;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.mailtemplate.entity.MailTemplate;
import ch.plaintext.mailtemplate.service.MailTemplateService;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status report 29.08.2026, measure 13 (JaCoCo gate): form logic of the mail texts —
 * mandatory fields, saving with a form reset, loading for editing, removal, error messages.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MailTemplateBackingBean")
class MailTemplateBackingBeanTest {

    @Mock
    private MailTemplateService service;

    @Mock
    private FacesContext facesContext;

    private MockedStatic<FacesContext> facesStatic;
    private MockedStatic<PlaintextSecurityHolder> holder;
    private MailTemplateBackingBean bean;

    @BeforeEach
    void setUp() {
        facesStatic = mockStatic(FacesContext.class);
        facesStatic.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
        holder = mockStatic(PlaintextSecurityHolder.class);
        holder.when(PlaintextSecurityHolder::getMandat).thenReturn("guild42");
        bean = new MailTemplateBackingBean();
        ReflectionTestUtils.setField(bean, "mailTemplateService", service);
    }

    @AfterEach
    void tearDown() {
        holder.close();
        facesStatic.close();
    }

    private static MailTemplate override(long id, String key) {
        MailTemplate t = new MailTemplate();
        t.setId(id);
        t.setTemplateKey(key);
        t.setBetreff("Betreff " + key);
        t.setBody("Body " + key);
        t.setHtml(true);
        return t;
    }

    private FacesMessage letzteMeldung() {
        ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
        verify(facesContext).addMessage(isNull(), captor.capture());
        return captor.getValue();
    }

    @Test
    void onLoadHoltDieOverridesDesMandanten() {
        List<MailTemplate> liste = List.of(override(1, "rechnung"));
        when(service.getOverrides("guild42")).thenReturn(liste);

        bean.onLoad();

        assertSame(liste, bean.getTemplates());
    }

    @Test
    void saveVerlangtTemplateKey() {
        bean.setBetreff("B");
        bean.setBody("T");

        bean.save();

        assertEquals(FacesMessage.SEVERITY_WARN, letzteMeldung().getSeverity());
        verify(service, never()).save(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void saveVerlangtBetreffUndBody() {
        bean.setTemplateKey("rechnung");
        bean.setBetreff("B");
        bean.setBody("  ");

        bean.save();

        assertEquals(FacesMessage.SEVERITY_WARN, letzteMeldung().getSeverity());
        verify(service, never()).save(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void saveSpeichertTrimmtDenKeyUndLeertDasFormular() {
        bean.setTemplateKey("  rechnung ");
        bean.setBetreff("Ihre Rechnung");
        bean.setBody("Hallo");
        bean.setHtml(true);

        bean.save();

        verify(service).save("guild42", "rechnung", "Ihre Rechnung", "Hallo", true);
        verify(service).getOverrides("guild42");
        assertEquals(FacesMessage.SEVERITY_INFO, letzteMeldung().getSeverity());
        assertNull(bean.getTemplateKey());
        assertNull(bean.getBetreff());
        assertNull(bean.getBody());
        assertFalse(bean.isHtml());
    }

    @Test
    void saveMeldetLaufzeitfehlerAlsError() {
        bean.setTemplateKey("rechnung");
        bean.setBetreff("B");
        bean.setBody("T");
        when(service.save(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new IllegalStateException("Unbekannter Template-Key"));

        bean.save();

        FacesMessage m = letzteMeldung();
        assertEquals(FacesMessage.SEVERITY_ERROR, m.getSeverity());
        assertEquals("Unbekannter Template-Key", m.getDetail());
        assertEquals("rechnung", bean.getTemplateKey(), "Formular bleibt bei Fehler erhalten");
    }

    @Test
    void deleteEntferntUndLaedtNeu() {
        bean.delete(override(7, "rechnung"));

        verify(service).deleteOverride(7L);
        verify(service).getOverrides("guild42");
        assertTrue(letzteMeldung().getDetail().contains("rechnung"));
    }

    @Test
    void deleteMeldetLaufzeitfehler() {
        doThrow(new IllegalStateException("weg")).when(service).deleteOverride(7L);

        bean.delete(override(7, "rechnung"));

        assertEquals(FacesMessage.SEVERITY_ERROR, letzteMeldung().getSeverity());
    }

    @Test
    void editLaedtDenOverrideInsFormular() {
        bean.edit(override(3, "mahnung"));

        assertEquals("mahnung", bean.getTemplateKey());
        assertEquals("Betreff mahnung", bean.getBetreff());
        assertEquals("Body mahnung", bean.getBody());
        assertTrue(bean.isHtml());
        assertEquals(FacesMessage.SEVERITY_INFO, letzteMeldung().getSeverity());
    }

    @Test
    void ohneFacesContextGehtKeineMeldungVerloren() {
        facesStatic.when(FacesContext::getCurrentInstance).thenReturn(null);

        bean.edit(override(3, "mahnung"));

        verify(facesContext, times(0)).addMessage(any(), any());
    }
}
