package net.es.sense.rm.driver.nsi.properties;

import com.fasterxml.jackson.annotation.JsonRootName;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import net.es.nsi.dds.lib.dao.AccessControlType;
import net.es.nsi.dds.lib.dao.SecureType;
import net.es.nsi.dds.lib.dao.ServerType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@lombok.Data
@lombok.NoArgsConstructor
@Configuration
@ConfigurationProperties(prefix = "nsi")
@Validated
@JsonRootName(value = "nsi")
public class NsiProperties {
  // The URL of the local container runtime.
  @NotNull
  private String ddsUrl;
  private String proxy;

  // The NSA identifier of this RA in URN format.
  @NotNull
  private String nsaId;

  // How often the we will audit out subscription to peer DDS servers.
  private long ddsAuditTimer = 600;

  // How long do we maintain a document in our cache after it has expired.
  private long ddsExpiryInterval = 600;

  // How man actors do we instantiate for the dds audit pool.
  private int ddsPoolSize = 4;

  // The DDS servers we will connect to for discovery information.
  private List<String> peers = new ArrayList<>();

  // How often (in seconds) do we audit the NSI connection on our associated NSA?
  private int connectionAuditTimer = 100;

  // The NSA identifier of the target aggregator for our connection requests.
  @NotNull
  private String providerNsaId;
  private String providerConnectionURL = "http://localhost:9000/nsi-v2/ConnectionServiceProvider";
  private String requesterConnectionURL = "http://localhost:8080/nsi-v2/ConnectionServiceRequester";

  // The identifier of the network to expose through the RA.
  private String networkId;

  private String defaultServiceType = "http://services.ogf.org/nsi/2013/12/descriptions/EVTS.A-GOLE";
  private String defaultType = "guaranteedCapped";
  private String defaultUnits = "bps";
  private long defaultGranularity = 1L;

  private long modelAuditTimer = 120;

  // Configuration for web server and client.
  //@Value ("${server}")
  @Valid
  private ServerType server; // = new ServerType();

  // Configuration for client.
  //@Value ("${client}")
  @Valid
  private SecureType client; // = new SecureType();

  // I do not believe this is need for a DDS client.
  //@Value ("${accessControl}")
  @Valid
  private AccessControlType accessControl = new AccessControlType();
}
