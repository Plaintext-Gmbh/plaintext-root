/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.paperless;

import ch.plaintext.modules.ModulApi;

import java.util.List;
import java.util.Optional;

/**
 * Cross-module access to the document store Paperless-ngx: file a PDF and update status tags on the
 * filed document. The implementation lives in the correspondence module (where the
 * {@code PaperlessClient} including Vault token resolution lives). Consumers are other z-modules that
 * must not depend directly on the correspondence module ({@code ModuleDependencyTest}).
 *
 * <p>Docking point of the shell like {@link ch.plaintext.ablagen.DateiAblage} (Card 1525): the contract
 * lives in root, so mailbox, payouts and flow do not depend on the correspondence module and the
 * cycle mailbox&lt;-&gt;correspondence disappears.</p>
 *
 * <p><b>Fail-safe like the underlying client:</b> if the connection is switched off, no token can be
 * resolved or Paperless is unreachable, all methods return {@link Optional#empty()} or
 * {@code false}. An exception is <b>never</b> passed outside. A failure of the document store must not
 * drag down the caller's main business purpose (e.g. a running workflow).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE)
public interface IPaperlessAblage {

    /**
     * {@code true} if the connection is active and a token can be resolved. Consumers can use it to
     * decide whether to offer the filing step at all.
     *
     * @return {@code true} if usable
     */
    boolean isVerfuegbar();

    /**
     * Files a PDF in Paperless and sets the given tags (tags that do not exist are created).
     *
     * @param pdf         PDF bytes (not empty)
     * @param titel       title of the document (also used as the file name), may be {@code null}
     * @param erstelltIso creation date as ISO date ({@code yyyy-MM-dd}) or ISO datetime, may be {@code null}
     * @param tags        tag names to set, may be {@code null} or empty
     * @return document id, if Paperless resolved it within its time budget
     */
    Optional<Long> ablegen(byte[] pdf, String titel, String erstelltIso, List<String> tags);

    /**
     * Like {@link #ablegen(byte[], String, String, List)}, but puts the REAL file name and MIME type of
     * the file into the upload (Paperless shows the original file name) and can optionally set a
     * Paperless storage path (by numeric id).
     *
     * <p>Built for the payout reconciliation (request by Daniel, 28.08.2026): receipts should land in
     * Paperless under their correct file name, with a target (storage path, tags) per user. The
     * default implementation ignores file name/type/storage path and delegates to
     * {@link #ablegen(byte[], String, String, List)}. Implementations with a real Paperless connection
     * override it. The implementation in the correspondence module then also leaves exactly the given
     * {@code tags} on the document (Card 1335, see {@link #ablegenDateiMitToken}).</p>
     *
     * @param inhalt        file bytes (not empty); PDF or image
     * @param dateiname     original file name including extension, may be {@code null}
     * @param contentType   MIME type of the file, may be {@code null}
     * @param titel         title of the document, may be {@code null}
     * @param erstelltIso   creation date as ISO date/datetime, may be {@code null}
     * @param tags          tag names to set, may be {@code null} or empty
     * @param storagePathId Paperless storage path id, may be {@code null}
     * @return document id, if Paperless resolved it within its time budget
     */
    default Optional<Long> ablegenDatei(byte[] inhalt, String dateiname, String contentType, String titel,
                                        String erstelltIso, List<String> tags, Long storagePathId) {
        return ablegen(inhalt, titel, erstelltIso, tags);
    }

    /**
     * Like {@link #ablegenDatei}, but authenticates with an EXPLICIT Paperless token instead of the
     * globally configured one, for tenant-wide stores whose token an admin has stored in the module
     * (payout reconciliation, Daniel 28.08.2026). The default implementation ignores the token and
     * delegates. Implementations with a real connection override it. With its own token no Vault
     * token is needed, only the connection must be switched on.
     *
     * <p><b>Card 1335:</b> the document then carries EXACTLY the given {@code tags}. Whatever
     * Paperless attaches by itself on consumption (the inbox tag {@code neu}, automatically assigned
     * tags) is removed again by the implementation as soon as the document id is known.</p>
     *
     * @param token Paperless API token; {@code null}/blank = use the global token
     */
    default Optional<Long> ablegenDateiMitToken(byte[] inhalt, String dateiname, String contentType,
                                                String titel, String erstelltIso, List<String> tags,
                                                Long storagePathId, String token) {
        return ablegenDatei(inhalt, dateiname, contentType, titel, erstelltIso, tags, storagePathId);
    }

    /**
     * Updates the tags of an already filed document: the tags from {@code hinzufuegen} are added (and
     * created in Paperless if needed), those from {@code entfernen} are dropped. Existing tags named in
     * neither list stay untouched.
     *
     * @param dokumentId  id of the Paperless document
     * @param hinzufuegen tag names to set; may be {@code null} or empty
     * @param entfernen   tag names to remove; may be {@code null} or empty
     * @return {@code true} if Paperless confirmed the change
     */
    boolean tagsFortschreiben(long dokumentId, List<String> hinzufuegen, List<String> entfernen);

    /**
     * Deletes a document (Card 1045). <b>Permanently</b>, unless a recycle bin is configured in
     * Paperless.
     *
     * <p>The caller is responsible for the id belonging to a document that this application created
     * itself. An implementation that cannot do this returns {@code false}, so existing behaviour stays
     * unchanged.
     *
     * @return {@code true} if it is deleted or no longer existed
     */
    default boolean loeschen(long dokumentId) {
        return false;
    }

    /**
     * Downloads a filed document, the opposite direction of {@link #ablegen}.
     *
     * <p>Built for attachments from the document store (request by Daniel, 28.08.2026): a receipt
     * stored in Paperless should be attachable to an email by reference instead of travelling as
     * Base64 text through tool parameters. 64 KB become ~85'000 characters there and break every
     * parameter limit. The server fetches the bytes itself.</p>
     *
     * <p>Fail-safe like everything here: not available, unknown id or HTTP error yield
     * {@link Optional#empty()}, never an exception.</p>
     *
     * @param dokumentId id of the Paperless document
     * @return the document with bytes, file name and MIME type
     */
    Optional<PaperlessDokument> herunterladen(long dokumentId);

    /**
     * Link to the document in the Paperless user interface (for display/linking in the UI).
     *
     * @param dokumentId id of the Paperless document
     * @return absolute URL
     */
    String dokumentLink(long dokumentId);
}
