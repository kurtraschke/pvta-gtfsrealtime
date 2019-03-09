package com.kurtraschke.pvtagtfsrealtime.producers;

import com.availtec.infopoint.client.InfopointClient;
import com.availtec.infopoint.client.InfopointClientException;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.kurtraschke.pvtagtfsrealtime.resolvers.RouteResolver;
import org.datacontract.schemas._2004._07.availtec_myavail_tids_datamanager.PublicMessageType;
import org.jetbrains.annotations.NotNull;
import org.nnsoft.guice.sli4j.core.InjectLogger;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFullUpdate;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeLibrary;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSink;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.Alerts;

@Singleton
public class GtfsRealtimeAlertProducer {

    private final GtfsRealtimeSink alertsSink;
    private final ScheduledExecutorService scheduledExecutorService;
    private final int refreshRate;
    private final InfopointClient infopointClient;
    private final RouteResolver routeResolver;
    private final GtfsRelationalDao dao;
    private final CalendarService cs;

    private ScheduledFuture<?> updater;

    @InjectLogger
    private Logger LOG;

    @Inject
    public GtfsRealtimeAlertProducer(@Alerts GtfsRealtimeSink alertsSink,
                                     ScheduledExecutorService scheduledExecutorService,
                                     @Named("refreshRate.alerts") int refreshRate,
                                     InfopointClient infopointClient,
                                     RouteResolver routeResolver,
                                     GtfsRelationalDao dao,
                                     CalendarService cs) {
        this.alertsSink = alertsSink;
        this.scheduledExecutorService = scheduledExecutorService;
        this.refreshRate = refreshRate;
        this.infopointClient = infopointClient;
        this.routeResolver = routeResolver;
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
        final GtfsRealtimeFullUpdate update = new GtfsRealtimeFullUpdate();

        try {
            final List<PublicMessageType> messages = infopointClient.getAllMessages().getPublicMessage();

            for (PublicMessageType message : messages) {
                if (!message.isPublished() || message.getPublicAccess() != 1) {
                    continue;
                }

                final FeedEntity.Builder feb = FeedEntity.newBuilder();

                feb.setId(Integer.toString(message.getMessageId()));

                final Alert.Builder ab = feb.getAlertBuilder();

                final ImmutableSet<AgencyAndId> routeIds = message.getRoutes().getInt().stream()
                        .map(routeResolver::resolveRoute)
                        .collect(toImmutableSet());

                for (AgencyAndId routeId : routeIds) {
                    final EntitySelector.Builder ieb = ab.addInformedEntityBuilder();
                    ieb.setRouteId(routeId.getId());
                }

                final TimeZone agencyTimeZone = routeIds.stream()
                        .map(dao::getRouteForId)
                        .map(Route::getAgency)
                        .map(Agency::getId)
                        .distinct()
                        .map(cs::getTimeZoneForAgencyId)
                        .collect(onlyElement());

                final TimeRange.Builder apb = ab.addActivePeriodBuilder();

                apb.setStart(timestamp(message.getFromDate(), message.getFromTime(), agencyTimeZone));
                apb.setEnd(timestamp(message.getToDate(), message.getToTime(), agencyTimeZone));

                ab.setDescriptionText(GtfsRealtimeLibrary.getTextAsTranslatedString(message.getMessage()));

                update.addEntity(feb.build());
            }

        } catch (InfopointClientException e) {
            LOG.error("Error while updating alerts.", e);
        }

        alertsSink.handleFullUpdate(update);
    }

    @NotNull
    private static Long timestamp(XMLGregorianCalendar date, XMLGregorianCalendar time, TimeZone agencyTimeZone) {
        return ZonedDateTime.of(
                date
                        .toGregorianCalendar(agencyTimeZone, null, null)
                        .toZonedDateTime()
                        .toLocalDate(),
                time
                        .toGregorianCalendar(agencyTimeZone, null, null)
                        .toZonedDateTime()
                        .toLocalTime(),
                agencyTimeZone.toZoneId()
        )
                .toEpochSecond();

    }

}
