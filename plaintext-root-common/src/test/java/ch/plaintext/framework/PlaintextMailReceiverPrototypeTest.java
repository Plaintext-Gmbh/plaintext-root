/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import com.sun.mail.util.BASE64DecoderStream;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaintextMailReceiverPrototypeTest {

    private PlaintextMailReceiverPrototype receiver;

    @BeforeEach
    void setUp() {
        receiver = new PlaintextMailReceiverPrototype();
    }

    // -------------------------------------------------------------------------
    // Default state
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Default state")
    class DefaultState {

        @Test
        void connectionUp_returnsFalseByDefault() {
            assertFalse(receiver.connectionUp());
        }

        @Test
        void seenmails_isZeroByDefault() {
            assertEquals(0, receiver.getSeenmails());
        }

        @Test
        void inbox_isNullByDefault() {
            assertNull(receiver.getInbox());
        }
    }

    // -------------------------------------------------------------------------
    // checkMail - connection failure (no inbox set, triggers reconnect which fails)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkMail - connection failure path")
    class ConnectionFailure {

        @Test
        void checkMail_withNullInbox_setsConnectionUpFalseAndReturnsEmptyList() {
            // inbox is null, so it tries to reconnect via Session/Store which will fail
            // because host/user/password are null
            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertTrue(result.isEmpty());
            assertFalse(receiver.connectionUp());
        }

        @Test
        void checkMail_withClosedInbox_setsConnectionUpFalse() throws Exception {
            Folder mockFolder = mock(Folder.class);
            when(mockFolder.isOpen()).thenReturn(false);

            ReflectionTestUtils.setField(receiver, "inbox", mockFolder);
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);

            // inbox is not open -> connectionUp becomes false -> tries reconnect -> fails
            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertTrue(result.isEmpty());
            assertFalse(receiver.connectionUp());
        }
    }

    // -------------------------------------------------------------------------
    // checkMail - with pre-configured open inbox (happy path)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkMail - with mocked open inbox")
    class WithMockedInbox {

        private Folder mockInbox;

        @BeforeEach
        void setUpInbox() throws Exception {
            mockInbox = mock(Folder.class);
            when(mockInbox.isOpen()).thenReturn(true);

            ReflectionTestUtils.setField(receiver, "inbox", mockInbox);
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);
        }

        @Test
        void checkMail_noMessages_returnsEmptyList() throws Exception {
            when(mockInbox.getMessages()).thenReturn(new Message[0]);

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertTrue(result.isEmpty());
            assertTrue(receiver.connectionUp());
            assertEquals(0, receiver.getSeenmails());
        }

        @Test
        void checkMail_singleMessage_parsesCorrectly() throws Exception {
            Message message = createMockMessage(
                    "sender@example.com",
                    new String[]{"recipient@example.com"},
                    "Test Subject",
                    "Hello World",
                    false
            );

            when(mockInbox.getMessages()).thenReturn(new Message[]{message});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(1, result.size());
            PlaintextMailModel mail = result.get(0);
            assertEquals("sender@example.com", mail.getSender());
            assertTrue(mail.getReceiver().contains("recipient@example.com"));
            assertEquals("Test Subject", mail.getSubject());
            assertEquals("Hello World", mail.getBody());
            assertEquals(1, receiver.getSeenmails());
            assertTrue(receiver.connectionUp());
        }

        @Test
        void checkMail_multipleRecipients_allParsed() throws Exception {
            Message message = createMockMessage(
                    "sender@test.com",
                    new String[]{"a@test.com", "b@test.com", "c@test.com"},
                    "Multi",
                    "Body",
                    false
            );

            when(mockInbox.getMessages()).thenReturn(new Message[]{message});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(1, result.size());
            assertEquals(3, result.get(0).getReceiver().size());
            assertTrue(result.get(0).getReceiver().contains("a@test.com"));
            assertTrue(result.get(0).getReceiver().contains("b@test.com"));
            assertTrue(result.get(0).getReceiver().contains("c@test.com"));
        }

        @Test
        void checkMail_multipleMessages_incrementsSeenmails() throws Exception {
            Message msg1 = createMockMessage("a@test.com", new String[]{"b@test.com"}, "S1", "B1", false);
            Message msg2 = createMockMessage("c@test.com", new String[]{"d@test.com"}, "S2", "B2", false);
            Message msg3 = createMockMessage("e@test.com", new String[]{"f@test.com"}, "S3", "B3", false);

            when(mockInbox.getMessages()).thenReturn(new Message[]{msg1, msg2, msg3});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(3, result.size());
            assertEquals(3, receiver.getSeenmails());
        }

        @Test
        void checkMail_seenmailsAccumulatesAcrossCalls() throws Exception {
            Message msg = createMockMessage("a@b.com", new String[]{"c@d.com"}, "S", "B", false);

            when(mockInbox.getMessages()).thenReturn(new Message[]{msg});

            receiver.checkMail(false);
            receiver.checkMail(false);

            assertEquals(2, receiver.getSeenmails());
        }
    }

    // -------------------------------------------------------------------------
    // checkMail - SEEN flag filtering (onlyNotSeen=true)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkMail - SEEN flag filtering")
    class SeenFlagFiltering {

        private Folder mockInbox;

        @BeforeEach
        void setUpInbox() throws Exception {
            mockInbox = mock(Folder.class);
            when(mockInbox.isOpen()).thenReturn(true);

            ReflectionTestUtils.setField(receiver, "inbox", mockInbox);
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);
        }

        @Test
        void checkMail_onlyNotSeen_skipsSeenMessages() throws Exception {
            // Use lightweight mock for seen message (no content stubs needed - it gets skipped)
            Message seenMsg = createSeenOnlyMockMessage();
            Message unseenMsg = createMockMessage("e@f.com", new String[]{"g@h.com"}, "Unseen", "Body2", false);

            when(mockInbox.getMessages()).thenReturn(new Message[]{seenMsg, unseenMsg});

            List<PlaintextMailModel> result = receiver.checkMail(true);

            assertEquals(1, result.size());
            assertEquals("Unseen", result.get(0).getSubject());
            assertEquals("e@f.com", result.get(0).getSender());
            // seenmails counter still incremented for both
            assertEquals(2, receiver.getSeenmails());
        }

        @Test
        void checkMail_onlyNotSeen_setsSeenFlagOnProcessedMessages() throws Exception {
            Message unseenMsg = createMockMessage("a@b.com", new String[]{"c@d.com"}, "New", "Body", false);

            when(mockInbox.getMessages()).thenReturn(new Message[]{unseenMsg});

            receiver.checkMail(true);

            verify(unseenMsg).setFlag(Flags.Flag.SEEN, true);
        }

        @Test
        void checkMail_onlyNotSeen_doesNotSetSeenFlagOnSkippedMessages() throws Exception {
            // Use lightweight mock - seen message gets skipped so no content stubs needed
            Message seenMsg = createSeenOnlyMockMessage();

            when(mockInbox.getMessages()).thenReturn(new Message[]{seenMsg});

            List<PlaintextMailModel> result = receiver.checkMail(true);

            assertTrue(result.isEmpty());
            verify(seenMsg, never()).setFlag(any(Flags.Flag.class), anyBoolean());
        }

        @Test
        void checkMail_notOnlyNotSeen_includesSeenMessages() throws Exception {
            Message seenMsg = createMockMessage("a@b.com", new String[]{"c@d.com"}, "Seen", "Body", true);

            when(mockInbox.getMessages()).thenReturn(new Message[]{seenMsg});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(1, result.size());
            assertEquals("Seen", result.get(0).getSubject());
        }

        @Test
        void checkMail_notOnlyNotSeen_doesNotSetSeenFlag() throws Exception {
            Message unseenMsg = createMockMessage("a@b.com", new String[]{"c@d.com"}, "New", "Body", false);

            when(mockInbox.getMessages()).thenReturn(new Message[]{unseenMsg});

            receiver.checkMail(false);

            verify(unseenMsg, never()).setFlag(any(Flags.Flag.class), anyBoolean());
        }
    }

    // -------------------------------------------------------------------------
    // checkMail - multipart with nested MimeMultipart (attachments)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkMail - attachment handling")
    class AttachmentHandling {

        private Folder mockInbox;

        @BeforeEach
        void setUpInbox() throws Exception {
            mockInbox = mock(Folder.class);
            when(mockInbox.isOpen()).thenReturn(true);

            ReflectionTestUtils.setField(receiver, "inbox", mockInbox);
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);
        }

        @Test
        void checkMail_messageWithNestedMimeMultipartAttachment() throws Exception {
            byte[] attachmentBytes = {1, 2, 3, 4, 5};

            // Inner MimeMultipart body part with a named attachment
            BodyPart innerBodyPart = mock(BodyPart.class);
            when(innerBodyPart.getContentType()).thenReturn("application/pdf; name=\"report.pdf\"; charset=utf-8");
            when(innerBodyPart.getInputStream()).thenReturn(new ByteArrayInputStream(attachmentBytes));

            // MimeMultipart whose toString contains "javax.mail.internet.MimeMultipart"
            MimeMultipart innerMultipart = mock(MimeMultipart.class);
            when(innerMultipart.toString()).thenReturn("javax.mail.internet.MimeMultipart@123");
            when(innerMultipart.getCount()).thenReturn(1);
            when(innerMultipart.getBodyPart(0)).thenReturn(innerBodyPart);

            // Outer body part whose content is the MimeMultipart
            BodyPart outerBodyPart = mock(BodyPart.class);
            when(outerBodyPart.getContent()).thenReturn(innerMultipart);

            // Text body part
            BodyPart textBodyPart = mock(BodyPart.class);
            when(textBodyPart.getContent()).thenReturn("Plain text body");

            Multipart multipart = mock(Multipart.class);
            when(multipart.getCount()).thenReturn(2);
            when(multipart.getBodyPart(0)).thenReturn(textBodyPart);
            when(multipart.getBodyPart(1)).thenReturn(outerBodyPart);

            Message message = createMockMessageWithMultipart(
                    "sender@test.com",
                    new String[]{"rcpt@test.com"},
                    "With Attachment",
                    multipart,
                    false
            );

            when(mockInbox.getMessages()).thenReturn(new Message[]{message});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(1, result.size());
            PlaintextMailModel mail = result.get(0);
            assertEquals("Plain text body", mail.getBody());
            assertEquals(1, mail.getAttachments().size());
            assertEquals("report.pdf", mail.getAttachments().get(0).getName());
            assertArrayEquals(attachmentBytes, mail.getAttachments().get(0).getAttachement());
        }

        @Test
        void checkMail_messageWithBase64DecoderStreamAttachment() throws Exception {
            byte[] icsBytes = "BEGIN:VCALENDAR".getBytes();

            // Create a mock whose toString contains the expected class name
            BASE64DecoderStream base64Stream = mock(BASE64DecoderStream.class);
            when(base64Stream.toString()).thenReturn("com.sun.mail.util.BASE64DecoderStream@abc");
            when(base64Stream.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
                byte[] buf = invocation.getArgument(0);
                int off = invocation.getArgument(1);
                int len = invocation.getArgument(2);
                int bytesToCopy = Math.min(len, icsBytes.length);
                System.arraycopy(icsBytes, 0, buf, off, bytesToCopy);
                return bytesToCopy;
            }).thenReturn(-1);

            BodyPart base64BodyPart = mock(BodyPart.class);
            when(base64BodyPart.getContent()).thenReturn(base64Stream);

            // Text body part
            BodyPart textBodyPart = mock(BodyPart.class);
            when(textBodyPart.getContent()).thenReturn("Calendar invite");

            Multipart multipart = mock(Multipart.class);
            when(multipart.getCount()).thenReturn(2);
            when(multipart.getBodyPart(0)).thenReturn(textBodyPart);
            when(multipart.getBodyPart(1)).thenReturn(base64BodyPart);

            Message message = createMockMessageWithMultipart(
                    "cal@test.com",
                    new String[]{"user@test.com"},
                    "Meeting Invite",
                    multipart,
                    false
            );

            when(mockInbox.getMessages()).thenReturn(new Message[]{message});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(1, result.size());
            PlaintextMailModel mail = result.get(0);
            assertEquals("Calendar invite", mail.getBody());
            assertEquals(1, mail.getAttachments().size());
            assertEquals("appointment.ics", mail.getAttachments().get(0).getName());
        }

        @Test
        void checkMail_nestedMimeMultipart_bodyPartWithoutName_skipped() throws Exception {
            // Inner body part without "name" in content type -> should be skipped
            BodyPart innerBodyPart = mock(BodyPart.class);
            when(innerBodyPart.getContentType()).thenReturn("text/plain; charset=utf-8");

            MimeMultipart innerMultipart = mock(MimeMultipart.class);
            when(innerMultipart.toString()).thenReturn("javax.mail.internet.MimeMultipart@456");
            when(innerMultipart.getCount()).thenReturn(1);
            when(innerMultipart.getBodyPart(0)).thenReturn(innerBodyPart);

            BodyPart outerBodyPart = mock(BodyPart.class);
            when(outerBodyPart.getContent()).thenReturn(innerMultipart);

            Multipart multipart = mock(Multipart.class);
            when(multipart.getCount()).thenReturn(1);
            when(multipart.getBodyPart(0)).thenReturn(outerBodyPart);

            Message message = createMockMessageWithMultipart(
                    "sender@test.com",
                    new String[]{"rcpt@test.com"},
                    "No Attachment",
                    multipart,
                    false
            );

            when(mockInbox.getMessages()).thenReturn(new Message[]{message});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertEquals(1, result.size());
            // No body set (only nested multipart without text body part), no attachments
            assertTrue(result.get(0).getAttachments().isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // checkMail - exception during message processing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkMail - exception handling")
    class ExceptionHandling {

        private Folder mockInbox;

        @BeforeEach
        void setUpInbox() throws Exception {
            mockInbox = mock(Folder.class);
            when(mockInbox.isOpen()).thenReturn(true);

            ReflectionTestUtils.setField(receiver, "inbox", mockInbox);
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);
        }

        @Test
        void checkMail_exceptionDuringGetMessages_setsConnectionUpFalse() throws Exception {
            when(mockInbox.getMessages()).thenThrow(new MessagingException("Connection lost"));

            List<PlaintextMailModel> result = receiver.checkMail(false);

            assertTrue(result.isEmpty());
            assertFalse(receiver.connectionUp());
        }

        @Test
        void checkMail_exceptionDuringMessageParsing_setsConnectionUpFalse() throws Exception {
            Message badMessage = mock(Message.class);
            Flags flags = new Flags();
            when(badMessage.getFlags()).thenReturn(flags);
            when(badMessage.getFrom()).thenThrow(new MessagingException("Cannot read from"));

            when(mockInbox.getMessages()).thenReturn(new Message[]{badMessage});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            // Exception caught, connectionUp set to false
            assertFalse(receiver.connectionUp());
            // seenmails was incremented before the exception
            assertEquals(1, receiver.getSeenmails());
        }

        @Test
        void checkMail_exceptionAfterSomeMessagesProcessed_returnsPartialResults() throws Exception {
            // First message processes fine
            Message goodMsg = createMockMessage("a@b.com", new String[]{"c@d.com"}, "Good", "Body", false);

            // Second message throws exception
            Message badMsg = mock(Message.class);
            Flags flags = new Flags();
            when(badMsg.getFlags()).thenReturn(flags);
            when(badMsg.getFrom()).thenThrow(new MessagingException("Error"));

            when(mockInbox.getMessages()).thenReturn(new Message[]{goodMsg, badMsg});

            List<PlaintextMailModel> result = receiver.checkMail(false);

            // First message was added before exception on second
            assertEquals(1, result.size());
            assertEquals("Good", result.get(0).getSubject());
            assertFalse(receiver.connectionUp());
            assertEquals(2, receiver.getSeenmails());
        }
    }

    // -------------------------------------------------------------------------
    // connectionUp() method
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("connectionUp")
    class ConnectionUpTests {

        @Test
        void connectionUp_afterSuccessfulCheckMail_returnsTrue() throws Exception {
            Folder mockInbox = mock(Folder.class);
            when(mockInbox.isOpen()).thenReturn(true);
            when(mockInbox.getMessages()).thenReturn(new Message[0]);

            ReflectionTestUtils.setField(receiver, "inbox", mockInbox);
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);

            receiver.checkMail(false);

            assertTrue(receiver.connectionUp());
        }

        @Test
        void connectionUp_afterFailedCheckMail_returnsFalse() {
            // No inbox set, will fail
            receiver.checkMail(false);

            assertFalse(receiver.connectionUp());
        }

        @Test
        void connectionUp_reflectsFieldValue() {
            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.TRUE);
            assertTrue(receiver.connectionUp());

            ReflectionTestUtils.setField(receiver, "connectionUp", Boolean.FALSE);
            assertFalse(receiver.connectionUp());
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Creates a lightweight mock Message that is marked as SEEN.
     * Only stubs getFlags() - no content stubs needed since seen messages
     * are skipped when onlyNotSeen=true.
     */
    private Message createSeenOnlyMockMessage() throws Exception {
        Message message = mock(Message.class);
        Flags flags = new Flags();
        flags.add(Flags.Flag.SEEN);
        when(message.getFlags()).thenReturn(flags);
        return message;
    }

    /**
     * Creates a mock Message with a simple text body wrapped in a Multipart.
     */
    private Message createMockMessage(String from, String[] recipients, String subject,
                                      String body, boolean seen) throws Exception {
        Message message = mock(Message.class);

        // Flags
        Flags flags = new Flags();
        if (seen) {
            flags.add(Flags.Flag.SEEN);
        }
        when(message.getFlags()).thenReturn(flags);

        // From
        Address fromAddress = mock(Address.class);
        when(fromAddress.toString()).thenReturn(from);
        when(message.getFrom()).thenReturn(new Address[]{fromAddress});

        // Recipients
        Address[] recipientAddresses = new Address[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            recipientAddresses[i] = mock(Address.class);
            when(recipientAddresses[i].toString()).thenReturn(recipients[i]);
        }
        when(message.getAllRecipients()).thenReturn(recipientAddresses);

        // Subject
        when(message.getSubject()).thenReturn(subject);

        // Multipart with single text body part
        BodyPart bodyPart = mock(BodyPart.class);
        when(bodyPart.getContent()).thenReturn(body);

        Multipart multipart = mock(Multipart.class);
        when(multipart.getCount()).thenReturn(1);
        when(multipart.getBodyPart(0)).thenReturn(bodyPart);

        when(message.getContent()).thenReturn(multipart);

        return message;
    }

    /**
     * Creates a mock Message with a custom Multipart (for attachment testing).
     */
    private Message createMockMessageWithMultipart(String from, String[] recipients, String subject,
                                                   Multipart multipart, boolean seen) throws Exception {
        Message message = mock(Message.class);

        // Flags
        Flags flags = new Flags();
        if (seen) {
            flags.add(Flags.Flag.SEEN);
        }
        when(message.getFlags()).thenReturn(flags);

        // From
        Address fromAddress = mock(Address.class);
        when(fromAddress.toString()).thenReturn(from);
        when(message.getFrom()).thenReturn(new Address[]{fromAddress});

        // Recipients
        Address[] recipientAddresses = new Address[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            recipientAddresses[i] = mock(Address.class);
            when(recipientAddresses[i].toString()).thenReturn(recipients[i]);
        }
        when(message.getAllRecipients()).thenReturn(recipientAddresses);

        // Subject
        when(message.getSubject()).thenReturn(subject);

        // Content
        when(message.getContent()).thenReturn(multipart);

        return message;
    }
}
