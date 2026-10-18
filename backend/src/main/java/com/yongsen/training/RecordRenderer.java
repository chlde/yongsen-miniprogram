package com.yongsen.training;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders an allowlisted, escaped human-readable learning record without internal credentials or
 * raw JSON.
 */
public final class RecordRenderer {
    private static final String COMPLETED = "COMPLETED";
    private static final String VIDEO = "MP4";
    private static final String MOCK = "mock";
    private static final String EMPTY = "—";
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final double PERCENT_SCALE = 100.0;
    private static final String FORMULA_PREFIXES = "=+-@";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final Map<String, String> STATES =
            Map.ofEntries(
                    Map.entry("COMPLETED", "已完成本任务学习要求"),
                    Map.entry("READY_TO_CONFIRM", "已满足材料要求，待本人确认"),
                    Map.entry("IN_PROGRESS", "学习中，尚未完成"),
                    Map.entry("NOT_STARTED", "尚未开始"),
                    Map.entry("ACTIVE", "有效"),
                    Map.entry("CANCELLED", "已取消"),
                    Map.entry("CANCELED", "已取消"),
                    Map.entry("WAITING_BINDING", "等待身份核对"),
                    Map.entry("WAITING_SUBSCRIPTION", "等待订阅授权"),
                    Map.entry("READY", "等待发送"),
                    Map.entry("SENDING", "发送中"),
                    Map.entry("ACCEPTED", "平台已受理发送"),
                    Map.entry("FAILED", "发送失败"),
                    Map.entry("UNKNOWN", "结果未知"),
                    Map.entry("NOT_SENT", "尚未发送"),
                    Map.entry("DELIVERED", "已收到可关联的送达回执"),
                    Map.entry("DELIVERY_FAILED", "送达失败"));
    private static final String HEADER =
            """
            <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>永森油服 · 个人培训学习记录</title><style>
            *{box-sizing:border-box}body{margin:0;background:#eef2ef;color:#1e322c;
            font:14px/1.65 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
            main{max-width:1000px;margin:28px auto;padding:38px;background:white}
            h1{font-size:28px;margin:0 0 8px}h2{font-size:18px;margin:28px 0 10px;color:#245744}
            h3{font-size:15px;margin:18px 0 8px}.muted{color:#64726b;font-size:12px}
            .status{padding:12px 16px;background:#edf5ef;border-left:4px solid #387253}
            .boundary{padding:14px;background:#f5f6f2;border:1px solid #d7e0d9}
            table{width:100%;border-collapse:collapse;table-layout:fixed;margin:10px 0}
            th,td{border:1px solid #d7e0d9;padding:9px 11px;text-align:left;vertical-align:top;
            overflow-wrap:anywhere;white-space:pre-wrap}th{background:#f3f6f3;font-weight:600}
            .facts th{width:25%}.hash{font-family:ui-monospace,monospace;font-size:11px}
            footer{border-top:1px solid #d7e0d9;padding-top:16px;margin-top:28px}
            @page{size:A4;margin:15mm}@media print{body{background:white;font-size:11px}
            main{margin:0;padding:0;max-width:none}h1{font-size:23px}h2,h3{break-after:avoid}
            tr,.status,.boundary{break-inside:avoid}thead{display:table-header-group}}
            </style></head><body><main><h1>个人培训学习记录</h1>
            <p class="muted">永森油服 · 学习过程及本人确认记录 · 时间均为北京时间</p>
            """;

    private RecordRenderer() {}

