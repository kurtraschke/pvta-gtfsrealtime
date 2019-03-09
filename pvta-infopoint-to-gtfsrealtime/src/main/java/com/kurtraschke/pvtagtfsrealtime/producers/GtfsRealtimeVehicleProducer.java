package com.kurtraschke.pvtagtfsrealtime.producers;

import com.availtec.infopoint.client.InfopointClient;
import com.availtec.infopoint.client.InfopointClientException;
import com.google.inject.Inject;
import com.google.transit.realtime.GtfsRealtime.*;
import com.kurtraschke.pvtagtfsrealtime.resolvers.RouteResolver;
import com.kurtraschke.pvtagtfsrealtime.resolvers.VehicleToTripResolver;
import org.apache.commons.lang3.tuple.Pair;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.VehicleLocationType;
import org.jetbrains.annotations.NotNull;
import org.nnsoft.guice.sli4j.core.InjectLogger;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFullUpdate;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.TripUpdates;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.VehiclePositions;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSink;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TimeZone;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.MoreCollectors.onlyElement;

@Singleton
public class GtfsRealtimeVehicleProducer {
    private final GtfsRealtimeSink tripUpdatesSink;
    private final GtfsRealtimeSink vehiclePositionsSink;
    private final ScheduledExecutorService scheduledExecutorService;
    private final int refreshRate;
    private final InfopointClient infopointClient;
    private final VehicleToTripResolver tripResolver;
    private final RouteResolver routeResolver;
    private final GtfsRelationalDao dao;
    private final CalendarService cs;

    private ScheduledFuture<?> updater;

    @InjectLogger
    private Logger LOG;

    @Inject
    public GtfsRealtimeVehicleProducer(@TripUpdates GtfsRealtimeSink tripUpdatesSink,
                                       @VehiclePositions GtfsRealtimeSink vehiclePositionsSink,
                                       ScheduledExecutorService scheduledExecutorService,
                                       @Named("refreshRate.vehicles") int refreshRate,
                                       InfopointClient infopointClient,
                                       RouteResolver routeResolver,
                                       VehicleToTripResolver tripResolver,
                                       GtfsRelationalDao dao,
                                       CalendarService cs) {
        this.tripUpdatesSink = tripUpdatesSink;
        this.vehiclePositionsSink = vehiclePositionsSink;
        this.scheduledExecutorService = scheduledExecutorService;
        this.refreshRate = refreshRate;
        this.infopointClient = infopointClient;
        this.routeResolver = routeResolver;
        this.tripResolver = tripResolver;
        this.dao = dao;
        this.cs = cs;
    }

    @PostConstruct
    private void start() {
        updater = scheduledExecutorService.scheduleWithFixedDelay(this::update, 0, refreshRate, TimeUnit.SECONDS);
    }

    @PreDestroy
    private void stop() {
        updater.cancel(false);
    }

