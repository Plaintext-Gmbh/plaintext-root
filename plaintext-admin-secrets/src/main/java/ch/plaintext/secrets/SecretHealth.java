/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

/**
 * Result of the live test of a backend: does it work ({@code ok}) and – if not – what is missing
 * ({@code detail}). Displayed in the UI in the backend area.
 */
public record SecretHealth(boolean ok, String detail) {
    public static SecretHealth up(String detail) {
        return new SecretHealth(true, detail);
    }
    public static SecretHealth down(String detail) {
        return new SecretHealth(false, detail);
    }
}
