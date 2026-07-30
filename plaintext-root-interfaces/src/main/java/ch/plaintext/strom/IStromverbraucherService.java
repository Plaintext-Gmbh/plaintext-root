/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.strom;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Service interface for electricity consumer device management.
 * This interface allows different modules to access electricity consumer data
 * without direct dependency on the concrete StromverbraucherService.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface IStromverbraucherService {

    /**
     * Get all electricity consumers for a specific mandate.
     * @param mandat the mandate ID
     * @return list of electricity consumers
     */
    List<? extends IStromverbraucher> getAllVerbraucher(String mandat);

    /**
     * Get active electricity consumers for a specific mandate.
     * @param mandat the mandate ID
     * @return list of active electricity consumers
     */
    List<? extends IStromverbraucher> getAktiveVerbraucher(String mandat);

    /**
     * Get all distinct device types for a mandate.
     * @param mandat the mandate ID
     * @return list of device types
     */
    List<String> getAllTypen(String mandat);

    /**
     * Get all distinct locations for a mandate.
     * @param mandat the mandate ID
     * @return list of locations
     */
    List<String> getAllStandorte(String mandat);

    /**
     * Calculate total yearly consumption for a mandate.
     * @param mandat the mandate ID
     * @return total yearly consumption in kWh
     */
    BigDecimal getTotalYearlyConsumption(String mandat);

    /**
     * Get consumers by device type.
     * @param mandat the mandate ID
     * @param typ the device type
     * @return list of consumers of specified type
     */
    List<? extends IStromverbraucher> getByTyp(String mandat, String typ);

    /**
     * Get consumers by location.
     * @param mandat the mandate ID
     * @param standort the location
     * @return list of consumers at specified location
     */
    List<? extends IStromverbraucher> getByStandort(String mandat, String standort);

    /**
     * Find electricity consumer by ID.
     * @param id the consumer ID
     * @return the consumer if found
     */
    Optional<? extends IStromverbraucher> findById(Long id);
}