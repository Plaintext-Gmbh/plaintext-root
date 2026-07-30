/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Standardized API error response following RFC 7807 (Problem Details).
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        Instant expiredAt,
        String path
) implements IApiErrorResponse {

    @Override
    public int getStatus() { return status; }

    @Override
    public String getError() { return error; }

    @Override
    public String getMessage() { return message; }

    @Override
    public Instant getTimestamp() { return timestamp; }

    @Override
    public Instant getExpiredAt() { return expiredAt; }

    @Override
    public String getPath() { return path; }
    public ApiErrorResponse(int status, String error, String message) {
        this(status, error, message, Instant.now(), null, null);
    }

    public ApiErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, Instant.now(), null, path);
    }

    public static ApiErrorResponse tokenMissing(String path) {
        return new ApiErrorResponse(
                401,
                "TOKEN_MISSING",
                "Authentication required. Please provide a valid JWT token as Bearer token in the Authorization header.",
                Instant.now(),
                null,
                path
        );
    }

    public static ApiErrorResponse tokenExpired(Instant expiredAt, String path) {
        return new ApiErrorResponse(
                401,
                "TOKEN_EXPIRED",
                "The provided token has expired. Please request a new token.",
                Instant.now(),
                expiredAt,
                path
        );
    }

    public static ApiErrorResponse tokenInvalid(String path) {
        return new ApiErrorResponse(
                401,
                "TOKEN_INVALID",
                "The provided token is invalid. The signature could not be verified.",
                Instant.now(),
                null,
                path
        );
    }

    public static ApiErrorResponse tokenRevoked(String path) {
        return new ApiErrorResponse(
                401,
                "TOKEN_REVOKED",
                "The provided token has been revoked and is no longer valid.",
                Instant.now(),
                null,
                path
        );
    }

    public static ApiErrorResponse notFound(String resource, String path) {
        return new ApiErrorResponse(
                404,
                "NOT_FOUND",
                resource + " not found.",
                Instant.now(),
                null,
                path
        );
    }
}
