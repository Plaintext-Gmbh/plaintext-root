/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;/*
  Copyright (C) eMad, 2016.
 */


import java.util.List;

/**
 * @author Plaintext GmbH
 * @since 0.0.1
 */
public interface PlaintextMailReceiver {

    List<PlaintextMailModel> checkMail(Boolean onlyNotSeen);

    Boolean connectionUp();

    Integer getSeenmails();

}
