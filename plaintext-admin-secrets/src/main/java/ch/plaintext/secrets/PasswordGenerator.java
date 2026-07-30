/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Passwort-/Secret-Generator: Länge + Komplexität (Zeichenklassen) frei wählbar.
 * Garantiert mind. ein Zeichen je gewählter Klasse; SecureRandom.
 */
@Component
public class PasswordGenerator {

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";      // ohne l
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";       // ohne I, O
    private static final String DIGITS = "23456789";                     // ohne 0, 1
    private static final String SYMBOLS = "!@#$%&*+-_=?";
    private static final SecureRandom RNG = new SecureRandom();

    public String generate(int length, boolean lower, boolean upper, boolean digits, boolean symbols) {
        StringBuilder pool = new StringBuilder();
        List<Character> mandatory = new ArrayList<>();
        if (lower)   { pool.append(LOWER);   mandatory.add(pick(LOWER)); }
        if (upper)   { pool.append(UPPER);   mandatory.add(pick(UPPER)); }
        if (digits)  { pool.append(DIGITS);  mandatory.add(pick(DIGITS)); }
        if (symbols) { pool.append(SYMBOLS); mandatory.add(pick(SYMBOLS)); }
        if (pool.length() == 0) {
            pool.append(LOWER).append(UPPER).append(DIGITS);            // Fallback: keine Klasse gewählt
        }
        int len = Math.max(length, Math.max(4, mandatory.size()));
        List<Character> chars = new ArrayList<>(mandatory);
        while (chars.size() < len) {
            chars.add(pool.charAt(RNG.nextInt(pool.length())));
        }
        Collections.shuffle(chars, RNG);
        StringBuilder sb = new StringBuilder(len);
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static char pick(String s) {
        return s.charAt(RNG.nextInt(s.length()));
    }
}
