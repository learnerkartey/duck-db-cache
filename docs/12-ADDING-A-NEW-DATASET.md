# 12. Adding a New Dataset

Worked example: adding an `expense` dataset. **No Java class of any kind is required** - no new
Entity, Repository, Loader, Scheduler, Service, or Controller.

## 1. Create the source SQL

`data-cache-app/src/main/resources/datacache/dremio/expense.sql`:

```sql
SELECT
    fiscal_year,
    fiscal_month,
    cost_center,
    expense_category,
    expense_amount
FROM finance.expense_data
```

## 2. Register the dataset

`data-cache-app/src/main/resources/application.yml`:

```yaml
data-cache:
  datasets:
    expense:
      enabled: true
      table-name: expense
      source-sql: classpath:datacache/dremio/expense.sql
      refresh-cron: "0 45 1,7,13,19 * * *"
      load-on-startup: false
      retry:
        max-attempts: 3
        initial-delay: 10s
        multiplier: 2
        max-delay: 60s
      validation:
        minimum-row-count: 1
        required-columns:
          - fiscal_year
          - cost_center
          - expense_amount
```

## 3. Trigger a refresh

```bash
curl -X POST localhost:8080/api/v1/cache/admin/datasets/expense/refresh
```

or wait for its `refresh-cron` to fire - `DynamicRefreshScheduler` picks it up automatically on
the next application restart (or immediately, if the scheduler bean is (re)created - in practice,
restart the app after a config change).

## 4. Verify

```bash
curl localhost:8080/api/v1/cache/admin/datasets/expense
```

## 5. Use it in a query

```sql
SELECT cost_center, SUM(expense_amount) AS total_expense
FROM expense
WHERE fiscal_year = :fiscalYear
GROUP BY cost_center
```

Register per [13-ADDING-A-NEW-QUERY.md](13-ADDING-A-NEW-QUERY.md). That's the entire process.
