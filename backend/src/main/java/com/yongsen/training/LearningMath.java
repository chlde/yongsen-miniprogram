package com.yongsen.training;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Pure interval and page calculations; never trusts a client completion flag. */
public final class LearningMath {
    private static final long MAX_SAMPLE_GAP_MS = 3000;
    private static final double SPEED_RATIO = 1.2;
    private static final long POSITION_TOLERANCE_MS = 300;

    private LearningMath() {}

    public static List<Object> union(List<Object> input, long start, long end) {
        List<long[]> ranges = new ArrayList<>();
        for (Object value : input) {
            List<Object> pair = Data.list(value);
            if (pair.size() == 2) {
                ranges.add(
                        new long[] {
                            ((Number) pair.get(0)).longValue(), ((Number) pair.get(1)).longValue()
                        });
            }
        }
        if (end > start) {
            ranges.add(new long[] {start, end});
        }
        ranges.sort(Comparator.comparingLong(range -> range[0]));
        List<Object> result = new ArrayList<>();
        long[] current = null;
        for (long[] range : ranges) {
            if (current == null) {
                current = range.clone();
            } else if (range[0] <= current[1]) {
                current[1] = Math.max(current[1], range[1]);
            } else {
                result.add(List.of(current[0], current[1]));
                current = range.clone();
            }
        }
        if (current != null) {
            result.add(List.of(current[0], current[1]));
        }
        return result;
    }

    public static long length(List<Object> ranges) {
        long total = 0;
        for (Object value : ranges) {
            List<Object> range = Data.list(value);
            total += ((Number) range.get(1)).longValue() - ((Number) range.get(0)).longValue();
        }
        return total;
    }

    public static List<Object> missing(List<Object> ranges, long total) {
        List<Object> result = new ArrayList<>();
        long cursor = 0;
        for (Object raw : ranges) {
            List<Object> range = Data.list(raw);
            long start = ((Number) range.get(0)).longValue();
            long end = ((Number) range.get(1)).longValue();
            if (start > cursor) {
                result.add(List.of(cursor, start));
            }
            cursor = Math.max(cursor, end);
        }
        if (cursor < total) {
            result.add(List.of(cursor, total));
        }
        return result;
    }

    public static long apply(
            Map<String, Object> progress,
            Map<String, Object> item,
            Map<String, Object> previous,
            Map<String, Object> current,
            long budget) {
        if (previous.isEmpty() || budget <= 0) {
            return 0;
        }
        long dt = Data.num(current, "elapsedMs", -1) - Data.num(previous, "elapsedMs", -1);
        if (dt <= 0 || dt > MAX_SAMPLE_GAP_MS) {
            return 0;
        }
        Map<String, Object> before = Data.obj(previous.get("payload"));
        Map<String, Object> after = Data.obj(current.get("payload"));
        if (!visible(before)
                || !visible(after)
                || List.of("HIDE", "SEEK", "WAITING", "PAGE_LEAVE", "CLOSE")
                        .contains(Data.str(current, "type"))) {
            return 0;
        }
        long used =
                "MP4".equals(item.get("type"))
                        ? video(progress, item, before, after, current, dt, budget)
                        : document(progress, item, before, after, dt, budget);
        progress.put("effectiveMs", Data.num(progress, "effectiveMs", 0) + used);
        return used;
    }

    private static boolean visible(Map<String, Object> payload) {
        return Data.bool(payload, "appVisible")
                && Data.bool(payload, "pageVisible")
                && !Boolean.FALSE.equals(payload.get("playerVisible"));
    }

