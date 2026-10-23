package ani.rss.upgrade;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Service
public class LibraryPathMappingService {
    public Path map(String path,List<UpgradeSettings.PathMapping> mappings) {
        if(path==null || path.isBlank() || path.contains("://")) throw new IllegalArgumentException("不是可管理的本地媒体路径");
        String remote=normalizeRemote(path);
        UpgradeSettings.PathMapping best=null;
        int length=-1;
        for(var m:mappings) {
            String prefix=normalizeRemote(m.remoteRoot());
            boolean windows=prefix.matches("^[A-Za-z]:/.*") || prefix.startsWith("//");
            String a=windows?remote.toLowerCase(Locale.ROOT):remote;
            String b=windows?prefix.toLowerCase(Locale.ROOT):prefix;
            if((a.equals(b)||a.startsWith(b.endsWith("/")?b:b+"/"))&&prefix.length()>length) { best=m; length=prefix.length(); }
        }
        if(best==null) throw new IllegalArgumentException("没有匹配的路径映射："+path);
        Path root=Path.of(best.localRoot()).toAbsolutePath().normalize();
        String suffix=remote.substring(length);
        while(suffix.startsWith("/")) suffix=suffix.substring(1);
        Path result=root.resolve(suffix).normalize();
        if(!result.startsWith(root)) throw new IllegalArgumentException("路径超出允许根目录");
        return result;
    }
    public static String normalizeRemote(String path) {
        if(path==null || path.isBlank() || path.indexOf('\0')>=0) throw new IllegalArgumentException("路径为空或包含非法字符");
        String p=path.replace('\\','/');
        for(String segment:p.split("/")) if(segment.equals("..") || segment.equals(".")) throw new IllegalArgumentException("路径包含跳转段");
        while(p.endsWith("/") && p.length()>1 && !p.matches("[A-Za-z]:/")) p=p.substring(0,p.length()-1);
        return p;
    }
    public Path requireSafe(Path path,Path allowedRoot,boolean mustExist) throws IOException {
        Path root=allowedRoot.toAbsolutePath().normalize();
        Path target=path.toAbsolutePath().normalize();
        if(target.equals(root)||!target.startsWith(root)) throw new IOException("文件路径超出允许根目录");
        BasicFileAttributes rootAttributes=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(rootAttributes.isSymbolicLink()||rootAttributes.isOther()||!rootAttributes.isDirectory()) throw new IOException("允许根目录不能是符号链接或 junction");
        for(Path parent=root.getParent();parent!=null;parent=parent.getParent()) {
            var a=Files.readAttributes(parent,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||a.isOther()) throw new IOException("允许根目录的父路径不能是符号链接或 junction");
        }
        Path realRoot=root.toRealPath();
        for(Path p=target;p!=null&&!p.equals(root);p=p.getParent()) {
            if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isSymbolicLink()||a.isOther()||!p.toRealPath().startsWith(realRoot)) throw new IOException("不允许符号链接或 junction 路径");
            }
        }
        if(mustExist&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("路径不是普通文件");
        return target;
    }
}
