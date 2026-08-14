package com.enterprise.datacache.feature.cache.arrow;

import com.enterprise.datacache.feature.cache.exception.DecimalTypeNotSupportedException;
import com.enterprise.datacache.feature.cache.exception.UnsupportedArrowTypeException;
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
 *
 * <p><b>Decimal schema evolution policy: the source Arrow type is always authoritative.</b> This
 * method is called fresh, from the current Arrow schema, on every refresh - never from a previous
 * DuckDB version's column types. A dataset whose source DECIMAL precision/scale changed between
 * refreshes (e.g. {@code DECIMAL(18,3)} to {@code DECIMAL(38,9)}) gets a BUILDING version with the
 * new, wider type; the previous ACTIVE version's file and column types are never read, referenced,
 * or altered. If the source decimal definition cannot be represented exactly by DuckDB (currently:
 * precision above 38, or a scale outside {@code [0, precision]}), this method fails loudly with
 * {@link DecimalTypeNotSupportedException} rather than silently narrowing precision/scale,
 * rounding, truncating, or converting through {@code float}/{@code double}.
 */
public final class ArrowToDuckDbTypeMapper {

    private ArrowToDuckDbTypeMapper() {
    }

    /**
     * @param datasetName logical dataset name, used only to enrich {@link DecimalTypeNotSupportedException}
     *                     with which dataset/column failed
     */
    public static ColumnMapping map(String datasetName, Field field) {
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

            case DECIMAL -> mapDecimal(datasetName, name, (ArrowType.Decimal) arrowType);

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

    /**
     * Derives DuckDB {@code DECIMAL(precision,scale)} DDL directly from the source Arrow decimal
     * type passed in for THIS refresh - never from any previously built version's column types.
     * Values are streamed via {@link DecimalVector#getObject} (exact {@link java.math.BigDecimal},
     * never {@code float}/{@code double}) straight into {@code DuckDBAppender.append(BigDecimal)}.
     */
    private static ColumnMapping mapDecimal(String datasetName, String name, ArrowType.Decimal decimal) {
        int precision = decimal.getPrecision();
        int scale = decimal.getScale();
        String requestedDuckDbType = "DECIMAL(" + precision + "," + scale + ")";

        if (precision < 1 || precision > 38) {
            throw new DecimalTypeNotSupportedException(datasetName, name, precision, scale, requestedDuckDbType,
                    "precision must be between 1 and 38 (DuckDB's maximum); the source precision cannot be "
                            + "represented without narrowing it");
        }
        if (scale < 0 || scale > precision) {
            throw new DecimalTypeNotSupportedException(datasetName, name, precision, scale, requestedDuckDbType,
                    "scale must be between 0 and the column's precision (" + precision + ")");
        }

        return new ColumnMapping(name, requestedDuckDbType, (a, v, i) -> {
            if (v.isNull(i)) {
                a.appendNull();
            } else {
                a.append(((DecimalVector) v).getObject(i));
            }
        });
    }
}
