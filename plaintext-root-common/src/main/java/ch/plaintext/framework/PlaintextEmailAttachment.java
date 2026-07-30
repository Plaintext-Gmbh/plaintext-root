/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import lombok.Data;
import lombok.ToString;

/**
 * Sicherheitskopie einer Mail
 *
 * @author Plaintext GmbH
 * @since 0.0.1
 */
@Data
@ToString(exclude = {"attachement"})
public class PlaintextEmailAttachment {

    private String name;
    private byte[] attachement;

}
