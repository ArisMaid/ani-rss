package ani.rss.update;

import ani.rss.commons.FileUtils;
import ani.rss.commons.MavenUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.nio.file.Path;

public class DesktopUpdate implements BaseUpdate {
    @Override
    @SuppressFBWarnings(value = "DM_EXIT", justification = "桌面端等待进程退出后替换已暂存的 JAR")
    public void update(File updateFile) {
        File currentFile = MavenUtils.getCurrentFile().getFile();
        // 退出后由桌面端替换正在运行的 JAR。
        FileUtils.move(updateFile.toPath(), pendingPath(currentFile));
        System.exit(0);
    }

    static Path pendingPath(File currentFile) {
        return Path.of(currentFile.getPath() + ".pending");
    }
}
