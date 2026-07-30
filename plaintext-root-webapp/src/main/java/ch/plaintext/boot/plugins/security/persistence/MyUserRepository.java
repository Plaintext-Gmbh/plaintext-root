/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.persistence;


import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyUserRepository extends JpaRepository<MyUserEntity, Long> {
    MyUserEntity findByUsername(String username);
    MyUserEntity findByAutologinKey(String autologinKey);
    MyUserEntity findByOidcSubject(String oidcSubject);
}

