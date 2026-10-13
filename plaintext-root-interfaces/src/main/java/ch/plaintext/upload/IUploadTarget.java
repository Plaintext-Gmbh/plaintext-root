/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.upload;

import ch.plaintext.modules.ModulApi;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Contract for services that accept file uploads via the generic endpoint
 * {@code /nosec/root/upload}. Implementations are collected by Spring
 * ({@code List<IUploadTarget>}) and listed in the endpoint's service list.
 *
 * <h2>Why this contract lives in root</h2>
 * <p>It is a docking point of the shell (Card 1525): implementations live in modules
 * ({@code PostkontoUploadTarget}, {@code RunningUploadTarget}), the collecting endpoint
 * ({@code RootUploadController}) moves with the application base into the shell. Card 1204 had moved
 * it to app because no root module knew it; with modules loaded at runtime (Card 1503) the contract
 * has to come from the shell. The package stays {@code ch.plaintext.upload}; during the transition
 * the identical type exists in app-interfaces too, app deletes its copy with the bump.</p>
 */
@ModulApi(art = ModulApi.Art.ERWEITERUNG)
public interface IUploadTarget {

    /**
     * Unique service name through which this upload target is addressed.
     *
     * @return the service name
     */
    String getServiceName();

    /**
     * Readable description of what this upload target does.
     *
     * @return the description
     */
    String getDescription();

    /**
     * The accepted file types (MIME types or extensions).
     *
     * @return list of accepted file types
     */
    List<String> getAcceptedFileTypes();

    /**
     * Processes a file upload for the given tenant.
     *
     * @param file   the uploaded file
     * @param mandat tenant for which the upload is made
     * @param params additional parameters of the upload
     * @return the result of the upload
     */
    UploadResult handleUpload(MultipartFile file, String mandat, Map<String, String> params);

    /**
     * Ergebnis eines Upload-Vorgangs.
     *
     * @param success ob der Upload erfolgreich war
     * @param message lesbare Ergebnismeldung
     * @param details zusaetzliche Angaben zum Ergebnis
     */
    record UploadResult(boolean success, String message, Map<String, Object> details) {
        /**
         * Erfolgreiches Ergebnis ohne weitere Angaben.
         *
         * @param message die Erfolgsmeldung
         * @return ein erfolgreiches UploadResult
         */
        public static UploadResult ok(String message) {
            return new UploadResult(true, message, Map.of());
        }

        /**
         * Erfolgreiches Ergebnis mit weiteren Angaben.
         *
         * @param message die Erfolgsmeldung
         * @param details zusaetzliche Angaben zum Ergebnis
         * @return ein erfolgreiches UploadResult
         */
        public static UploadResult ok(String message, Map<String, Object> details) {
            return new UploadResult(true, message, details);
        }

        /**
         * Fehlgeschlagenes Ergebnis.
         *
         * @param message die Fehlermeldung
         * @return ein fehlgeschlagenes UploadResult
         */
        public static UploadResult error(String message) {
            return new UploadResult(false, message, Map.of());
        }
    }
}
