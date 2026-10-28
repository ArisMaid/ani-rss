package ani.rss.build;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
public class MacOS implements Runnable {
    // Upstream macOS 27 launcher fix; pin to avoid stale main.zip caches.
    private static final String LAUNCHER_REVISION = "2729d5b8e598bc03db9059b73548648d0542ff23";
    private static final String LAUNCHER_DIRECTORY = "ani-rss-macos-" + LAUNCHER_REVISION;
    @Override
    public void run() {
        String basedir = System.getProperty("basedir");

        File target = new File(basedir, "target");
        Assert.isTrue(target.exists(), "target not exists");

        File macosZip = new File(target, LAUNCHER_DIRECTORY + ".zip");

        if (!macosZip.exists()) {
            String url = "https://github.com/wushuo894/ani-rss-macos/archive/" + LAUNCHER_REVISION + ".zip";
            HttpUtil.downloadFile(url, macosZip);
        }

        ZipUtil.unzip(macosZip, macosZip.getParentFile());

        File jarFile = new File(target, "ani-rss.jar");

        Path path = Path.of(target.getPath(), LAUNCHER_DIRECTORY + "/ani-rss.app/Contents/MacOS/ani-rss.jar");

        FileUtil.copy(Paths.get(jarFile.getPath()), path, StandardCopyOption.REPLACE_EXISTING);

        OsInfo osInfo = SystemUtil.getOsInfo();

        if (osInfo.isMac()) {
            String appDir = Path.of(target.getPath(), LAUNCHER_DIRECTORY + "/ani-rss.app").toString();

            RuntimeUtil.execForStr("chmod", "-R", "755", appDir);
            RuntimeUtil.execForStr("xattr", "-cr", appDir);
        }
    }
}
