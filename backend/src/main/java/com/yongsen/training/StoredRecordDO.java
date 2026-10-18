package com.yongsen.training;

import java.time.Instant;
import java.util.Map;

/** Typed SQL query result; services explicitly project it into their JSON boundary model. */
public record StoredRecordDO(
        String id,
        String referenceId,
        String secondReferenceId,
        String uniqueKey,
        String snapshotJson,
        DomainRecords.DomainData domainData,
        long rowVersion,
        Instant createdAt) {
    public Map<String, Object> toBoundary() {
        Map<String, Object> result = Data.parse(snapshotJson);
        Map<String, Object> domain = Data.parse(Data.json(domainData));
        domain.values().removeIf(java.util.Objects::isNull);
        result.putAll(domain);
        result.putAll(
                Data.map(
                        "id",
                        id,
                        "_ref",
                        referenceId,
                        "_ref2",
                        secondReferenceId,
                        "_key",
                        uniqueKey,
                        "rowVersion",
                        rowVersion,
                        "_createdMs",
                        createdAt.toEpochMilli(),
                        "createdAt",
                        createdAt.toString()));
        return result;
    }
}
