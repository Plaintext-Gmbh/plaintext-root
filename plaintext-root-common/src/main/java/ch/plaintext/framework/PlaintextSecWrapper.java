/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;/*
  Copyright (C) plaintext.ch, 2016.
 */

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AccessToSecurity if Available
 *
 * @author Plaintext GmbH
 * @since 0.0.1
 */
@Component
@Slf4j
public class PlaintextSecWrapper {

    @Getter
    private static PlaintextSecWrapper instance = null;

    @Autowired(required = false)
    private PlaintextAuthentication sec;

    @Autowired(required = false)
    private List<PlaintextRoleProvider> roleProviders;

    @Autowired
    private Environment environment;

    //@Autowired(required = false)
    //private List<PlaintextUserSaveListener> saveListeners;

    public static String getMandat() {
        if (instance == null) {
            return "NI";
        }
        if (instance.getUser() == null) {
            return "NU";
        }
        return instance.getUser().getMandat();
    }

    public static void setMandant(String mandant) {
        if (instance != null) {
            instance.getUser().setMandat(mandant);
        }
    }

    //@Autowired(required = false)
    //private PictureLinkProvider pictureLink;

    @PostConstruct
    private void init() {
        // log.info("*** init(); " + this.getClass().getCanonicalName());
        PlaintextSecWrapper.instance = this;
    }

    public Boolean isSecurityEnabled() {
        List<String> profs = Arrays.asList(environment.getActiveProfiles());
        if (profs != null && profs.contains("nosecurity")) {
            return Boolean.FALSE;
        }

        if (sec == null) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    public Boolean isAutenticated() {
        if (!isSecurityEnabled()) {
            return true;
        }
        return sec.isAutenticated();
    }

    public Boolean hasRole(String role) {
        if (!isSecurityEnabled()) {
            return true;
        }
        try {
            return sec.hasRole(role);
        } catch (Exception e) {
            return false;
        }
    }

    public List<PlaintextUser> getAllUsers() {
        if (!isSecurityEnabled()) {
            return new ArrayList<>();
        }
        return sec.getAllUsers();
    }

    public Map<Long, PlaintextUser> getAllUsersOrigMap() {
        Map<Long, PlaintextUser> ret = new HashMap<>();
        if (!isSecurityEnabled()) {
            return ret;
        }
        for (PlaintextUser user : getAllUsers()) {
            ret.put(user.getOrigId(), user);
        }
        return ret;
    }

    public Map<String, PlaintextUser> getAllUsersEmailMap() {
        Map<String, PlaintextUser> ret = new HashMap<>();
        if (!isSecurityEnabled()) {
            return ret;
        }
        for (PlaintextUser user : getAllUsers()) {
            ret.put(user.getMail(), user);
        }
        return ret;
    }

    public void saveUser(PlaintextUser user, String template) {

        if (user.getId() == 0 || user.getId() < 1) {
            log.info(" ** New User");

            if (template == null) {
                log.info(" ** No Template, so no Email will to send");
            } else {
                log.info(" ** Templete here, will send Email");
            }

            log.info(" ** create User in Keycloak and send PW Reset Mail");
        }


    }

    @Deprecated
    public PlaintextUser createNewUser() {
        return sec.createNewUser();
    }

    public String getUsername() {
        if (sec != null && sec.getUsername() != null) {
            return sec.getUsername();
        }
        return "no-user";
    }

    public PlaintextUser getUser(boolean... force) {
        return sec.getUser(force);

    }

    public PlaintextUser getUser() {
        if (sec == null) {
            return null;
        }
        return sec.getUser(false);
    }


    public Set<String> getAllRoles() {
        Set<String> ret = new HashSet<>();
        for (PlaintextRoleProvider pr : roleProviders) {
            ret.addAll(pr.getRoles());
        }

        return ret;
    }

    public List<String> getAllRolesAsList(String query) {
        List<String> ret = new ArrayList<>();
        for (String role : getAllRoles()) {
            if (role.toLowerCase().contains(query.toLowerCase())) {
                ret.add(role);
            }
        }

        if (ret.size() < 1) {
            ret.add(query);
        }
        return ret;
    }

    public String getPicture() {
        log.warn("ACHTUNG:46");
        return "";
    }

    public void saveUser(PlaintextUser user) {
        log.warn("ACHTUNG:45");



    }
}