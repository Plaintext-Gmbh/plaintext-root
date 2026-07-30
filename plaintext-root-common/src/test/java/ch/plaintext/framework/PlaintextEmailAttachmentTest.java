/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaintextEmailAttachmentTest {

    // -------------------------------------------------------------------------
    // Getters / Setters (Lombok @Data)
    // -------------------------------------------------------------------------

    @Test
    void setAndGetName() {
        PlaintextEmailAttachment attachment = new PlaintextEmailAttachment();
        attachment.setName("report.pdf");
        assertEquals("report.pdf", attachment.getName());
    }

    @Test
    void setAndGetAttachement() {
        PlaintextEmailAttachment attachment = new PlaintextEmailAttachment();
        byte[] data = {1, 2, 3, 4, 5};
        attachment.setAttachement(data);
        assertArrayEquals(data, attachment.getAttachement());
    }

    @Test
    void defaultValues_areNull() {
        PlaintextEmailAttachment attachment = new PlaintextEmailAttachment();
        assertNull(attachment.getName());
        assertNull(attachment.getAttachement());
    }

    // -------------------------------------------------------------------------
    // toString (excludes attachement)
    // -------------------------------------------------------------------------

    @Test
    void toString_excludesAttachement() {
        PlaintextEmailAttachment attachment = new PlaintextEmailAttachment();
        attachment.setName("test.txt");
        attachment.setAttachement(new byte[]{1, 2, 3});

        String result = attachment.toString();
        assertTrue(result.contains("test.txt"));
        // The @ToString(exclude = {"attachement"}) should exclude the byte array
        assertFalse(result.contains("[1, 2, 3]"));
    }

    // -------------------------------------------------------------------------
    // equals / hashCode (Lombok @Data)
    // -------------------------------------------------------------------------

    @Test
    void equals_sameContent_returnsTrue() {
        PlaintextEmailAttachment a1 = new PlaintextEmailAttachment();
        a1.setName("test.txt");
        a1.setAttachement(new byte[]{1, 2, 3});

        PlaintextEmailAttachment a2 = new PlaintextEmailAttachment();
        a2.setName("test.txt");
        a2.setAttachement(new byte[]{1, 2, 3});

        assertEquals(a1, a2);
        assertEquals(a1.hashCode(), a2.hashCode());
    }

    @Test
    void equals_differentName_returnsFalse() {
        PlaintextEmailAttachment a1 = new PlaintextEmailAttachment();
        a1.setName("test1.txt");

        PlaintextEmailAttachment a2 = new PlaintextEmailAttachment();
        a2.setName("test2.txt");

        assertNotEquals(a1, a2);
    }
}
