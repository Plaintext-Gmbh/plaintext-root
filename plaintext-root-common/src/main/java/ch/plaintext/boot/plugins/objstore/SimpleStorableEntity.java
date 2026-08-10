/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Entity
public class SimpleStorableEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Getter
    private Long version;

    @Column(length = 30000)
    @Getter @Setter
    @Convert(converter = SimpleStorableConverter.class)
    private SimpleStorable myObject;


    @Column(unique = true, nullable = false)
    private String uniqueId;

    @PrePersist
    @PreUpdate
    private void syncUniqueId() {
        this.uniqueId = myObject.getUniqueId();
    }

}