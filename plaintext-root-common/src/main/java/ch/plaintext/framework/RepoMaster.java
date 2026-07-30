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
 * Dump Super Class
 *
 * @author Plaintext GmbH
 * @since 600
 */
@Controller
@Slf4j
public class RepoMaster extends SuperModel {

    public static RepoMaster instance;

    @Autowired
    private List<PlaintextRepository> repos = new ArrayList<>();

    private Map<String, PlaintextRepository> map = new HashMap<>();

    @PostConstruct
    private void init() {
        // log.info("*** init(); " + this.getClass().getCanonicalName());

        for (PlaintextRepository repo : repos) {
            String name = repo.getEntityName().toLowerCase();
            map.put(name, repo);
        }

        instance = this;
    }

    public JpaRepository getRepo(String typ) {
        return map.get(typ.toLowerCase());
    }


    public long getNextID(Object object) {

        String typ = object.getClass().getSimpleName().toLowerCase();

        if (!map.containsKey(typ)) {
            log.warn("Repo fuer Typ" + typ + " nicht gefunden");
            return -1;
        }

        Long id = null;
        try {
            id = map.get(typ).getMaxID();
        } catch (Exception e) {
            log.info("repo error ! " + e.getMessage());
        }

        if (id == null) {
            id = 1L;
        } else {
            id++;
        }

        //long id = map.get(typ).getMaxID() + 1;
        log.info("neue id: " + id + " für " + typ);
        return id;

    }


}
