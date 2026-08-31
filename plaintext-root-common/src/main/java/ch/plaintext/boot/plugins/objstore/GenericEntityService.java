/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// TODO-REFACTOR-113: Unchecked cast warning without type safety
// Rationale: findByUniqueId() performs an unchecked cast (T) storable.getMyObject()
//            No runtime type check, can throw a ClassCastException at runtime
// Suggestion: generic type token pattern or validation with instanceof

// TODO-REFACTOR-307: Inconsistent exception handling
// Rationale: save() catches Exception and logs, but deleteByUniqueId() has no try-catch
//            deleteByUniqueId() can throw a NullPointerException when entity is null
// Suggestion: consistent exception handling, null check before delete()

// TODO-REFACTOR-604: Missing @Transactional on the delete operation
// Rationale: deleteByUniqueId() has no @Transactional annotation
// Suggestion: @Transactional for consistency with the save() method

// TODO-REFACTOR-206: Naming - "MyObject" is too generic
// Rationale: storable.getMyObject() and storable.setMyObject() are not expressive
// Suggestion: rename to getStoredEntity() / setStoredEntity()

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