package com.yongsen.training;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RecordRendererTest {
    @Test
    void escapesUserTextAndKeepsInternalFieldsOutOfPrint() {
        Map<String, Object> detail =
                Data.map(
                        "title",
                        "<img src=x onerror=alert(1)>",
                        "status",
                        "IN_PROGRESS",
                        "taskStatus",
                        "ACTIVE",
                        "materials",
                        List.of(),
                        "encryptedOpenid",
                        "secret-openid",
                        "token",
                        "secret-token",
                        "objectKey",
                        "/private/files/original");
        Map<String, Object> assignment =
                Data.map(
                        "id",
                        "record-1",
                        "status",
                        "IN_PROGRESS",
                        "personSnapshot",
                        Data.map("name", "张三 & 李四", "personNo", "001"));

        String html = RecordRenderer.render(detail, assignment, "mock");

        assertThat(html)
                .contains("&lt;img src=x onerror=alert(1)&gt;", "张三 &amp; 李四", "LOCAL 本地模拟渠道");
        assertThat(html)
                .doesNotContain(
                        "<img",
                        "secret-openid",
                        "secret-token",
                        "/private/files/original",
                        "<pre>");
        assertThat(html).contains("不是已完成证明", "尚未提交完成后的本人确认");
    }

    @Test
    void printsConfirmedSnapshotAndCurrentNotificationFactsSeparately() {
        Map<String, Object> snapshot =
                Data.map(
                        "title",
                        "确认时任务",
                        "status",
                        "READY_TO_CONFIRM",
                        "confirmationText",
                        "我已完成学习",
                        "confirmationTextVersion",
                        "mvp-1",
                        "materials",
                        List.of(
                                Data.map(
                                        "title",
                                        "安全视频",
                                        "type",
                                        "MP4",
                                        "durationMs",
                                        12000L,
                                        "taskMaterialId",
                                        "fixed-1",
                                        "materialId",
                                        "version-1",
                                        "rule",
                                        Map.of("playbackRate", 1),
                                        "materialSnapshot",
                                        Data.map("filename", "training.mp4", "sha256", "file-sha"),
                                        "progress",
                                        Data.map(
                                                "effectiveMs",
                                                12000L,
                                                "coverageRatio",
                                                1.0,
                                                "requirementsMet",
                                                true))));
        Map<String, Object> assignment =
                Data.map(
                        "id",
                        "record-2",
                        "status",
                        "COMPLETED",
                        "confirmationSnapshot",
                        snapshot,
                        "confirmationHash",
                        "snapshot-sha",
                        "personSnapshot",
                        Data.map("name", "王五"),
                        "confirmedAt",
                        "2026-10-16T10:00:00Z");
        Map<String, Object> detail =
                Data.map(
                        "title",
                        "后来的标题",
                        "taskStatus",
                        "CANCELLED",
                        "notifications",
                        List.of(
                                Data.map(
                                        "id",
                                        "notice-1",
                                        "sendStatus",
                                        "ACCEPTED",
                                        "deliveryStatus",
                                        "UNKNOWN")));

        String html = RecordRenderer.render(detail, assignment, "real");

        assertThat(html).contains("确认时任务", "已完成本任务学习要求", "我已完成学习", "snapshot-sha", "file-sha");
        assertThat(html).contains("2026-10-16 18:00:00", "结果未知", "API 成功不等于已送达", "100.0%");
        assertThat(html).doesNotContain("后来的标题", "尚未提交完成后的本人确认", "LOCAL 本地模拟渠道");
        assertThat(html).contains("资料版本编号", "version-1").doesNotContain("原始文件 / 资料版本编号", "training.mp4");
    }

    @Test
    void keepsObservedCoverageAndLabelsAcceptedTerminalTolerance() {
        Map<String, Object> progress =
                Data.map(
                        "effectiveMs", 11940L,
                        "coverageRatio", 0.995,
                        "requirementsMet", true,
                        "missingRangesMs", List.of(List.of(11940L, 12000L)));
        Map<String, Object> material =
                Data.map("title", "12秒视频", "type", "MP4", "durationMs", 12000L, "progress", progress);
        Map<String, Object> detail = Data.map("materials", List.of(material));
        Map<String, Object> assignment = Data.map("id", "terminal-record", "status", "IN_PROGRESS");

        String html = RecordRenderer.render(detail, assignment, "mock");

        assertThat(html).contains("首尾各不超过 1 秒", "合计不超过总时长 1%", "内部缺口须补看");
        assertThat(html).contains("99.5%", "首尾容差内未观察片段", "11.9 秒—12.0 秒", "已达到本材料学习要求");
        assertThat(html).doesNotContain("100.0%", "<th>待补看片段</th>");
    }

    @Test
    void labelsUnmetVideoGapsAsRequiringFurtherViewing() {
        Map<String, Object> progress =
                Data.map(
                        "effectiveMs", 11500L,
                        "coverageRatio", 0.958,
                        "requirementsMet", false,
                        "missingRangesMs", List.of(List.of(8000L, 8500L)));
        Map<String, Object> material =
                Data.map("title", "存在内部缺口的视频", "type", "MP4", "durationMs", 12000L, "progress", progress);
        Map<String, Object> detail = Data.map("materials", List.of(material));
        Map<String, Object> assignment = Data.map("id", "gap-record", "status", "IN_PROGRESS");

        String html = RecordRenderer.render(detail, assignment, "mock");

        assertThat(html).contains("<th>待补看片段</th>", "8.0 秒—8.5 秒", "尚未达到本材料学习要求");
        assertThat(html).doesNotContain("首尾容差内未观察片段", "100.0%");
    }

    @Test
    void neutralizesFormulaPrefixesAfterWhitespaceAndControls() {
        for (String value :
                List.of("=1+1", " +SUM(1,2)", "\t@SUM(1)", "\n\r-1", "\u00a0=1", "\ufeff=1")) {
            assertThat(RecordRenderer.csvField(value)).startsWith("\"'").endsWith("\"");
        }
        assertThat(RecordRenderer.csvField("普通姓名")).isEqualTo("\"普通姓名\"");
        assertThat(RecordRenderer.csvField("a,\"b\"\n行")).isEqualTo("\"a,\"\"b\"\"\n行\"");
        assertThat(RecordRenderer.csvField(null)).isEqualTo("\"\"");
    }
}
