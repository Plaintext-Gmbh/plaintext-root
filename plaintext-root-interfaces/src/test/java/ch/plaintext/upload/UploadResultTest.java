/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.upload;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UploadResultTest {

    @Test
    void ok_withMessage_createsSuccessResult() {
        IUploadTarget.UploadResult result = IUploadTarget.UploadResult.ok("Upload successful");

        assertTrue(result.success());
        assertEquals("Upload successful", result.message());
        assertNotNull(result.details());
        assertTrue(result.details().isEmpty());
    }

    @Test
    void ok_withMessageAndDetails_createsSuccessResult() {
        Map<String, Object> details = Map.of("count", 5, "file", "test.csv");
        IUploadTarget.UploadResult result = IUploadTarget.UploadResult.ok("Imported", details);

        assertTrue(result.success());
        assertEquals("Imported", result.message());
        assertEquals(5, result.details().get("count"));
        assertEquals("test.csv", result.details().get("file"));
    }

    @Test
    void error_createsFailureResult() {
        IUploadTarget.UploadResult result = IUploadTarget.UploadResult.error("File too large");

        assertFalse(result.success());
        assertEquals("File too large", result.message());
        assertNotNull(result.details());
        assertTrue(result.details().isEmpty());
    }

    @Test
    void equalsAndHashCode_sameValues() {
        IUploadTarget.UploadResult r1 = IUploadTarget.UploadResult.ok("test");
        IUploadTarget.UploadResult r2 = IUploadTarget.UploadResult.ok("test");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void notEquals_differentSuccess() {
        IUploadTarget.UploadResult r1 = IUploadTarget.UploadResult.ok("msg");
        IUploadTarget.UploadResult r2 = IUploadTarget.UploadResult.error("msg");

        assertNotEquals(r1, r2);
    }
}
