package com.kurtraschke.pvtagtfsrealtime;

import com.availtec.infopoint.client.InfopointClient;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import org.nnsoft.guice.sli4j.slf4j.Slf4jLoggingModule;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import org.onebusaway.guice.jetty_exporter.JettyExporterModule;
import org.onebusaway.guice.jsr250.JSR250Module;
import com.kurtraschke.pvtagtfsrealtime.providers.CalendarServiceProvider;
import com.kurtraschke.pvtagtfsrealtime.providers.GtfsRelationalDaoProvider;
import com.kurtraschke.pvtagtfsrealtime.providers.InfopointClientProvider;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class GtfsRealtimeModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new JSR250Module());
        install(new JettyExporterModule());
        install(new GtfsRealtimeExporterModule());
        install(new Slf4jLoggingModule());

        bind(ScheduledExecutorService.class)
                .toInstance(Executors.newSingleThreadScheduledExecutor());

        bind(CalendarService.class)
                .toProvider(CalendarServiceProvider.class)
                .in(Scopes.SINGLETON);

        bind(GtfsRelationalDao.class)
                .toProvider(GtfsRelationalDaoProvider.class)
                .in(Scopes.SINGLETON);

        bind(InfopointClient.class)
                .toProvider(InfopointClientProvider.class)
                .in(Scopes.SINGLETON);

    }

}
