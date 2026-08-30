/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.service;

import ch.plaintext.mailtemplate.entity.MailTemplate;
import ch.plaintext.mailtemplate.repository.MailTemplateRepository;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin-editable mail texts: DB override (tenant-scoped) with a fallback to the code default
 * passed in by the caller. Cache analogous to {@code I18nService} (loaded completely at startup,
 * updated selectively on save/delete instead of being reloaded in full).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailTemplateService implements IMailTemplateProvider {

    private final MailTemplateRepository repository;

    private final Map<String, MailTemplate> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        loadCache();
    }

    private void loadCache() {
        cache.clear();
        for (MailTemplate t : repository.findAll()) {
            cache.put(cacheKey(t.getMandat(), t.getTemplateKey()), t);
        }
        log.info("MailTemplate: {} Override(s) geladen", cache.size());
    }

    @Override
    public RenderedMail render(String mandat, String templateKey, String defaultBetreff, String defaultBody,
                               Map<String, String> platzhalter) {
        MailTemplate override = cache.get(cacheKey(mandat, templateKey));
        String betreff = override != null ? override.getBetreff() : defaultBetreff;
        String body = override != null ? override.getBody() : defaultBody;
        return new RenderedMail(ersetzePlatzhalter(betreff, platzhalter), ersetzePlatzhalter(body, platzhalter));
    }

    private static String ersetzePlatzhalter(String text, Map<String, String> platzhalter) {
        if (text == null || platzhalter == null || platzhalter.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> e : platzhalter.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    /** Creates an override or updates it. */
    @Transactional
    public MailTemplate save(String mandat, String templateKey, String betreff, String body, boolean html) {
        MailTemplate t = repository.findByMandatAndTemplateKey(mandat, templateKey).orElseGet(MailTemplate::new);
        t.setMandat(mandat);
        t.setTemplateKey(templateKey);
        t.setBetreff(betreff);
        t.setBody(body);
        t.setHtml(html);
        MailTemplate saved = repository.save(t);
        cache.put(cacheKey(mandat, templateKey), saved);
        return saved;
    }

    /** Removes an override — future {@link #render} calls fall back to the code default again. */
    @Transactional
    public void deleteOverride(Long id) {
        repository.findById(id).ifPresent(t -> {
            cache.remove(cacheKey(t.getMandat(), t.getTemplateKey()));
            repository.delete(t);
        });
    }

    public List<MailTemplate> getOverrides(String mandat) {
        return repository.findByMandatOrderByTemplateKeyAsc(mandat);
    }

    public Optional<MailTemplate> getOverride(Long id) {
        return repository.findById(id);
    }

    private static String cacheKey(String mandat, String templateKey) {
        return (mandat == null ? "" : mandat) + "::" + templateKey;
    }
}
