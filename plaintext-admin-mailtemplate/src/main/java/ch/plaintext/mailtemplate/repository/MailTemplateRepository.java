/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.repository;

import ch.plaintext.mailtemplate.entity.MailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailTemplateRepository extends JpaRepository<MailTemplate, Long> {

    Optional<MailTemplate> findByMandatAndTemplateKey(String mandat, String templateKey);

    List<MailTemplate> findByMandatOrderByTemplateKeyAsc(String mandat);
}
