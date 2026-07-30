/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

/**
 * @author : mad
 * @since : 26.08.2024
 **/
public interface SimpleStorable<T> {

    String getUniqueId();

    void setUniqueId(String id);

}