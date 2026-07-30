/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentityGenerator;

import java.io.Serializable;

@Slf4j
public class UseExistingIdOtherwiseGenerateUsingIdentity extends IdentityGenerator {

    private long idb = 1000000L;

    public Serializable generate(SharedSessionContractImplementor session, Object object) throws HibernateException {

        synchronized (this) {
            // Get the entity persister and extract the identifier
            var persister = session.getEntityPersister(null, object);
            Serializable id = (Serializable) persister.getIdentifier(object, session);

            if (id != null) {
                return id;
            } else {

                if (RepoMaster.instance == null) {
                    idb++;
                    log.error("ACHTUNG ID OHNE SPRING: " + idb + " FUER: " + object.getClass().getSimpleName());
                    return idb;

                }

                Long idN = RepoMaster.instance.getNextID(object);
                return idN;

            }
        }

    }
}