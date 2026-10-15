package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Daily gateway durations are seconds and include listening. Missing cached days are unknown. */
final class ReadingStatistics {
    final Map<LocalDate, Long> days;
    final long syncedAt;

    ReadingStatistics(Map<LocalDate, Long> days, long syncedAt) {
        this.days = Collections.unmodifiableMap(new LinkedHashMap<>(days));
        this.syncedAt = syncedAt;
    }

    static ReadingStatistics fetch(LibrarySync.Api api, long now, ZoneId zone,
                                   BooleanSupplier stopped) throws Exception {
        LocalDate today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate();
        LocalDate first = weekStart(today).minusWeeks(1);
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) days.put(date, 0L);
        // Keep last week and this week through today, including across month/year boundaries.
        for (YearMonth month = YearMonth.from(first); !month.isAfter(YearMonth.from(today));
             month = month.plusMonths(1)) {
            if (stopped.getAsBoolean()) throw new CancellationException();
            long baseTime = month.atDay(1).atTime(12, 0).atZone(zone).toEpochSecond();
            JSONObject response = api.call("/readdata/detail", new JSONObject()
                    .put("mode", "monthly").put("baseTime", baseTime));
            if (stopped.getAsBoolean()) throw new CancellationException();
            JSONObject times = response.getJSONObject("readTimes");
            for (java.util.Iterator<String> keys = times.keys(); keys.hasNext();) {
                String key = keys.next();
                LocalDate date = Instant.ofEpochSecond(Long.parseLong(key)).atZone(zone).toLocalDate();
                long seconds = times.getLong(key);
                if (seconds < 0) throw new JSONException("阅读时长不能为负数");
                if (YearMonth.from(date).equals(month) && days.containsKey(date)) days.put(date, seconds);
            }
        }
        return new ReadingStatistics(days, now);
    }

    Long secondsOn(LocalDate day) { return days.get(day); }

    static LocalDate weekStart(LocalDate day) {
        return day.minusDays(day.getDayOfWeek().getValue() - 1L);
    }

    long totalSeconds(LocalDate today) {
        long total = 0;
        for (LocalDate date = weekStart(today); !date.isAfter(today); date = date.plusDays(1)) {
            Long seconds = secondsOn(date);
            if (seconds != null) total += seconds;
        }
        return total;
    }

    int knownDays(LocalDate today) {
        int count = 0;
        for (LocalDate date = weekStart(today); !date.isAfter(today); date = date.plusDays(1)) {
            if (days.containsKey(date)) count++;
        }
        return count;
    }

    int readingDays(LocalDate today) {
        int count = 0;
        for (LocalDate date = weekStart(today); !date.isAfter(today); date = date.plusDays(1)) {
            Long seconds = secondsOn(date);
            if (seconds != null && seconds >= 60) count++;
        }
        return count;
    }

    static List<ShelfBook> recentBooks(List<ShelfBook> books, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate();
        long start = weekStart(today).atStartOfDay(zone).toEpochSecond();
        List<ShelfBook> recent = new ArrayList<>();
        for (ShelfBook book : books) {
            if (!"articles".equals(book.kind) && book.lastReadAt > 0
                    && book.lastReadAt >= start && book.lastReadAt <= now) recent.add(book);
        }
        recent.sort(Comparator.comparingLong((ShelfBook book) -> book.lastReadAt).reversed()
                .thenComparing(book -> book.id));
        return recent;
    }

    JSONObject toJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (Map.Entry<LocalDate, Long> day : days.entrySet()) {
            array.put(new JSONObject().put("date", day.getKey().toString()).put("seconds", day.getValue()));
        }
        return new JSONObject().put("syncedAt", syncedAt).put("days", array);
    }

    static ReadingStatistics fromJson(JSONObject json) throws JSONException {
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        JSONArray array = json.getJSONArray("days");
        for (int i = 0; i < array.length(); i++) {
            JSONObject day = array.getJSONObject(i);
            long seconds = day.getLong("seconds");
            if (seconds < 0) throw new JSONException("无效的阅读时长");
            days.put(LocalDate.parse(day.getString("date")), seconds);
        }
        return new ReadingStatistics(days, json.getLong("syncedAt"));
    }
}
