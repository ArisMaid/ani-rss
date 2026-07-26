package ani.rss.commons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenUtilsTest {
    @TempDir
    Path tempDir;

    @Test
    void readsTheProjectVersionInsteadOfTheParentVersion() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>parent</artifactId>
                    <version>9.9.9</version>
                  </parent>
                  <groupId>ani.rss</groupId>
                  <artifactId>ani-rss</artifactId>
                  <version>3.2.2.49</version>
                </project>
                """, StandardCharsets.UTF_8);

        assertEquals(Optional.of("3.2.2.49"), MavenUtils.readProjectVersion(pom.toFile()));
    }

    @Test
    void ignoresAnUnrelatedOrMalformedWorkingDirectoryPom() throws Exception {
        Path unrelated = tempDir.resolve("unrelated.xml");
        Files.writeString(unrelated, """
                <project>
                  <artifactId>another-application</artifactId>
                  <version>99.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);
        Path malformed = tempDir.resolve("malformed.xml");
        Files.writeString(malformed, "<project>", StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), MavenUtils.readProjectVersion(unrelated.toFile()));
        assertEquals(Optional.empty(), MavenUtils.readProjectVersion(malformed.toFile()));
    }
}
