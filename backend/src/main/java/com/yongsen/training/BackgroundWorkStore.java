package com.yongsen.training;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

/**
 * Durable event work. Lease tokens fence stale invocations; generations retain concurrent wakes.
 */
@Repository
public class BackgroundWorkStore {
    public record Work(
            String id,
            String kind,
            String targetId,
            String status,
            long generation,
            long runningGeneration,
            String cursor,
            long leaseUntil,
            String leaseToken,
            int attempts,
            String errorCode,
            long updatedMs) {
        @Override
        public String toString() {
            return "Work[id=" + id + ", kind=" + kind + ", status=" + status + "]";
        }
    }

    private static final String COLUMNS =
            "id, kind, target_id, status, generation, running_generation, "
                    + "cursor_id, lease_until_ms, lease_token, attempts, error_code, updated_ms";
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BackgroundWorkStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String enqueue(String kind, String targetId) {
        String id = Data.hash(kind + ":" + targetId);
        jdbc.update(
                """
                INSERT INTO background_work
                  (id, kind, target_id, status, generation, running_generation, cursor_id,
                   lease_until_ms, lease_token, attempts, error_code, updated_ms)
                VALUES (?, ?, ?, 'PENDING', 1, 0, '', 0, '', 0, '', ?)
                ON DUPLICATE KEY UPDATE generation = generation + 1,
                  cursor_id = CASE WHEN status = 'RUNNING' THEN cursor_id ELSE '' END,
                  attempts = CASE WHEN status = 'RUNNING' THEN attempts ELSE 0 END,
                  status = CASE WHEN status = 'RUNNING' THEN status ELSE 'PENDING' END,
                  updated_ms = ?
                """,
                id,
                kind,
                targetId,
                clock.millis(),
                clock.millis());
        return id;
    }

    public Work get(String id, boolean lock) {
        List<Work> rows =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM background_work WHERE id = ?"
                                + (lock ? " FOR UPDATE" : ""),
                        this::read,
                        id);
        if (rows.isEmpty()) {
            throw new BusinessException("NOT_FOUND", "Background work not found", 404);
        }
        return rows.get(0);
    }

    private Work read(ResultSet row, int ignored) throws SQLException {
        return new Work(
                row.getString("id"),
                row.getString("kind"),
                row.getString("target_id"),
                row.getString("status"),
                row.getLong("generation"),
                row.getLong("running_generation"),
                row.getString("cursor_id"),
                row.getLong("lease_until_ms"),
                row.getString("lease_token"),
                row.getInt("attempts"),
                row.getString("error_code"),
                row.getLong("updated_ms"));
    }

    public Work claim(String id) {
        Work row = get(id, true);
        if (List.of("DONE", "FAILED").contains(row.status())) {
            return row;
        }
        if ("RUNNING".equals(row.status()) && row.leaseUntil() > clock.millis()) {
            return new Work(
                    row.id(),
                    row.kind(),
                    row.targetId(),
                    "BUSY",
                    row.generation(),
                    row.runningGeneration(),
                    row.cursor(),
                    row.leaseUntil(),
                    "",
                    row.attempts(),
                    row.errorCode(),
                    row.updatedMs());
        }
        String token = Data.token();
        jdbc.update(
                """
                UPDATE background_work SET status = 'RUNNING', lease_token = ?, lease_until_ms = ?,
                  cursor_id = CASE WHEN generation <> running_generation THEN '' ELSE cursor_id END,
                  running_generation = generation, attempts = attempts + 1, updated_ms = ? WHERE id = ?
                """,
                token,
                clock.millis() + 1200000,
                clock.millis(),
                id);
        return get(id, false);
    }

    public boolean finish(Work claimed, String cursor, boolean more) {
        Work current = get(claimed.id(), true);
        if (!claimed.leaseToken().equals(current.leaseToken())) {
            return false;
        }
        boolean dirty = current.generation() != claimed.runningGeneration();
        String nextCursor = dirty ? "" : cursor;
        boolean pending = dirty || more;
        jdbc.update(
                """
                UPDATE background_work SET status = ?, cursor_id = ?, lease_until_ms = 0,
                  lease_token = '', attempts = 0, error_code = '', updated_ms = ? WHERE id = ?
                """,
                pending ? "PENDING" : "DONE",
                nextCursor,
                clock.millis(),
                claimed.id());
        return pending;
    }

    public String fail(Work claimed, String code) {
        Work current = get(claimed.id(), true);
        if (!claimed.leaseToken().equals(current.leaseToken())) {
            return "busy";
        }
        String status = claimed.attempts() >= 5 ? "FAILED" : "RETRY";
        jdbc.update(
                """
                UPDATE background_work SET status = ?, lease_until_ms = 0, lease_token = '',
                  error_code = ?, updated_ms = ? WHERE id = ?
                """,
                status,
                code,
                clock.millis(),
                claimed.id());
        return "FAILED".equals(status) ? "failed" : "retry";
    }

    public void dispatchResult(String id, boolean accepted) {
        jdbc.update(
                """
                UPDATE background_work SET status = ?, error_code = ?, updated_ms = ?
                  WHERE id = ? AND status IN ('PENDING', 'RETRY', 'DISPATCHED')
                """,
                accepted ? "DISPATCHED" : "RETRY",
                accepted ? "" : "DISPATCH_FAILED",
                clock.millis(),
                id);
    }

    public void retry(String id) {
        Work row = get(id, true);
        Data.require(
                !"RUNNING".equals(row.status()) || row.leaseUntil() <= clock.millis(),
                "WORK_BUSY",
                "Background work is still running");
        Data.require(
                !"DONE".equals(row.status()), "WORK_COMPLETE", "Background work already completed");
        jdbc.update(
                """
                UPDATE background_work SET status = 'PENDING', cursor_id = '', attempts = 0, lease_until_ms = 0,
                  lease_token = '', error_code = '', updated_ms = ? WHERE id = ?
                """,
                clock.millis(),
                id);
    }

    public List<Work> list(int limit) {
        return list(Math.max(1, Math.min(limit, 100)), 0);
    }

    /** Includes one lookahead row when called with pageSize + 1 by the controller. */
    public List<Work> list(int limit, int offset) {
        Data.require(
                limit >= 1 && limit <= 101 && offset >= 0 && offset <= 100000,
                "INVALID_PAGINATION",
                "Background work pagination is outside the allowed range");
        return jdbc.query(
                "SELECT "
                        + COLUMNS
                        + " FROM background_work"
                        + " ORDER BY CASE WHEN status = 'DONE' THEN 1 ELSE 0 END,"
                        + " updated_ms DESC, id ASC LIMIT ? OFFSET ?",
                this::read,
                limit,
                offset);
    }
}
