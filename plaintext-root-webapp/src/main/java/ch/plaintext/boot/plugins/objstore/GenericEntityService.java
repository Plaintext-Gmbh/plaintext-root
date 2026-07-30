/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// TODO-REFACTOR-113: Unchecked Cast Warning ohne Type Safety
// Begründung: findByUniqueId() macht unchecked cast (T) storable.getMyObject()
//             Keine Runtime-Type-Überprüfung, kann ClassCastException zur Laufzeit werfen
// Vorschlag: Generic Type Token Pattern oder Validation mit instanceof

// TODO-REFACTOR-307: Inconsistent Exception Handling
// Begründung: save() catcht Exception und loggt, aber deleteByUniqueId() hat keinen try-catch
//             deleteByUniqueId() kann NullPointerException werfen wenn entity null ist
// Vorschlag: Konsistentes Exception Handling, Null-Check vor delete()

// TODO-REFACTOR-604: Fehlende @Transactional bei Delete-Operation
// Begründung: deleteByUniqueId() hat keine @Transactional Annotation
// Vorschlag: @Transactional für Konsistenz mit save() Methode

// TODO-REFACTOR-206: Naming - "MyObject" ist zu generisch
// Begründung: storable.getMyObject() und storable.setMyObject() sind nicht aussagekräftig
// Vorschlag: Umbenennen zu getStoredEntity() / setStoredEntity()

@Slf4j
@Service
public class GenericEntityService<T extends SimpleStorable> {

    @Autowired
    private SimpleStorableEntityRepository repository;

    @Transactional
    public void save(T object) {
        try {
            log.debug("Saving object with uniqueId: {}", object.getUniqueId());
            SimpleStorableEntity storable = repository.findByUniqueId(object.getUniqueId());
            if(storable == null){
                storable = new SimpleStorableEntity();
            }
            storable.setMyObject(object);
            repository.save(storable);
            log.debug("Successfully saved object with uniqueId: {}", object.getUniqueId());
        } catch (Exception e) {
            log.error("Error saving object: " + e.getMessage(), e);
        }
    }

    public T findByUniqueId(String uniqueId) {
        SimpleStorableEntity storable = repository.findByUniqueId(uniqueId);
        if(storable == null){
            return null;
        }
        return (T) storable.getMyObject();
    }

    public void deleteByUniqueId(String uniqueId) {
        SimpleStorableEntity entity = repository.findByUniqueId(uniqueId);
        repository.delete(entity);
    }

}