    /**
     * Uses the saved confirmation snapshot for completed records and current progress for
     * unfinished records.
     */
    public static String render(
            Map<String, Object> detail, Map<String, Object> assignment, String providerMode) {
        boolean completed = COMPLETED.equals(assignment.get("status"));
        Map<String, Object> snapshot = Data.obj(assignment.get("confirmationSnapshot"));
        Map<String, Object> record = completed && !snapshot.isEmpty() ? snapshot : detail;
        Map<String, Object> person = Data.obj(assignment.get("personSnapshot"));
        StringBuilder html = new StringBuilder(HEADER);
        html.append("<p class=\"status\">")
                .append(escape(status(assignment.get("status"))))
                .append("</p>");
        html.append("<h2>人员与任务</h2><table class=\"facts\"><tbody>");
        row(html, "记录编号", assignment.get("id"));
        row(
                html,
                "人员编号 / 姓名",
                display(person.get("personNo")) + " / " + display(person.get("name")));
        row(html, "单位 / 项目", display(person.get("unit")) + " / " + display(person.get("project")));
        row(html, "学习任务", record.get("title"));
        row(
                html,
                "任务编号 / 当前状态",
                display(assignment.get("taskId")) + " / " + status(detail.get("taskStatus")));
        row(html, "任务截止时间", time(record.get("dueAt")));
        row(html, "首次进入任务", time(assignment.get("firstEnteredAt")));
        row(
                html,
                "首次 / 最后有效学习",
                time(assignment.get("firstLearningAt"))
                        + " / "
                        + time(assignment.get("lastLearningAt")));
        row(
                html,
                "本人确认 / 完成时间",
                time(assignment.get("confirmedAt")) + " / " + time(assignment.get("completedAt")));
        html.append("</tbody></table><h2>固定学习材料与已确认进度</h2>");
        for (Object raw : Data.list(record.get("materials"))) {
            material(html, Data.obj(raw));
        }
        confirmation(html, record, assignment, completed);
        notifications(html, detail, providerMode);
        html.append("<footer><p>本记录说明系统采集的学习过程与本人确认，不能单独证明持续注意力、实际理解、考试或实操合格，")
                .append("也不自动等同可靠电子签名或取得行业资质。</p>")
                .append("<p class=\"muted\">原始事件、材料原件与发布时规则由系统保留，可按记录编号追溯。生成时间：")
                .append(escape(time(Data.now())))
                .append("。可通过浏览器打印或保存为 PDF。</p></footer></main></body></html>");
        return html.toString();
    }

    private static void material(StringBuilder html, Map<String, Object> material) {
        Map<String, Object> original = Data.obj(material.get("materialSnapshot"));
        Map<String, Object> rule = Data.obj(material.get("rule"));
        Map<String, Object> progress = Data.obj(material.get("progress"));
        boolean video = VIDEO.equals(material.get("type"));
        html.append("<h3>")
                .append(escape(material.get("title")))
                .append("</h3><table class=\"facts\"><tbody>");
        row(
                html,
                "材料类型 / 固定材料编号",
                display(material.get("type")) + " / " + display(material.get("taskMaterialId")));
        row(html, "资料版本编号", material.get("materialId"));
        row(html, "原始文件 SHA-256", original.get("sha256"));
        row(
                html,
                "材料规格",
                video
                        ? seconds(material.get("durationMs"))
                        : display(material.get("pageCount")) + " 页");
        row(
                html,
                "发布时学习规则",
                video
                        ? "按正常 1 倍速观看；仅首尾各不超过 1 秒且合计不超过总时长 1% 的未观察片段可按容差达标，内部缺口须补看"
                        : "逐页完整查看，每页前台有效停留至少 " + seconds(rule.get("minPageVisibleMs")));
        row(
                html,
                "有效过程 / 内容覆盖",
                seconds(progress.get("effectiveMs"))
                        + " / "
                        + percentage(progress.get("coverageRatio")));
        row(html, "材料判定", Data.bool(progress, "requirementsMet") ? "已达到本材料学习要求" : "尚未达到本材料学习要求");
        if (video) {
            row(
                    html,
                    Data.bool(progress, "requirementsMet") ? "首尾容差内未观察片段" : "待补看片段",
                    missingRanges(Data.list(progress.get("missingRangesMs"))));
        }
        html.append("</tbody></table>");
        if (!video) {
            documentPages(html, progress);
        }
    }

    private static void documentPages(StringBuilder html, Map<String, Object> progress) {
        Map<String, Object> pages = Data.obj(progress.get("pageProgress"));
        if (pages.isEmpty()) {
            html.append("<p class=\"muted\">尚无已确认的逐页阅读记录。</p>");
            return;
        }
        html.append("<table><thead><tr><th>页码</th><th>有效停留</th><th>页面判定</th></tr></thead><tbody>");
        pages.entrySet().stream()
                .sorted(
                        Comparator.comparingLong(
                                entry -> Data.num(Data.obj(entry.getValue()), "pageNo", 0)))
                .forEach(
                        entry -> {
                            Map<String, Object> page = Data.obj(entry.getValue());
                            html.append("<tr><td>")
                                    .append(escape(entry.getKey()))
                                    .append("</td><td>")
                                    .append(escape(seconds(page.get("visibleMs"))))
                                    .append("</td><td>")
                                    .append(
                                            Data.bool(page, "requirementsMet")
                                                    ? "已达到页面要求"
                                                    : "需继续阅读或补看区域")
                                    .append("</td></tr>");
                        });
        html.append("</tbody></table>");
    }