    private void update() {
        final GtfsRealtimeFullUpdate vehiclePositionsUpdate = new GtfsRealtimeFullUpdate();
        final GtfsRealtimeFullUpdate tripUpdatesUpdate = new GtfsRealtimeFullUpdate();

        try {
            final List<VehicleLocationType> vehicleLocations = infopointClient.getAllVehicles().getVehicleLocation();

            int entityIndex = 0;

            for (VehicleLocationType vl : vehicleLocations) {
                final String id = String.format("%05d", ++entityIndex);

                final AgencyAndId resolvedRouteId = routeResolver.resolveRoute(vl.getRouteId());

                if (resolvedRouteId == null) {
                    LOG.warn("Unknown route for vehicle {}", vl.getVehicleId());
                    continue;
                }

                final Route resolvedRoute = dao.getRouteForId(resolvedRouteId);
                final TimeZone agencyTimeZone = cs.getTimeZoneForAgencyId(resolvedRoute.getAgency().getId());

                ServiceDate mappedServiceDate;
                Trip mappedTrip;

                try {
                    final Pair<ServiceDate, Trip> mappedTripAndServiceDate = tripResolver.resolveVehicle(vl, resolvedRoute);
                    mappedServiceDate = mappedTripAndServiceDate.getLeft();
                    mappedTrip = mappedTripAndServiceDate.getRight();
                } catch (NoSuchElementException | IllegalStateException e) {
                    LOG.warn("Unknown trip for vehicle {} on route {}", vl.getName(), resolvedRoute.getId());
                    mappedServiceDate = null;
                    mappedTrip = null;
                }

                final TripDescriptor td = tripDescriptor(resolvedRoute, mappedTrip, mappedServiceDate);

                StopTime currentStopTime;

                try {
                    currentStopTime = dao.getStopTimesForTrip(mappedTrip)
                            .stream()
                            .filter(st -> st.getStop().getName().equals(vl.getLastStop()))
                            .collect(onlyElement());

                } catch (NoSuchElementException | IllegalArgumentException e) {
                    LOG.warn("Unknown stop {}", vl.getLastStop());
                    currentStopTime = null;
                }

                final VehicleDescriptor vd = vehicleDescriptor(vl);

                final long timestamp = timestamp(vl, agencyTimeZone);

                tripUpdatesUpdate.addEntity(tripUpdateFeedEntity(id, vl, td, vd, timestamp, currentStopTime));
                vehiclePositionsUpdate.addEntity(vehiclePositionFeedEntity(id, vl, td, vd, timestamp));
            }
        } catch (InfopointClientException e) {
            LOG.error("Error while updating vehicles.", e);
        }

        vehiclePositionsSink.handleFullUpdate(vehiclePositionsUpdate);
        tripUpdatesSink.handleFullUpdate(tripUpdatesUpdate);
    }

    @NotNull
    private static Long timestamp(VehicleLocationType vl, TimeZone agencyTimeZone) {
        return vl.getLastUpdated().toGregorianCalendar(agencyTimeZone, null, null)
                .toInstant()
                .getEpochSecond();
    }

    @NotNull
    private static VehicleDescriptor vehicleDescriptor(VehicleLocationType vl) {
        final VehicleDescriptor.Builder vdb = VehicleDescriptor.newBuilder();

        vdb.setId(Integer.toString(vl.getVehicleId()));
        vdb.setLabel(vl.getName());

        return vdb.build();
    }

    @NotNull
    private static TripDescriptor tripDescriptor(@Nullable Route route, @Nullable Trip trip, @Nullable ServiceDate serviceDate) {
        final TripDescriptor.Builder tdb = TripDescriptor.newBuilder();

        if (route != null) {
            tdb.setRouteId(route.getId().getId());
        }

        if (trip != null) {
            tdb.setTripId(trip.getId().getId());
        }

        if (serviceDate != null) {
            tdb.setStartDate(serviceDate.getAsString());
        }

        return tdb.build();
    }

    @NotNull
    private static FeedEntity tripUpdateFeedEntity(String id, VehicleLocationType vl,
                                                   TripDescriptor td, VehicleDescriptor vd,
                                                   long timestamp, @Nullable StopTime currentStopTime) {
        final FeedEntity.Builder feb = FeedEntity.newBuilder();
        feb.setId(id);

        final TripUpdate.Builder tub = feb.getTripUpdateBuilder();

        tub.setTrip(td);
        tub.setVehicle(vd);

        final int delay = 60 * vl.getDeviation();

        tub.setDelay(delay);

        if (currentStopTime != null) {
            final TripUpdate.StopTimeUpdate.Builder stub = tub.addStopTimeUpdateBuilder();
            stub.setStopId(currentStopTime.getStop().getId().getId());
            final TripUpdate.StopTimeEvent.Builder steb = stub.getDepartureBuilder();
            steb.setDelay(delay);
        }

        tub.setTimestamp(timestamp);

        return feb.build();
    }

    @NotNull
    private static FeedEntity vehiclePositionFeedEntity(String id, VehicleLocationType vl, TripDescriptor td, VehicleDescriptor vd, long timestamp) {
        final FeedEntity.Builder feb = FeedEntity.newBuilder();
        feb.setId(id);

        final VehiclePosition.Builder vpb = feb.getVehicleBuilder();

        vpb.setTrip(td);
        vpb.setVehicle(vd);

        vpb.setTimestamp(timestamp);

        final Position.Builder pb = vpb.getPositionBuilder();

        pb.setLatitude((float) vl.getLatitude());
        pb.setLongitude((float) vl.getLongitude());

        return feb.build();
    }
}
