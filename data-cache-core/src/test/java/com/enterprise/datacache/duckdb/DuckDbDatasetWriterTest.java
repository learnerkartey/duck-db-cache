package com.enterprise.datacache.duckdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.config.DuckDbProperties;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDbDatasetWriterTest {

    @TempDir
    Path tempDir;

    private RootAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    private Schema buildSchema() {
        return new Schema(List.of(
                new Field("id", FieldType.notNullable(new ArrowType.Int(32, true)), null),
                new Field("name", FieldType.nullable(new ArrowType.Utf8()), null),
                new Field("active", FieldType.nullable(new ArrowType.Bool()), null),
                new Field("amount", FieldType.nullable(new ArrowType.Decimal(12, 2, 128)), null),
                new Field("score", FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
                new Field("as_of_date", FieldType.nullable(new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY)), null),
                new Field("created_at", FieldType.nullable(new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)), null),
                new Field("big_id", FieldType.nullable(new ArrowType.Int(64, true)), null)));
    }

    @Test
    void writesBatchesAndPreservesTypesAndNulls() throws Exception {
        Schema schema = buildSchema();
        Path dbFile = tempDir.resolve("financial").resolve("financial_v1.duckdb");
        DuckDbProperties props = new DuckDbProperties();
        props.setBaseDirectory(tempDir.toString());
        props.setTempDirectory(tempDir.resolve("temp").toString());

        try (DuckDbDatasetWriter writer = new DuckDbDatasetWriter(dbFile, "financial", props)) {
            writer.begin(schema);

            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
                root.allocateNew();
                IntVector id = (IntVector) root.getVector("id");
                VarCharVector name = (VarCharVector) root.getVector("name");
                BitVector active = (BitVector) root.getVector("active");
                DecimalVector amount = (DecimalVector) root.getVector("amount");
                Float8Vector score = (Float8Vector) root.getVector("score");
                DateDayVector asOfDate = (DateDayVector) root.getVector("as_of_date");
                TimeStampMicroVector createdAt = (TimeStampMicroVector) root.getVector("created_at");
                BigIntVector bigId = (BigIntVector) root.getVector("big_id");

                // Row 0: fully populated.
                id.setSafe(0, 1);
                name.setSafe(0, "alpha".getBytes());
                active.setSafe(0, 1);
                amount.setSafe(0, new BigDecimal("1234.56").unscaledValue().longValue());
                score.setSafe(0, 3.14);
                asOfDate.setSafe(0, (int) LocalDate.of(2026, 1, 15).toEpochDay());
                createdAt.setSafe(0, LocalDateTime.of(2026, 1, 15, 10, 30, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L);
                bigId.setSafe(0, 9_000_000_000L);

                // Row 1: every nullable column is null.
                id.setSafe(1, 2);
                name.setNull(1);
                active.setNull(1);
                amount.setNull(1);
                score.setNull(1);
                asOfDate.setNull(1);
                createdAt.setNull(1);
                bigId.setNull(1);

                root.setRowCount(2);
                for (FieldVector v : root.getFieldVectors()) {
                    v.setValueCount(2);
                }
                writer.writeBatch(root);
            }

            long written = writer.finish();
            assertThat(written).isEqualTo(2);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbFile.toAbsolutePath());
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM financial ORDER BY id")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("id")).isEqualTo(1);
            assertThat(rs.getString("name")).isEqualTo("alpha");
            assertThat(rs.getBoolean("active")).isTrue();
            assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("1234.56");
            assertThat(rs.getDouble("score")).isEqualTo(3.14);
            assertThat(rs.getDate("as_of_date").toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(rs.getLong("big_id")).isEqualTo(9_000_000_000L);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("id")).isEqualTo(2);
            assertThat(rs.getString("name")).isNull();
            rs.getBoolean("active");
            assertThat(rs.wasNull()).isTrue();
            assertThat(rs.getBigDecimal("amount")).isNull();
            assertThat(rs.getObject("score")).isNull();
            assertThat(rs.getDate("as_of_date")).isNull();
            assertThat(rs.getObject("big_id")).isNull();

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void createsSeparateFilePerDataset() {
        Schema schema = new Schema(List.of(new Field("id", FieldType.notNullable(new ArrowType.Int(32, true)), null)));
        Path dbFile = tempDir.resolve("organization").resolve("organization_v3.duckdb");
        DuckDbProperties props = new DuckDbProperties();
        props.setTempDirectory(tempDir.resolve("temp2").toString());

        try (DuckDbDatasetWriter writer = new DuckDbDatasetWriter(dbFile, "organization", props)) {
            writer.begin(schema);
            writer.finish();
        }

        assertThat(dbFile).exists();
    }
}
