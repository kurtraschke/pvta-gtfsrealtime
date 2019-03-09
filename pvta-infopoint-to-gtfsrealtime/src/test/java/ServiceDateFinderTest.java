import com.google.common.collect.ImmutableList;
import com.kurtraschke.pvtagtfsrealtime.ServiceDateFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.onebusaway.gtfs.model.calendar.ServiceDate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDateFinderTest {

    private ServiceDateFinder sdf;

    @BeforeEach
    void setUp() {
        sdf = new ServiceDateFinder(11400, ZoneId.of("US/Eastern"));
    }

    @Test
    void possibleServiceDatesInOverlap() {
        final List<ServiceDate> possibleServiceDates = sdf.possibleServiceDates(Instant.parse("2019-03-03T06:00:00.0Z"));

        assertIterableEquals(
                ImmutableList.of(
                        new ServiceDate(2019, 3, 3),
                        new ServiceDate(2019, 3, 2)
                ),
                possibleServiceDates);
    }

    @Test
    void possibleServiceDatesNoOverlap() {
        final List<ServiceDate> possibleServiceDates = sdf.possibleServiceDates(Instant.parse("2019-03-03T01:00:00.0Z"));

        assertIterableEquals(
                ImmutableList.of(new ServiceDate(2019, 3, 2)),
                possibleServiceDates);
    }
}