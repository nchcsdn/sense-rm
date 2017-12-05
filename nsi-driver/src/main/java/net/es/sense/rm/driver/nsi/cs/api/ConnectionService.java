package net.es.sense.rm.driver.nsi.cs.api;

import java.util.ArrayList;
import java.util.List;
import javax.jws.WebService;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.ws.Holder;
import lombok.extern.slf4j.Slf4j;
import net.es.nsi.common.constants.Nsi;
import net.es.nsi.cs.lib.CsParser;
import net.es.nsi.cs.lib.SimpleStp;
import net.es.sense.rm.driver.nsi.cs.db.Operation;
import net.es.sense.rm.driver.nsi.cs.db.OperationMapRepository;
import net.es.sense.rm.driver.nsi.cs.db.Reservation;
import net.es.sense.rm.driver.nsi.cs.db.ReservationService;
import net.es.sense.rm.driver.nsi.cs.db.StateType;
import org.ogf.schemas.nsi._2013._12.connection.requester.ServiceException;
import org.ogf.schemas.nsi._2013._12.connection.types.ChildSummaryListType;
import org.ogf.schemas.nsi._2013._12.connection.types.ChildSummaryType;
import org.ogf.schemas.nsi._2013._12.connection.types.DataPlaneStateChangeRequestType;
import org.ogf.schemas.nsi._2013._12.connection.types.DataPlaneStatusType;
import org.ogf.schemas.nsi._2013._12.connection.types.ErrorEventType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericAcknowledgmentType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericErrorType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericFailedType;
import org.ogf.schemas.nsi._2013._12.connection.types.LifecycleStateEnumType;
import org.ogf.schemas.nsi._2013._12.connection.types.MessageDeliveryTimeoutRequestType;
import org.ogf.schemas.nsi._2013._12.connection.types.ObjectFactory;
import org.ogf.schemas.nsi._2013._12.connection.types.QueryNotificationConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QueryRecursiveConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QueryResultConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QuerySummaryConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QuerySummaryResultCriteriaType;
import org.ogf.schemas.nsi._2013._12.connection.types.QuerySummaryResultType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReservationConfirmCriteriaType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReservationStateEnumType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReserveConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReserveTimeoutRequestType;
import org.ogf.schemas.nsi._2013._12.framework.headers.CommonHeaderType;
import org.ogf.schemas.nsi._2013._12.framework.types.ServiceExceptionType;
import org.ogf.schemas.nsi._2013._12.services.point2point.P2PServiceBaseType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

/**
 *
 * @author hacksaw
 */
@Slf4j
@Component
@WebService(serviceName = "ConnectionServiceRequester", portName = "ConnectionServiceRequesterPort", endpointInterface = "org.ogf.schemas.nsi._2013._12.connection.requester.ConnectionRequesterPort", targetNamespace = "http://schemas.ogf.org/nsi/2013/12/connection/requester", wsdlLocation = "")
public class ConnectionService {

  private final ReservationService reservationService;
  private final OperationMapRepository operationMap;

  private final static ObjectFactory FACTORY = new ObjectFactory();

  public ConnectionService(ReservationService reservationService, OperationMapRepository operationMap) {
    this.reservationService = reservationService;
    this.operationMap = operationMap;
  }

  public GenericAcknowledgmentType reserveConfirmed(ReserveConfirmedType reserveConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveConfirmed.getConnectionId());

    ReservationConfirmCriteriaType criteria = reserveConfirmed.getCriteria();
    DataPlaneStatusType dataPlaneStatus = FACTORY.createDataPlaneStatusType();
    dataPlaneStatus.setVersion(criteria.getVersion());
    dataPlaneStatus.setActive(false);
    dataPlaneStatus.setVersionConsistent(true);

