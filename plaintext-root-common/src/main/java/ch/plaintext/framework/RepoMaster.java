/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;/*
  Copyright (C) eMad, 2017.
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
 * Registry der {@link PlaintextRepository}-Beans, nach Entitätsname aufschlüsselbar.
 *
 * <p><b>Karte 687:</b> Hier stand bis zum 12.08.2026 zusätzlich {@code getNextID(Object)} — eine
 * Id-Vergabe über {@code max(id) + 1}, die für einen unbekannten Typ <b>{@code -1}</b> zurückgab.
 * Ihr einziger Aufrufer war der Generator {@code UseExistingIdOtherwiseGenerateUsingIdentity} in
 * {@link SuperModel}, und der lief seit dem Hibernate-7-Umstieg nicht mehr (Begründung dort). Die
 * Methode war damit unerreichbar — und wäre sie es nicht gewesen, hätte sie Schaden angerichtet:
 * für Entitätstypen ohne {@code PlaintextRepository} die Id {@code -1}, für alle anderen eine unter
 * Nebenläufigkeit doppelt vergebene. Zwei Ausfallmechanismen, von denen einer den anderen verdeckte.
 *
 * <p>Mit ihr ist das statische {@code instance}-Feld entfallen; auch das hatte nur der Generator
 * gelesen. Was bleibt, ist die Registry selbst.
 *
 * @author Plaintext GmbH
 * @since 600
 */
@Controller
@Slf4j
public class RepoMaster extends SuperModel {

    @Autowired
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