    private static long video(
            Map<String, Object> progress,
            Map<String, Object> item,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> current,
            long dt,
            long budget) {
        boolean terminal = List.of("PAUSE", "ENDED").contains(Data.str(current, "type"));
        double rateBefore = Data.decimal(before, "playbackRate", Data.decimal(before, "rate", 1));
        double rateAfter = Data.decimal(after, "playbackRate", Data.decimal(after, "rate", 1));
        if (!Data.bool(before, "playing")
                || (!Data.bool(after, "playing") && !terminal)
                || Data.bool(before, "buffering")
                || Data.bool(after, "buffering")
                || rateBefore != 1
                || rateAfter != 1) {
            return 0;
        }
        long start = Data.num(before, "positionMs", -1);
        long end = Data.num(after, "positionMs", -1);
        long duration = Data.num(item, "durationMs", 0);
        long dp = end - start;
        if (start < 0
                || end > duration
                || dp <= 0
                || dp > SPEED_RATIO * dt + POSITION_TOLERANCE_MS) {
            return 0;
        }
        if (Math.min(dt, dp) > budget + POSITION_TOLERANCE_MS) {
            return 0;
        }
        long used = Math.min(Math.min(dt, dp), budget);
        // Preserve the observed contiguous segment. Session cumulative wall-time bounds prevent
        // repeated tolerance credit; clipping each segment here creates artificial millisecond
        // gaps.
        List<Object> ranges = union(Data.list(progress.get("coveredRanges")), start, end);
        progress.put("coveredRanges", ranges);
        progress.put("coverageRatio", duration == 0 ? 0 : (double) length(ranges) / duration);
        List<Object> gaps = missing(ranges, duration);
        long terminalGap = 0;
        boolean internal = false;
        for (Object raw : gaps) {
            List<Object> pair = Data.list(raw);
            long low = ((Number) pair.get(0)).longValue();
            long high = ((Number) pair.get(1)).longValue();
            if ((low != 0 && high != duration) || high - low > 1000) {
                internal = true;
            }
            terminalGap += high - low;
        }
        progress.put("requirementsMet", !internal && terminalGap <= duration / 100);
        return used;
    }

    private static long document(
            Map<String, Object> progress,
            Map<String, Object> item,
            Map<String, Object> before,
            Map<String, Object> after,
            long dt,
            long budget) {
        long pageNo = Data.num(after, "pageNo", 0);
        List<Object> pages = Data.list(item.get("pages"));
        if (pageNo < 1
                || pageNo > pages.size()
                || pageNo != Data.num(before, "pageNo", 0)
                || !rendered(before)
                || !rendered(after)) {
            return 0;
        }
        Map<String, Object> page = Data.obj(pages.get((int) pageNo - 1));
        long height = Data.num(page, "height", 0);
        long low =
                Math.max(Data.num(before, "visibleTopPx", -1), Data.num(after, "visibleTopPx", -1));
        long high =
                Math.min(
                        Data.num(before, "visibleBottomPx", -1),
                        Data.num(after, "visibleBottomPx", -1));
        if (low < 0 || high <= low || high > height) {
            return 0;
        }
        Map<String, Object> all = Data.obj(progress.get("pageProgress"));
        String pageKey = Long.toString(pageNo);
        Map<String, Object> state = Data.obj(all.get(pageKey));
        long used = Math.min(dt, budget);
        List<Object> ranges = union(Data.list(state.get("ranges")), low, high);
        long visibleMs = Data.num(state, "visibleMs", 0) + used;
        long minimum = Data.num(Data.obj(item.get("rule")), "minPageVisibleMs", 30000);
        state.putAll(
                Data.map(
                        "pageNo",
                        pageNo,
                        "visibleMs",
                        visibleMs,
                        "ranges",
                        ranges,
                        "requirementsMet",
                        visibleMs >= minimum && length(ranges) >= height));
        all.put(pageKey, state);
        progress.put("pageProgress", all);
        long complete =
                all.values().stream()
                        .filter(value -> Data.bool(Data.obj(value), "requirementsMet"))
                        .count();
        progress.put("coverageRatio", pages.isEmpty() ? 0 : (double) complete / pages.size());
        progress.put("requirementsMet", complete == pages.size());
        return used;
    }

    private static boolean rendered(Map<String, Object> payload) {
        return (Data.bool(payload, "rendered") || Data.bool(payload, "renderReady"))
                && !Boolean.FALSE.equals(payload.get("readerUnobscured"));
    }
}
