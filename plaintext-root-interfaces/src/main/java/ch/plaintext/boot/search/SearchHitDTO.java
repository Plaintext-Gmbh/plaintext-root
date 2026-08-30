/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Simple, reusable {@link SearchProvider.SearchHit} implementation, so that a provider does not
 * need an inner class of its own. A module typically builds its hits like this:
 * <pre>{@code
 * new SearchHitDTO(k.getTitel(), k.getDatum().toString(),
 *                  "korrespondenz.html?id=" + k.getId(), "pi pi-envelope", score)
 * }</pre>
 *
 * @author plaintext.ch
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchHitDTO implements SearchProvider.SearchHit, Serializable {

    private static final long serialVersionUID = 1L;

    /** Main text of the hit. */
    private String title;

    /** Context line (date/tenant/short description), may be {@code null}. */
    private String subtitle;

    /** Deep link to the module's target page (relative to the context path), like a {@code MenuAnnotation.link}. */
    private String link;

    /** PrimeFaces icon class, may be {@code null}. */
    private String icon;

    /** Ranking within the module group (higher = further up). */
    private int score;
}
