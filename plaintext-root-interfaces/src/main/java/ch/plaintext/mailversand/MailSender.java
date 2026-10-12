/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailversand;

import ch.plaintext.modules.ModulApi;

import java.util.List;

/**
 * Central, cross-module interface for <b>sending</b> emails via a mailbox account (SMTP). All methods
 * are <b>explicit about tenant and user</b> (no {@code SecurityContext} needed) and therefore
 * <b>suitable for cron</b>: series, notice and system crons run headless. The implementation lives in
 * the mailbox module. Other z-modules use only this interface. It has completely replaced the older, SecurityContext-based
 * {@code MailInvitationSender} (which only checked the tenant, not the account scope).
 *
 * <p><b>Scope enforcement:</b> every access to an account ({@code accountId}) is checked against the
 * account's {@code FreigabeScope}: a {@code MANDAT} account may be used by anyone
 * in the same tenant, a {@code PERSOENLICH} account only by its creator, a {@code GLOBAL} account
 * (system mail) not at all via this generic path. In addition the account must be active and not
 * deleted. This way no module can send via a guessed {@code accountId} on other people's accounts.</p>
 *
 * <p><b>Contract of the shell (card 1504, decision Daniel in card 1502).</b> It used to live in
 * {@code plaintext-app-interfaces} as {@code ch.plaintext.mail.MailSender}, which tied contacts,
 * messenger, correspondence and mailbox into a cycle. Like {@link ch.plaintext.SystemMailSender} the
 * shell now owns the contract and {@code plaintext-z-mailbox} implements it, so a consumer never needs
 * the mailbox jar. New package instead of the old name: during the switch app still carries the old
 * type, and two classes with one fully qualified name in two jars would let the classpath order decide.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE)
public interface MailSender {

    /**
     * List of the active mailbox accounts that {@code user} may use in {@code mandant} (filtered by
     * scope): {@code MANDAT} accounts of the tenant and own {@code PERSOENLICH} accounts. For selection
     * drop-downs in other z-modules.
     *
     * @param mandant tenant of the caller
     * @param user    user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     *                (then only {@code MANDAT} accounts)
     * @return the usable accounts (id + display name + address), or an empty list
     */
    List<MailAccountRef> listAccounts(String mandant, String user);

    /**
     * Sender address (From) of an account, checked for scope and tenant.
     *
     * @param accountId id of the mailbox account
     * @param mandant   tenant of the caller
     * @param user      user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     * @return the email address of the account, or {@code null} if not present / no access
     */
    String accountEmailAddress(Long accountId, String mandant, String user);

    /**
     * Sends a simple text mail (without attachment) via the given account.
     *
     * @param accountId id of the mailbox account (checked for scope and tenant)
     * @param mandant   tenant of the caller
     * @param user      user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     * @param to        recipient address
     * @param subject   subject
     * @param body      text body
     * @throws IllegalArgumentException if the account does not exist or the caller has no access
     */
    void sendPlain(Long accountId, String mandant, String user, String to, String subject, String body);

    /**
     * Sends a text mail <b>with file attachments</b> via the given account, explicit about the tenant
     * and therefore also usable from contexts <b>without</b> a {@code SecurityContext} (crons, public
     * {@code /nosec} flows). This is exactly how it differs from {@code MailboxService.send(...)}: there
     * the account is resolved via the caller's {@code SecurityContext}, which fails headless.
     *
     * <p>Without attachments ({@code null} or empty list) it behaves like
     * {@link #sendPlain(Long, String, String, String, String, String)}.</p>
     *
     * @param accountId   id of the mailbox account (checked for scope and tenant)
     * @param mandant     tenant of the caller
     * @param user        user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     * @param to          recipient address
     * @param subject     subject
     * @param body        text body
     * @param attachments the files to attach; may be {@code null} or empty
     * @throws IllegalArgumentException if the account does not exist or the caller has no access
     */
    void sendWithAttachments(Long accountId, String mandant, String user, String to, String subject, String body,
                             List<MailAttachment> attachments);

    /**
     * Like {@link #sendWithAttachments}, but with additional header lines.
     *
     * <p><b>What for (Card 1071, request by Daniel 05.09.2026):</b> "unsubscribing should also work
     * […] according to the RFC for mailing lists". For this a mailing list sends
     * {@code List-Unsubscribe} and {@code List-Unsubscribe-Post} along. Gmail, Outlook and others then
     * offer an unsubscribe button right next to the sender (RFC 2369 and RFC 8058), instead of someone
     * having to look for the link in the footer.</p>
     *
     * <p>The default ignores the header lines and calls {@link #sendWithAttachments}. An
     * implementation without a real SMTP path does not have to do anything, and in the worst case a
     * caller gets a mail without the button instead of none at all.</p>
     *
     * @param header additional header lines; {@code null} or empty behaves like the call without
     */
    default void sendWithHeaders(Long accountId, String mandant, String user, String to, String subject,
                                 String body, List<MailAttachment> attachments,
                                 java.util.Map<String, String> header) {
        sendWithAttachments(accountId, mandant, user, to, subject, body, attachments);
    }

    /**
     * Sends a calendar invitation ({@code .ics} with {@code METHOD:REQUEST}) via the given account, so
     * that common mail clients recognise the appointment as an invitation and offer "Accept".
     *
     * @param accountId  id of the mailbox account (checked for scope and tenant)
     * @param mandant    tenant of the caller
     * @param user       user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     * @param to         recipient address
     * @param subject    subject
     * @param body       HTML body of the accompanying mail
     * @param icsRequest the finished VCALENDAR bytes with {@code METHOD:REQUEST}
     * @throws IllegalArgumentException if the account does not exist or the caller has no access
     */
    void sendCalendarRequest(Long accountId, String mandant, String user, String to, String subject, String body,
                             byte[] icsRequest);

    /**
     * Like {@link #sendCalendarRequest}, but with a <b>plain text</b> accompanying body (e.g. single
     * appointment invitations from the calendar hosting, whose accompanying text is not HTML).
     *
     * @param accountId  id of the mailbox account (checked for scope and tenant)
     * @param mandant    tenant of the caller
     * @param user       user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     * @param to         recipient address
     * @param subject    subject
     * @param body       text body of the accompanying mail
     * @param icsRequest the finished VCALENDAR bytes with {@code METHOD:REQUEST}
     * @throws IllegalArgumentException if the account does not exist or the caller has no access
     */
    void sendCalendarRequestPlain(Long accountId, String mandant, String user, String to, String subject, String body,
                                  byte[] icsRequest);

    /**
     * Sends a calendar <b>cancellation</b> ({@code .ics} with {@code METHOD:CANCEL}) via the given account,
     * so that the appointment is cancelled automatically for the recipients (e.g. when cancelling an
     * instance of a series).
     *
     * @param accountId id of the mailbox account (checked for scope and tenant)
     * @param mandant   tenant of the caller
     * @param user      user of the caller (for {@code PERSOENLICH} accounts); may be {@code null}
     * @param to        recipient address
     * @param subject   subject
     * @param body      HTML body of the accompanying mail (the series cancellations send HTML)
     * @param icsCancel the finished VCALENDAR bytes with {@code METHOD:CANCEL}
     * @throws IllegalArgumentException if the account does not exist or the caller has no access
     */
    void sendCalendarCancel(Long accountId, String mandant, String user, String to, String subject, String body,
                            byte[] icsCancel);

    // ── Entwuerfe (Karte 551, 30.08.2026) ────────────────────────────────────────────────────
    // Damit ein Fachmodul (rechnungen) eine Mail in die „Vorbereitet"-Box legen kann, ohne vom
    // Mailbox-Modul abzuhaengen: KEIN Versand — der Benutzer prueft und sendet in der Mailbox.

    /**
     * Creates a mail draft ("Vorbereitet" box) for the account. Sends NOTHING.
     *
     * <p>Card 551: lets a business module (rechnungen) put a mail into the "Vorbereitet" box without
     * depending on the mailbox module. The user checks and sends it in the mailbox.
     *
     * @return id of the draft
     * @throws UnsupportedOperationException if the implementation does not know drafts
     */
    default Long prepareDraft(Long accountId, String mandant, String user, String to, String cc, String subject,
                              String body, List<MailAttachment> attachments) {
        throw new UnsupportedOperationException("Mail-Entwuerfe werden von dieser Implementierung nicht unterstuetzt");
    }

    /** {@code true} as long as the draft is still in the "Vorbereitet" box (neither sent nor discarded). */
    default boolean draftExists(Long draftId, String mandant) {
        return false;
    }

    /**
     * Default sender account of the tenant for automatically prepared mails: the active account shared
     * tenant-wide ({@code MANDAT}) with the smallest id; empty if none is configured.
     */
    default java.util.Optional<Long> defaultMandatAccountId(String mandant, String user) {
        return java.util.Optional.empty();
    }
}
