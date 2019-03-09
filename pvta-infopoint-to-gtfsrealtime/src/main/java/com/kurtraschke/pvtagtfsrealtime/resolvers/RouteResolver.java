package com.kurtraschke.pvtagtfsrealtime.resolvers;

import com.availtec.infopoint.client.InfopointClient;
import com.availtec.infopoint.client.InfopointClientException;
import com.google.common.collect.ImmutableMap;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.RouteType;
import org.nnsoft.guice.sli4j.core.InjectLogger;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

import static com.google.common.collect.MoreCollectors.onlyElement;

@Singleton
public class RouteResolver {

    private final Map<Integer, AgencyAndId> infopointRouteIdToGtfsRouteMap;

    @Inject
    public RouteResolver(InfopointClient ic, GtfsRelationalDao dao) {
        final ImmutableMap.Builder<Integer, AgencyAndId> infopointRouteIdToGtfsRouteMapBuilder = ImmutableMap.builder();

        try {
            for (RouteType infopointRoute : ic.getAllRoutes().getRoute()) {
                Predicate<Route> shortNameMatchesId = gtfsRoute -> gtfsRoute.getId().getId().equals(infopointRoute.getShortName());
                Predicate<Route> abbreviationMatchesShortName = gtfsRoute -> gtfsRoute.getShortName().equals(infopointRoute.getRouteAbbreviation());
                Predicate<Route> googleDescriptionMatchesLongName = gtfsRoute -> gtfsRoute.getLongName().equals(infopointRoute.getGoogleDescription());

                try {
                    final Route gtfsRoute = dao.getAllRoutes().stream()
                            .filter(shortNameMatchesId
                                    .or(abbreviationMatchesShortName)
                                    .or(googleDescriptionMatchesLongName))
                            .collect(onlyElement());

                    infopointRouteIdToGtfsRouteMapBuilder.put(infopointRoute.getRouteId(), gtfsRoute.getId());
                } catch (NoSuchElementException | IllegalArgumentException ignored) {

                }
            }
        } catch (InfopointClientException e) {
            throw new RuntimeException("Error while fetching route definitions.", e);
        }

        infopointRouteIdToGtfsRouteMap = infopointRouteIdToGtfsRouteMapBuilder.build();
    }

    public AgencyAndId resolveRoute(int routeId) {
        return infopointRouteIdToGtfsRouteMap.get(routeId);
    }


}
