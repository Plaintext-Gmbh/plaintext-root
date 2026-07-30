/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.entity.BrandingLogo;
import ch.plaintext.settings.service.BrandingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandingRestControllerTest {

    @Mock
    private BrandingService brandingService;

    @Mock
    private PlaintextSecurity security;

    @InjectMocks
    private BrandingRestController controller;

    // --- getLogo ---

    @Test
    void getLogoReturnsNotFoundWhenNoLogo() {
        when(security.getMandat()).thenReturn("mandatA");
        when(brandingService.getLogo("mandatA", "light")).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getLogo("light");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getLogoReturns200WithImageData() {
        when(security.getMandat()).thenReturn("mandatA");

        byte[] imageBytes = "PNG DATA".getBytes();
        BrandingLogo logo = new BrandingLogo();
        logo.setMandat("mandatA");
        logo.setTheme("light");
        logo.setContentType("image/png");
        logo.setImageData(Base64.getEncoder().encodeToString(imageBytes));
        logo.setCreatedDate(LocalDateTime.now());
        when(brandingService.getLogo("mandatA", "light")).thenReturn(Optional.of(logo));
        when(brandingService.getLogoBytes(logo)).thenReturn(imageBytes);

        ResponseEntity<byte[]> response = controller.getLogo("light");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(imageBytes);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
    }

    @Test
    void getLogoDefaultsToLightTheme() {
        when(security.getMandat()).thenReturn("mandatA");
        when(brandingService.getLogo("mandatA", "light")).thenReturn(Optional.empty());

        controller.getLogo("light");

        verify(brandingService).getLogo("mandatA", "light");
    }

    @Test
    void getLogoUsesEtagFromLastModifiedDate() {
        when(security.getMandat()).thenReturn("mandatA");

        LocalDateTime modifiedDate = LocalDateTime.of(2025, 6, 15, 10, 30);
        byte[] imageBytes = "data".getBytes();
        BrandingLogo logo = new BrandingLogo();
        logo.setContentType("image/png");
        logo.setImageData(Base64.getEncoder().encodeToString(imageBytes));
        logo.setLastModifiedDate(modifiedDate);
        logo.setCreatedDate(LocalDateTime.now());
        when(brandingService.getLogo("mandatA", "dark")).thenReturn(Optional.of(logo));
        when(brandingService.getLogoBytes(logo)).thenReturn(imageBytes);

        ResponseEntity<byte[]> response = controller.getLogo("dark");

        assertThat(response.getHeaders().getETag()).isNotNull();
    }

    @Test
    void getLogoUsesEtagFromCreatedDateWhenNoModified() {
        when(security.getMandat()).thenReturn("mandatA");

        LocalDateTime createdDate = LocalDateTime.of(2025, 1, 1, 0, 0);
        byte[] imageBytes = "data".getBytes();
        BrandingLogo logo = new BrandingLogo();
        logo.setContentType("image/png");
        logo.setImageData(Base64.getEncoder().encodeToString(imageBytes));
        logo.setLastModifiedDate(null);
        logo.setCreatedDate(createdDate);
        when(brandingService.getLogo("mandatA", "light")).thenReturn(Optional.of(logo));
        when(brandingService.getLogoBytes(logo)).thenReturn(imageBytes);

        ResponseEntity<byte[]> response = controller.getLogo("light");

        assertThat(response.getHeaders().getETag()).isNotNull();
    }
}
