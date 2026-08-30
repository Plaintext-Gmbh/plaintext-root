/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides in ONE place whether a string is deliverable as a mail address.
 *
 * <p><b>Why this is needed (Card 596):</b> in this application the username is also the mail
 * address — self-registration sets it that way
 * ({@code RegistrationService: user.setUsername(token.getEmail())}), the password reset sends to
 * it, and user management enforces the mail form when creating an account. <b>That has only been
 * enforced since this check, however, and only through the user interface</b>: legacy data holds
 * names such as {@code plafferma}, and machine writers leave {@code anonymousUser} behind. Anyone
 * using the username as a recipient without checking it produces a silent failure in exactly
 * those cases.
 *
 * @author info@plaintext.ch
 * @since 07.08.2026
 */
public final class PlaintextEmailAddress {

    /** Same expression as in user management (MyUserBackingBean), so that both accept the same values. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private PlaintextEmailAddress() {
        // Utility class
    }

    /**
     * Whether the value is deliverable as a mail address.
     *
     * @param wert value to check, may be {@code null}
     * @return true if the value has the form of a mail address
     */
    public static boolean isDeliverable(String wert) {
        return wert != null && EMAIL_PATTERN.matcher(wert.trim()).matches();
    }

    /**
     * Returns the value as a deliverable address — or nothing.
     *
     * <p>The {@link Optional} return type is deliberate: it forces the caller to handle the
     * "not deliverable" case instead of passing on an unusable address.
     *
     * @param wert value to check, may be {@code null}
     * @return the trimmed address, or {@link Optional#empty()} if it is not deliverable
     */
    public static Optional<String> asDeliverable(String wert) {
        return isDeliverable(wert) ? Optional.of(wert.trim()) : Optional.empty();
    }
}
