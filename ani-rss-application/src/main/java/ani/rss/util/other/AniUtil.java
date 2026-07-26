package ani.rss.util.other;

import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PathPolicy;
import ani.rss.entity.*;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.exception.ResultException;
import ani.rss.ownership.OwnershipService;
import ani.rss.persistence.SubscriptionRepository;
import ani.rss.service.SafeImageFetcher;
import ani.rss.service.DownloadService;
import ani.rss.service.MikanService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class AniUtil {
    private static final Object SYNC_LOCK = new Object();

    public static final List<Ani> ANI_LIST = new CopyOnWriteArrayList<>();
    public static final String FILE_NAME = "ani.v2.json";
    private static final SubscriptionRepository REPOSITORY = new SubscriptionRepository(
            ANI_LIST,
            () -> getAniFile().toPath());

    /**
     * 获取订阅配置文件
     *
     * @return 配置文件
     */
    public static File getAniFile() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir + File.separator + FILE_NAME);
    }

    /**
     * 加载订阅
     */
    public static void load() {
        load(true);
    }

    public static void load(boolean hydrateCovers) {
        File configFile = getAniFile();

        if (!configFile.exists()) {
            try {
                AtomicFileWriter.writeUtf8(configFile.toPath(), GsonStatic.toJson(List.of()));
            } catch (Exception e) {
                throw new IllegalStateException("create subscription file failed", e);
            }
        }
        List<Ani> anis;
        try {
            anis = REPOSITORY.readFromDisk();
        } catch (Exception e) {
            throw new IllegalStateException("load subscriptions failed", e);
        }

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true)
                .setOverride(false);

        List<Ani> loaded = new ArrayList<>();
        for (Ani ani : anis) {
            Date releaseDate = ani.getReleaseDate();
            if (Objects.isNull(releaseDate)) {
                releaseDate = new Date();
                // 处理旧的日期数据
                try {
                    Integer year = ani.getYear();
                    Integer month = ani.getMonth();
                    Integer date = ani.getDate();
                    String format = StrUtil.format("{}-{}-{}", year, month, date);
                    releaseDate = DateUtil.parse(format, DatePattern.NORM_DATE_PATTERN);
                } catch (RuntimeException e) {
                    log.warn("invalid legacy release date; using current time subscriptionId:{} type:{}",
                            ani.getId(), e.getClass().getSimpleName());
                }
                ani.setReleaseDate(releaseDate);
            }

            // 自动修补缺失的封面
            if (hydrateCovers) {
                String image = ani.getImage();
                saveCover(image);
            }

            Ani newAni = AniUtil.createAni();
            BeanUtil.copyProperties(newAni, ani, copyOptions);
            loaded.add(ani);
        }
        REPOSITORY.markCommitted(loaded);
        log.debug("加载订阅 共{}项", loaded.size());
    }

    /**
     * 将订阅配置保存到磁盘
     */
    public static void sync() {
        synchronized (SYNC_LOCK) {
            syncLocked();
        }
    }

    private static void syncLocked() {
        REPOSITORY.commitRuntimeCandidate();
        log.debug("保存成功 {}", REPOSITORY.path());
    }

    public static List<Ani> snapshot() {
        return REPOSITORY.snapshot();
    }

    public static List<Ani> validateCandidate(List<Ani> candidate) {
        return REPOSITORY.validateCandidate(candidate);
    }

    public static void commit(List<Ani> subscriptions) {
        REPOSITORY.commit(subscriptions);
        log.debug("保存成功 {}", REPOSITORY.path());
    }

    /**
     * 获取动漫信息
     *
     * @param dto DTO
     * @return 订阅
     */
    public static Ani getAni(RssToAniDTO dto) {
        String url = dto.getUrl();
        String type = dto.getType();
        Boolean enable = dto.getEnable();
        enable = ObjectUtil.defaultIfNull(enable, true);

        Assert.notBlank(url, "RSS地址 不能为空");

        type = StrUtil.blankToDefault(type, "mikan");

        Ani ani = AniUtil.createAni();
        ani.setUrl(url);

        Map<String, String> paramMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        switch (type) {
            case "mikan":
                try {
                    String subgroup = dto.getSubgroup();
                    String bgmUrl = dto.getBgmUrl();
                    if (StrUtil.isAllBlank(subgroup, bgmUrl)) {
                        String subgroupId = MikanService.getSubgroupId(url);
                        MikanService.getMikanInfo(ani, subgroupId);
                    } else {
                        ani.setBgmUrl(bgmUrl)
                                .setSubgroup(subgroup);
                    }
                } catch (Exception e) {
                    throw ResultException.exception("获取失败");
                }
                break;
            case "ani-bt":
                if (paramMap.containsKey("bgmId")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("bgmId");
                    ani.setBgmUrl(bgmUrl);
                }

                String subgroup = dto.getSubgroup();
                if (paramMap.containsKey("groupSlug") && StrUtil.isBlank(subgroup)) {
                    subgroup = paramMap.get("groupSlug");
                }
                ani.setSubgroup(subgroup);
                break;
            case "anime-garden":
                if (paramMap.containsKey("subject")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("subject");
                    ani.setBgmUrl(bgmUrl);
                }
                if (paramMap.containsKey("fansub")) {
                    ani.setSubgroup(paramMap.get("fansub"));
                }
                break;
            default:
                String bgmUrl = dto.getBgmUrl();
                ani.setBgmUrl(bgmUrl);
        }

        String bgmUrl = ani.getBgmUrl();
        String subgroup = ani.getSubgroup();

        Assert.notBlank(bgmUrl, "bgmUrl 不能为空");

        BgmInfo bgmInfo = BgmUtil.getBgmInfo(ani, true);

        BgmUtil.toAni(bgmInfo, ani);

        Config config = ConfigUtil.CONFIG;

        // 只下载最新集
        Boolean downloadNew = config.getDownloadNew();
        // 默认启用全局排除
        Boolean enabledExclude = config.getEnabledExclude();
        // 默认导入全局排除
        Boolean importExclude = config.getImportExclude();
        // 全局排除
        List<String> exclude = config.getExclude();

        // 默认导入全局排除
        if (importExclude) {
            exclude = new ArrayList<>(exclude);
            exclude.addAll(ani.getExclude());
            exclude = exclude.stream().distinct().toList();
            ani.setExclude(exclude);
        }

        ani
                // 只下载最新集
                .setDownloadNew(downloadNew)
                // 是否启用全局排除
                .setGlobalExclude(enabledExclude)
                // type mikan or other
                .setType(type)
                .setEnable(enable);

        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        if (subgroup.equals("未知字幕组")) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            subgroup = ItemsUtil.getSubgroup(items);
        }

        ani.setSubgroup(subgroup);

        List<StandbyRss> standbyRssList = ani.getStandbyRssList();

        boolean copyMasterToStandby = config.getCopyMasterToStandby();
        boolean standbyRss = config.getStandbyRss();
        if (copyMasterToStandby && standbyRss) {
            StandbyRss copyStandbyRss = new StandbyRss()
                    .setUrl(url.trim())
                    .setOffset(0)
                    .setLabel(ani.getSubgroup());
            standbyRssList.add(copyStandbyRss);
        }

        log.debug("获取到动漫信息 title:{} id:{}", ani.getTitle(), ani.getId());
        if (ani.getOva()) {
            return ani;
        }

        // 自动推断剧集偏移
        if (config.getOffset()) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            if (items.isEmpty()) {
                return ani;
            }
            Double offset = -(items.stream()
                    .map(Item::getEpisode)
                    .min(Comparator.comparingDouble(i -> i))
                    .get() - 1);
            log.debug("自动获取到剧集偏移为 {}", offset);
            ani.setOffset(offset.intValue());

            for (StandbyRss rss : standbyRssList) {
                rss.setOffset(offset.intValue());
            }
        }
        return ani;
    }


    public static String saveCover(String coverUrl) {
        return saveCover(coverUrl, false);
    }

    /**
     * 保存图片
     *
     * @param coverUrl   图片链接
     * @param isOverride 是否覆盖
     * @return 相对位置
     */
    public static String saveCover(String coverUrl, Boolean isOverride) {
        // 默认空图片
        String cover = "cover.png";
        Path configRoot = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Path filesRoot = configRoot.resolve("files");
        try {
            Files.createDirectories(configRoot);
            if (Files.exists(filesRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(filesRoot)) {
                throw new IllegalStateException("cover directory is a symbolic link");
            }
            Files.createDirectories(filesRoot);
            PathPolicy.requireNoSymbolicLinks(configRoot, filesRoot);
            PathPolicy.realPathWithin(configRoot, filesRoot);
            Path defaultFile = filesRoot.resolve(cover);
            if (!Files.exists(defaultFile, LinkOption.NOFOLLOW_LINKS)) {
                try (InputStream inputStream = ResourceUtil.getStream("image/cover.png")) {
                    writeCoverAtomically(filesRoot, defaultFile, inputStream.readAllBytes(), false);
                }
            } else if (!Files.isRegularFile(defaultFile, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(defaultFile)) {
                throw new IllegalStateException("default cover path is unsafe");
            }
        } catch (Exception e) {
            log.error("准备封面目录失败 type:{}", e.getClass().getSimpleName());
            return cover;
        }
        if (StrUtil.isBlank(coverUrl)) {
            return cover;
        }

        String hash = SecureUtil.sha256(coverUrl);
        String directory = hash.substring(0, 2);
        Path dir = filesRoot.resolve(directory);
        try {
            Files.createDirectories(dir);
            PathPolicy.requireNoSymbolicLinks(filesRoot, dir);
            PathPolicy.realPathWithin(filesRoot, dir);
        } catch (Exception e) {
            log.error("准备封面缓存失败 type:{}", e.getClass().getSimpleName());
            return cover;
        }
        if (!isOverride) {
            for (String existingExtension : List.of("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
                String existingName = hash + "." + existingExtension;
                if (Files.isRegularFile(dir.resolve(existingName), LinkOption.NOFOLLOW_LINKS)) {
                    return directory + "/" + existingName;
                }
            }
        }
        try {
            SafeImageFetcher.FetchedImage fetched = SafeImageFetcher.fetch(coverUrl, ConfigUtil.snapshot());
            String extName = switch (fetched.contentType()) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/gif" -> "gif";
                case "image/webp" -> "webp";
                case "image/bmp" -> "bmp";
                default -> throw new IllegalStateException("unsupported cover image type");
            };
            String filename = hash + "." + extName;
            Path target = dir.resolve(filename);
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !isOverride) {
                return directory + "/" + filename;
            }
            writeCoverAtomically(dir, target, fetched.bytes(), isOverride);
            return directory + "/" + filename;
        } catch (Exception e) {
            log.error("保存封面失败 type:{}", e.getClass().getSimpleName());
            return cover;
        }
    }

    private static void writeCoverAtomically(Path directory, Path target, byte[] bytes,
                                             boolean replace) throws java.io.IOException {
        Path temporary = Files.createTempFile(directory, ".cover-", ".part");
        try {
            Files.write(temporary, bytes);
            try {
                if (replace) {
                    Files.move(temporary, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (FileAlreadyExistsException e) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                    throw e;
                }
            } catch (AtomicMoveNotSupportedException e) {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 校验参数
     *
     * @param ani 订阅
     */
    public static void verify(Ani ani) {
        String url = ani.getUrl();
        List<String> exclude = ani.getExclude();
        Integer season = ani.getSeason();
        Integer offset = ani.getOffset();
        String title = ani.getTitle();
        Assert.notBlank(url, "RSS URL 不能为空");
        if (Objects.isNull(exclude)) {
            ani.setExclude(new ArrayList<>());
        }
        Assert.notNull(season, "季不能为空");
        Assert.notBlank(title, "标题不能为空");
        Assert.notNull(offset, "集数偏移不能为空");
    }


    /**
     * 获取蜜柑的bangumiId
     *
     * @param ani 订阅
     * @return bangumiId
     */
    public static String getBangumiId(Ani ani) {
        String url = ani.getUrl();
        if (StrUtil.isBlank(url)) {
            return "";
        }
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);
        return decodeParamMap.get("bangumiId");
    }


    /**
     * 订阅完结迁移
     *
     * @param ani 订阅
     */
    public static void completed(Ani ani) {
        ani = ObjectUtil.clone(ani);

        String title = ani.getTitle();
        Boolean completed = ani.getCompleted();
        boolean ova = ani.getOva();
        boolean enable = ani.getEnable();
        int currentEpisodeNumber = ani.getCurrentEpisodeNumber();
        int totalEpisodeNumber = ani.getTotalEpisodeNumber();

        if (!completed) {
            // 未开启
            return;
        }

        if (totalEpisodeNumber < 1) {
            // 总集数为空
            return;
        }

        if (currentEpisodeNumber < totalEpisodeNumber) {
            // 未完结
            return;
        }

        if (enable) {
            // 仍是启用的话 主RSS仍未完结
            return;
        }

        if (ova) {
            // 剧场版不进行迁移
            return;
        }

        Config config = ObjectUtil.clone(ConfigUtil.CONFIG);

        boolean autoDisabled = config.getAutoDisabled();
        if (!autoDisabled) {
            // 未开启自动禁用订阅
            return;
        }

        completed = config.getCompleted();
        if (!completed) {
            // 未开启
            return;
        }

        String completedPathTemplate = config.getCompletedPathTemplate();

        Boolean customCompleted = ani.getCustomCompleted();
        if (customCompleted) {
            // 自定义完结迁移
            completedPathTemplate = ani.getCustomCompletedPathTemplate();
        }

        if (StrUtil.isBlank(completedPathTemplate)) {
            // 路径为空
            return;
        }

        // 旧文件路径
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        Ani pathAni = ObjectUtil.clone(ani);
        String oldPath = downloadService.getDownloadPath(pathAni, config);

        config.setDownloadPathTemplate(completedPathTemplate);
        // 因为临时修改下载位置模版以获取对应下载位置, 要关闭自定义下载位置
        pathAni.setCustomDownloadPath(false);

        // 新文件路径
        String newPath = downloadService.getDownloadPath(pathAni, config);

        if (!FileUtil.exist(oldPath)) {
            // 旧文件不存在
            return;
        }

        FileUtil.mkdir(newPath);

        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
        OwnershipService ownershipService = SpringUtil.getBean(OwnershipService.class);
        ownershipService.validateSubscriptionMove(ani.getId(), newPath);

        for (TorrentsInfo torrentsInfo : torrentsInfos) {
            if (!ownershipService.belongsTo(torrentsInfo, ani.getId())) {
                continue;
            }
            // 修改保存位置
            TorrentUtil.setSavePath(torrentsInfo, newPath);
        }

        if (!torrentsInfos.isEmpty()) {
            ThreadUtil.sleep(3000);
        }

        ownershipService.moveSubscriptionFiles(ani.getId(), newPath);
        log.info("订阅已完结 {}, 已移动归属清单文件", title);
    }

    public static Ani createAni() {
        Ani newAni = new Ani();
        Config config = ConfigUtil.CONFIG;
        return newAni
                .setId(UUID.randomUUID().toString())
                .setMikanTitle("")
                .setStandbyRssList(new ArrayList<>())
                .setOffset(0)
                .setReleaseDate(new DateTime())
                .setEnable(true)
                .setOva(false)
                .setScore(0.0)
                .setLastDownloadTime(0L)
                .setImage("")
                .setThemoviedbName("")
                .setCustomDownloadPath(false)
                .setDownloadPath("")
                .setGlobalExclude(false)
                .setCurrentEpisodeNumber(0)
                .setTotalEpisodeNumber(0)
                .setMatch(List.of())
                .setExclude(List.of("720[Pp]", "\\d-\\d", "合集", "特别篇"))
                .setBgmUrl("")
                .setSubgroup("")
                .setCustomEpisode(config.getCustomEpisode())
                .setCustomEpisodeStr(config.getCustomEpisodeStr())
                .setCustomEpisodeGroupIndex(config.getCustomEpisodeGroupIndex())
                .setOmit(true)
                .setDownloadNew(false)
                .setNotDownload(new ArrayList<>())
                .setTmdb(
                        new Tmdb()
                                .setId("")
                                .setName("")
                                .setDate(new Date())
                )
                .setUpload(config.getUpload())
                .setProcrastinating(true)
                .setCustomRenameTemplate(config.getRenameTemplate())
                .setCustomRenameTemplateEnable(false)
                .setCustomPriorityKeywordsEnable(false)
                .setCustomPriorityKeywords(new ArrayList<>())
                .setMessage(true)
                .setCustomUploadPathTarget("")
                .setCustomUploadEnable(false)
                .setCompleted(true)
                .setCustomCompleted(false)
                .setCustomCompletedPathTemplate("")
                .setCustomTags(new ArrayList<>())
                .setCustomTagsEnable(false);
    }


}
