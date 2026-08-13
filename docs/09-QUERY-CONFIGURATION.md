# 09. Query Configuration

```yaml
data-cache:
  queries:
    cfo-summary:
      sql: classpath:datacache/query/cfo-summary.sql
      datasets:
        - financial
        - organization
      max-page-size: 2000   # optional; overrides the global pagination.max-page-size for this query
```

Query names are never hardcoded in Java - `QueryRegistry` builds `QueryDefinition`s from this
configuration at startup, resolving the SQL resource and pre-parsing its named parameters
(`NamedParameterSqlBinder`). Requesting an unregistered name raises `QueryNotFoundException`
(HTTP 404) rather than falling through to arbitrary SQL execution - there is no way to submit
ad-hoc SQL through either the Java or REST surface.

## `datasets`

Every entry must be an **enabled** key under `data-cache.datasets`
(`DataCacheConfigurationValidator` rejects unknown or disabled references at startup). This list
drives which versions get pinned and attached before the query runs - see
[07-REFRESH-AND-VERSIONING.md](07-REFRESH-AND-VERSIONING.md#query-version-pinning).

## Adding a query

See [13-ADDING-A-NEW-QUERY.md](13-ADDING-A-NEW-QUERY.md) - one SQL file plus one YAML entry, no
Java.
