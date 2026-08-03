/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Locale;

/**
 * Model class representing a session attribute with its name and size
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Data
public class SessionAttribute implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String type;
    private long sizeInBytes;
    private String formattedSize;
    private Object value;

    public SessionAttribute(String name, Object value, long sizeInBytes) {
        this.name = name;
        this.value = value;
        this.type = value != null ? value.getClass().getSimpleName() : "null";
        this.sizeInBytes = sizeInBytes;
        this.formattedSize = formatSize(sizeInBytes);
    }

    /**
     * Formats a byte count with a fixed decimal point.
     * <p>
     * {@code Locale.ROOT} rather than the platform default: without it the separator follows the
     * server's locale, so the same session renders as "2.00 KB" or "2,00 KB" depending on where
     * the application happens to run — and the tests only pass on an English machine.
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        } else {
            return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }
}
