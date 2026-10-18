package com.yongsen.training;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Parameterized SQL repository. Named domain columns are authoritative over snapshots. */
@Repository
public class Store {
    public enum Table {
        ADMIN_USER,
        PERSON,
        WX_IDENTITY_BINDING,
        MATERIAL,
        TASK,
        TASK_MATERIAL,
        ASSIGNMENT,
        LEARNING_SESSION,
        LEARNING_EVENT,
        LEARNING_PROGRESS,
        NOTIFICATION,
        NOTIFICATION_ATTEMPT,
        SUBSCRIPTION_RECORD,
        WECHAT_CALLBACK,
        AUDIT_LOG,
        APP_CONFIG,
        AUTH_TOKEN,
        MEDIA_TICKET,
        MOCK_CARD
    }

    private static final String BASE_COLUMNS =
            "id, ref_id, ref2, unique_key, snapshot_json, row_version, created_at";
    private final JdbcTemplate jdbc;

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StoredRecordDO get(Table table, String id) {
        return one(table, "id = ?", id, false);
    }

    public StoredRecordDO lock(Table table, String id) {
        return one(table, "id = ?", id, true);
    }

    public StoredRecordDO byKey(Table table, String key) {
        return one(table, "unique_key = ?", key, false);
    }

    public java.util.Optional<StoredRecordDO> findKey(Table table, String key) {
        List<StoredRecordDO> rows = select(table, " WHERE unique_key = ?", key);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    public List<StoredRecordDO> eventsAfter(String sessionId, long sequence) {
        return select(
                Table.LEARNING_EVENT,
                " WHERE ref_id = ? AND seq > ? ORDER BY seq LIMIT 200",
                sessionId,
                sequence);
    }

    public StoredRecordDO maybe(Table table, String id) {
        List<StoredRecordDO> rows = select(table, " WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private StoredRecordDO one(Table table, String predicate, String value, boolean lock) {
        List<StoredRecordDO> rows =
                select(table, " WHERE " + predicate + (lock ? " FOR UPDATE" : ""), value);
        if (rows.isEmpty()) {
            throw new BusinessException("NOT_FOUND", "Record not found", 404);
        }
        return rows.get(0);
    }

    public List<StoredRecordDO> all(Table table) {
        return select(table, " ORDER BY created_at DESC");
    }

    public List<StoredRecordDO> refs(Table table, String reference) {
        return select(table, " WHERE ref_id = ? ORDER BY created_at", reference);
    }

    public List<StoredRecordDO> status(Table table, String state) {
        return select(table, " WHERE record_status = ? ORDER BY created_at", state);
    }

    private List<StoredRecordDO> select(Table table, String suffix, Object... parameters) {
        List<RecordColumns.Column> columns = RecordColumns.forTable(table);
        String names =
                columns.stream()
                        .map(RecordColumns.Column::sqlName)
                        .collect(Collectors.joining(", "));
        List<StoredRecordDO> rows =
                jdbc.query(
                        "SELECT "
                                + BASE_COLUMNS
                                + ", "
                                + names
                                + " FROM "
                                + table.name().toLowerCase()
                                + suffix,
                        (result, row) -> readRow(table, result),
                        parameters);
        return rows;
    }

    private StoredRecordDO readRow(Table table, ResultSet result) throws SQLException {
        return new StoredRecordDO(
                result.getString("id"),
                result.getString("ref_id"),
                result.getString("ref2"),
                result.getString("unique_key"),
                result.getString("snapshot_json"),
                DomainRecords.read(table, result),
                result.getLong("row_version"),
                result.getTimestamp("created_at").toInstant());
    }

    public Map<String, Object> create(
            Table table,
            Map<String, Object> body,
            String reference,
            String reference2,
            String status,
            String uniqueKey) {
        String id = body.containsKey("id") ? Data.str(body, "id") : Data.id();
        body.putAll(Data.map("id", id, "_ref", reference, "_ref2", reference2, "_key", uniqueKey));
        List<String> names =
                new ArrayList<>(
                        List.of(
                                "id",
                                "ref_id",
                                "ref2",
                                "record_status",
                                "unique_key",
                                "snapshot_json",
                                "row_version",
                                "created_at",
                                "updated_at"));
        Timestamp now = Timestamp.from(Instant.now());
        List<Object> args = new ArrayList<>();
        java.util.Collections.addAll(
                args,
                id,
                reference,
                reference2,
                status,
                uniqueKey,
                snapshot(table, body),
                1L,
                now,
                now);
        appendDomain(table, body, names, args);
        String marks = names.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        jdbc.update(
                "INSERT INTO "
                        + table.name().toLowerCase()
                        + " ("
                        + String.join(", ", names)
                        + ") VALUES ("
                        + marks
                        + ")",
                args.toArray());
        body.putAll(
                Data.map(
                        "rowVersion",
                        1L,
                        "createdAt",
                        now.toInstant().toString(),
                        "_createdMs",
                        now.getTime()));
        return body;
    }

    private String snapshot(Table table, Map<String, Object> body) {
        Map<String, Object> snapshot = new LinkedHashMap<>(body);
        snapshot.keySet()
                .removeIf(
                        key ->
                                key.startsWith("_")
                                        || List.of("id", "rowVersion", "createdAt").contains(key));
        RecordColumns.forTable(table).forEach(column -> snapshot.remove(column.field()));
        return Data.json(snapshot);
    }

    private void appendDomain(
            Table table, Map<String, Object> body, List<String> names, List<Object> args) {
        for (RecordColumns.Column column : RecordColumns.forTable(table)) {
            names.add(column.sqlName());
            Object value = body.get(column.field());
            if (value != null && "J".equals(column.kind())) {
                value = Data.json(value);
            } else if (value != null && "I".equals(column.kind())) {
                value =
                        value.toString().isBlank()
                                ? null
                                : Timestamp.from(Instant.parse(value.toString()));
            }
            args.add(value);
        }
        if (table == Table.WX_IDENTITY_BINDING) {
            names.add("active_identity_key");
            args.add(
                    List.of("ACTIVE", "PENDING").contains(Data.str(body, "status"))
                            ? body.get("identityId")
                            : null);
        }
    }

    public void save(Table table, Map<String, Object> body, String status) {
        List<String> names =
                new ArrayList<>(
                        List.of("snapshot_json", "record_status", "unique_key", "updated_at"));
        List<Object> args = new ArrayList<>();
        java.util.Collections.addAll(
                args,
                snapshot(table, body),
                status,
                body.get("_key"),
                Timestamp.from(Instant.now()));
        appendDomain(table, body, names, args);
        String sets = names.stream().map(name -> name + " = ?").collect(Collectors.joining(", "));
        long version = Data.num(body, "rowVersion", 1L);
        args.add(body.get("id"));
        args.add(version);
        int count =
                jdbc.update(
                        "UPDATE "
                                + table.name().toLowerCase()
                                + " SET "
                                + sets
                                + ", row_version = row_version + 1 WHERE id = ? AND row_version ="
                                + " ?",
                        args.toArray());
        if (count != 1) {
            throw new BusinessException(
                    "VERSION_CONFLICT", "Record changed; refresh and retry", 409);
        }
        body.put("rowVersion", version + 1L);
    }

    public void audit(String actor, String action, String target, Object detail) {
        create(
                Table.AUDIT_LOG,
                Data.map(
                        "actorId",
                        actor,
                        "action",
                        action,
                        "targetId",
                        target,
                        "occurredAt",
                        Data.now(),
                        "detail",
                        detail),
                target,
                actor,
                action,
                null);
    }

    public static Map<String, Object> publicView(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")
                    || List.of(
                                    "openid",
                                    "encryptedOpenid",
                                    "identityId",
                                    "passwordHash",
                                    "inviteHash",
                                    "nonceHash",
                                    "ticketHash",
                                    "uploadHash",
                                    "uploadKey",
                                    "archiveKey",
                                    "archiveSourceETag",
                                    "storageMode",
                                    "processClaim",
                                    "processLeaseUntilMs",
                                    "processingFence",
                                    "uploadInProgress",
                                    "uploadExpiresMs",
                                    "inviteExpiresMs",
                                    "inviteConsumed",
                                    "originalKey",
                                    "objectKey",
                                    "fileKey",
                                    "path",
                                    "key")
                            .contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                value = publicView(Data.obj(value));
            } else if (value instanceof List<?>) {
                value =
                        Data.list(value).stream()
                                .map(
                                        item ->
                                                item instanceof Map<?, ?>
                                                        ? publicView(Data.obj(item))
                                                        : item)
                                .toList();
            }
            result.put(key, value);
        }
        return result;
    }
}
