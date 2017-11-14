package net.es.sense.rm.driver.nsi.cs.db;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 *
 * @author hacksaw
 */
@Repository
public interface ReservationRepository extends CrudRepository<Reservation, Long> {

  public Iterable<Reservation> findByConnectionId(@Param("connectionId") String connectionId);

  public Iterable<Reservation> findByTopologyId(@Param ("topologyId") String topologyId);

  public Reservation findByProviderNsaAndConnectionId(
          @Param ("providerNSA") String providerNsa,
          @Param("connectionId") String connectionId);
}