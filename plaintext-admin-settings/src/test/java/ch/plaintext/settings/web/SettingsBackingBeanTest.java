/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.service.SettingsServiceImpl;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsBackingBeanTest {

    @Mock
    private SettingsServiceImpl service;

    @Mock
    private PlaintextSecurity security;

    private SettingsBackingBean bean;

    @BeforeEach
    void setUp() {
        bean = new SettingsBackingBean(service, security);
    }

    // --- onLoad ---

    @Test
    void onLoadLoadsSettingsWhenRoot() {
        when(security.ifGranted("ROLE_ROOT")).thenReturn(true);
        when(security.getMandat()).thenReturn("mandatA");
        Setting s = new Setting();
        s.setKey("app.name");
        when(service.getAllSettings("mandatA")).thenReturn(List.of(s));

        bean.onLoad();

        assertThat(bean.isRoot()).isTrue();
        assertThat(bean.getSettings()).hasSize(1);
    }

    @Test
    void onLoadRedirectsAndSkipsLoadWhenNotRoot() throws Exception {
        when(security.ifGranted("ROLE_ROOT")).thenReturn(false);

        try (MockedStatic<FacesContext> fcMock = mockStatic(FacesContext.class)) {
            FacesContext facesContext = mock(FacesContext.class);
            ExternalContext externalContext = mock(ExternalContext.class);
            fcMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);

            bean.onLoad();

            assertThat(bean.isRoot()).isFalse();
            verify(externalContext).redirect("/index.xhtml");
            verifyNoInteractions(service);
        }
    }

    // --- select / clearSelection ---

    @Test
    void selectDoesNothing() {
        bean.select(); // just verifies it doesn't throw
    }

    @Test
    void clearSelectionSetsSelectedToNull() {
        bean.setSelected(new Setting());
        assertThat(bean.getSelected()).isNotNull();

        bean.clearSelection();
        assertThat(bean.getSelected()).isNull();
    }

    // --- newSetting ---

    @Test
    void newSettingCreatesSettingWithDefaults() {
        when(security.getMandat()).thenReturn("mandatA");

        bean.newSetting();

        Setting selected = bean.getSelected();
        assertThat(selected).isNotNull();
        assertThat(selected.getKey()).isEmpty();
        assertThat(selected.getMandat()).isEqualTo("mandatA");
        assertThat(selected.getValue()).isEmpty();
        assertThat(selected.getValueType()).isEqualTo("STRING");
        assertThat(selected.getDescription()).isEmpty();
    }

    // --- getBooleanValue / setBooleanValue ---

    @Test
    void getBooleanValueReturnsFalseWhenNoSelected() {
        bean.setSelected(null);
        assertThat(bean.getBooleanValue()).isFalse();
    }

    @Test
    void getBooleanValueReturnsFalseWhenValueIsNull() {
        Setting s = new Setting();
        s.setValue(null);
        bean.setSelected(s);

        assertThat(bean.getBooleanValue()).isFalse();
    }

    @Test
    void getBooleanValueReturnsTrueForTrueString() {
        Setting s = new Setting();
        s.setValue("true");
        bean.setSelected(s);

        assertThat(bean.getBooleanValue()).isTrue();
    }

    @Test
    void getBooleanValueReturnsFalseForFalseString() {
        Setting s = new Setting();
        s.setValue("false");
        bean.setSelected(s);

        assertThat(bean.getBooleanValue()).isFalse();
    }

    @Test
    void setBooleanValueSetsValueOnSelected() {
        Setting s = new Setting();
        bean.setSelected(s);

        bean.setBooleanValue(true);
        assertThat(s.getValue()).isEqualTo("true");

        bean.setBooleanValue(false);
        assertThat(s.getValue()).isEqualTo("false");
    }

    @Test
    void setBooleanValueSetsFalseForNull() {
        Setting s = new Setting();
        bean.setSelected(s);

        bean.setBooleanValue(null);
        assertThat(s.getValue()).isEqualTo("false");
    }

    @Test
    void setBooleanValueDoesNothingWhenNoSelected() {
        bean.setSelected(null);
        bean.setBooleanValue(true); // should not throw
    }

    // --- getDateValue / setDateValue ---

    @Test
    void getDateValueReturnsNullWhenNoSelected() {
        bean.setSelected(null);
        assertThat(bean.getDateValue()).isNull();
    }

    @Test
    void getDateValueReturnsNullWhenValueIsNull() {
        Setting s = new Setting();
        s.setValue(null);
        bean.setSelected(s);

        assertThat(bean.getDateValue()).isNull();
    }

    @Test
    void getDateValueReturnsNullWhenValueIsEmpty() {
        Setting s = new Setting();
        s.setValue("   ");
        bean.setSelected(s);

        assertThat(bean.getDateValue()).isNull();
    }

    @Test
    void getDateValueReturnsParsedDate() {
        Setting s = new Setting();
        s.setValue("2025-06-15T14:30:00");
        bean.setSelected(s);

        LocalDateTime result = bean.getDateValue();
        assertThat(result).isEqualTo(LocalDateTime.of(2025, 6, 15, 14, 30, 0));
    }

    @Test
    void getDateValueReturnsNullForInvalidFormat() {
        Setting s = new Setting();
        s.setValue("not-a-date");
        bean.setSelected(s);

        assertThat(bean.getDateValue()).isNull();
    }

    @Test
    void setDateValueSetsFormattedValue() {
        Setting s = new Setting();
        bean.setSelected(s);

        LocalDateTime date = LocalDateTime.of(2025, 3, 20, 10, 0, 0);
        bean.setDateValue(date);

        assertThat(s.getValue()).isEqualTo("2025-03-20T10:00:00");
    }

    @Test
    void setDateValueSetsEmptyStringForNull() {
        Setting s = new Setting();
        bean.setSelected(s);

        bean.setDateValue(null);
        assertThat(s.getValue()).isEmpty();
    }

    @Test
    void setDateValueDoesNothingWhenNoSelected() {
        bean.setSelected(null);
        bean.setDateValue(LocalDateTime.now()); // should not throw
    }

    // --- getFilteredSettings ---

    @Test
    void getFilteredSettingsReturnsAllWhenNoFilter() {
        Setting s1 = new Setting();
        s1.setKey("key1");
        Setting s2 = new Setting();
        s2.setKey("key2");
        bean.setSettings(List.of(s1, s2));

        bean.setSearchFilter(null);
        assertThat(bean.getFilteredSettings()).hasSize(2);

        bean.setSearchFilter("  ");
        assertThat(bean.getFilteredSettings()).hasSize(2);
    }

    @Test
    void getFilteredSettingsFiltersByKey() {
        Setting s1 = new Setting();
        s1.setKey("mail.host");
        s1.setValue("v1");
        Setting s2 = new Setting();
        s2.setKey("app.name");
        s2.setValue("v2");
        bean.setSettings(List.of(s1, s2));

        bean.setSearchFilter("mail");
        assertThat(bean.getFilteredSettings()).hasSize(1);
        assertThat(bean.getFilteredSettings().get(0).getKey()).isEqualTo("mail.host");
    }

    @Test
    void getFilteredSettingsFiltersByValue() {
        Setting s1 = new Setting();
        s1.setKey("key1");
        s1.setValue("smtp.example.com");
        Setting s2 = new Setting();
        s2.setKey("key2");
        s2.setValue("MyApp");
        bean.setSettings(List.of(s1, s2));

        bean.setSearchFilter("smtp");
        assertThat(bean.getFilteredSettings()).hasSize(1);
        assertThat(bean.getFilteredSettings().get(0).getKey()).isEqualTo("key1");
    }

    @Test
    void getFilteredSettingsFiltersByDescription() {
        Setting s1 = new Setting();
        s1.setKey("key1");
        s1.setValue("val");
        s1.setDescription("SMTP configuration");
        Setting s2 = new Setting();
        s2.setKey("key2");
        s2.setValue("val");
        s2.setDescription("App name");
        bean.setSettings(List.of(s1, s2));

        bean.setSearchFilter("smtp");
        assertThat(bean.getFilteredSettings()).hasSize(1);
    }

    @Test
    void getFilteredSettingsHandlesNullValueAndDescription() {
        Setting s1 = new Setting();
        s1.setKey("key1");
        s1.setValue(null);
        s1.setDescription(null);
        bean.setSettings(List.of(s1));

        bean.setSearchFilter("something");
        assertThat(bean.getFilteredSettings()).isEmpty();
    }

    @Test
    void getFilteredSettingsIsCaseInsensitive() {
        Setting s1 = new Setting();
        s1.setKey("Mail.Host");
        s1.setValue("v");
        bean.setSettings(List.of(s1));

        bean.setSearchFilter("MAIL");
        assertThat(bean.getFilteredSettings()).hasSize(1);
    }

    // --- getAllMandate ---

    @Test
    void getAllMandateReturnsMandateFromSecurity() {
        when(security.getAllMandate()).thenReturn(Set.of("mandatA", "mandatB"));

        List<String> result = bean.getAllMandate();
        assertThat(result).containsExactlyInAnyOrder("mandatA", "mandatB");
    }

    // --- getValueTypes ---

    @Test
    void getValueTypesReturnsAllTypes() {
        List<String> types = bean.getValueTypes();
        assertThat(types).containsExactly("STRING", "INTEGER", "BOOLEAN", "DATE", "LIST");
    }

    // --- getter/setter coverage ---

    @Test
    void searchFilterGetterSetter() {
        bean.setSearchFilter("test");
        assertThat(bean.getSearchFilter()).isEqualTo("test");
    }

    @Test
    void rootGetterSetter() {
        bean.setRoot(true);
        assertThat(bean.isRoot()).isTrue();
        bean.setRoot(false);
        assertThat(bean.isRoot()).isFalse();
    }

    @Test
    void serviceAndSecurityAreAccessible() {
        assertThat(bean.getService()).isSameAs(service);
        assertThat(bean.getSecurity()).isSameAs(security);
    }

    @Test
    void settingsGetterSetter() {
        Setting s = new Setting();
        s.setKey("test");
        bean.setSettings(List.of(s));

        assertThat(bean.getSettings()).hasSize(1);
        assertThat(bean.getSettings().get(0).getKey()).isEqualTo("test");
    }

    @Test
    void selectedGetterSetter() {
        Setting s = new Setting();
        s.setKey("selected.key");
        bean.setSelected(s);

        assertThat(bean.getSelected()).isNotNull();
        assertThat(bean.getSelected().getKey()).isEqualTo("selected.key");
    }
}
