package net.es.sense.rm.driver.nsi.cs.db;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * Maintain a list of deltas with associated NSI connectionIds through to
 * the committed phase.
 *
 * @author hacksaw
 */
@Slf4j
@Repository
public class DeltaMapRepository {
  private final Map<String, DeltaConnection> map = new ConcurrentHashMap<>();

  public DeltaConnection store(DeltaConnection delta) {
    return map.put(delta.getDeltaId(), delta);
  }

  public DeltaConnection get(String deltaId) {
    return map.get(deltaId);
  }

  public DeltaConnection delete(String deltaId) {
    return map.remove(deltaId);
  }

  public void delete(List<String> deltaIds) {
    deltaIds.forEach((deltaId) -> {
      map.remove(deltaId);
    });
  }
}
