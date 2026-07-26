package ani.rss.service;

import ani.rss.commons.GroupRegexUtils;
import ani.rss.entity.*;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MikanService {
    private static final int MIKAN_REQUEST_TIMEOUT_MILLIS = 10_000;

    @Resource
    private PublicScoreService publicScoreService;

    public static String getMikanHost() {
        Config config = ConfigUtil.CONFIG;
        String mikanHost = config.getMikanHost();
        mikanHost = StrUtil.blankToDefault(mikanHost, "https://mikanani.me");
        return mikanHost;
    }

    /**
     * 搜索mikan番剧列表
     *
     * @param text   关键字
     * @param season 集度
     * @return Mikan
     */
    public Mikan list(String text, Mikan.Season season) {
        Mikan mikan = search(text, season);
        List<MikanInfo> mikanInfos = Optional.ofNullable(mikan.getWeeks())
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(Mikan.Week::getItems)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .toList();

        Map<String, MikanBgm> mikanBgmMap = new HashMap<>();
        try {
            mikanBgmMap = publicScoreService.getMikanScores(mikanInfos);
        } catch (RuntimeException e) {
            // Scores are an enhancement. A public score source outage must not hide a Mikan season.
            log.warn("Unable to enrich the Mikan list with public scores");
        }

        Set<String> bgmIds = AniUtil.ANI_LIST
                .stream()
                .map(Ani::getBgmUrl)
                .filter(StrUtil::isNotBlank)
                .map(BgmUtil::getSubjectId)
                .collect(Collectors.toSet());

        applyScores(mikan, mikanBgmMap, bgmIds);

        return mikan;
    }

    static void applyScores(Mikan mikan, Map<String, MikanBgm> mikanBgmMap, Set<String> subscribedBgmIds) {
        if (mikan == null || mikan.getWeeks() == null) {
            return;
        }
        Map<String, MikanBgm> scores = Optional.ofNullable(mikanBgmMap).orElseGet(Map::of);
        Set<String> subscriptions = Optional.ofNullable(subscribedBgmIds).orElseGet(Set::of);
        List<Mikan.Week> weeks = mikan.getWeeks();
        for (Mikan.Week week : weeks) {
            List<MikanInfo> mikanInfos = week.getItems();
            if (mikanInfos == null) {
                continue;
            }
            for (MikanInfo mikanInfo : mikanInfos) {
                if (mikanInfo == null) {
                    continue;
                }
                mikanInfo.setScore(0.0);
                String url = mikanInfo.getUrl();
                String mikanId = PublicScoreService.extractMikanId(url);
                if (StrUtil.isBlank(mikanId)) {
                    continue;
                }
                MikanBgm mikanBgm = scores.get(mikanId);
                if (mikanBgm == null) {
                    continue;
                }

                Double score = Optional.ofNullable(mikanBgm.getScore()).orElse(0.0);
                String bgmId = mikanBgm.getBgmId();
                mikanInfo.setScore(score)
                        .setBgmId(bgmId);

                if (subscriptions.contains(bgmId)) {
                    mikanInfo.setExists(true);
                }
            }
            ListUtil.sort(
                    mikanInfos,
                    Comparator.comparingDouble((MikanInfo info) -> Optional.ofNullable(info.getScore()).orElse(0.0))
                            .reversed()
            );
        }
    }

    public Mikan search(String text, Mikan.Season season) {
        Set<String> bangumiIdSet = AniUtil.ANI_LIST.stream()
                .map(AniUtil::getBangumiId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());

        Mikan mikan = new Mikan();
        List<Mikan.Week> weeks = new ArrayList<>();
        List<Mikan.Season> seasons = new ArrayList<>();

        String regex = "^id: (\\d+)$";

        if (ReUtil.contains(regex, text)) {
            String mikanId = ReUtil.get(regex, text, 1);

            MikanInfo mikanInfo = getMikanInfo(mikanId);

            weeks.add(
                    new Mikan.Week()
                            .setWeekLabel("Search")
                            .setItems(Collections.singletonList(mikanInfo))
            );

            return mikan
                    .setTotalItem(1)
                    .setWeeks(weeks)
                    .setSeasons(seasons);
        }

        String url = getMikanHost();
        if (StrUtil.isNotBlank(text)) {
            url = url + "/Home/Search?searchstr=" + URLUtil.encodeBlank(text);
        } else {
            Integer year = season.getYear();
            String seasonStr = season.getSeason();
            if (Objects.nonNull(year) && StrUtil.isNotBlank(seasonStr)) {
                url = StrUtil.format(
                        "{}/Home/BangumiCoverFlowByDayOfWeek?year={}&seasonStr={}",
                        url, year, seasonStr
                );
            }
        }

        try {
            HttpReq.get(url)
                    .timeout(MIKAN_REQUEST_TIMEOUT_MILLIS)
                    .then(res -> {
                    Document document = Jsoup.parse(res.body());
                    Elements dateSelects = document.select(".date-select");
                    if (!dateSelects.isEmpty()) {
                        Element dateSelect = dateSelects.get(0);
                        String dateText = dateSelects.get(0).select(".date-text").text().trim();
                        Element dropdownMenu = dateSelect.selectFirst(".dropdown-menu");
                        if (dropdownMenu == null) {
                            throw new IllegalStateException("Mikan response is missing the season menu");
                        }
                        for (Element child : dropdownMenu.children()) {
                            Elements seasonItems = child.select("li");
                            for (Element seasonItem : seasonItems.subList(1, seasonItems.size())) {
                                Element a = seasonItem.selectFirst("a");
                                if (a == null) {
                                    throw new IllegalStateException("Mikan response contains a season without a link");
                                }
                                String dataYear = a.attr("data-year");
                                String dataSeason = a.attr("data-season");
                                String selectLabel = StrUtil.format("{} {}", dataYear, dataSeason);
                                seasons.add(
                                        new Mikan.Season()
                                                .setYear(Integer.parseInt(dataYear))
                                                .setSeason(dataSeason)
                                                .setSeasonLabel(selectLabel)
                                                .setSelect(dateText.startsWith(selectLabel))
                                );
                            }
                        }
                    }

                    Function<Element, List<MikanInfo>> get = (el) -> {
                        List<MikanInfo> mikanInfos = new ArrayList<>();
                        if (Objects.isNull(el)) {
                            return mikanInfos;
                        }
                        Elements lis = el.select("li");
                        for (Element li : lis) {
                            Element image = li.selectFirst("span");
                            String img = image == null ? "" : getMikanHost() + image.attr("data-src");
                            Elements aa = li.select("a");
                            if (aa.isEmpty()) {
                                continue;
                            }
                            String href = getMikanHost() + aa.get(0).attr("href");
                            String title = aa.get(0).text();

                            String id = ReUtil.get("\\d+(/)?$", href, 0);
                            id = StrUtil.blankToDefault(id, "");
                            mikanInfos.add(
                                    new MikanInfo()
                                            .setCover(img)
                                            .setTitle(title)
                                            .setUrl(href)
                                            .setExists(bangumiIdSet.contains(id))
                                            .setScore(0.0)
                            );
                        }
                        return mikanInfos;
                    };

                    Elements skBangumis = document.select(".sk-bangumi");

                    if (skBangumis.isEmpty()) {
                        List<MikanInfo> mikanInfos = get.apply(document.selectFirst(".an-ul"));

                        Mikan.Week item = new Mikan.Week();
                        item.setItems(mikanInfos)
                                .setWeekLabel("Search");

                        weeks.add(item);
                    } else {
                        for (Element skBangumi : skBangumis) {
                            List<MikanInfo> mikanInfos = get.apply(skBangumi);
                            if (mikanInfos.isEmpty()) {
                                // 番剧为空
                                continue;
                            }

                            // 星期
                            String label = skBangumi.children().get(0).text().trim();

                            Mikan.Week week = new Mikan.Week();
                            week.setWeekLabel(label)
                                    .setItems(mikanInfos);
                            weeks.add(week);
                        }
                    }
                    });
        } catch (Exception e) {
            // Keep the Mikan picker responsive when its upstream is unavailable or stalls.
            log.warn("Mikan list request failed");
        }

        int totalItems = weeks
                .stream()
                .mapToInt(it -> it.getItems().size())
                .sum();

        return mikan
                .setWeeks(weeks)
                .setTotalItem(totalItems)
                .setSeasons(seasons);
    }

    /**
     * 获取番剧字幕组
     *
     * @param url 链接
     * @return 字幕组列表
     */
    public List<Mikan.Group> getGroups(String url) {
        List<Mikan.Group> groupList = HttpReq.get(url)
                .timeout(MIKAN_REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    Document document = Jsoup.parse(res.body());
                    List<Mikan.Group> groups = new ArrayList<>();

                    String bgmUrl = "";
                    Elements bangumiInfos = document.select(".bangumi-info");
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            Element link = bangumiInfo.selectFirst("a");
                            if (link != null) {
                                bgmUrl = link.attr("href");
                            }
                        }
                    }

                    Elements subgroupTitles = document.select(".leftbar-item");

                    for (Element subgroupText : subgroupTitles) {
                        Mikan.Group group = new Mikan.Group();
                        List<Mikan.Item> items = new ArrayList<>();
                        group.setItems(items)
                                .setBgmUrl(bgmUrl);
                        String label = subgroupText.select("a.subgroup-name").text().trim();
                        // id锚点，例如 #213
                        String id = subgroupText.select("a.subgroup-name").attr("data-anchor");
                        Element anchor = document.selectFirst(id);
                        Element rss = anchor == null ? null : anchor.selectFirst(".mikan-rss");
                        if (rss == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup RSS link");
                        }
                        String attr = rss.attr("href");
                        group.setLabel(label)
                                .setRss(getMikanHost() + attr);
                        groups.add(group);
                        // 字幕组更新日期
                        String day = subgroupText.select(".date").text().trim();
                        group.setUpdateDay(day);

                        Element subgroupAnchor = document.selectFirst(id);
                        Element table = subgroupAnchor == null ? null : subgroupAnchor.nextElementSibling();
                        Element tbody = table == null ? null : table.selectFirst("tbody");
                        if (tbody == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup table");
                        }
                        for (Element tr : tbody.children()) {
                            String title = tr.select("a").get(0).ownText();
                            String magnet = tr.select("a").get(1).attr("data-clipboard-text");
                            String formatSize = tr.select("td").get(2).text().trim();
                            String dateStr = tr.select("td").get(3).text().trim();

                            String torrent = tr.select("a").get(2).attr("href");

                            String mikanHost = getMikanHost();

                            items.add(
                                    new Mikan.Item()
                                            .setTitle(title)
                                            .setMagnet(magnet)
                                            .setFormatSize(formatSize)
                                            .setCreatedAt(DateUtil.parse(dateStr))
                                            .setTorrent(mikanHost + torrent)
                            );
                        }
                    }

                    return groups;
                });


        for (Mikan.Group group : groupList) {
            List<Mikan.Item> items = group.getItems();
            GroupRegex groupRegx = GroupRegexUtils.toGroupRegx(items, Mikan.Item::getTitle);

            group.setGroupRegex(groupRegx);
        }

        return groupList;
    }

    public static MikanInfo getMikanInfo(String bangumiId) {
        URI host = URLUtil.getHost(URLUtil.url(getMikanHost()));
        String url = host + "/Home/Bangumi/" + bangumiId;
        return HttpReq.get(url)
                .timeout(MIKAN_REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    MikanInfo mikanInfo = new MikanInfo();

                    mikanInfo.setUrl(url);

                    Document html = Jsoup.parse(res.body());

                    Element cover = html.selectFirst(".content > img");
                    if (Objects.nonNull(cover)) {
                        mikanInfo.setCover(host + cover.attr("src"));
                    }

                    Element bangumiTitle = html.selectFirst(".bangumi-title");
                    if (Objects.nonNull(bangumiTitle)) {
                        mikanInfo.setTitle(bangumiTitle.text().trim());
                    }

                    Elements bangumiInfos = html.select(".bangumi-info");
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            Element link = bangumiInfo.selectFirst("a");
                            if (link != null) {
                                mikanInfo.setBgmUrl(link.attr("href"));
                            }
                        }
                    }

                    // 获取字幕组
                    List<Mikan.Group> groups = new ArrayList<>();

                    Elements subgroupTitles = html.select(".leftbar-item");

                    for (Element subgroupText : subgroupTitles) {
                        Mikan.Group group = new Mikan.Group();
                        groups.add(group);

                        List<Mikan.Item> items = new ArrayList<>();
                        group.setItems(items);

                        String label = subgroupText.select("a.subgroup-name").text().trim();

                        // id锚点，例如 #213
                        String id = subgroupText.select("a.subgroup-name").attr("data-anchor");

                        Element anchor = html.selectFirst(id);
                        Element rss = anchor == null ? null : anchor.selectFirst(".mikan-rss");
                        if (rss == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup RSS link");
                        }
                        String attr = rss.attr("href");

                        group.setLabel(label)
                                .setSubgroupId(id.replace("#", "").trim())
                                .setRss(getMikanHost() + attr);

                        // 字幕组更新日期
                        String day = subgroupText.select(".date").text().trim();

                        group.setUpdateDay(day);

                        Element subgroupAnchor = html.selectFirst(id);
                        Element table = subgroupAnchor == null ? null : subgroupAnchor.nextElementSibling();
                        Element tbody = table == null ? null : table.selectFirst("tbody");
                        if (tbody == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup table");
                        }
                        for (Element tr : tbody.children()) {
                            String title = tr.select("a").get(0).ownText();
                            String magnet = tr.select("a").get(1).attr("data-clipboard-text");
                            String formatSize = tr.select("td").get(2).text().trim();
                            String dateStr = tr.select("td").get(3).text().trim();

                            String torrent = tr.select("a").get(2).attr("href");

                            String mikanHost = getMikanHost();

                            items.add(
                                    new Mikan.Item()
                                            .setTitle(title)
                                            .setMagnet(magnet)
                                            .setFormatSize(formatSize)
                                            .setCreatedAt(DateUtil.parse(dateStr))
                                            .setTorrent(mikanHost + torrent)
                            );
                        }
                    }

                    mikanInfo.setGroups(groups);
                    return mikanInfo;
                });
    }

    public static void getMikanInfo(Ani ani, String subgroupId) {
        String bangumiId = AniUtil.getBangumiId(ani);
        if (StrUtil.isBlank(bangumiId)) {
            return;
        }

        MikanInfo mikanInfo = getMikanInfo(bangumiId);
        Assert.notNull(mikanInfo, "未获取到 Mikan 信息");

        String title = mikanInfo.getTitle();
        String bgmUrl = mikanInfo.getBgmUrl();
        List<Mikan.Group> groups = mikanInfo.getGroups();

        ani
                .setMikanTitle(title)
                .setBgmUrl(bgmUrl);

        for (Mikan.Group group : groups) {
            String id = group.getSubgroupId();
            String label = group.getLabel();
            if (subgroupId.equals(id)) {
                ani.setSubgroup(label);
            }
        }
    }

    /**
     * 从rss中获得字幕组id
     *
     * @param url 链接
     * @return 字幕组id
     */
    public static String getSubgroupId(String url) {
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : decodeParamMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("subgroupid")) {
                return entry.getValue();
            }
        }
        return "";
    }

}
