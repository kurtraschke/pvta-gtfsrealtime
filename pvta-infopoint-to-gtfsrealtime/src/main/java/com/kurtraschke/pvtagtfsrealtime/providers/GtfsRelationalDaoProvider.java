package com.kurtraschke.pvtagtfsrealtime.providers;

import org.nnsoft.guice.sli4j.core.InjectLogger;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.io.File;
import java.io.IOException;

public class GtfsRelationalDaoProvider implements Provider<GtfsRelationalDao> {

    @InjectLogger
    private Logger LOG;

    @Inject
    @Named("PVTA.gtfsPath")
    private File gtfsPath;

    public void setGtfsPath(File gtfsPath) {
        this.gtfsPath = gtfsPath;
    }

    @Override
    public GtfsRelationalDao get() {
        LOG.info("Loading GTFS from {}", gtfsPath.toString());
        GtfsRelationalDaoImpl dao = new GtfsRelationalDaoImpl();
        GtfsReader reader = new GtfsReader();
        reader.setEntityStore(dao);
        try {
            reader.setInputLocation(gtfsPath);
            reader.run();
            reader.close();
        } catch (IOException e) {
            throw new RuntimeException("Failure while reading GTFS", e);
        }
        return dao;
    }
}