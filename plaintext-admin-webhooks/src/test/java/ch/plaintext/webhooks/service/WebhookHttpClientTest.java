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

/** Tests for {@link WebhookHttpClient}: 2xx = success, anything else/exception = failure, never lets an exception escape. */
class WebhookHttpClientTest {

    private final HttpClient jdkClient = mock(HttpClient.class);
    /** Name resolution without DNS: example.com public, intern.lan private. */
    private static java.net.InetAddress[] aufloesen(String host) throws java.net.UnknownHostException {
        if (Character.isDigit(host.charAt(0))) {
            return new java.net.InetAddress[]{java.net.InetAddress.getByName(host)}; // IP literal, no DNS
        }
        byte[] ip = host.equals("intern.lan") ? new byte[]{(byte) 192, (byte) 168, 1, 10} : new byte[]{93, (byte) 184, (byte) 215, 14};
        return new java.net.InetAddress[]{java.net.InetAddress.getByAddress(host, ip)};
    }

    private final WebhookHttpClient client =
            new WebhookHttpClient(jdkClient, java.util.Set.of(), WebhookHttpClientTest::aufloesen);

    /** Card 1449 S-11: an internal target is never contacted; the positive control is post_2xx_liefertErfolg. */
    @Test
    void post_internesZiel_wirdNichtAngefragt() throws Exception {
        WebhookHttpClient.DeliveryResult result = client.post("http://intern.lan/admin", Map.of(), "{}");

        assertFalse(result.success());
        assertTrue(result.responseSnippet().startsWith("Ziel nicht erlaubt"));
        org.mockito.Mockito.verifyNoInteractions(jdkClient);
        assertFalse(client.post("http://127.0.0.1:8080/", Map.of(), "{}").success());
        org.mockito.Mockito.verifyNoInteractions(jdkClient);
    }

    /** Card 1449 S-11: an internal host in the allowlist stays reachable (e.g. a LAN receiver). */
    @Test
    void post_internesZielInDerAllowlist_wirdAngefragt() throws Exception {
        HttpResponse<String> resp = response(200, "ok");
        when(jdkClient.<String>send(any(), any())).thenReturn(resp);
        WebhookHttpClient erlaubt = new WebhookHttpClient(jdkClient, java.util.Set.of("intern.lan"), WebhookHttpClientTest::aufloesen);

        assertTrue(erlaubt.post("http://intern.lan/hook", Map.of(), "{}").success());
    }

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
