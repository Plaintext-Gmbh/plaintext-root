/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Fail-safe HTTP POST client for webhook deliveries (pattern analogous to {@code PaperlessClient}:
 * JDK {@link HttpClient}, per-request timeouts, test constructor with an injected client, never let
 * an exception escape).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
public class WebhookHttpClient {

    private static final int RESPONSE_SNIPPET_MAX = 2000;

    private final HttpClient httpClient;

    public WebhookHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Test constructor with an injected {@link HttpClient}. */
    WebhookHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public record DeliveryResult(boolean success, Integer httpStatus, String responseSnippet) {
        static DeliveryResult fehler(String message) {
            return new DeliveryResult(false, null, message);
        }
    }

    /** Sends {@code body} via POST to {@code url} with the given headers. Never lets an exception escape. */
    public DeliveryResult post(String url, Map<String, String> headers, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            String snippet = kuerzen(response.body());
            if (!success) {
                log.info("Webhook-Zustellung an {} fehlgeschlagen: HTTP {}", url, response.statusCode());
            }
            return new DeliveryResult(success, response.statusCode(), snippet);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.fehler("Unterbrochen");
        } catch (Exception e) {
            log.info("Webhook-Zustellung an {} fehlgeschlagen: {}", url, e.toString());
            return DeliveryResult.fehler(e.toString());
        }
    }

    private static String kuerzen(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > RESPONSE_SNIPPET_MAX ? body.substring(0, RESPONSE_SNIPPET_MAX) : body;
    }
}
