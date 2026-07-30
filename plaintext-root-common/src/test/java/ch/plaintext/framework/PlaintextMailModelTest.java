/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaintextMailModelTest {

    private PlaintextMailModel model;

    @BeforeEach
    void setUp() {
        model = new PlaintextMailModel();
    }

    // -------------------------------------------------------------------------
    // Default values
    // -------------------------------------------------------------------------

    @Test
    void defaults_areSetCorrectly() {
        assertEquals("default", model.getMandat());
        assertEquals("", model.getSender());
        assertTrue(model.getReceiver().isEmpty());
        assertTrue(model.getReceiverCC().isEmpty());
        assertTrue(model.getReceiverBCC().isEmpty());
        assertNull(model.getSubject());
        assertNull(model.getBody());
        assertTrue(model.getAttachments().isEmpty());
        assertFalse(model.getHtml());
    }

    // -------------------------------------------------------------------------
    // addTo
    // -------------------------------------------------------------------------

    @Test
    void addTo_singleAddress_addsToReceiver() {
        model.addTo("user@example.com");
        assertEquals(1, model.getReceiver().size());
        assertTrue(model.getReceiver().contains("user@example.com"));
    }

    @Test
    void addTo_commaSeparated_addsAll() {
        model.addTo("a@test.com,b@test.com,c@test.com");
        assertEquals(3, model.getReceiver().size());
        assertTrue(model.getReceiver().contains("a@test.com"));
        assertTrue(model.getReceiver().contains("b@test.com"));
        assertTrue(model.getReceiver().contains("c@test.com"));
    }

    @Test
    void addTo_null_doesNothing() {
        model.addTo(null);
        assertTrue(model.getReceiver().isEmpty());
    }

    @Test
    void addTo_emptyString_doesNothing() {
        model.addTo("");
        assertTrue(model.getReceiver().isEmpty());
    }

    @Test
    void addTo_duplicate_deduplicatedBySet() {
        model.addTo("user@example.com");
        model.addTo("user@example.com");
        assertEquals(1, model.getReceiver().size());
    }

    // -------------------------------------------------------------------------
    // addCC
    // -------------------------------------------------------------------------

    @Test
    void addCC_singleAddress_addsToReceiverCC() {
        model.addCC("cc@example.com");
        assertEquals(1, model.getReceiverCC().size());
        assertTrue(model.getReceiverCC().contains("cc@example.com"));
    }

    @Test
    void addCC_commaSeparated_addsAll() {
        model.addCC("a@test.com,b@test.com");
        assertEquals(2, model.getReceiverCC().size());
        assertTrue(model.getReceiverCC().contains("a@test.com"));
        assertTrue(model.getReceiverCC().contains("b@test.com"));
    }

    @Test
    void addCC_null_doesNothing() {
        model.addCC(null);
        assertTrue(model.getReceiverCC().isEmpty());
    }

    @Test
    void addCC_emptyString_doesNothing() {
        model.addCC("");
        assertTrue(model.getReceiverCC().isEmpty());
    }

    // -------------------------------------------------------------------------
    // addBCC
    // -------------------------------------------------------------------------

    @Test
    void addBCC_singleAddress_addsToReceiverBCC() {
        model.addBCC("bcc@example.com");
        assertEquals(1, model.getReceiverBCC().size());
        assertTrue(model.getReceiverBCC().contains("bcc@example.com"));
    }

    @Test
    void addBCC_commaSeparated_addsAll() {
        model.addBCC("a@test.com,b@test.com");
        assertEquals(2, model.getReceiverBCC().size());
        assertTrue(model.getReceiverBCC().contains("a@test.com"));
        assertTrue(model.getReceiverBCC().contains("b@test.com"));
    }

    @Test
    void addBCC_null_doesNothing() {
        model.addBCC(null);
        assertTrue(model.getReceiverBCC().isEmpty());
    }

    @Test
    void addBCC_emptyString_doesNothing() {
        model.addBCC("");
        assertTrue(model.getReceiverBCC().isEmpty());
    }

    // -------------------------------------------------------------------------
    // addAttachment
    // -------------------------------------------------------------------------

    @Test
    void addAttachment_addsToList() {
        byte[] content = {1, 2, 3};
        model.addAttachment("file.pdf", content);

        assertEquals(1, model.getAttachments().size());
        PlaintextEmailAttachment att = model.getAttachments().get(0);
        assertEquals("file.pdf", att.getName());
        assertArrayEquals(content, att.getAttachement());
    }

    @Test
    void addAttachment_multipleAttachments() {
        model.addAttachment("file1.pdf", new byte[]{1});
        model.addAttachment("file2.pdf", new byte[]{2});
        model.addAttachment("file3.pdf", new byte[]{3});

        assertEquals(3, model.getAttachments().size());
    }

    // -------------------------------------------------------------------------
    // addReceiver (deprecated)
    // -------------------------------------------------------------------------

    @Test
    void addReceiver_addsToReceiverSet() {
        model.addReceiver("deprecated@example.com");
        assertTrue(model.getReceiver().contains("deprecated@example.com"));
    }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    @Test
    void setSubjectAndBody() {
        model.setSubject("Test Subject");
        model.setBody("Test Body");
        assertEquals("Test Subject", model.getSubject());
        assertEquals("Test Body", model.getBody());
    }

    @Test
    void setHtml() {
        model.setHtml(true);
        assertTrue(model.getHtml());
    }

    @Test
    void setSender() {
        model.setSender("sender@example.com");
        assertEquals("sender@example.com", model.getSender());
    }

    @Test
    void setMandat() {
        model.setMandat("customMandat");
        assertEquals("customMandat", model.getMandat());
    }
}