    Reservation reservation = processConfirmedCriteria(
            header.value.getProviderNSA(),
            reserveConfirmed.getGlobalReservationId(),
            reserveConfirmed.getDescription(),
            reserveConfirmed.getConnectionId(),
            ReservationStateEnumType.RESERVE_HELD,
            LifecycleStateEnumType.CREATED,
            dataPlaneStatus,
            criteria);

    if (reservation != null) {
      Reservation r = reservationService.get(reservation.getProviderNsa(), reservation.getConnectionId());
      if (r == null) {
        // We have not seen this reservation before so store it.
        log.info("[ConnectionService] reserveConfirmed: storing new reservation, cid = {}",
                reservation.getId());
        reservationService.store(reservation);
      } else if (r.diff(reservation)) {
        // We have to determine if the stored reservation needs to be updated.
        log.info("[ConnectionService] reserveConfirmed: storing reservation update, cid = {}",
                reservation.getId());
        reservation.setId(r.getId());
        reservationService.store(reservation);
      } else {
        log.info("[ConnectionService] reserveConfirmed: reservation no change, cid = {}",
                reservation.getId());
      }
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.reserved);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  private Reservation processConfirmedCriteria(
          String providerNsa,
          String gid,
          String description,
          String cid,
          ReservationStateEnumType reservationState,
          LifecycleStateEnumType lifecycleState,
          DataPlaneStatusType dataPlaneStatus,
          ReservationConfirmCriteriaType criteria) {

    log.info("[ConnectionService] processConfirmedCriteria: connectionId = {}", cid);

    Reservation reservation = new Reservation();
    reservation.setGlobalReservationId(gid);
    reservation.setDescription(description);
    reservation.setDiscovered(System.currentTimeMillis());
    reservation.setProviderNsa(providerNsa);
    reservation.setConnectionId(cid);
    reservation.setReservationState(reservationState);
    reservation.setLifecycleState(lifecycleState);
    reservation.setDataPlaneActive(dataPlaneStatus.isActive());
    reservation.setVersion(criteria.getVersion());
    reservation.setServiceType(criteria.getServiceType().trim());
    reservation.setStartTime(getStartTime(criteria.getSchedule().getStartTime()));
    reservation.setEndTime(getEndTime(criteria.getSchedule().getEndTime()));

    // Now we need to determine the network based on the STP used in the service.
    try {
      serializeP2PS(criteria.getServiceType(), criteria.getAny(), reservation);
      return reservation;
    } catch (JAXBException ex) {
      log.error("[ConnectionService] processReservation failed for connectionId = {}",
              reservation.getConnectionId(), ex);
      return null;
    }
  }

