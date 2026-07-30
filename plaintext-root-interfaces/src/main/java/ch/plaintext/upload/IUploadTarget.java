/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.upload;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Interface for services that accept file uploads via the generic /nosec/root/upload endpoint.
 * Implementations are auto-discovered by Spring and listed in the upload services API.
 */
public interface IUploadTarget {

    /**
     * Gets the unique service name identifying this upload target.
     *
     * @return the service name
     */
    String getServiceName();

    /**
     * Gets a human-readable description of what this upload target does.
     *
     * @return the description
     */
    String getDescription();

    /**
     * Gets the list of accepted file types (MIME types or extensions).
     *
     * @return list of accepted file types
     */
    List<String> getAcceptedFileTypes();

    /**
     * Handles a file upload for the given mandate.
     *
     * @param file   the uploaded file
     * @param mandat the mandate/tenant identifier
     * @param params additional parameters passed with the upload
     * @return the upload result indicating success or failure
     */
    UploadResult handleUpload(MultipartFile file, String mandat, Map<String, String> params);

    /**
     * Represents the result of a file upload operation.
     *
     * @param success whether the upload was successful
     * @param message a human-readable result message
     * @param details additional details about the upload result
     */
    record UploadResult(boolean success, String message, Map<String, Object> details) {
        /**
         * Creates a successful upload result with no additional details.
         *
         * @param message the success message
         * @return a successful UploadResult
         */
        public static UploadResult ok(String message) {
            return new UploadResult(true, message, Map.of());
        }

        /**
         * Creates a successful upload result with additional details.
         *
         * @param message the success message
         * @param details additional details about the result
         * @return a successful UploadResult
         */
        public static UploadResult ok(String message, Map<String, Object> details) {
            return new UploadResult(true, message, details);
        }

        /**
         * Creates a failed upload result.
         *
         * @param message the error message
         * @return a failed UploadResult
         */
        public static UploadResult error(String message) {
            return new UploadResult(false, message, Map.of());
        }
    }
}
