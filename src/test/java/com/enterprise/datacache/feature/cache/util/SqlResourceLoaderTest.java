package com.enterprise.datacache.feature.cache.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.datacache.feature.cache.exception.InvalidConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlResourceLoaderTest {

    @TempDir
    Path tempDir;

    private final SqlResourceLoader loader = new SqlResourceLoader();

    @Test
    void loadsFromClasspath() {
        String sql = loader.load("classpath:testdata/test-dataset.sql");
        assertThat(sql).contains("SELECT");
    }

    @Test
    void loadsFromFilesystem() throws Exception {
        Path file = tempDir.resolve("query.sql");
        Files.writeString(file, "SELECT 1");
        String sql = loader.load("file:" + file.toAbsolutePath());
        assertThat(sql).isEqualTo("SELECT 1");
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> loader.load("classpath:testdata/does-not-exist.sql"))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        Path file = tempDir.resolve("empty.sql");
        Files.writeString(file, "   \n  ");
        assertThatThrownBy(() -> loader.load("file:" + file.toAbsolutePath()))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsBlankLocation() {
        assertThatThrownBy(() -> loader.load(" "))
                .isInstanceOf(InvalidConfigurationException.class);
    }
}
