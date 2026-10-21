/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Counts every call in {@code claude_nutzung} (one row per day, calling module and model), so that a
 * restart does not lose the consumption. A failing write is logged and swallowed: the statistics
 * must never break the caller's request.
 */
@Slf4j
@Component
public class ClaudeNutzungSpeicher {

    private final JdbcTemplate jdbc;

    public ClaudeNutzungSpeicher(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Adds one call to today's row. */
    public void erfasse(String quelle, String modell, boolean fehler, long input, long output, long cache, long latenzMs) {
        try {
            jdbc.update("""
                    INSERT INTO claude_nutzung (tag, quelle, modell, aufrufe, fehler, input_tokens, output_tokens, cache_tokens, latenz_ms)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
                    ON CONFLICT (tag, quelle, modell) DO UPDATE SET
                        aufrufe = claude_nutzung.aufrufe + 1,
                        fehler = claude_nutzung.fehler + EXCLUDED.fehler,
                        input_tokens = claude_nutzung.input_tokens + EXCLUDED.input_tokens,
                        output_tokens = claude_nutzung.output_tokens + EXCLUDED.output_tokens,
                        cache_tokens = claude_nutzung.cache_tokens + EXCLUDED.cache_tokens,
                        latenz_ms = claude_nutzung.latenz_ms + EXCLUDED.latenz_ms
                    """, Date.valueOf(LocalDate.now()), kurz(quelle, 200), kurz(modell, 100), fehler ? 1 : 0,
                    input, output, cache, latenzMs);
        } catch (DataAccessException e) {
            log.warn("Claude-Verbrauch nicht gespeichert: {}", e.getMessage());
        }
    }

    /** All rows from {@code ab} (inclusive) on. */
    public List<ClaudeNutzung.Zeile> ladeAb(LocalDate ab) {
        return jdbc.query("SELECT tag, quelle, modell, aufrufe, fehler, input_tokens, output_tokens, cache_tokens, latenz_ms"
                        + " FROM claude_nutzung WHERE tag >= ?",
                (rs, i) -> new ClaudeNutzung.Zeile(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)),
                Date.valueOf(ab));
    }

    private static String kurz(String s, int max) {
        String w = s == null || s.isBlank() ? "?" : s;
        return w.length() > max ? w.substring(0, max) : w;
    }
}
