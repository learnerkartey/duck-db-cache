package com.enterprise.datacache.feature.cache.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites SQL containing {@code :namedParameter} placeholders into standard JDBC {@code ?}
 * positional-parameter SQL, tracking which named parameter each {@code ?} corresponds to (in
 * order, supporting the same name repeated multiple times). Values are always bound through
 * {@link java.sql.PreparedStatement}, never through string concatenation, so parameter values -
 * including ones containing quotes, semicolons, or SQL comment markers - are treated strictly as
 * data, never as SQL syntax.
 *
 * <p>Recognizes and skips {@code :name} occurrences inside single-quoted string literals
 * (including the {@code ''} escaped-quote form), double-quoted identifiers, {@code --} line
 * comments, and {@code /* *}{@code /} block comments, so a literal colon in those contexts is
 * never mistaken for a parameter marker.
 */
public final class NamedParameterSqlBinder {

    private NamedParameterSqlBinder() {
    }

    public record BoundSql(String jdbcSql, List<String> parameterOrder) {
    }

    public static BoundSql parse(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> paramOrder = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = skipSingleQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '"') {
                int end = skipDoubleQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = skipLineComment(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = skipBlockComment(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == ':' && i + 1 < n && isIdentifierStart(sql.charAt(i + 1))) {
                int start = i + 1;
                int end = start;
                while (end < n && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String paramName = sql.substring(start, end);
                paramOrder.add(paramName);
                out.append('?');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return new BoundSql(out.toString(), paramOrder);
    }

    private static int skipSingleQuoted(String sql, int start) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static int skipDoubleQuoted(String sql, int start) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            if (sql.charAt(i) == '"') {
                if (i + 1 < n && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static int skipLineComment(String sql, int start) {
        int i = start;
        int n = sql.length();
        while (i < n && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int start) {
        int i = start + 2;
        int n = sql.length();
        while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
            i++;
        }
        return Math.min(i + 2, n);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
