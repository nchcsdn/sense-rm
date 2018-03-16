/*
 * SENSE Resource Manager (SENSE-RM) Copyright (c) 2016, The Regents
 * of the University of California, through Lawrence Berkeley National
 * Laboratory (subject to receipt of any required approvals from the
 * U.S. Dept. of Energy).  All rights reserved.
 *
 * If you have questions about your rights to use or distribute this
 * software, please contact Berkeley Lab's Innovation & Partnerships
 * Office at IPO@lbl.gov.
 *
 * NOTICE.  This Software was developed under funding from the
 * U.S. Department of Energy and the U.S. Government consequently retains
 * certain rights. As such, the U.S. Government has been granted for
 * itself and others acting on its behalf a paid-up, nonexclusive,
 * irrevocable, worldwide license in the Software to reproduce,
 * distribute copies to the public, prepare derivative works, and perform
 * publicly and display publicly, and to permit other to do so.
 *
 */
package net.es.sense.rm.driver.nsi;

import java.util.Collection;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.es.sense.rm.driver.nsi.cs.db.ConnectionMapService;
import net.es.sense.rm.driver.nsi.cs.db.ReservationService;
import net.es.sense.rm.driver.nsi.db.Model;
import net.es.sense.rm.driver.nsi.db.ModelService;
import net.es.sense.rm.driver.nsi.dds.api.DocumentReader;
import net.es.sense.rm.driver.nsi.mrml.MrmlFactory;
import net.es.sense.rm.driver.nsi.mrml.NmlModel;
import net.es.sense.rm.driver.nsi.mrml.SwitchingSubnetModel;
import net.es.sense.rm.driver.nsi.properties.NsiProperties;
import org.apache.jena.riot.Lang;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 *
 * @author hacksaw
 */
@Slf4j
@Service
public class AuditServiceBean implements AuditService {
  @Autowired
  private NsiProperties nsiProperties;

  @Autowired
  private DocumentReader documentReader;

  @Autowired
  private ReservationService reservationService;

  @Autowired
  private ConnectionMapService connectionMapService;

  @Autowired
  private ModelService modelService;

  @Override
  public void audit() {
    log.info("[AuditService] starting audit.");

    // Get the new document context.
    NmlModel nml = new NmlModel(documentReader);

    String topologyId = nsiProperties.getNetworkId();

    //nml.getTopologyIds().forEach((topologyId) -> {
      log.info("[AuditService] processing topologyId = {}", topologyId);

      SwitchingSubnetModel ssm = new SwitchingSubnetModel(reservationService, connectionMapService, nml, topologyId);
      MrmlFactory mrml = new MrmlFactory(nml, ssm, topologyId);

      // Check to see if this is a new version.
      if (modelService.isPresent(topologyId, mrml.getVersion())) {
        log.info("[AuditService] found matching model topologyId = {}, version = {}.", topologyId, mrml.getVersion());
      } else {
        log.info("[AuditService] adding new topology version, topologyId = {}, version = {}", topologyId, mrml.getVersion());
        UUID uuid = UUID.randomUUID();
        Model model = new Model();
        model.setTopologyId(topologyId);
        model.setModelId(uuid.toString());
        model.setVersion(mrml.getVersion());
        model.setBase(mrml.getModelAsString(Lang.TURTLE));
        modelService.create(model);
      }
    //});

    // TODO: Go through and delete any models for topologies no longer avalable.
    // TODO: Write an audit to clean up old model versions.
    Collection<Model> models = modelService.get();
    log.info("[AuditService] stored models after the audit...");
    for (Model m : models) {
      log.info("idx = {}, modelId= {}, topologyId = {}, version = {}, ", m.getIdx(), m.getModelId(), m.getTopologyId(), m.getVersion());
    }
  }

  @Override
  public void audit(String topologyId) {
    log.info("[AuditService] starting audit for {}.", topologyId);

    // Get the new document context.
    NmlModel nml = new NmlModel(documentReader);
    SwitchingSubnetModel ssm = new SwitchingSubnetModel(reservationService, connectionMapService, nml, topologyId);
    MrmlFactory mrml = new MrmlFactory(nml, ssm, topologyId);

    // Check to see if this is a new version.
    if (modelService.isPresent(topologyId, mrml.getVersion())) {
      log.debug("[AuditService] found matching model topologyId = {}, version = {}.", topologyId, mrml.getVersion());
    } else {
      log.info("[AuditService] adding new topology version, topologyId = {}, version = {}", topologyId, mrml.getVersion());
      UUID uuid = UUID.randomUUID();
      Model model = new Model();
      model.setTopologyId(topologyId);
      model.setModelId(uuid.toString());
      model.setVersion(mrml.getVersion());
      model.setBase(mrml.getModelAsString(Lang.TURTLE));
      modelService.create(model);
    }
  }
}