package com.enterprise.datacache.arrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.datacache.exception.UnsupportedArrowTypeException;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.Test;

class ArrowToDuckDbTypeMapperTest {

    @Test
    void mapsAllSupportedScalarTypes() {
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Utf8())).duckDbType()).isEqualTo("VARCHAR");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Bool())).duckDbType()).isEqualTo("BOOLEAN");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Int(8, true))).duckDbType()).isEqualTo("TINYINT");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Int(16, true))).duckDbType()).isEqualTo("SMALLINT");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Int(32, true))).duckDbType()).isEqualTo("INTEGER");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Int(64, true))).duckDbType()).isEqualTo("BIGINT");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a",
                new ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE))).duckDbType())
                .isEqualTo("FLOAT");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a",
                new ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE))).duckDbType())
                .isEqualTo("DOUBLE");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Decimal(10, 2, 128))).duckDbType())
                .isEqualTo("DECIMAL(10,2)");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a", new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY)))
                .duckDbType()).isEqualTo("DATE");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a",
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null))).duckDbType())
                .isEqualTo("TIMESTAMP");
        assertThat(ArrowToDuckDbTypeMapper.map(field("a",
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, "UTC"))).duckDbType())
                .isEqualTo("TIMESTAMPTZ");
    }

    @Test
    void rejectsUnsupportedTypesClearly() {
        Field listField = new Field("tags", FieldType.nullable(new ArrowType.List()), java.util.List.of(
                new Field("item", FieldType.nullable(new ArrowType.Utf8()), null)));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(listField))
                .isInstanceOf(UnsupportedArrowTypeException.class)
                .hasMessageContaining("tags");
    }

    @Test
    void rejectsDecimalPrecisionAboveDuckDbMaximum() {
        Field field = field("amount", new ArrowType.Decimal(40, 10, 128));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(field))
                .isInstanceOf(UnsupportedArrowTypeException.class)
                .hasMessageContaining("38");
    }

    @Test
    void rejectsDecimal256AsUnsupported() {
        Field field = field("amount", new ArrowType.Decimal(50, 10, 256));
        assertThatThrownBy(() -> ArrowToDuckDbTypeMapper.map(field))
                .isInstanceOf(UnsupportedArrowTypeException.class);
    }

    private static Field field(String name, ArrowType type) {
        return new Field(name, FieldType.nullable(type), null);
    }
}
