/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class MyRememberMeRepositoryRepository implements PersistentTokenRepository {

    private final MyRememberMeRepository persistentLoginRepository;

    public MyRememberMeRepositoryRepository(MyRememberMeRepository persistentLoginRepository) {
        this.persistentLoginRepository = persistentLoginRepository;
    }

    @Override
    public void createNewToken(PersistentRememberMeToken token) {
        MyRememberMe login = new MyRememberMe();
        login.setSeries(token.getSeries());
        login.setUsername(token.getUsername());
        login.setToken(token.getTokenValue());
        login.setLastUsed(token.getDate());
        persistentLoginRepository.save(login);
    }

    @Override
    public void updateToken(String series, String tokenValue, Date lastUsed) {
        MyRememberMe login = persistentLoginRepository.findById(series).orElse(null);
        if (login != null) {
            login.setToken(tokenValue);
            login.setLastUsed(lastUsed);
            persistentLoginRepository.save(login);
        }
    }

    @Override
    public PersistentRememberMeToken getTokenForSeries(String seriesId) {
        MyRememberMe login = persistentLoginRepository.findById(seriesId).orElse(null);
        return login != null ? new PersistentRememberMeToken(login.getUsername(), login.getSeries(), login.getToken(), login.getLastUsed()) : null;
    }

    @Override
    public void removeUserTokens(String username) {
        persistentLoginRepository.deleteAllByUsername(username);
    }
}
