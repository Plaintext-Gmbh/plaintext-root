/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;/*
  Copyright (C) plaintext.ch, 2017.
 */

import ch.plaintext.framework.SuperModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the {@link PlaintextRepository} beans, keyed by entity name.
 *
 * <p><b>Karte 687:</b> until 12.08.2026 this additionally held {@code getNextID(Object)} — an id
 * assignment via {@code max(id) + 1} that returned <b>{@code -1}</b> for an unknown type. Its
 * only caller was the generator {@code UseExistingIdOtherwiseGenerateUsingIdentity} in
 * {@link SuperModel}, and that one had not run since the move to Hibernate 7 (the reasoning is
 * there). The method was therefore unreachable — and had it not been, it would have done damage:
 * for entity types without a {@code PlaintextRepository} the id {@code -1}, for all others an id
 * handed out twice under concurrency. Two failure mechanisms, one of which concealed the other.
 *
 * <p>Along with it the static {@code instance} field is gone; only the generator had read that
 * one as well. What remains is the registry itself.
 *
 * @author Plaintext GmbH
 * @since 600
 */
@Controller
@Slf4j
public class RepoMaster extends SuperModel {

    /**
     * {@code required = false}, so that a context without a single {@link PlaintextRepository} bean
     * starts (status report 29.08.2026, §3 "aggregator without an opt-out"). Previously the standard
     * injection of an empty list stopped startup — and the only implementations in the framework
     * live in {@code plaintext-admin-modules} and {@code plaintext-admin-secrets}. Whoever
     * deselected both got a startup error in a place that has nothing to do with either module.
     * This changes nothing about the behaviour of the existing apps: as soon as at least one bean
     * is there, Spring injects the list as before; without one, the already initialized
     * {@code new ArrayList<>()} stays in place, {@link #init()} builds an empty map and
     * {@link #getRepo(String)} returns {@code null} — exactly as it already does today for any unknown type.
     */
    @Autowired(required = false)
    private List<PlaintextRepository> repos = new ArrayList<>();

    private Map<String, PlaintextRepository> map = new HashMap<>();

    @PostConstruct
    private void init() {
        for (PlaintextRepository repo : repos) {
            String name = repo.getEntityName().toLowerCase();
            map.put(name, repo);
        }
    }

    public JpaRepository getRepo(String typ) {
        return map.get(typ.toLowerCase());
    }

}
