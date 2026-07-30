/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.strom;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Interface for electricity consumer devices.
 * This interface allows different modules to work with electricity consumer data
 * without direct dependency on the concrete Stromverbraucher entity.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface IStromverbraucher {

    /**
     * Get the unique identifier of the device.
     * @return the device ID
     */
    Long getId();

    /**
     * Get the mandate ID.
     * @return the mandate ID
     */
    String getMandat();

    /**
     * Get the device name.
     * @return the device name
     */
    String getName();

    /**
     * Get the device type.
     * @return the device type
     */
    String getTyp();

    /**
     * Get the device location.
     * @return the location
     */
    String getStandort();

    /**
     * Get the power consumption in watts.
     * @return the power in watts
     */
    BigDecimal getLeistungWatt();

    /**
     * Get the operating hours per day.
     * @return the operating hours per day
     */
    BigDecimal getBetriebsstundenProTag();

    /**
     * Get the operating days per year.
     * @return the operating days per year
     */
    Integer getBetriebstageProJahr();

    /**
     * Check if the device is active.
     * @return true if active, false otherwise
     */
    Boolean getAktiv();

    /**
     * Get the purchase date.
     * @return the purchase date
     */
    LocalDateTime getAnschaffungsdatum();

    /**
     * Get the serial number.
     * @return the serial number
     */
    String getSeriennummer();

    /**
     * Get the manufacturer.
     * @return the manufacturer
     */
    String getHersteller();

    /**
     * Get the model.
     * @return the model
     */
    String getModell();

    /**
     * Get remarks/comments.
     * @return the remarks
     */
    String getBemerkungen();
}