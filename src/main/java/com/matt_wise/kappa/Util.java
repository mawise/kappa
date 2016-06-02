package com.matt_wise.kappa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Created by matt on 5/29/16.
 */
public class Util {
    private Util(){}

    /***
     * Rounds down to the minute.
     * @param unixTime
     * @return
     */
    public static LocalDateTime minuteTimeFromUnix(long unixTime){
        return LocalDateTime
                .ofInstant(Instant.ofEpochSecond(unixTime/1000), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.MINUTES);
    }

    public static long minuteEpocFromUnix(long unixTime){
        ZoneId zoneId = ZoneId.systemDefault();
        return LocalDateTime
                .ofInstant(Instant.ofEpochSecond(unixTime/1000), zoneId)
                .truncatedTo(ChronoUnit.MINUTES)
                .atZone(zoneId)
                .toEpochSecond() * 1000;
    }

}
