/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Set;

/**
 * @author Plaintext GmbH
 * @since 2017
 */
@NoRepositoryBean
public interface PlaintextRepository<T> extends JpaRepository<T, Long> {

    @Query("select distinct o.id from #{#entityName} o")
    public Set<String> getIds();

    @Query("select distinct o.id from #{#entityName} o")
    public List<Long> getIDList();

    @Query("select max(o.id) from #{#entityName} o")
    public Long getMaxID();

    @Query(value = "VALUES ('#{#entityName}')", nativeQuery = true)
    public String getEntityName();

    @Query("select distinct year(o.createdDate) from #{#entityName} o")
    public Set<Integer> getJahre();

    @Query("select distinct year(o.createdDate) from #{#entityName} o")
    public Set<String> getJahreAsString();

    @Query("select distinct o.mandat from #{#entityName} o")
    Set<String> getMandaten();

    @Query("select distinct year(o.createdDate) from #{#entityName} o where o.mandat = ?1")
    public Set<Integer> getJahre(String mandat);

    @Query("select o from #{#entityName} o where YEAR(o.lastModifiedDate) = ?1  and o.mandat = ?2 order by o.lastModifiedDate desc")
    List<T> findByYearAndMandant(Integer jahr, String mandat);

    public List<T> findByMandat(String mandant);

    public List<T> findByMandatAndDeleted(String mandant, boolean deleted);

    public List<T> findByCreatedBy(String creator);

    public List<T> findByDeleted(boolean deleted);

    public T findFirstByOrderByCreatedDateDesc();

    public T findFirstByOrderByLastModifiedDateDesc();

}
