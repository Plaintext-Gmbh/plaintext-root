/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Status report 2026-08-29, measure 13 (JaCoCo gate): the generator promises at least
 * one character per selected class, no confusable characters (0/O, 1/l/I) and a
 * minimum length — that is verifiable without pinning down the randomness.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("PasswordGenerator")
class PasswordGeneratorTest {

    private final PasswordGenerator generator = new PasswordGenerator();

    @RepeatedTest(20)
    void jedeGewaehlteKlasseKommtVorUndDieLaengeStimmt() {
        String pw = generator.generate(16, true, true, true, true);

        assertEquals(16, pw.length());
        assertTrue(pw.chars().anyMatch(Character::isLowerCase), pw);
        assertTrue(pw.chars().anyMatch(Character::isUpperCase), pw);
        assertTrue(pw.chars().anyMatch(Character::isDigit), pw);
        assertTrue(pw.chars().anyMatch(c -> "!@#$%&*+-_=?".indexOf(c) >= 0), pw);
    }

    @RepeatedTest(20)
    void verwechselbareZeichenFehlen() {
        String pw = generator.generate(64, true, true, true, false);

        for (char verboten : "0O1lI".toCharArray()) {
            assertTrue(pw.indexOf(verboten) < 0, "'" + verboten + "' in " + pw);
        }
    }

    @Test
    void nurZiffernWennNurZiffernGewaehlt() {
        String pw = generator.generate(12, false, false, true, false);

        assertEquals(12, pw.length());
        assertTrue(pw.chars().allMatch(Character::isDigit), pw);
    }

    @Test
    void ohneKlasseFaelltDerPoolAufBuchstabenUndZiffernZurueck() {
        String pw = generator.generate(10, false, false, false, false);

        assertEquals(10, pw.length());
        assertTrue(pw.chars().allMatch(Character::isLetterOrDigit), pw);
    }

    @Test
    void mindestlaengeVierUndMindestensEinZeichenJeKlasse() {
        assertEquals(4, generator.generate(1, true, false, false, false).length());
        assertEquals(4, generator.generate(0, true, true, true, true).length());
        assertFalse(generator.generate(4, true, true, true, true).isBlank());
    }
}
