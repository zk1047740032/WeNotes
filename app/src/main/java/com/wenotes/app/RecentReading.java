package com.wenotes.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RecentReading {
    enum Period {
        WEEK, MONTH, HALF_YEAR;

        long startAt(long now, ZoneId zone) {
            ZonedDateTime end = Instant.ofEpochSecond(now).atZone(zone);
            switch (this) {
                case MONTH: return end.minusMonths(1).toEpochSecond();
                case HALF_YEAR: return end.minusMonths(6).toEpochSecond();
                default: return end.minusDays(7).toEpochSecond();
            }
        }
    }

    static List<ShelfBook> filter(List<ShelfBook> books, Period period, long now, ZoneId zone) {
        long start = period.startAt(now, zone);
        List<ShelfBook> recent = new ArrayList<>();
        for (ShelfBook book : books) {
            if (!"articles".equals(book.kind) && book.lastReadAt > 0
                    && book.lastReadAt >= start && book.lastReadAt <= now) {
                recent.add(book);
            }
        }
        recent.sort(Comparator.comparingLong((ShelfBook book) -> book.lastReadAt).reversed()
                .thenComparing(book -> book.id));
        return recent;
    }
}
