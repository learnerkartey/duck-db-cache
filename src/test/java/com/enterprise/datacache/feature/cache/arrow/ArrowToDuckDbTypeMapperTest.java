package com.enterprise.datacache.feature.cache.arrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.datacache.feature.cache.exception.DecimalTypeNotSupportedException;
import com.enterprise.datacache.feature.cache.exception.UnsupportedArrowTypeException;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.Test;

class ArrowToDuckDbTypeMapperTest {

    private static final String DATASET = "financial";

    @Test
    void mapsAllSupportedScalarTypes() {
        assertThat(map(field("a", new ArrowType.Utf8())).duckDbType()).isEqualTo("VARCHAR");
        assertThat(map(field("a", new ArrowType.Bool())).duckDbType()).isEqualTo("BOOLEAN");
        assertThat(map(field("a", new ArrowType.Int(8, true))).duckDbType()).isEqualTo("TINYINT");
        assertThat(map(field("a", new ArrowType.Int(16, true))).duckDbType()).isEqualTo("SMALLINT");
        assertThat(map(field("a", new ArrowType.Int(32, true))).duckDbType()).isEqualTo("INTEGER");
        assertThat(map(field("a", new ArrowType.Int(64, true))).duckDbType()).isEqualTo("BIGINT");
        assertThat(map(field("a",
                new ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE))).duckDbType())
                .isEqualTo("FLOAT");
        assertThat(map(field("a",
                new ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE))).duckDbType())
                .isEqualTo("DOUBLE");
        assertThat(map(field("a", new ArrowType.Decimal(10, 2, 128))).duckDbType())
                .isEqualTo("DECIMAL(10,2)");
        assertThat(map(field("a", new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY)))
                .duckDbType()).isEqualTo("DATE");
        assertThat(map(field("a",
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null))).duckDbType())
                .isEqualTo("TIMESTAMP");
        assertThat(map(field("a",
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, "UTC"))).duckDbType())
                .isEqualTo("TIMESTAMPTZ");
    }

    @Test
    void mapsWideDecimalExactlyAsTheSourceDeclaresIt() {
        // Source-type-authoritative policy: DECIMAL(38,9) must map to DECIMAL(38,9), never be
        // narrowed toward some previously-seen version's DECIMAL(18,3).
        assertThat(map(field("amount", new ArrowType.Decimal(38, 9, 128))).duckDbType())
                .isEqualTo("DECIMAL(38,9)");
    }

    @Test
    void rejectsUnsupportedTypesClearly() {
        Field listField = new Field("tags", FieldType.nullable(new ArrowType.List()), java.util.List.of(
                new Field("item", FieldType.nullable(new ArrowType.Utf8()), null)));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(DATASET, listField))
                .isInstanceOf(UnsupportedArrowTypeException.class)
                .hasMessageContaining("tags");
    }

    @Test
    void rejectsDecimalPrecisionAboveDuckDbMaximumWithFullContext() {
        Field field = field("amount", new ArrowType.Decimal(40, 10, 128));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(DATASET, field))
                .isInstanceOf(DecimalTypeNotSupportedException.class)
                .hasFieldOrPropertyWithValue("errorCode", "DECIMAL_TYPE_NOT_SUPPORTED")
                .hasMessageContaining(DATASET)
                .hasMessageContaining("amount")
                .hasMessageContaining("40")
                .hasMessageContaining("10")
                .hasMessageContaining("DECIMAL(40,10)");
    }

    @Test
    void rejectsDecimal256AsUnsupported() {
        // Arrow's 256-bit decimal is a distinct minor type from the 128-bit DECIMAL this mapper
        // handles - it never reaches decimal-specific precision/scale validation, so it fails as a
        // generically unsupported Arrow type rather than DECIMAL_TYPE_NOT_SUPPORTED.
        Field field = field("amount", new ArrowType.Decimal(50, 10, 256));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(DATASET, field))
                .isInstanceOf(UnsupportedArrowTypeException.class);
    }

    @Test
    void rejectsScaleGreaterThanPrecision() {
        Field field = field("amount", new ArrowType.Decimal(10, 12, 128));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(DATASET, field))
                .isInstanceOf(DecimalTypeNotSupportedException.class)
                .hasFieldOrPropertyWithValue("errorCode", "DECIMAL_TYPE_NOT_SUPPORTED");
    }

    private static ColumnMapping map(Field field) {
        return ArrowToDuckDbTypeMapper.map(DATASET, field);
    }

    private static Field field(String name, ArrowType type) {
        return new Field(name, FieldType.nullable(type), null);
    }
}
