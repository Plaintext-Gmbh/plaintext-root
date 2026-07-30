/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.repository.SettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceImplTest {

    @Mock
    private SettingRepository repository;

    @Mock
    private PlaintextSecurity security;

    @InjectMocks
    private SettingsServiceImpl service;

    // --- getString ---

    @Test
    void getStringReturnsValueWhenFound() {
        Setting setting = new Setting();
        setting.setValue("smtp.example.com");
        when(repository.findByKeyAndMandat("mail.host", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getString("mail.host", "mandatA")).isEqualTo("smtp.example.com");
    }

    @Test
    void getStringReturnsNullWhenNotFound() {
        when(repository.findByKeyAndMandat("missing", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getString("missing", "mandatA")).isNull();
    }

    @Test
    void getStringWithCurrentMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        when(repository.findByKeyAndMandat("key", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getString("key")).isNull();
        verify(repository).findByKeyAndMandat("key", "mandatA");
    }

    @Test
    void getStringThrowsForInvalidMandat() {
        when(security.getMandat()).thenReturn("NO_AUTH");

        assertThatThrownBy(() -> service.getString("key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid mandat");
    }

    // --- getInt ---

    @Test
    void getIntReturnsParsedInteger() {
        Setting setting = new Setting();
        setting.setValue("42");
        when(repository.findByKeyAndMandat("port", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getInt("port", "mandatA")).isEqualTo(42);
    }

    @Test
    void getIntReturnsNullForNonNumericValue() {
        Setting setting = new Setting();
        setting.setValue("not-a-number");
        when(repository.findByKeyAndMandat("port", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getInt("port", "mandatA")).isNull();
    }

    @Test
    void getIntReturnsNullWhenNotFound() {
        when(repository.findByKeyAndMandat("missing", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getInt("missing", "mandatA")).isNull();
    }

    @Test
    void getIntWithCurrentMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        Setting setting = new Setting();
        setting.setValue("8080");
        when(repository.findByKeyAndMandat("port", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getInt("port")).isEqualTo(8080);
    }

    // --- getBoolean ---

    @Test
    void getBooleanReturnsTrueForTrueValue() {
        Setting setting = new Setting();
        setting.setValue("true");
        when(repository.findByKeyAndMandat("enabled", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getBoolean("enabled", "mandatA")).isTrue();
    }

    @Test
    void getBooleanReturnsFalseForFalseValue() {
        Setting setting = new Setting();
        setting.setValue("false");
        when(repository.findByKeyAndMandat("enabled", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getBoolean("enabled", "mandatA")).isFalse();
    }

    @Test
    void getBooleanReturnsNullWhenNotFound() {
        when(repository.findByKeyAndMandat("missing", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getBoolean("missing", "mandatA")).isNull();
    }

    @Test
    void getBooleanWithCurrentMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        Setting setting = new Setting();
        setting.setValue("true");
        when(repository.findByKeyAndMandat("flag", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getBoolean("flag")).isTrue();
    }

    // --- getDate ---

    @Test
    void getDateReturnsParsedDate() {
        Setting setting = new Setting();
        setting.setValue("2025-01-15T10:30:00");
        when(repository.findByKeyAndMandat("date", "mandatA")).thenReturn(Optional.of(setting));

        LocalDateTime result = service.getDate("date", "mandatA");
        assertThat(result).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 30, 0));
    }

    @Test
    void getDateReturnsNullForInvalidFormat() {
        Setting setting = new Setting();
        setting.setValue("not-a-date");
        when(repository.findByKeyAndMandat("date", "mandatA")).thenReturn(Optional.of(setting));

        assertThat(service.getDate("date", "mandatA")).isNull();
    }

    @Test
    void getDateReturnsNullWhenNotFound() {
        when(repository.findByKeyAndMandat("missing", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getDate("missing", "mandatA")).isNull();
    }

    @Test
    void getDateWithCurrentMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        when(repository.findByKeyAndMandat("date", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getDate("date")).isNull();
    }

    // --- getList ---

    @Test
    void getListReturnsSplitValues() {
        Setting setting = new Setting();
        setting.setValue("a, b, c");
        when(repository.findByKeyAndMandat("tags", "mandatA")).thenReturn(Optional.of(setting));

        List<String> result = service.getList("tags", "mandatA");
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void getListFiltersEmptyEntries() {
        Setting setting = new Setting();
        setting.setValue("a,,b, ,c");
        when(repository.findByKeyAndMandat("tags", "mandatA")).thenReturn(Optional.of(setting));

        List<String> result = service.getList("tags", "mandatA");
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void getListReturnsEmptyForNullValue() {
        when(repository.findByKeyAndMandat("missing", "mandatA")).thenReturn(Optional.empty());

        List<String> result = service.getList("missing", "mandatA");
        assertThat(result).isEmpty();
    }

    @Test
    void getListReturnsEmptyForEmptyValue() {
        Setting setting = new Setting();
        setting.setValue("  ");
        when(repository.findByKeyAndMandat("tags", "mandatA")).thenReturn(Optional.of(setting));

        List<String> result = service.getList("tags", "mandatA");
        assertThat(result).isEmpty();
    }

    @Test
    void getListWithCurrentMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        when(repository.findByKeyAndMandat("tags", "mandatA")).thenReturn(Optional.empty());

        assertThat(service.getList("tags")).isEmpty();
    }

    // --- setSetting ---

    @Test
    void setSettingCreatesNewSetting() {
        when(repository.findByKeyAndMandat("app.name", "mandatA")).thenReturn(Optional.empty());
        when(repository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setSetting("app.name", "mandatA", "MyApp", "STRING", "Application name");

        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        verify(repository).save(captor.capture());

        Setting saved = captor.getValue();
        assertThat(saved.getKey()).isEqualTo("app.name");
        assertThat(saved.getMandat()).isEqualTo("mandatA");
        assertThat(saved.getValue()).isEqualTo("MyApp");
        assertThat(saved.getValueType()).isEqualTo("STRING");
        assertThat(saved.getDescription()).isEqualTo("Application name");
    }

    @Test
    void setSettingUpdatesExistingSetting() {
        Setting existing = new Setting();
        existing.setKey("app.name");
        existing.setMandat("mandatA");
        existing.setValue("OldApp");

        when(repository.findByKeyAndMandat("app.name", "mandatA")).thenReturn(Optional.of(existing));
        when(repository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setSetting("app.name", "mandatA", "NewApp", "STRING", "Updated");

        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getValue()).isEqualTo("NewApp");
    }

    @Test
    void setSettingDefaultsValueTypeToString() {
        when(repository.findByKeyAndMandat("key", "mandatA")).thenReturn(Optional.empty());
        when(repository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setSetting("key", "mandatA", "val", null, null);

        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getValueType()).isEqualTo("STRING");
    }

    @Test
    void setSettingThrowsForNullKey() {
        assertThatThrownBy(() -> service.setSetting(null, "mandatA", "v", "STRING", "d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key");
    }

    @Test
    void setSettingThrowsForEmptyKey() {
        assertThatThrownBy(() -> service.setSetting("  ", "mandatA", "v", "STRING", "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setSettingThrowsForNullMandat() {
        assertThatThrownBy(() -> service.setSetting("key", null, "v", "STRING", "d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mandat");
    }

    @Test
    void setSettingWithCurrentMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        when(repository.findByKeyAndMandat("key", "mandatA")).thenReturn(Optional.empty());
        when(repository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setSetting("key", "val", "STRING", "desc");

        verify(repository).save(any(Setting.class));
    }

    // --- deleteSetting ---

    @Test
    void deleteSettingCallsRepository() {
        service.deleteSetting("key", "mandatA");
        verify(repository).deleteByKeyAndMandat("key", "mandatA");
    }

    // --- exists ---

    @Test
    void existsReturnsTrueWhenFound() {
        when(repository.existsByKeyAndMandat("key", "mandatA")).thenReturn(true);

        assertThat(service.exists("key", "mandatA")).isTrue();
    }

    @Test
    void existsReturnsFalseWhenNotFound() {
        when(repository.existsByKeyAndMandat("missing", "mandatA")).thenReturn(false);

        assertThat(service.exists("missing", "mandatA")).isFalse();
    }

    // --- getAllKeys ---

    @Test
    void getAllKeysReturnsKeys() {
        when(repository.findAllKeysByMandat("mandatA")).thenReturn(List.of("key1", "key2"));

        assertThat(service.getAllKeys("mandatA")).containsExactly("key1", "key2");
    }

    // --- getChildKeys ---

    @Test
    void getChildKeysReturnsMatchingKeys() {
        Setting s1 = new Setting();
        s1.setKey("mail.smtp.host");
        Setting s2 = new Setting();
        s2.setKey("mail.smtp.port");

        when(repository.findByKeyPrefixAndMandat("mail.%", "mandatA")).thenReturn(List.of(s1, s2));

        List<String> result = service.getChildKeys("mail", "mandatA");
        assertThat(result).containsExactly("mail.smtp.host", "mail.smtp.port");
    }

    // --- getAllSettings ---

    @Test
    void getAllSettingsReturnsSettingsForMandat() {
        Setting s = new Setting();
        when(repository.findByMandatOrderByKeyAsc("mandatA")).thenReturn(List.of(s));

        assertThat(service.getAllSettings("mandatA")).hasSize(1);
    }

    @Test
    void getAllSettingsForCurrentUserUsesSecurityMandat() {
        when(security.getMandat()).thenReturn("mandatA");
        when(repository.findByMandatOrderByKeyAsc("mandatA")).thenReturn(List.of());

        assertThat(service.getAllSettingsForCurrentUser()).isEmpty();
        verify(repository).findByMandatOrderByKeyAsc("mandatA");
    }

    @Test
    void getCurrentMandatThrowsForNoUser() {
        when(security.getMandat()).thenReturn("NO_USER");

        assertThatThrownBy(() -> service.getAllSettingsForCurrentUser())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getCurrentMandatThrowsForError() {
        when(security.getMandat()).thenReturn("ERROR");

        assertThatThrownBy(() -> service.getAllSettingsForCurrentUser())
                .isInstanceOf(IllegalStateException.class);
    }
}