  public GenericAcknowledgmentType reserveFailed(GenericFailedType reserveFailed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveFailed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveFailed.getConnectionId());
    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveFailed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      op.setException(reserveFailed.getServiceException());
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveCommitConfirmed(GenericConfirmedType reserveCommitConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveCommitConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveCommitConfirmed.getConnectionId());

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveCommitConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.committed);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveCommitFailed(GenericFailedType reserveCommitFailed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveCommitFailed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveCommitFailed.getConnectionId());
    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveCommitFailed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      op.setException(reserveCommitFailed.getServiceException());
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveAbortConfirmed(GenericConfirmedType reserveAbortConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType provisionConfirmed(GenericConfirmedType provisionConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] provisionConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), provisionConfirmed.getConnectionId());

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] provisionConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.provisioned);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType releaseConfirmed(GenericConfirmedType releaseConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType terminateConfirmed(GenericConfirmedType parameters, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType querySummaryConfirmed(QuerySummaryConfirmedType querySummaryConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    // Get the providerNSA identifier.
    String providerNsa = header.value.getProviderNSA();

    // Extract the uPA connection segments associated with individual networks.
    List<QuerySummaryResultType> reservations = querySummaryConfirmed.getReservation();
    log.info("[ConnectionService] querySummaryConfirmed: providerNSA = {}, # of reservations = {}",
            providerNsa, reservations.size());

    // Process each reservation returned.
    List<Reservation> results = new ArrayList<>();
    for (QuerySummaryResultType reservation : reservations) {
      // Get the parent reservation information to apply to child connections.
      ReservationStateEnumType reservationState = reservation.getConnectionStates().getReservationState();
      LifecycleStateEnumType lifecycleState = reservation.getConnectionStates().getLifecycleState();
      DataPlaneStatusType dataPlaneStatus = reservation.getConnectionStates().getDataPlaneStatus();

      log.info("[ConnectionService] querySummaryConfirmed: cid = {}, gid = {}, decription = {}, rstate = {}, lstate = {}",
              reservation.getConnectionId(), reservation.getGlobalReservationId(), reservation.getDescription(),
              reservationState, lifecycleState);

      // If this reservation is in the process of being created, or failed
      // creation, then there will be no associated criteria.
      if (reservation.getCriteria().isEmpty()) {
        results.add(processReservationNoCriteria(
                providerNsa,
                reservation.getGlobalReservationId(),
                reservation.getDescription(),
                reservation.getConnectionId(),
                reservationState,
                lifecycleState,
                dataPlaneStatus));
      } else {
        results.addAll(processSummaryCriteria(
                providerNsa,
                reservation.getGlobalReservationId(),
                reservation.getDescription(),
                reservation.getConnectionId(),
                reservationState,
                lifecycleState,
                dataPlaneStatus,
                reservation.getCriteria()));
      }
    }

    // Determine if we need to update each reservation in the database.
    for (Reservation reservation : results) {
      Reservation r = reservationService.get(reservation.getProviderNsa(), reservation.getConnectionId());
      if (r == null) {
        // We have not seen this reservation before so store it.
        log.info("[ConnectionService] querySummaryConfirmed: storing new reservation, cid = {}",
                reservation.getConnectionId());
        reservationService.store(reservation);
      } else if (r.diff(reservation)) {
        // We have to determine if the stored reservation needs to be updated.
        log.info("[ConnectionService] querySummaryConfirmed: storing reservation update, cid = {}",
                reservation.getConnectionId());
        reservation.setId(r.getId());
        reservationService.store(reservation);
      } else {
        log.info("[ConnectionService] querySummaryConfirmed: reservation no change, cid = {}",
                reservation.getConnectionId());
      }
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  private Reservation processReservationNoCriteria(
          String providerNsa,
          String gid,
          String description,
          String cid,
          ReservationStateEnumType reservationState,
          LifecycleStateEnumType lifecycleState,
          DataPlaneStatusType dataPlaneStatus) {

    // We have had a state change so update the reservation.
    Reservation reservation = new Reservation();
    reservation.setGlobalReservationId(gid);
    reservation.setDescription(description);
    reservation.setProviderNsa(providerNsa);
    reservation.setConnectionId(cid);
    reservation.setVersion(0);
    reservation.setReservationState(reservationState);
    reservation.setLifecycleState(lifecycleState);
    reservation.setDataPlaneActive(dataPlaneStatus.isActive());
    reservation.setDiscovered(System.currentTimeMillis());

    return reservation;
  }

  private List<Reservation> processSummaryCriteria(
          String providerNsa,
          String gid,
          String description,
          String cid,
          ReservationStateEnumType reservationState,
          LifecycleStateEnumType lifecycleState,
          DataPlaneStatusType dataPlaneStatus,
          List<QuerySummaryResultCriteriaType> criteriaList) {

    log.info("[ConnectionService] processSummaryCriteria: connectionId = {}", cid);

    List<Reservation> results = new ArrayList<>();

    // There will be one criteria for each version of this reservation. We
    // will check to see if there are any new versions than what is already
    // stored.
    for (QuerySummaryResultCriteriaType criteria : criteriaList) {
      log.info("[ConnectionService] processCriteria: cid = {}, version = {}, serviceType = {}",
              cid, criteria.getVersion(), criteria.getServiceType());
      ChildSummaryListType children = criteria.getChildren();
      if (children == null || children.getChild().isEmpty()) {
        // We are at a leaf child so check to see if we need to store this reservation information.
        Reservation reservation = new Reservation();
        reservation.setGlobalReservationId(gid);
        reservation.setDescription(description);
        reservation.setDiscovered(System.currentTimeMillis());
        reservation.setProviderNsa(providerNsa);
        reservation.setConnectionId(cid);
        reservation.setReservationState(reservationState);
        reservation.setLifecycleState(lifecycleState);
        reservation.setDataPlaneActive(dataPlaneStatus.isActive());
        reservation.setVersion(criteria.getVersion());
        reservation.setServiceType(criteria.getServiceType().trim());
        reservation.setStartTime(getStartTime(criteria.getSchedule().getStartTime()));
        reservation.setEndTime(getEndTime(criteria.getSchedule().getEndTime()));

        // Now we need to determine the network based on the STP used in the service.
        try {
          serializeP2PS(criteria.getServiceType(), criteria.getAny(), reservation);
        } catch (JAXBException ex) {
          log.error("[ConnectionService] processReservation failed for cid = {}", cid, ex);
          continue;
        }

        results.add(reservation);
      } else {
        // We still have children so this must be an aggregator.
        for (ChildSummaryType child : children.getChild()) {
          log.info("[ConnectionService] querySummaryConfirmed: child cid = {}, gid = {}, decription = {}, rstate = {}, lstate = {}",
                  child.getConnectionId(), gid, description, reservationState, lifecycleState);

          Reservation reservation = new Reservation();
          reservation.setDiscovered(System.currentTimeMillis());
          reservation.setGlobalReservationId(gid);
          reservation.setDescription(description);
          reservation.setProviderNsa(child.getProviderNSA());
          reservation.setConnectionId(child.getConnectionId());
          reservation.setVersion(criteria.getVersion());
          reservation.setServiceType(child.getServiceType().trim());
          reservation.setReservationState(reservationState);
          reservation.setLifecycleState(lifecycleState);
          reservation.setDataPlaneActive(dataPlaneStatus.isActive());
          reservation.setStartTime(getStartTime(criteria.getSchedule().getStartTime()));
          reservation.setEndTime(getEndTime(criteria.getSchedule().getEndTime()));

          // Now we need to determine the network based on the STP used in the service.
          try {
            serializeP2PS(child.getServiceType(), child.getAny(), reservation);
          } catch (JAXBException ex) {
            log.error("[ConnectionService] processReservation failed for connectionId = {}",
                    reservation.getConnectionId(), ex);
            continue;
          }

          results.add(reservation);
        }
      }
    }

    return results;
  }

  private void serializeP2PS(String serviceType, List<Object> any, Reservation reservation) throws JAXBException {
    if (Nsi.NSI_SERVICETYPE_EVTS.equalsIgnoreCase(serviceType)
            || Nsi.NSI_SERVICETYPE_EVTS_OPENNSA.equalsIgnoreCase(serviceType)) {
      reservation.setServiceType(Nsi.NSI_SERVICETYPE_EVTS);
      for (Object object : any) {
        if (object instanceof JAXBElement) {
          JAXBElement jaxb = (JAXBElement) object;
          if (jaxb.getValue() instanceof P2PServiceBaseType) {
            P2PServiceBaseType p2ps = (P2PServiceBaseType) jaxb.getValue();
            SimpleStp stp = new SimpleStp(p2ps.getSourceSTP());
            reservation.setTopologyId(stp.getNetworkId());
            reservation.setService(CsParser.getInstance().p2ps2xml(p2ps));
            break;

          }
        } else if (object instanceof org.apache.xerces.dom.ElementNSImpl) {
          org.apache.xerces.dom.ElementNSImpl element = (org.apache.xerces.dom.ElementNSImpl) object;
          if ("p2ps".equalsIgnoreCase(element.getLocalName())) {
            P2PServiceBaseType p2p = CsParser.getInstance().node2p2ps((Node) element);
            SimpleStp stp = new SimpleStp(p2p.getSourceSTP());
            reservation.setTopologyId(stp.getNetworkId());
            reservation.setService(CsParser.getInstance().p2ps2xml(p2p));
            break;
          }
        }
      }
    }
  }

  public GenericAcknowledgmentType queryRecursiveConfirmed(QueryRecursiveConfirmedType queryRecursiveConfirmed,
          Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  /**
   * public GenericAcknowledgmentType queryRecursiveConfirmed( QueryRecursiveConfirmedType queryRecursiveConfirmed,
   * Holder<CommonHeaderType> header) throws ServiceException {
   *
   * log.debug("[ConnectionService] queryRecursiveConfirmed: reservationService = {}", reservationService);
   *
   * // Get the providerNSA identifier. String providerNsa = header.value.getProviderNSA();
   *
   * // Extract the uPA connection segments associated with individual networks. List<QueryRecursiveResultType>
   * reservations = queryRecursiveConfirmed.getReservation(); log.info("[ConnectionService] queryRecursiveConfirmed:
   * providerNSA = {}, # of reservations = {}", providerNsa, reservations.size());
   *
   * // Process each reservation returned. for (QueryRecursiveResultType reservation : reservations) { // Get the
   * parent reservation information to apply to child connections. ReservationStateEnumType reservationState =
   * reservation.getConnectionStates().getReservationState(); DataPlaneStatusType dataPlaneStatus =
   * reservation.getConnectionStates().getDataPlaneStatus(); log.info("[ConnectionService] queryRecursiveConfirmed: cid
   * = {}, gid = {}, state = {}", reservation.getConnectionId(), reservation.getGlobalReservationId(),
   * reservationState);
   *
   * processRecursiveCriteria( providerNsa, reservation.getGlobalReservationId(), reservation.getConnectionId(),
   * reservationState, dataPlaneStatus, reservation.getCriteria()); }
   *
   * return FACTORY.createGenericAcknowledgmentType(); }
   *
   * private void processRecursiveCriteria( String providerNsa, String gid, String cid, ReservationStateEnumType
   * reservationState, DataPlaneStatusType dataPlaneStatus, List<QueryRecursiveResultCriteriaType> criteriaList) {
   *
   * // There will be one criteria for each version of this reservation. We // will check to see if there are any new
   * versions than what is already // stored. for (QueryRecursiveResultCriteriaType criteria : criteriaList) {
   * log.info("[ConnectionService] processCriteria: cid = {}, version = {}, serviceType = {}", cid,
   * criteria.getVersion(), criteria.getServiceType());
   *
   * ChildRecursiveListType children = criteria.getChildren(); if (children == null || children.getChild().isEmpty()) {
   * // We are at a leaf child so check to see if we need to store this reservation information. Reservation existing =
   * reservationService.get(providerNsa, cid); if (existing != null && existing.getVersion() >= criteria.getVersion()) {
   * // We have already stored this so update only if state has changed. if
   * (reservationState.compareTo(existing.getReservationState()) != 0 || dataPlaneStatus.isActive() !=
   * existing.isDataPlaneActive()) { existing.setReservationState(reservationState);
   * existing.setDataPlaneActive(dataPlaneStatus.isActive()); existing.setDiscovered(System.currentTimeMillis());
   * reservationService.update(existing); } continue; }
   *
   * Reservation reservation = new Reservation(); reservation.setDiscovered(System.currentTimeMillis());
   * reservation.setGlobalReservationId(gid); reservation.setProviderNsa(providerNsa); reservation.setConnectionId(cid);
   * reservation.setVersion(criteria.getVersion()); reservation.setServiceType(criteria.getServiceType().trim());
   * reservation.setStartTime(getStartTime(criteria.getSchedule().getStartTime()));
   * reservation.setEndTime(getEndTime(criteria.getSchedule().getEndTime()));
   *
   * // Now we need to determine the network based on the STP used in the service. if
   * (Nsi.NSI_SERVICETYPE_EVTS.equalsIgnoreCase(reservation.getServiceType()) ||
   * Nsi.NSI_SERVICETYPE_EVTS_OPENNSA.equalsIgnoreCase(reservation.getServiceType())) {
   * reservation.setServiceType(Nsi.NSI_SERVICETYPE_EVTS); for (Object any : criteria.getAny()) { if (any instanceof
   * JAXBElement) { JAXBElement jaxb = (JAXBElement) any; if (jaxb.getDeclaredType() == P2PServiceBaseType.class) {
   * log.debug("[ConnectionService] processRecursiveCriteria: found P2PServiceBaseType");
   * reservation.setService(XmlUtilities.jaxbToString(P2PServiceBaseType.class, jaxb));
   *
   * // Get the network identifier from and STP. P2PServiceBaseType p2p = (P2PServiceBaseType) jaxb.getValue();
   * SimpleStp stp = new SimpleStp(p2p.getSourceSTP()); reservation.setTopologyId(stp.getNetworkId()); break; } } } }
   *
   * // Replace the existing entry with this new criteria if we already have one. if (existing != null) {
   * reservation.setId(existing.getId()); reservationService.update(reservation); } else {
   * reservationService.create(reservation); } } else { // We still have children so this must be an aggregator.
   * children.getChild().forEach((child) -> { child.getConnectionStates(); processRecursiveCriteria(
   * child.getProviderNSA(), gid, child.getConnectionId(), child.getConnectionStates().getReservationState(),
   * child.getConnectionStates().getDataPlaneStatus(), child.getCriteria()); }); } } }
   *
   */
  private long getStartTime(JAXBElement<XMLGregorianCalendar> time) {
    if (time == null || time.getValue() == null) {
      return 0;
    }

    return time.getValue().toGregorianCalendar().getTimeInMillis();
  }

  private long getEndTime(JAXBElement<XMLGregorianCalendar> time) {
    if (time == null || time.getValue() == null) {
      return Long.MAX_VALUE;
    }

    return time.getValue().toGregorianCalendar().getTimeInMillis();
  }

  public GenericAcknowledgmentType queryNotificationConfirmed(QueryNotificationConfirmedType queryNotificationConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType queryResultConfirmed(QueryResultConfirmedType queryResultConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType error(GenericErrorType error, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType errorEvent(ErrorEventType errorEvent, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType dataPlaneStateChange(DataPlaneStateChangeRequestType dataPlaneStateChange, Holder<CommonHeaderType> header) throws ServiceException {
    log.error("[ConnectionService] dataPlaneStateChange for connectionId = {}, active = {}",
            dataPlaneStateChange.getConnectionId(), dataPlaneStateChange.getDataPlaneStatus().isActive());

    // This state change is in the context of the local providerNSA and not
    // the child connection, so we need to look up the connectionId associated
    // with the provider which may not be the connectionId stored in the DB.
    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveTimeout(ReserveTimeoutRequestType reserveTimeout, Holder<CommonHeaderType> header) throws ServiceException {
    log.error("[ConnectionService] reserveTimeout for correlationId = {}, connectionId = {}",
            header.value.getCorrelationId(), reserveTimeout.getConnectionId());

    // We can fail the delta request based on this.  We do not have an outstanding
    // operation (or may have one just starting) so no operation to correltate to.
    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType messageDeliveryTimeout(MessageDeliveryTimeoutRequestType messageDeliveryTimeout, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] messageDeliveryTimeout recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), messageDeliveryTimeout.getConnectionId());
    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] messageDeliveryTimeout can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      ServiceExceptionType sex = new ServiceExceptionType();
      sex.setNsaId(value.getProviderNSA());
      sex.setText("messageDeliveryTimeout received");
      sex.setConnectionId(messageDeliveryTimeout.getConnectionId());
      op.setException(sex);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }
}
