/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests für {@link WebhookHttpClient}: 2xx = Erfolg, sonst/Exception = Fehler, nie eine Exception nach aussen. */
class WebhookHttpClientTest {

    private final HttpClient jdkClient = mock(HttpClient.class);
    private final WebhookHttpClient client = new WebhookHttpClient(jdkClient);

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int statusCode, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(statusCode);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    void post_2xx_liefertErfolg() throws Exception {
        HttpResponse<String> resp = response(200, "ok");
        when(jdkClient.<String>send(any(), any())).thenReturn(resp);

        WebhookHttpClient.DeliveryResult result = client.post("https://example.com", Map.of(), "{}");

        assertTrue(result.success());
        assertEquals(200, result.httpStatus());
        assertEquals("ok", result.responseSnippet());
    }

    @Test
    void post_4xx_liefertMisserfolgAberKeineException() throws Exception {
        HttpResponse<String> resp = response(404, "not found");
        when(jdkClient.<String>send(any(), any())).thenReturn(resp);

        WebhookHttpClient.DeliveryResult result = client.post("https://example.com", Map.of(), "{}");

        assertFalse(result.success());
        assertEquals(404, result.httpStatus());
    }

    @Test
    void post_ioException_liefertMisserfolgOhneException() throws Exception {
        when(jdkClient.<String>send(any(), any())).thenThrow(new IOException("connect timeout"));

        WebhookHttpClient.DeliveryResult result = client.post("https://example.com", Map.of(), "{}");

        assertFalse(result.success());
        assertEquals(null, result.httpStatus());
    }

    @Test
    void post_langeAntwort_wirdGekuerzt() throws Exception {
        String lang = "x".repeat(5000);
        HttpResponse<String> resp = response(200, lang);
        when(jdkClient.<String>send(any(), any())).thenReturn(resp);

        WebhookHttpClient.DeliveryResult result = client.post("https://example.com", Map.of(), "{}");

        assertEquals(2000, result.responseSnippet().length());
    }
}