    private static void confirmation(
            StringBuilder html,
            Map<String, Object> record,
            Map<String, Object> assignment,
            boolean completed) {
        html.append("<h2>本人确认及证据摘要</h2>");
        if (completed) {
            html.append("<blockquote>")
                    .append(escape(record.get("confirmationText")))
                    .append("</blockquote>");
        } else {
            html.append("<p>尚未提交完成后的本人确认。本页为当前学习过程记录，不是已完成证明。</p>");
        }
        Map<String, Object> binding = Data.obj(assignment.get("bindingSnapshot"));
        html.append("<table class=\"facts\"><tbody>");
        row(html, "确认声明版本", completed ? record.get("confirmationTextVersion") : EMPTY);
        row(html, "确认时身份核对时间", completed ? time(binding.get("checkedAt")) : EMPTY);
        row(html, "本人确认时间", completed ? time(assignment.get("confirmedAt")) : EMPTY);
        row(html, "确认快照 SHA-256", completed ? assignment.get("confirmationHash") : EMPTY);
        row(
                html,
                "快照说明",
                completed ? "以上材料与进度使用本人确认时固定的快照；人员信息为任务发布时快照。" : "当前进度尚未形成完成快照；后续学习会继续更新进度。");
        html.append("</tbody></table><p class=\"muted\">摘要用于与系统保存的快照核对，不代表独立第三方存证。</p>");
    }

    private static void notifications(
            StringBuilder html, Map<String, Object> detail, String providerMode) {
        html.append("<h2>任务通知记录</h2><div class=\"boundary\">");
        if (MOCK.equals(providerMode)) {
            html.append("<strong>LOCAL 本地模拟渠道</strong><p>下列发送、回执和打开记录来自本地联调，")
                    .append("不是真实个人微信收件或微信送达证明。</p>");
        } else {
            html.append("<p>平台受理发送、送达回执与打开任务分别记录。API 成功不等于已送达；")
                    .append("只有可可靠关联的回执可标记送达，未知结果不能视为成功或失败。</p>");
        }
        html.append("</div>");
        List<Object> notices = Data.list(detail.get("notifications"));
        if (notices.isEmpty()) {
            html.append("<p class=\"muted\">本页未提供通知明细，不能据此推断已送达。学习完成判定与通知状态相互独立。</p>");
            return;
        }
        html.append("<table><thead><tr><th>通知编号</th><th>发送状态</th><th>送达状态</th>")
                .append("<th>打开个人任务时间</th></tr></thead><tbody>");
        for (Object raw : notices) {
            Map<String, Object> notice = Data.obj(raw);
            html.append("<tr><td>")
                    .append(escape(notice.get("id")))
                    .append("</td><td>")
                    .append(escape(status(notice.get("sendStatus"))))
                    .append("</td><td>")
                    .append(escape(status(notice.get("deliveryStatus"))))
                    .append("</td><td>")
                    .append(escape(time(notice.get("openedAt"))))
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");
    }

    /**
     * Quotes CSV data and neutralizes spreadsheet formulas, including whitespace-prefixed formulas.
     */
    public static String csvField(Object value) {
        String text = value == null ? "" : value.toString();
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        if (offset < text.length() && FORMULA_PREFIXES.indexOf(text.charAt(offset)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static void row(StringBuilder html, String label, Object value) {
        html.append("<tr><th>")
                .append(escape(label))
                .append("</th><td>")
                .append(escape(display(value)))
                .append("</td></tr>");
    }

    private static String display(Object value) {
        return value == null || value.toString().isBlank() ? EMPTY : value.toString();
    }

    private static String escape(Object value) {
        String text = value == null ? "" : value.toString();
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String time(Object value) {
        if (value == null || value.toString().isBlank()) {
            return EMPTY;
        }
        try {
            return TIME_FORMAT.format(Instant.parse(value.toString()));
        } catch (DateTimeParseException exception) {
            return "时间格式待核对：" + value;
        }
    }

    private static String status(Object value) {
        return STATES.getOrDefault(display(value), display(value));
    }

    private static String seconds(Object value) {
        if (!(value instanceof Number number)) {
            return EMPTY;
        }
        return String.format(Locale.ROOT, "%.1f 秒", number.doubleValue() / MILLIS_PER_SECOND);
    }

    private static String percentage(Object value) {
        return value instanceof Number number
                ? String.format(Locale.ROOT, "%.1f%%", number.doubleValue() * PERCENT_SCALE)
                : EMPTY;
    }

    private static String missingRanges(List<Object> ranges) {
        if (ranges.isEmpty()) {
            return "无未观察片段";
        }
        return ranges.stream()
                .map(Data::list)
                .filter(range -> range.size() == 2)
                .map(range -> seconds(range.get(0)) + "—" + seconds(range.get(1)))
                .collect(java.util.stream.Collectors.joining("；"));
    }
}
