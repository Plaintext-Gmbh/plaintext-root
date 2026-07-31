/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.entity.BrandingLogo;
import ch.plaintext.settings.repository.BrandingLogoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandingServiceTest {

    @Mock
    private BrandingLogoRepository logoRepository;

    @Mock
    private ISettingsService settingsService;

    @Mock
    private PlaintextSecurity security;

    @InjectMocks
    private BrandingService service;

    // --- getLogo ---

    @Test
    void getLogoReturnsPresentWhenFound() {
        BrandingLogo logo = new BrandingLogo();
        logo.setMandat("mandatA");
        logo.setTheme("light");
        when(logoRepository.findByMandatAndTheme("mandatA", "light")).thenReturn(Optional.of(logo));

        assertThat(service.getLogo("mandatA", "light")).isPresent();
    }

    @Test
    void getLogoReturnsEmptyWhenNotFound() {
        when(logoRepository.findByMandatAndTheme("mandatA", "dark")).thenReturn(Optional.empty());

        assertThat(service.getLogo("mandatA", "dark")).isEmpty();
    }

    // --- saveLogo ---

    @Test
    void saveLogoCreatesNewLogo() {
        when(logoRepository.findByMandatAndTheme("mandatA", "light")).thenReturn(Optional.empty());
        when(logoRepository.save(any(BrandingLogo.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] imageData = png();
        service.saveLogo("mandatA", "light", imageData, "image/png", "logo.png", 200, 50);

        ArgumentCaptor<BrandingLogo> captor = ArgumentCaptor.forClass(BrandingLogo.class);
        verify(logoRepository).save(captor.capture());
        BrandingLogo saved = captor.getValue();
        assertThat(saved.getMandat()).isEqualTo("mandatA");
        assertThat(saved.getTheme()).isEqualTo("light");
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getFileName()).isEqualTo("logo.png");
        assertThat(saved.getLogoWidth()).isEqualTo(200);
        assertThat(saved.getLogoHeight()).isEqualTo(50);
        assertThat(saved.getImageData()).isEqualTo(Base64.getEncoder().encodeToString(imageData));
    }

    @Test
    void saveLogoUpdatesExistingLogo() {
        BrandingLogo existing = new BrandingLogo();
        existing.setId(1L);
        existing.setMandat("mandatA");
        existing.setTheme("dark");
        when(logoRepository.findByMandatAndTheme("mandatA", "dark")).thenReturn(Optional.of(existing));
        when(logoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        byte[] imageData = jpeg();
        service.saveLogo("mandatA", "dark", imageData, "image/jpeg", "new.jpg", null, null);

        ArgumentCaptor<BrandingLogo> captor = ArgumentCaptor.forClass(BrandingLogo.class);
        verify(logoRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getLogoWidth()).isEqualTo(180); // default
        assertThat(captor.getValue().getLogoHeight()).isEqualTo(40); // default
    }

    @Test
    void saveLogoThrowsForInvalidContentType() {
        byte[] imageData = "data".getBytes();

        assertThatThrownBy(() -> service.saveLogo("mandatA", "light", imageData, "application/pdf", "f.pdf", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungültiges Bildformat");
    }

    @Test
    void saveLogoThrowsForOversizedFile() {
        byte[] oversized = new byte[3 * 1024 * 1024]; // 3MB

        assertThatThrownBy(() -> service.saveLogo("mandatA", "light", oversized, "image/png", "big.png", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 MB");
    }

    @Test
    void saveLogoAcceptsAllValidContentTypes() {
        when(logoRepository.findByMandatAndTheme(any(), any())).thenReturn(Optional.empty());
        when(logoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveLogo("m", "light", png(), "image/png", "f.png", null, null);
        service.saveLogo("m", "light", webp(), "image/webp", "f.webp", null, null);
        service.saveLogo("m", "light", jpeg(), "image/jpeg", "f.jpg", null, null);

        verify(logoRepository, times(3)).save(any());
    }

    /**
     * SECURITY (Karte 314, Punkt 14): SVG ist als Logo-Format nicht mehr zulaessig. Ein SVG ist
     * ein XML-Dokument mit erlaubtem {@code <script>} und wurde same-origin mit gespeichertem
     * Content-Type ausgeliefert — also gespeichertes XSS im Anwendungs-Origin.
     */
    @Test
    void saveLogoRejectsSvg() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x()</script></svg>".getBytes();

        assertThatThrownBy(() -> service.saveLogo("m", "light", svg, "image/svg+xml", "x.svg", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungültiges Bildformat");
    }

    /**
     * SECURITY (Karte 314, Punkt 14): der Content-Type ist client-kontrolliert. Ein als
     * "image/png" deklariertes SVG darf nicht durchrutschen — sonst waere die Typ-Allowlist
     * durch blosses Umdeklarieren umgehbar.
     */
    @Test
    void saveLogoRejectsSvgDisguisedAsPng() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x()</script></svg>".getBytes();

        assertThatThrownBy(() -> service.saveLogo("m", "light", svg, "image/png", "x.png", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dateiinhalt passt nicht");
    }

    /**
     * SECURITY (Karte 314, Punkt 14): bereits gespeicherte SVG-Logos (Alt-Bestand) duerfen nicht
     * mehr ausgeliefert werden.
     */
    @Test
    void svgIsNotDeliverable() {
        assertThat(BrandingService.isDeliverableContentType("image/svg+xml")).isFalse();
        assertThat(BrandingService.isDeliverableContentType("image/png")).isTrue();
        assertThat(BrandingService.isDeliverableContentType(null)).isFalse();
    }

    // --- getLogoBytes ---

    @Test
    void getLogoBytesDecodesBase64() {
        byte[] original = "test image data".getBytes();
        BrandingLogo logo = new BrandingLogo();
        logo.setImageData(Base64.getEncoder().encodeToString(original));

        byte[] result = service.getLogoBytes(logo);

        assertThat(result).isEqualTo(original);
    }

    // --- deleteLogo ---

    @Test
    void deleteLogoDelegatesToRepository() {
        service.deleteLogo("mandatA", "light");

        verify(logoRepository).deleteByMandatAndTheme("mandatA", "light");
    }

    // --- getFooterText ---

    @Test
    void getFooterTextReturnsValueFromSettings() {
        when(settingsService.getString("branding.footer.text", "mandatA")).thenReturn("Custom Footer");

        assertThat(service.getFooterText("mandatA")).isEqualTo("Custom Footer");
    }

    @Test
    void getFooterTextReturnsEmptyWhenNull() {
        when(settingsService.getString("branding.footer.text", "mandatA")).thenReturn(null);

        assertThat(service.getFooterText("mandatA")).isEmpty();
    }

    // --- isShowVersion ---

    @Test
    void isShowVersionReturnsTrueByDefault() {
        when(settingsService.getBoolean("branding.footer.showVersion", "mandatA")).thenReturn(null);

        assertThat(service.isShowVersion("mandatA")).isTrue();
    }

    @Test
    void isShowVersionReturnsFalseWhenSetToFalse() {
        when(settingsService.getBoolean("branding.footer.showVersion", "mandatA")).thenReturn(false);

        assertThat(service.isShowVersion("mandatA")).isFalse();
    }

    // --- isShowRootVersion ---

    @Test
    void isShowRootVersionReturnsTrueByDefault() {
        when(settingsService.getBoolean("branding.footer.showRootVersion", "mandatA")).thenReturn(null);

        assertThat(service.isShowRootVersion("mandatA")).isTrue();
    }

    // --- isShowBuildTimestamp ---

    @Test
    void isShowBuildTimestampReturnsTrueByDefault() {
        when(settingsService.getBoolean("branding.footer.showBuildTimestamp", "mandatA")).thenReturn(null);

        assertThat(service.isShowBuildTimestamp("mandatA")).isTrue();
    }

    @Test
    void isShowBuildTimestampReturnsFalseWhenSetToFalse() {
        when(settingsService.getBoolean("branding.footer.showBuildTimestamp", "mandatA")).thenReturn(false);

        assertThat(service.isShowBuildTimestamp("mandatA")).isFalse();
    }

    // --- saveFooterSettings ---

    @Test
    void saveFooterSettingsSavesAllSettings() {
        service.saveFooterSettings("mandatA", "My Footer", true, false, true);

        verify(settingsService).setSetting("branding.footer.text", "mandatA", "My Footer", "STRING", "Custom footer text");
        verify(settingsService).setSetting("branding.footer.showVersion", "mandatA", "true", "BOOLEAN", "Show app version in footer");
        verify(settingsService).setSetting("branding.footer.showRootVersion", "mandatA", "false", "BOOLEAN", "Show root version in footer");
        verify(settingsService).setSetting("branding.footer.showBuildTimestamp", "mandatA", "true", "BOOLEAN", "Show build timestamp in footer");
    }

    // --- getLogoWidth / getLogoHeight ---

    @Test
    void getLogoWidthReturnsValueFromLogo() {
        BrandingLogo logo = new BrandingLogo();
        logo.setLogoWidth(250);
        when(logoRepository.findByMandatAndTheme("mandatA", "light")).thenReturn(Optional.of(logo));

        assertThat(service.getLogoWidth("mandatA", "light")).isEqualTo(250);
    }

    @Test
    void getLogoWidthReturnsDefaultWhenNoLogo() {
        when(logoRepository.findByMandatAndTheme("mandatA", "light")).thenReturn(Optional.empty());

        assertThat(service.getLogoWidth("mandatA", "light")).isEqualTo(180);
    }

    @Test
    void getLogoHeightReturnsValueFromLogo() {
        BrandingLogo logo = new BrandingLogo();
        logo.setLogoHeight(60);
        when(logoRepository.findByMandatAndTheme("mandatA", "dark")).thenReturn(Optional.of(logo));

        assertThat(service.getLogoHeight("mandatA", "dark")).isEqualTo(60);
    }

    @Test
    void getLogoHeightReturnsDefaultWhenNoLogo() {
        when(logoRepository.findByMandatAndTheme("mandatA", "dark")).thenReturn(Optional.empty());

        assertThat(service.getLogoHeight("mandatA", "dark")).isEqualTo(40);
    }

    // --- hasLogo ---

    @Test
    void hasLogoReturnsTrueWhenPresent() {
        when(logoRepository.findByMandatAndTheme("mandatA", "light")).thenReturn(Optional.of(new BrandingLogo()));

        assertThat(service.hasLogo("mandatA", "light")).isTrue();
    }

    @Test
    void hasLogoReturnsFalseWhenAbsent() {
        when(logoRepository.findByMandatAndTheme("mandatA", "dark")).thenReturn(Optional.empty());

        assertThat(service.hasLogo("mandatA", "dark")).isFalse();
    }

    // --- updateLogoDimensions ---

    @Test
    void updateLogoDimensionsSavesLogo() {
        BrandingLogo logo = new BrandingLogo();
        logo.setId(1L);
        logo.setLogoWidth(300);

        service.updateLogoDimensions(logo);

        verify(logoRepository).save(logo);
    }

    // --- SECURITY (Karte 314, Punkt 14): Testdaten mit echten Magic Bytes ---------------------
    // saveLogo prueft den Dateiinhalt gegen den behaupteten Content-Type. Die Tests muessen
    // deshalb Bytes liefern, die wie das jeweilige Format aussehen.

    private static byte[] png() {
        byte[] b = new byte[32];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private static byte[] jpeg() {
        byte[] b = new byte[32];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    private static byte[] webp() {
        byte[] b = new byte[32];
        byte[] sig = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }
}
