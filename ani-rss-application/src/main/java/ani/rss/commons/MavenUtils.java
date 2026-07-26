package ani.rss.commons;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class MavenUtils {
    private static final Object VERSION_LOCK = new Object();
    private static volatile String version;

    public static CurrentFile getCurrentFile() {
        OsInfo osInfo = SystemUtil.getOsInfo();
        String splitStr = osInfo.isWindows() ? ";" : ":";
        String s = System.getProperty("java.class.path")
                .split(splitStr)[0];
        return new CurrentFile()
                .setFile(new File(s));
    }

    public static String getVersion() {
        String current = version;
        if (Objects.nonNull(current)) {
            return current;
        }
        synchronized (VERSION_LOCK) {
            if (version == null) {
                if (getCurrentFile().isDirectory()) {
                    Optional<String> projectVersion = readProjectVersion(new File("pom.xml"));
                    if (projectVersion.isPresent()) {
                        version = projectVersion.get();
                        return version;
                    }
                }
                try {
                    BuildProperties buildProperties = SpringUtil.getBean(BuildProperties.class);
                    version = buildProperties.getVersion();
                } catch (RuntimeException ignored) {
                    Package packageInfo = MavenUtils.class.getPackage();
                    String implementationVersion = packageInfo == null ? null : packageInfo.getImplementationVersion();
                    version = StrUtil.blankToDefault(implementationVersion,
                            System.getProperty("ani-rss.version", "dev"));
                }
            }
            return version;
        }
    }

    static Optional<String> readProjectVersion(File pom) {
        if (pom == null || !pom.isFile()) {
            return Optional.empty();
        }
        try {
            Document document = XmlUtil.readXML(pom);
            Element root = document.getDocumentElement();
            Element artifactId = XmlUtil.getElement(root, "artifactId");
            Element versionElement = XmlUtil.getElement(root, "version");
            if (artifactId == null || versionElement == null ||
                    !"ani-rss".equals(artifactId.getTextContent().trim())) {
                return Optional.empty();
            }
            String candidate = versionElement.getTextContent().trim();
            return StrUtil.isBlank(candidate) ? Optional.empty() : Optional.of(candidate);
        } catch (RuntimeException e) {
            log.debug("ignore unreadable development pom: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Data
    @Accessors(chain = true)
    public static class CurrentFile implements Serializable {
        private File file;

        public String getName() {
            return file.getName();
        }

        public Boolean isDirectory() {
            return file.isDirectory();
        }

        public Boolean isFile() {
            return file.isFile();
        }

        public Boolean isExe() {
            if (isDirectory()) {
                return false;
            }

            String extName = FileUtil.extName(file);
            if (StrUtil.isBlank(extName)) {
                return false;
            }

            return "exe".equalsIgnoreCase(extName);
        }

        public Boolean isJar() {
            if (isDirectory()) {
                return false;
            }

            String extName = FileUtil.extName(file);
            if (StrUtil.isBlank(extName)) {
                return false;
            }

            return "jar".equalsIgnoreCase(extName);
        }
    }

}
