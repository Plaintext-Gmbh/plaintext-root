/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.List;
import java.util.Map;

public interface PlaintextUser {

    String getUsername();

    void setUsername(String username);

    // die beiden folgenden methoden werden benoetigt um im webgui die rollen zuweisen zu koennen
    List<String> getAuths();

    void setAuths(List<String> coll);

    Map<String, String> getProperties();

    Long getId();

    Long getOrigId();

    void setOrigId(Long origId);

    String getVorname();

    void setVorname(String vorname);

    String getNachname();

    void setNachname(String nachname);

    java.util.Date getGeburtstag();

    void setGeburtstag(java.util.Date geburtstag);

    String getMobile();

    void setMobile(String mobile);

    String getMail();

    void setMail(String mail);

    String getMandat();

    void setMandat(String mandat);

    String getLinkToken();

    boolean isGesperrt();

    public String getNameVornameId();

}
