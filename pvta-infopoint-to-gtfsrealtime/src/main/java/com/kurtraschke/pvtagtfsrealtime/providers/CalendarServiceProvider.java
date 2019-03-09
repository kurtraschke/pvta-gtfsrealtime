package com.kurtraschke.pvtagtfsrealtime.providers;

import com.google.inject.Provider;
import org.onebusaway.gtfs.impl.calendar.CalendarServiceDataFactoryImpl;
import org.onebusaway.gtfs.services.GtfsRelationalDao;
import org.onebusaway.gtfs.services.calendar.CalendarService;

import javax.inject.Inject;

public class CalendarServiceProvider implements Provider<CalendarService> {

    @Inject
    private GtfsRelationalDao dao;

    @Override
    public CalendarService get() {
        return CalendarServiceDataFactoryImpl.createService(dao);
    }
}