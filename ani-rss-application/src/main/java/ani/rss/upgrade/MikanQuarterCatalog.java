package ani.rss.upgrade;

import ani.rss.entity.Mikan;
import ani.rss.service.MikanService;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Mikan's real season menu and directory; Bangumi remains the premiere authority. */
@Service
public class MikanQuarterCatalog {
    private final MikanService mikan;
    private final UpgradeRepository repository;
    public MikanQuarterCatalog(MikanService mikan,UpgradeRepository repository) { this.mikan=mikan; this.repository=repository; }
    public List<Mikan.Season> seasons() {
        var result=mikan.catalog(new Mikan.Season());
        if(result.getSeasons()==null||result.getSeasons().isEmpty()) throw new UpgradeHttp.SourceFailure("MIKAN_SEASON_MENU_MISSING",true);
        var available=result.getSeasons().stream().filter(s->s.getYear()!=null&&s.getSeason()!=null&&List.of("冬","春","夏","秋").contains(s.getSeason()))
                .distinct().toList();
        if(available.isEmpty()) throw new UpgradeHttp.SourceFailure("MIKAN_SEASON_MENU_INVALID",false);
        return available;
    }
    public static void normalize(Campaign campaign) {
        if(!"MIKAN".equals(campaign.getCatalogSource())) {
            if(campaign.getCatalogSource()!=null&&!"DATE".equals(campaign.getCatalogSource())) throw new IllegalArgumentException("目录来源无效");
            campaign.setMikanYear(null); campaign.setMikanSeason(null); return;
        }
        if(campaign.getMikanYear()==null||campaign.getMikanYear()<1900||campaign.getMikanYear()>2200) throw new IllegalArgumentException("请选择有效年份");
        int month=switch(Objects.toString(campaign.getMikanSeason(),"")) { case "冬"->1; case "春"->4; case "夏"->7; case "秋"->10; default->throw new IllegalArgumentException("请选择冬、春、夏或秋季"); };
        LocalDate start=LocalDate.of(campaign.getMikanYear(),month,1);
        campaign.setStartDate(start.toString()); campaign.setEndDate(start.plusMonths(3).minusDays(1).toString());
    }
    public void validateSelection(Campaign campaign) {
        normalize(campaign);
        if("MIKAN".equals(campaign.getCatalogSource())&&seasons().stream().noneMatch(s->Objects.equals(s.getYear(),campaign.getMikanYear())&&Objects.equals(s.getSeason(),campaign.getMikanSeason())))
            throw new IllegalArgumentException("Mikan 尚未提供该年份、季度目录，请刷新后选择");
    }
    public List<MikanCatalogEntry> preview(int year,String season) {
        Campaign selection=new Campaign(); selection.setCatalogSource("MIKAN"); selection.setMikanYear(year); selection.setMikanSeason(season); validateSelection(selection);
        Mikan result=mikan.catalog(new Mikan.Season().setYear(year).setSeason(season));
        if(result.getWeeks()==null) throw new UpgradeHttp.SourceFailure("MIKAN_DIRECTORY_MISSING",true);
        Map<String,MikanCatalogEntry> entries=new LinkedHashMap<>();
        var idPattern=Pattern.compile("/Home/Bangumi/(\\d+)$");
        for(var week:result.getWeeks()) if(week!=null&&week.getItems()!=null) for(var info:week.getItems()) {
            if(info==null||info.getUrl()==null) continue;
            var match=idPattern.matcher(URI.create(info.getUrl()).getPath());
            if(!match.find()||!sameSource(info.getUrl())) throw new UpgradeHttp.SourceFailure("MIKAN_DIRECTORY_ID_INVALID",false);
            MikanCatalogEntry entry=new MikanCatalogEntry(); entry.setMikanId(match.group(1)); entry.setTitle(info.getTitle()); entry.setCategory(week.getWeekLabel()); entry.setUrl(info.getUrl()); entry.setCover(info.getCover());
            entries.putIfAbsent(entry.getMikanId(),entry);
        }
        return new ArrayList<>(entries.values());
    }
    private static boolean sameSource(String url) {
        URI source=URI.create(UpgradeHttp.base(ConfigUtil.snapshot().getMikanHost())), target=URI.create(UpgradeHttp.resourceUrl(url));
        return Objects.equals(source.getHost(),target.getHost())&&source.getPort()==target.getPort()&&Objects.equals(source.getScheme(),target.getScheme());
    }
    public record Binding(String subjectId,List<String> feeds) {}
    public static Binding binding(String html,String url) {
        var document=Jsoup.parse(html,url); Set<String> ids=new LinkedHashSet<>();
        var mikanId=Pattern.compile("/Home/Bangumi/(\\d+)$").matcher(URI.create(url).getPath());
        if(!mikanId.find()) throw new IllegalArgumentException("Mikan 条目地址无效");
        for(var link:document.select(".bangumi-info a[href]")) {
            URI uri;
            try { uri=URI.create(link.absUrl("href")); } catch(RuntimeException e) { continue; }
            if(!Set.of("bgm.tv","bangumi.tv","chii.in").contains(Objects.toString(uri.getHost(),""))) continue;
            var match=Pattern.compile("^/subject/(\\d+)/?$").matcher(Objects.toString(uri.getPath(),"")); if(match.matches()) ids.add(match.group(1));
        }
        if(ids.size()!=1) throw new UpgradeHttp.SourceFailure(ids.isEmpty()?"MIKAN_SUBJECT_LINK_MISSING":"MIKAN_SUBJECT_LINK_CONFLICT",false);
        Set<String> feeds=new LinkedHashSet<>();
        for(var link:document.select("a[href]")) {
            String href=link.absUrl("href");
            if(href.isBlank()) continue;
            URI uri=URI.create(href);
            if("/RSS/Bangumi".equalsIgnoreCase(uri.getPath())&&sameSource(href)&&Objects.toString(uri.getQuery(),"").matches("(?i)(?:.*&)?bangumiId="+mikanId.group(1)+"(?:&.*)?")) feeds.add(href);
        }
        if(feeds.isEmpty()) throw new UpgradeHttp.SourceFailure("MIKAN_RSS_LINK_MISSING",false);
        var aggregate=feeds.stream().filter(feed->!Objects.toString(URI.create(feed).getQuery(),"").toLowerCase(Locale.ROOT).contains("subgroupid=")).toList();
        return new Binding(ids.iterator().next(),aggregate.isEmpty()?new ArrayList<>(feeds):aggregate);
    }
    /** At most three detail requests per scheduler step, with durable snapshot and offset. */
    public boolean scanStep(Campaign campaign,SubjectCatalogProvider subjects) {
        String id="mikan-catalog:"+campaign.getId();
        Cursor cursor=repository.get("upgrade_scan_cursors",id,Cursor.class).orElseGet(Cursor::new);
        if(cursor.isComplete()) return true;
        if(cursor.getMikanEntries()==null) {
            cursor.setMikanEntries(preview(campaign.getMikanYear(),campaign.getMikanSeason())); cursor.setTotal(cursor.getMikanEntries().size());
            cursor.setId(id); cursor.setScope(campaign.getId()); cursor.setScanStarted(System.currentTimeMillis()); save(cursor);
        }
        int stop=Math.min(cursor.getOffset()+3,cursor.getMikanEntries().size());
        for(int i=cursor.getOffset();i<stop;i++) {
            var entry=cursor.getMikanEntries().get(i);
            try {
                Binding binding=binding(UpgradeHttp.getText(entry.getUrl()),entry.getUrl());
                var subject=subjects.detail(binding.subjectId());
                if(!binding.subjectId().equals(UpgradeHttp.string(subject,"id"))||UpgradeHttp.integer(subject,"type",0)!=2)
                    throw new UpgradeHttp.SourceFailure("MIKAN_SUBJECT_IDENTITY_CONFLICT",false);
                Target target=subjects.accept(campaign,subject,"BANGUMI_DETAIL");
                if(target!=null) {
                    if(entry.getTitle()!=null&&!entry.getTitle().isBlank()&&!target.getAliases().contains(entry.getTitle())) target.getAliases().add(entry.getTitle());
                    Set<String> feeds=new LinkedHashSet<>(target.getRssUrls()); feeds.addAll(binding.feeds()); target.setRssUrls(new ArrayList<>(feeds));
                    repository.save("upgrade_targets",target.getId(),target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target);
                }
            } catch(UpgradeHttp.SourceFailure failure) {
                cursor.setError(entry.getTitle()+"："+failure.getMessage());
                if(failure.retryable()) { save(cursor); throw failure; }
                // Continue valid titles, retain unresolved evidence and never claim complete coverage.
                cursor.setCoverageIncomplete(true); repository.event(campaign.getId(),failure.getMessage(),entry.getTitle()+" "+entry.getUrl());
            }
            cursor.setOffset(i+1); save(cursor);
        }
        if(cursor.getOffset()>=cursor.getTotal()) { cursor.setComplete(true); cursor.setLastSuccess(System.currentTimeMillis()); if(!cursor.isCoverageIncomplete()) cursor.setError(null); save(cursor); }
        return cursor.isComplete();
    }
    private void save(Cursor cursor) { repository.save("upgrade_scan_cursors",cursor.getId(),cursor.getScope(),null,cursor.isComplete()?(cursor.isCoverageIncomplete()?"INCOMPLETE":"COMPLETE"):"SCANNING",1,cursor); }
}
