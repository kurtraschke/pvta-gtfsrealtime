package com.kurtraschke.pvtagtfsrealtime;

import com.google.common.collect.ImmutableList;
import org.onebusaway.gtfs.model.calendar.ServiceDate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

public class ServiceDateFinder {
    private final int overlapSeconds;
    private final ZoneId timeZone;

    public ServiceDateFinder(int overlapSeconds, ZoneId timeZone) {
        this.overlapSeconds = overlapSeconds;
        this.timeZone = timeZone;
    }

    public List<ServiceDate> possibleServiceDates(Instant probeTime) {
        final ImmutableList.Builder<ServiceDate> possibleServiceDatesListBuilder = ImmutableList.builder();
        final ZonedDateTime localProbeTime = ZonedDateTime.ofInstant(probeTime, timeZone);

        ServiceDate today = new ServiceDate(GregorianCalendar.from(localProbeTime));

        possibleServiceDatesListBuilder.add(today);

        long probeTimeSinceMidnight = Duration.between(
                today.getAsCalendar(TimeZone.getTimeZone(timeZone)).toInstant(),
                probeTime)
                .toSeconds();

        if (probeTimeSinceMidnight <= overlapSeconds) {
            ServiceDate yesterday = today.previous();
            possibleServiceDatesListBuilder.add(yesterday);
        }

        return possibleServiceDatesListBuilder.build();
    }
}
