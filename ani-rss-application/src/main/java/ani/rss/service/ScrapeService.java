package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.enums.StringEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TmdbUtils;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import wushuo.tmdb.api.entity.*;
import wushuo.tmdb.api.enums.TmdbTypeEnum;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 刮削
 */
@Slf4j
@Service
public class ScrapeService {

    @Resource
    private NfoGenerator nfoGenerator;

    @Lazy
    @Resource
    private DownloadService downloadService;

    /**
     * 刮削
     *
     * @param ani         订阅
     * @param forceScrape 强制刮削
     */
    public void scrape(Ani ani, Boolean forceScrape) {
        String title = ani.getTitle();

        Tmdb tmdb = ani.getTmdb();

        if (Objects.isNull(tmdb)) {
            return;
        }

        boolean isOva = ani.getOva();
        try {
            log.info("正在刮削 ... {}", title);
            saveBangumiIni(ani, forceScrape);
            if (isOva) {
                scrapeMovie(ani, forceScrape);
            } else {
                scrapeTv(ani, forceScrape);
            }
            log.info("刮削完成 {}", title);
        } catch (Exception e) {
            log.error("刮削错误 {}", title);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 电影刮削
     *
     * @param ani   订阅
     * @param force 强制
     * @throws Exception 异常
     */
    public void scrapeMovie(Ani ani, Boolean force) throws Exception {
        Tmdb tmdb = ani.getTmdb();

        // 更新tmdb信息
        Optional<Tmdb> tmdbOptional = TmdbUtils.getTmdb(tmdb, TmdbTypeEnum.MOVIE);
        if (tmdbOptional.isEmpty()) {
            log.warn("获取tmdb失败 {}", tmdb.getId());
            return;
        }
        tmdb = tmdbOptional.get();

        // 下载位置
        String downloadPath = downloadService.getDownloadPath(ani);
        File[] files = FileUtils.listFiles(downloadPath);

        if (ArrayUtil.isEmpty(files)) {
            return;
        }

        Optional<File> first = Stream.of(files)
                .filter(file -> {
                    String extName = FileUtil.extName(file);
                    if (StrUtil.isBlank(extName)) {
                        return false;
                    }
                    return FileUtils.isVideoFormat(extName);
                })
                .max(Comparator.comparingLong(File::length));

        if (first.isEmpty()) {
            // 找不到视频文件
            return;
        }

        File file = first.get();
        String mainName = FileUtil.mainName(file);

        // 保存nfo
        String outputPath = downloadPath + "/" + mainName + ".nfo";
        if (force || !FileUtil.exist(outputPath)) {
            nfoGenerator.generateMovieNfo(tmdb, outputPath);
        }

        saveTmdbImages(tmdb, TmdbTypeEnum.MOVIE, downloadPath, force);
    }

    /**
     * 电视剧刮削
     *
     * @param ani   订阅
     * @param force 强制
     * @throws Exception 异常
     */
    public void scrapeTv(Ani ani, Boolean force) throws Exception {
        Tmdb tmdb = ani.getTmdb();

        // 更新tmdb信息
        Optional<Tmdb> tmdbOptional = TmdbUtils.getTmdb(tmdb, TmdbTypeEnum.TV);
        if (tmdbOptional.isEmpty()) {
            log.warn("获取tmdb失败 {}", tmdb.getId());
            return;
        }
        tmdb = tmdbOptional.get();

        // 下载位置
        File downloadPath = new File(downloadService.getDownloadPath(ani));
        if (!FileUtil.exist(downloadPath)) {
            return;
        }

        // tvshow.nfo
        File tvShowNfoFile = new File(downloadPath.getParent(), "tvshow.nfo");
        if (force || !FileUtil.exist(tvShowNfoFile)) {
            nfoGenerator.generateTvShowNfo(tmdb, tvShowNfoFile.toString());
        }

        saveTmdbImages(tmdb, TmdbTypeEnum.TV, downloadPath.getParent(), force);

        Integer season = ani.getSeason();

        Optional<TmdbSeason> optional = TmdbUtils.getTmdbSeason(tmdb, season);
        if (optional.isEmpty()) {
            return;
        }

        String seasonFormat = String.format("%02d", season);

        TmdbSeason tmdbSeason = optional.get();

        // 季封面
        String seasonPosterPath = tmdbSeason.getPosterPath();
        seasonPosterPath = StrUtil.blankToDefault(seasonPosterPath, tmdb.getPosterPath());
        String seasonPosterExtName = FileUtil.extName(seasonPosterPath);
        File seasonPosterFile = new File(downloadPath.getParent(), "season" + seasonFormat + "-poster." + seasonPosterExtName);
        saveImages(seasonPosterPath, seasonPosterFile, force);

        // 季nfo
        File seasonNfoFile = new File(downloadPath, "season.nfo");
        if (force || !seasonNfoFile.exists()) {
            nfoGenerator.generateSeasonNfo(tmdbSeason, seasonNfoFile.toString());
        }

        File[] files = FileUtils.listFiles(downloadPath);

        Map<Integer, TmdbEpisode> episodeMap = tmdbSeason
                .getEpisodes()
                .stream()
                .collect(Collectors.toMap(TmdbEpisode::getEpisodeNumber, it -> it));

        Config config = ConfigUtil.CONFIG;
        // 追更天数
        Integer followDay = config.getFollowDay();

        // 以下开始保存集的 thumb、nfo
        for (File file : files) {
            String extName = FileUtil.extName(file);
            if (StrUtil.isBlank(extName)) {
                continue;
            }

            if (!FileUtils.isVideoFormat(extName)) {
                // 非视频文件
                continue;
            }

            String mainName = FileUtil.mainName(file);
            if (!ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
                // 命名不标准
                continue;
            }

            int seasonNumber = Integer.parseInt(ReUtil.get(StringEnum.SEASON_REG, mainName, 1));
            if (season != seasonNumber) {
                // 季对应不上 跳过
                continue;
            }

            Integer episodeNumber =
                    Integer.parseInt(ReUtil.get(StringEnum.SEASON_REG, mainName, 2));
            if (!episodeMap.containsKey(episodeNumber)) {
                // 找不到对应集
                continue;
            }

            TmdbEpisode tmdbEpisode = episodeMap.get(episodeNumber);

            // 该集的播出日期
            Date airDate = Optional.of(tmdbEpisode)
                    .map(TmdbEpisode::getAirDate)
                    .orElse(new Date());

            // 最晚追更时间
            Date date = DateUtil.offsetDay(new Date(), -followDay);

            // 播出日期 >= 最晚追更时间 强制刷新元数据
            boolean isFollow = airDate.getTime() >= date.getTime();

            // thumb
            String thumbPath = tmdbEpisode.getStillPath();
            if (StrUtil.isNotBlank(thumbPath)) {
                String thumbExtName = FileUtil.extName(thumbPath);
                File thumbFile = new File(downloadPath, mainName + "-thumb." + thumbExtName);

                // 判断条件: 追更 or 强制
                saveImages(thumbPath, thumbFile, isFollow || force);
            }

            // nfo
            String episodeFile = downloadPath + "/" + mainName + ".nfo";
            // 判断条件: 追更 or 强制 or 元数据不存在
            if (isFollow || force || !FileUtil.exist(episodeFile)) {
                nfoGenerator.generateEpisodeNfo(tmdbEpisode, episodeFile);
            }
        }
    }

    public void saveTmdbImages(Tmdb tmdb, String outputPath, Boolean force) {
        if (tmdb == null || tmdb.getTmdbType() == null) {
            return;
        }
        saveTmdbImages(tmdb, tmdb.getTmdbType(), outputPath, force);
    }

    private void saveTmdbImages(Tmdb tmdb, TmdbTypeEnum tmdbType, String outputPath, Boolean force) {
        if (tmdb == null || StrUtil.isBlank(outputPath)) {
            return;
        }

        saveNamedImage(tmdb.getPosterPath(), outputPath, "poster", force);
        String primaryBackdrop = tmdb.getBackdropPath();
        saveNamedImage(primaryBackdrop, outputPath, "fanart", force);

        TmdbImages images = TmdbUtils.getTmdbImages(tmdb, tmdbType);
        if (images == null) {
            return;
        }

        List<TmdbImage> backdrops = selectAdditionalBackdrops(primaryBackdrop, images.getBackdrops());
        for (int index = 0; index < backdrops.size(); index++) {
            saveNamedImage(backdrops.get(index).getFilePath(), outputPath,
                    "fanart" + (index + 1), force);
        }

        List<TmdbImage> logos = images.getLogos();
        if (logos != null) {
            logos.stream()
                    .filter(Objects::nonNull)
                    .map(TmdbImage::getFilePath)
                    .filter(StrUtil::isNotBlank)
                    .findFirst()
                    .ifPresent(path -> saveNamedImage(path, outputPath, "clearlogo", force));
        }
    }

    static List<TmdbImage> selectAdditionalBackdrops(String primaryBackdrop, List<TmdbImage> backdrops) {
        if (backdrops == null || backdrops.isEmpty()) {
            return List.of();
        }
        Set<String> selectedPaths = new LinkedHashSet<>();
        List<TmdbImage> selected = new ArrayList<>();
        for (TmdbImage backdrop : backdrops) {
            if (backdrop == null || backdrop.getWidth() == null || backdrop.getWidth() < 1280) {
                continue;
            }
            String path = backdrop.getFilePath();
            if (StrUtil.isBlank(path) || StrUtil.isBlank(FileUtil.extName(path)) ||
                    Objects.equals(primaryBackdrop, path) || !selectedPaths.add(path)) {
                continue;
            }
            selected.add(backdrop);
            if (selected.size() == 4) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private void saveNamedImage(String tmdbPath, String outputPath, String name, Boolean force) {
        if (StrUtil.isBlank(tmdbPath)) {
            return;
        }
        String extension = FileUtil.extName(tmdbPath);
        if (StrUtil.isBlank(extension)) {
            return;
        }
        saveImages(tmdbPath, new File(outputPath, name + "." + extension), force);
    }

    /**
     * 保存图片
     *
     * @param tmdbPath tmdb路径
     * @param saveFile 保存位置
     * @param force    强制
     */
    public void saveImages(String tmdbPath, File saveFile, Boolean force) {
        if (StrUtil.isBlank(tmdbPath)) {
            return;
        }

        Path target = saveFile.toPath().toAbsolutePath().normalize();
        boolean replace = Boolean.TRUE.equals(force);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target) ||
                    !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("scraped image target is unsafe");
            }
            if (!replace) {
                return;
            }
        }

        Config config = ConfigUtil.CONFIG;
        String tmdbImage = config.getTmdbImage();

        HttpReq.get(tmdbImage + "/t/p/original" + tmdbPath)
                .then(res -> {
                    HttpReq.assertStatus(res);
                    try (InputStream inputStream = res.bodyStream()) {
                        writeImageAtomically(target, inputStream, replace);
                    } catch (IOException | RuntimeException e) {
                        throw new IllegalStateException("save scraped image failed", e);
                    }
                });

        log.info("已保存图片 {}", saveFile);
    }

    static void writeImageAtomically(Path target, InputStream inputStream, boolean replace)
            throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("scraped image target has no parent directory");
        }
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) {
            throw new IOException("scraped image parent is a symbolic link");
        }
        if (Files.exists(absoluteTarget, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(absoluteTarget) ||
                        !Files.isRegularFile(absoluteTarget, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("scraped image target is unsafe");
        }

        Path temporary = Files.createTempFile(parent, ".ani-rss-image-", ".part");
        try {
            try (FileOutputStream outputStream = new FileOutputStream(temporary.toFile())) {
                inputStream.transferTo(outputStream);
                outputStream.flush();
                outputStream.getChannel().force(true);
            }
            moveImageIntoPlace(temporary, absoluteTarget, replace);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveImageIntoPlace(Path temporary, Path target, boolean replace)
            throws IOException {
        if (!replace) {
            Files.move(temporary, target);
            return;
        }
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 保存 bangumi.ini
     *
     * @param ani   订阅
     * @param force 强制
     */
    public void saveBangumiIni(Ani ani, Boolean force) {
        Config config = ConfigUtil.CONFIG;
        Boolean bangumiIniEnabled = config.getBangumiIniEnabled();
        if (!bangumiIniEnabled) {
            // 未开启 bangumi.ini
            return;
        }

        String downloadPath = downloadService.getDownloadPath(ani);

        File file = new File(downloadPath, "bangumi.ini");
        if (!force) {
            if (file.exists()) {
                // 非强制模式
                return;
            }
        }

        String subjectId = BgmUtil.getSubjectId(ani);
        Integer offset = ani.getOffset();

        String s = """
                [Bangumi]
                id={}
                offset={}
                """;

        s = StrUtil.format(s, subjectId, offset);

        FileUtil.writeUtf8String(s, file);

        log.info("已保存 {}", file);
    }

}
