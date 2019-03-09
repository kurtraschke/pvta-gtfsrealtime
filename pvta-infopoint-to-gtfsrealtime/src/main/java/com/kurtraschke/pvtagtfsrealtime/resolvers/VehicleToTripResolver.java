package com.kurtraschke.pvtagtfsrealtime.resolvers;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import com.kurtraschke.pvtagtfsrealtime.ServiceDateFinder;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.VehicleLocationType;
import org.gavaghan.geodesy.Ellipsoid;
import org.gavaghan.geodesy.GeodeticCalculator;
import org.gavaghan.geodesy.GlobalCoordinates;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.onebusaway.gtfs.model.*;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.onebusaway.utility.InterpolationLibrary;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.NavigableMap;
import java.util.TimeZone;
import java.util.stream.IntStream;

import static com.kurtraschke.common.Utils.fp;
import static com.kurtraschke.common.Utils.pf;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.onebusaway.utility.EOutOfRangeStrategy.LAST_VALUE;

@Singleton
public class VehicleToTripResolver {

    private final GtfsRelationalDao dao;
    private final CalendarService cs;
    private final GeometryFactory gf;
    private final GeodeticCalculator gc;

    private final LoadingCache<AgencyAndId, LengthIndexedLine> shapeLineCache;
    private final LoadingCache<Trip, NavigableMap<Integer, Double>> tripTimeToLocationMapCache;

    private final int overlapSeconds;

    @Inject
    public VehicleToTripResolver(GtfsRelationalDao dao,
                                 CalendarService cs,
                                 GeometryFactory gf,
                                 GeodeticCalculator gc) {
        this.dao = dao;
        this.cs = cs;
        this.gf = gf;
        this.gc = gc;

        shapeLineCache = CacheBuilder.newBuilder()
                .maximumSize(50)
                .build(CacheLoader.from(key -> new LengthIndexedLine(lineForShape(key))));

        tripTimeToLocationMapCache = CacheBuilder.newBuilder()
                .maximumSize(200)
                .build(CacheLoader.from(key -> {
                    final ImmutableSortedMap.Builder<Integer, Double> timeToLocationMapBuilder = new ImmutableSortedMap.Builder<>(Comparator.naturalOrder());
                    final LengthIndexedLine lil = shapeLineCache.getUnchecked(key.getShapeId());
                    double index = -1;

                    for (StopTime st : dao.getStopTimesForTrip(key)) {
                        final Coordinate stopCoordinate = new CoordinateXY(st.getStop().getLon(), st.getStop().getLat());

                        if (index >= 0) {
                            index = lil.indexOfAfter(stopCoordinate, index);
                        } else {
                            index = lil.indexOf(stopCoordinate);
                        }

                        if (st.isArrivalTimeSet()) {
                            timeToLocationMapBuilder.put(st.getArrivalTime(), index);
                        }

                        if (st.isDepartureTimeSet() && (!st.isArrivalTimeSet() || st.getDepartureTime() != st.getArrivalTime())) {
                            timeToLocationMapBuilder.put(st.getDepartureTime(), index);
                        }
                    }

                    return timeToLocationMapBuilder.build();
                }));

        overlapSeconds = dao.getAllStopTimes().stream()
                .flatMapToInt(VehicleToTripResolver::stopTimeIntStream)
                .max()
                .orElseThrow() - 86400;
    }

    public Pair<ServiceDate, Trip> resolveVehicle(VehicleLocationType vl, Route route) {
        final Coordinate probeCoordinate = new Coordinate(vl.getLongitude(), vl.getLatitude());

        final TimeZone agencyTimeZone = cs.getTimeZoneForAgencyId(route.getAgency().getId());

        final int deviation = vl.getDeviation();

        final Instant probeTime = vl.getLastUpdated()
                .toGregorianCalendar(agencyTimeZone, null, null)
                .toInstant()
                .minus(deviation, MINUTES);

        final ServiceDateFinder sdf = new ServiceDateFinder(overlapSeconds, agencyTimeZone.toZoneId());

        final Pair<ServiceDate, Trip> resolvedTripAndServiceDate = dao.getTripsForRoute(route)
                .stream()
                .filter(t -> t.getShapeId() != null)
                .filter(t -> t.getTripHeadsign().trim().equals(vl.getDestination()))
                .flatMap(t -> sdf.possibleServiceDates(probeTime).stream().map(sd -> ImmutablePair.of(sd, t)))
                .filter(fp(pf((sd, t) -> cs.getServiceIdsOnDate(sd).contains(t.getServiceId()))))
                .filter(fp(pf((sd, t) -> tripStopTimeRange(t, sd.getAsCalendar(agencyTimeZone).toInstant(), 30L, MINUTES).contains(probeTime))))
                .min(Comparator.comparing(pf((sd, t) -> distanceFromExpectedPosition(probeTime, probeCoordinate, t, sd, agencyTimeZone))))
                .orElseThrow();

        return resolvedTripAndServiceDate;
    }

    private double distanceFromExpectedPosition(Instant probeTime, Coordinate probeCoordinate, Trip t, ServiceDate sd, TimeZone agencyTimeZone) {
        final int probeTimeSeconds = (int) Duration.between(sd.getAsCalendar(agencyTimeZone).toInstant(), probeTime).toSeconds();

        final double interpolatedLocation = InterpolationLibrary.interpolate(
                tripTimeToLocationMapCache.getUnchecked(t),
                probeTimeSeconds,
                LAST_VALUE
        );

        final Coordinate coordinate = shapeLineCache.getUnchecked(t.getShapeId())
                .extractPoint(interpolatedLocation);

        final double meters = gc.calculateGeodeticCurve(
                Ellipsoid.WGS84,
                coordinateToGlobalCoordinates(coordinate),
                coordinateToGlobalCoordinates(probeCoordinate)
        )
                .getEllipsoidalDistance();

        return meters;
    }

    @NotNull
    @Contract("_ -> new")
    private static GlobalCoordinates coordinateToGlobalCoordinates(Coordinate coordinate) {
        return new GlobalCoordinates(coordinate.getY(), coordinate.getX());
    }

    @NotNull
    private Range<Instant> tripStopTimeRange(Trip t, Instant base,
                                             Long window, TemporalUnit unit) {
        final IntSummaryStatistics intSummaryStatistics = dao.getStopTimesForTrip(t)
                .stream()
                .flatMapToInt(VehicleToTripResolver::stopTimeIntStream)
                .summaryStatistics();

        return Range.closed(
                base.plusSeconds(intSummaryStatistics.getMin()).minus(window, unit),
                base.plusSeconds(intSummaryStatistics.getMax()).plus(window, unit)
        );
    }

    @NotNull
    private Range<Instant> tripStopTimeRange(Trip t, Instant base) {
       return tripStopTimeRange(t, base, 0L, SECONDS);
    }

    private LineString lineForShape(AgencyAndId shapeId) {
        return gf.createLineString(
                dao.getShapePointsForShapeId(shapeId)
                        .stream()
                        .sorted(Comparator.comparing(ShapePoint::getSequence))
                        .map(sp -> new CoordinateXY(sp.getLon(), sp.getLat()))
                        .toArray(Coordinate[]::new)
        );
    }

    private static IntStream stopTimeIntStream(StopTime st) {
        IntStream.Builder b = IntStream.builder();

        if (st.isArrivalTimeSet()) {
            b.accept(st.getArrivalTime());
        }

        if (st.isDepartureTimeSet()) {
            b.accept(st.getDepartureTime());
        }

        return b.build();
    }

}
