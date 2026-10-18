package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class LearningMathTest {
    @Test
    void intervalUnionNeverDoubleCountsAndPreservesInternalGaps() {
        List<Object> ranges = LearningMath.union(List.of(), 0, 5000);
        ranges = LearningMath.union(ranges, 1000, 3000);
        ranges = LearningMath.union(ranges, 7000, 9000);
        assertEquals(7000, LearningMath.length(ranges));
        assertEquals(
                List.of(List.of(5000L, 7000L), List.of(9000L, 10000L)),
                LearningMath.missing(ranges, 10000));
    }

    @Test
    void boundedNetworkJitterDoesNotInventTinyInteriorGaps() {
        Map<String, Object> progress = Data.map("coveredRanges", List.of(), "effectiveMs", 0L);
        Map<String, Object> item = Data.map("type", "MP4", "durationMs", 2000L);
        LearningMath.apply(progress, item, sample(0, 0, true), sample(1000, 1000, true), 998);
        LearningMath.apply(progress, item, sample(1000, 1000, true), sample(2000, 2000, true), 999);
        assertTrue(Data.bool(progress, "requirementsMet"));
        assertEquals(1997, Data.num(progress, "effectiveMs", -1));
        assertEquals(List.of(List.of(0L, 2000L)), progress.get("coveredRanges"));
    }

    @Test
    void jumpsHiddenAndExcessivelyOldSamplesCannotCompleteVideo() {
        Map<String, Object> progress = Data.map("coveredRanges", List.of(), "effectiveMs", 0L);
        Map<String, Object> item = Data.map("type", "MP4", "durationMs", 12000L);
        assertEquals(
                0,
                LearningMath.apply(
                        progress, item, sample(0, 0, true), sample(1000, 12000, true), 1000));
        assertEquals(
                0,
                LearningMath.apply(
                        progress, item, sample(0, 0, true), sample(1000, 1000, false), 1000));
        assertEquals(
                0,
                LearningMath.apply(
                        progress, item, sample(0, 0, true), sample(5000, 5000, true), 5000));
        assertFalse(Data.bool(progress, "requirementsMet"));
    }

    @Test
    void pdfRequiresTimeAndEntireAreaAndDoesNotCountPagesInParallel() {
        Map<String, Object> item =
                Data.map(
                        "type",
                        "PDF",
                        "pages",
                        List.of(Data.map("height", 1000)),
                        "rule",
                        Data.map("minPageVisibleMs", 2000L));
        Map<String, Object> progress = Data.map("pageProgress", Data.map(), "effectiveMs", 0L);
        Map<String, Object> first = doc(0, 0, 500);
        Map<String, Object> second = doc(1000, 0, 500);
        LearningMath.apply(progress, item, first, second, 1000);
        LearningMath.apply(progress, item, second, doc(2000, 0, 500), 1000);
        assertFalse(Data.bool(progress, "requirementsMet"));
        LearningMath.apply(progress, item, doc(3000, 500, 1000), doc(4000, 500, 1000), 1000);
        assertTrue(Data.bool(progress, "requirementsMet"));
        assertEquals(3000L, Data.num(progress, "effectiveMs", 0));
    }

    private Map<String, Object> sample(long elapsed, long position, boolean visible) {
        return Data.map(
                "type",
                "VIDEO_SAMPLE",
                "elapsedMs",
                elapsed,
                "payload",
                Data.map(
                        "positionMs",
                        position,
                        "playing",
                        true,
                        "playbackRate",
                        1,
                        "buffering",
                        false,
                        "appVisible",
                        visible,
                        "pageVisible",
                        true));
    }

    private Map<String, Object> doc(long elapsed, long top, long bottom) {
        return Data.map(
                "type",
                "DOC_SAMPLE",
                "elapsedMs",
                elapsed,
                "payload",
                Data.map(
                        "pageNo",
                        1,
                        "rendered",
                        true,
                        "visibleTopPx",
                        top,
                        "visibleBottomPx",
                        bottom,
                        "appVisible",
                        true,
                        "pageVisible",
                        true));
    }
}
