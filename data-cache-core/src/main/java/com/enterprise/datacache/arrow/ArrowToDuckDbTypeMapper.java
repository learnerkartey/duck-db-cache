package com.enterprise.datacache.arrow;

import com.enterprise.datacache.exception.UnsupportedArrowTypeException;
import java.math.BigInteger;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TimeStampSecVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * Maps Arrow {@link Field} schemas produced by Dremio to DuckDB column DDL and to the row-copy
 * strategy used by {@code DuckDbDatasetWriter}. Supports VARCHAR, BOOLEAN, TINYINT/SMALLINT/INTEGER/
 * BIGINT (signed and unsigned), FLOAT, DOUBLE, DECIMAL (precision/scale preserved), DATE and
 * TIMESTAMP (with and without time zone), always preserving SQL NULL. Any other Arrow type fails
 * loudly with {@link UnsupportedArrowTypeException} rather than silently degrading to VARCHAR.
 */
public final class ArrowToDuckDbTypeMapper {

    private ArrowToDuckDbTypeMapper() {
    }

    public static ColumnMapping map(Field field) {
        String name = field.getName();
        ArrowType arrowType = field.getType();
        Types.MinorType minorType = Types.getMinorTypeForArrowType(arrowType);

        return switch (minorType) {
            case BIT -> new ColumnMapping(name, "BOOLEAN", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((BitVector) v).getObject(i));
                }
            });

            case TINYINT -> new ColumnMapping(name, "TINYINT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((TinyIntVector) v).get(i));
                }
            });

            case SMALLINT -> new ColumnMapping(name, "SMALLINT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((SmallIntVector) v).get(i));
                }
            });

            case INT -> new ColumnMapping(name, "INTEGER", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((IntVector) v).get(i));
                }
            });

            case BIGINT -> new ColumnMapping(name, "BIGINT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((BigIntVector) v).get(i));
                }
            });

            // Unsigned Arrow integers are widened to the next signed DuckDB type so the full
            // unsigned value range is representable without relying on unsigned appender columns.
            case UINT1 -> new ColumnMapping(name, "SMALLINT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((UInt1Vector) v).getObjectNoOverflow(i).shortValue());
                }
            });
            case UINT2 -> new ColumnMapping(name, "INTEGER", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append((int) ((UInt2Vector) v).getObject(i).charValue());
                }
            });
            case UINT4 -> new ColumnMapping(name, "BIGINT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((UInt4Vector) v).getObjectNoOverflow(i));
                }
            });
            case UINT8 -> new ColumnMapping(name, "HUGEINT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    BigInteger value = ((UInt8Vector) v).getObjectNoOverflow(i);
                    a.append(value);
                }
            });

            case FLOAT4 -> new ColumnMapping(name, "FLOAT", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((Float4Vector) v).get(i));
                }
            });

            case FLOAT8 -> new ColumnMapping(name, "DOUBLE", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((Float8Vector) v).get(i));
                }
            });

            case DECIMAL -> mapDecimal(name, (ArrowType.Decimal) arrowType);

            case VARCHAR, LARGEVARCHAR -> new ColumnMapping(name, "VARCHAR", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((VarCharVector) v).getObject(i).toString());
                }
            });

            case DATEDAY -> new ColumnMapping(name, "DATE", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.appendEpochDays(((DateDayVector) v).get(i));
                }
            });

            case DATEMILLI -> new ColumnMapping(name, "DATE", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((DateMilliVector) v).getObject(i).toLocalDate());
                }
            });

            case TIMESTAMPSEC -> new ColumnMapping(name, "TIMESTAMP", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((TimeStampSecVector) v).getObject(i));
                }
            });
            case TIMESTAMPMILLI -> new ColumnMapping(name, "TIMESTAMP", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((TimeStampMilliVector) v).getObject(i));
                }
            });
            case TIMESTAMPMICRO -> new ColumnMapping(name, "TIMESTAMP", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((TimeStampMicroVector) v).getObject(i));
                }
            });
            case TIMESTAMPNANO -> new ColumnMapping(name, "TIMESTAMP", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.append(((TimeStampNanoVector) v).getObject(i));
                }
            });

            case TIMESTAMPMILLITZ -> new ColumnMapping(name, "TIMESTAMPTZ", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.appendEpochMillis(((TimeStampMilliTZVector) v).getObject(i));
                }
            });
            case TIMESTAMPMICROTZ -> new ColumnMapping(name, "TIMESTAMPTZ", (a, v, i) -> {
                if (v.isNull(i)) {
                    a.appendNull();
                } else {
                    a.appendEpochMicros(((TimeStampMicroTZVector) v).getObject(i));
                }
            });

            default -> throw new UnsupportedArrowTypeException(name, minorType.name());
        };
    }

    private static ColumnMapping mapDecimal(String name, ArrowType.Decimal decimal) {
        int precision = decimal.getPrecision();
        int scale = decimal.getScale();
        if (precision > 38) {
            throw new UnsupportedArrowTypeException(name, "DECIMAL(" + precision + "," + scale
                    + ") exceeds DuckDB's maximum precision of 38");
        }
        String ddl = "DECIMAL(" + precision + "," + scale + ")";
        return new ColumnMapping(name, ddl, (a, v, i) -> {
            if (v.isNull(i)) {
                a.appendNull();
            } else {
                a.append(((DecimalVector) v).getObject(i));
            }
        });
    }
}
