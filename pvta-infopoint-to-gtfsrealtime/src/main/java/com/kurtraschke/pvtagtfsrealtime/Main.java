package com.kurtraschke.pvtagtfsrealtime;

import com.google.inject.ConfigurationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.kurtraschke.pvtagtfsrealtime.producers.GtfsRealtimeAlertProducer;
import com.kurtraschke.pvtagtfsrealtime.producers.GtfsRealtimeVehicleProducer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nnsoft.guice.rocoto.configuration.ConfigurationModule;
import org.nnsoft.guice.rocoto.converters.FileConverter;
import org.nnsoft.guice.rocoto.converters.URLConverter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFileWriter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.Alerts;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.TripUpdates;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.VehiclePositions;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeServlet;
import org.onebusaway.guice.jsr250.LifecycleService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.URL;

@Command(name = "pvta-infopoint-to-gtfsrealtime", mixinStandardHelpOptions = true, version = "1.0")
public class Main implements Runnable {

    @Option(names = "--config", description = "Path to configuration file")
    private File configurationFile;

    public static void main(String[] args) {
        CommandLine.run(new Main(), args);
    }

    public void run() {
        Injector injector = Guice.createInjector(
                new URLConverter(),
                new FileConverter(),
                new ConfigurationModule() {
                    @Override
                    protected void bindConfigurations() {
                        bindSystemProperties();

                        if (configurationFile != null) {
                            bindProperties(configurationFile);
                        }
                    }
                },
                new GtfsRealtimeModule()
        );

        configureExporterFromConfigurationValues(
                injector,
                "vehiclePositions.url",
                "vehiclePositions.path",
                VehiclePositions.class
        );

        configureExporterFromConfigurationValues(
                injector,
                "tripUpdates.url",
                "tripUpdates.path",
                TripUpdates.class
        );

        configureExporterFromConfigurationValues(
                injector,
                "alerts.url",
                "alerts.path",
                Alerts.class

        );

        injector.getInstance(GtfsRealtimeVehicleProducer.class);
        injector.getInstance(GtfsRealtimeAlertProducer.class);

        injector.getInstance(LifecycleService.class).start();
    }

    private static void configureExporterFromConfigurationValues(Injector injector,
                                                          String urlConfigurationKey,
                                                          String fileConfigurationKey,
                                                          Class<? extends Annotation> exporterAnnotationType) {
        configureExporter(
                injector,
                getConfigurationValue(injector, URL.class, urlConfigurationKey),
                getConfigurationValue(injector, File.class, fileConfigurationKey),
                injector.getInstance(Key.get(GtfsRealtimeExporter.class, exporterAnnotationType))
        );
    }

    @Nullable
    private static <T> T getConfigurationValue(@NotNull Injector injector, @NotNull Class<T> type, @NotNull String configurationKey) {
        try {
            return injector.getInstance(Key.get(type, Names.named(configurationKey)));
        } catch (ConfigurationException e) {
            return null;
        }
    }

    private static void configureExporter(Injector injector, @Nullable URL feedUrl, @Nullable File feedPath, GtfsRealtimeExporter exporter) {
        if (feedUrl != null) {
            GtfsRealtimeServlet servlet = injector.getInstance(GtfsRealtimeServlet.class);
            servlet.setUrl(feedUrl);
            servlet.setSource(exporter);
        }

        if (feedPath != null) {
            GtfsRealtimeFileWriter writer = injector.getInstance(GtfsRealtimeFileWriter.class);
            writer.setPath(feedPath);
            writer.setSource(exporter);
        }
    }

}
