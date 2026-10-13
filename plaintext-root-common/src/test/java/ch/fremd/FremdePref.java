/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.fremd;

import ch.plaintext.boot.plugins.objstore.SimpleStorable;
import lombok.Getter;
import lombok.Setter;

/**
 * Card 1449 S-15: a type under {@code ch.} but outside {@code ch.plaintext.} — stands for
 * {@code ch.qos.logback.*} and any other library in the {@code ch} namespace.
 */
@Getter
@Setter
public class FremdePref implements SimpleStorable<FremdePref> {
    private String uniqueId;
}
