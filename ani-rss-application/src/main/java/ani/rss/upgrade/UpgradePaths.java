package ani.rss.upgrade;

import java.nio.file.Path;

/** Explicit file-path preconditions shared by media and metadata operations. */
public final class UpgradePaths {
    private UpgradePaths() {}
    public static String fileName(Path path) {
        Path name=path.getFileName();
        if(name==null) throw new IllegalArgumentException("媒体路径必须包含文件名");
        return name.toString();
    }
    public static Path parent(Path path) {
        Path parent=path.getParent();
        if(parent==null) throw new IllegalArgumentException("媒体路径必须包含父目录");
        return parent;
    }
}
