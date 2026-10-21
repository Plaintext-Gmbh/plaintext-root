/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files;

import ch.plaintext.files.config.FilesProperties;
import ch.plaintext.files.mcp.FilesMcpTools;
import ch.plaintext.files.service.FilesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1559, portiert aus dem Inventar ({@code FilesMcpControllerTest}): mit eingestellter Identität kommt nur
 * dieser Name durch - jede andere oder keine Antwort auf «Wer bist du?» wird abgewiesen. Ohne Einstellung fragt
 * niemand.
 */
class FilesMcpToolsTest {

    private final FilesService service = mock(FilesService.class);
    private final FilesProperties properties = new FilesProperties();
    private final FilesMcpTools tools = new FilesMcpTools(service, properties);

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    private static void anmelden() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anna.muster@example.ch", null, List.of()));
    }

    @Test
    void nurDieEingestellteIdentitaetKommtDurch() {
        properties.setMcpIdentitaet("Anna Muster");
        anmelden();
        when(service.liste("anna.muster@example.ch")).thenReturn(List.of());

        assertThat(tools.listFiles("  anna   MUSTER ")).isEqualTo("Keine Dateien.");
        for (String wer : new String[]{null, "", "Hans Muster", "Anna", "ich bin Anna Muster"}) {
            assertThat(tools.listFiles(wer)).as(String.valueOf(wer)).startsWith("FEHLER: ").contains("Wer bist du?");
        }
        tools.saveFile("Hans Muster", "x.txt", "inhalt", false);
        verify(service, never()).hochladen(any(), any(), any());
    }

    @Test
    void ohneEinstellungFragtNiemand() {
        anmelden();
        when(service.liste("anna.muster@example.ch")).thenReturn(List.of());
        assertThat(tools.listFiles(null)).isEqualTo("Keine Dateien.");
    }

    @Test
    void ablegenAlsTextUndBase64() {
        anmelden();
        when(service.hochladen(any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        when(service.frist()).thenReturn(java.time.Duration.ofDays(21));

        assertThat(tools.saveFile(null, "a.txt", "Grüezi", false)).startsWith("OK: a.txt");
        verify(service).hochladen(eq("anna.muster@example.ch"), eq("a.txt"), eq("Grüezi".getBytes(StandardCharsets.UTF_8)));
        assertThat(tools.saveFile(null, "b.bin", "AAEC", true)).contains("(3 Bytes)");
    }

    @Test
    void textOderBase64BeimLesen() {
        anmelden();
        when(service.herunterladen("anna.muster@example.ch", "a.txt")).thenReturn("Grüezi".getBytes(StandardCharsets.UTF_8));
        when(service.herunterladen("anna.muster@example.ch", "b.bin")).thenReturn(new byte[]{0, 1, 2});
        assertThat(tools.getFile(null, "a.txt")).isEqualTo("Grüezi");
        assertThat(tools.getFile(null, "b.bin")).isEqualTo("BASE64:AAEC");
        assertThat(tools.getFile(null, "fehlt")).startsWith("FEHLER: ");
    }
}
