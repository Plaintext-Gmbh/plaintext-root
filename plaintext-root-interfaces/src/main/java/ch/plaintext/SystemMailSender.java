/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.List;

/**
 * Sends system/auth mails (password reset, login link / magic link, registration confirmation)
 * through a <b>GLOBAL</b> system mail account of the mailbox (SMTP). The interface lives in
 * {@code plaintext-root-interfaces}; the <b>implementation is supplied by the application</b>
 * ({@code plaintext-z-mailbox}). Root consumes it optionally (e.g.
 * {@code @Autowired(required=false)} / {@link org.springframework.beans.factory.ObjectProvider}),
 * so that root also builds and tests without the application.
 *
 * <p>Supersedes the earlier, {@code configName}-based root mail infrastructure
 * ({@code plaintext-root-email}) for auth mails. Which GLOBAL account is used is chosen in the
 * root configuration (setup) and stored in {@code SetupConfig#systemMailAccountId}.</p>
 */
public interface SystemMailSender {

    /**
     * The available GLOBAL system mail accounts (scope {@code GLOBAL}, ROOT only) – for the
     * selection in the root configuration. Empty list if none has been created (show a hint then).
     */
    List<SystemMailAccount> listGlobalAccounts();

    /**
     * Sends a system mail through the given GLOBAL account.
     *
     * @param accountId id of the GLOBAL system mail account
     * @param to        recipient address
     * @param subject   subject
     * @param body      message text
     * @param html      {@code true} = HTML body, otherwise plain text
     * @return {@code true} if the mail was sent successfully; {@code false} if the account is
     * missing, is not a GLOBAL account, or sending was not possible (never an exception to the
     * outside)
     */
    boolean sendSystemMail(Long accountId, String to, String subject, String body, boolean html);
}
