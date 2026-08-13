# 06. DuckDB Cache

## Physical layout

```
${data-cache.duckdb.base-directory}/
├── metadata/
│   └── cache_metadata.duckdb        <- persistent version metadata (MetadataStore)
├── financial/
│   ├── financial_v10.duckdb         <- PREVIOUS
│   └── financial_v11.duckdb         <- ACTIVE
├── organization/
│   ├── organization_v5.duckdb
│   └── organization_v6.duckdb
└── headcount/
    ├── headcount_v3.duckdb
    └── headcount_v4.duckdb
```

Every dataset gets its own subdirectory and its own independent `.duckdb` files - datasets are
never combined into one file, so each has an independent refresh/version lifecycle. This must live
on a persistent volume in OpenShift (never ephemeral container storage) - see
[20-OPENSHIFT-DEPLOYMENT.md](20-OPENSHIFT-DEPLOYMENT.md).

## Connection settings

`DuckDbConnectionFactory.open()` opens `jdbc:duckdb:<absolute-path>` and immediately applies:

```sql
PRAGMA memory_limit='<duckdb.memory-limit>MB';
PRAGMA threads=<duckdb.threads>;
SET temp_directory='<duckdb.temp-directory>';
```

## Bulk ingestion: the DuckDB Appender

`DuckDbDatasetWriter` uses `DuckDBConnection.createAppender(schema, table)` - DuckDB's native
bulk-load API - never per-row `INSERT`/`executeUpdate()`. For each Arrow batch:

```java
for (int row = 0; row < batch.getRowCount(); row++) {
    appender.beginRow();
    for (each column) columnMapping.appender().append(appender, vector, row);  // typed append, or appendNull()
    appender.endRow();
}
```

The appender is flushed every 500,000 rows and on completion; `CHECKPOINT` is run before the file
is finalized. This is the exact API surface verified against `duckdb_jdbc:1.3.2.1` (see
`DuckDBAppender`'s public `append(...)`, `appendNull()`, `appendDecimal`, `append(LocalDate)`,
`append(LocalDateTime)`, `appendEpochMicros`, etc. - all exercised by
`DuckDbDatasetWriterTest`).

## Schema creation

`ArrowToDuckDbTypeMapper` inspects the Arrow `Schema` from the first batch and emits
`CREATE TABLE "<table>" ("col1" TYPE1, "col2" TYPE2, ...)` with quoted identifiers. See
[Arrow -> DuckDB type mapping](#type-mapping) below.

## Type mapping

| Arrow type | DuckDB type | Notes |
|---|---|---|
| `Utf8` / `LargeUtf8` | `VARCHAR` | |
| `Bool` | `BOOLEAN` | |
| `Int(8/16/32/64, signed)` | `TINYINT`/`SMALLINT`/`INTEGER`/`BIGINT` | |
| `Int(8/16/32/64, unsigned)` | widened to `SMALLINT`/`INTEGER`/`BIGINT`/`HUGEINT` | full unsigned range preserved |
| `FloatingPoint(SINGLE/DOUBLE)` | `FLOAT`/`DOUBLE` | |
| `Decimal(p, s)` | `DECIMAL(p, s)` | precision/scale preserved exactly; rejected if `p > 38` |
| `Date(DAY/MILLISECOND)` | `DATE` | |
| `Timestamp(unit, tz=null)` | `TIMESTAMP` | seconds/millis/micros/nanos all supported |
| `Timestamp(unit, tz=set)` | `TIMESTAMPTZ` | millis/micros with time zone |

Nulls are preserved for every type via `appender.appendNull()`. Any other Arrow type (List,
Struct, Map, Binary, ...) raises `UnsupportedArrowTypeException` with the column name and Arrow
type in the message - it never silently degrades to `VARCHAR`. If you hit this, adjust the source
SQL (e.g. `CAST(...)`) rather than the mapper.

## Why the Appender, not `registerArrowStream`

DuckDB JDBC also exposes `DuckDBConnection.registerArrowStream(name, arrowArrayStream)`, which
registers an Arrow C Data Interface stream as a virtual table. This was deliberately **not** used:
its expected argument shape is internal/JNI-specific and not part of the documented public
contract, so using it would mean guessing at an unstable API. The Appender API is fully public,
typed, documented, and directly verified against the exact dependency version in this project -
see `DuckDbDatasetWriterTest` for a real round-trip test covering every mapped type including
nulls.
