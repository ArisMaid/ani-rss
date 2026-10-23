package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.*;

@Service
public class ReleaseAnalysisService {
    private static final Pattern SEASON_EP=Pattern.compile("(?i)S(\\d{1,3})[ ._-]*E(\\d{1,4}(?:\\.\\d+)?)(?:\\s*[-~–]\\s*(?:E)?(\\d{1,4}))?");
    private static final Pattern EPISODE=Pattern.compile("(?i)(?:[\\[【]\\s*|\\s-\\s|\\bEP?\\s*|第)(\\d{1,4}(?:\\.\\d+)?)(?:\\s*[-~–]\\s*(\\d{1,4}))?(?:\\s*[\\]】集话話]|\\b)");
    public Release parse(String title,String subjectId,String source) {
        Release r=new Release(); r.setTitle(title); r.setSubjectId(subjectId); r.setSource(source);
        String s=title==null?"":title;
        if(s.matches("(?is).*(?:director[’']?s[ ._-]*cut|导演剪辑|導演剪輯).*")) r.setContentEdition("DIRECTORS_CUT");
        else if(s.matches("(?is).*(?:总集篇|總集篇|\\brecap\\b|\\bcompilation\\b).*")) r.setContentEdition("RECAP");
        else if(s.matches("(?is).*(?:extended[ ._-]*(?:cut|edition)|加长版|加長版|完全版).*")) r.setContentEdition("EXTENDED");
        Matcher se=SEASON_EP.matcher(s);
        if(se.find()) {
            r.setSeason(Integer.parseInt(se.group(1))); r.getEpisodes().add(number(se.group(2)));
            if(se.group(3)!=null) {
                var start=new java.math.BigDecimal(se.group(2)); int end=Integer.parseInt(se.group(3));
                if(start.stripTrailingZeros().scale()<=0&&end>=start.intValue()&&end-start.intValue()<=500) for(int n=start.intValue();n<=end;n++) r.getEpisodes().add(Integer.toString(n));
            }
        }
        else {
            Matcher ep=EPISODE.matcher(s);
            while(ep.find()) {
                double first=Double.parseDouble(ep.group(1));
                boolean year=ep.group().matches("^[\\[【]\\s*(?:19|20)\\d{2}\\s*[\\]】]$");
                if(Set.of(480D,576D,720D,1080D,2160D,4320D).contains(first) || year || first<0) continue;
                if(ep.group(2)==null) r.getEpisodes().add(number(ep.group(1)));
                else {
                    int end=Integer.parseInt(ep.group(2));
                    if(first%1!=0||end<first||end-first>500) continue;
                    for(int n=(int)first;n<=end;n++) r.getEpisodes().add(Integer.toString(n));
                }
                break;
            }
        }
        boolean normalBatch=r.getEpisodes().size()>1;
        Matcher special=Pattern.compile("(?i)\\b(?:SP|OVA|OAD)[ ._-]*(\\d{1,4}(?:\\.\\d+)?)(?:\\s*[-~–]\\s*(\\d{1,4}))?").matcher(s);
        if(special.find()&&!normalBatch) {
            r.getEpisodes().clear(); r.getEpisodes().add(number(special.group(1))); r.setChapterType("SPECIAL");
            if(special.group(2)!=null) {
                var start=new java.math.BigDecimal(special.group(1)); int end=Integer.parseInt(special.group(2));
                if(start.stripTrailingZeros().scale()<=0&&end>=start.intValue()&&end-start.intValue()<=500) for(int n=start.intValue();n<=end;n++) r.getEpisodes().add(Integer.toString(n));
            }
        }
        Matcher seasonOnly=Pattern.compile("(?i)\\bS(\\d{1,3})\\b|第([一二三四五六七八九十\\d]{1,3})季").matcher(s);
        if(r.getSeason()==null&&seasonOnly.find()) {
            if(seasonOnly.group(1)!=null) r.setSeason(Integer.parseInt(seasonOnly.group(1)));
            else { String n=seasonOnly.group(2); if(n.matches("\\d+")) r.setSeason(Integer.parseInt(n)); else if(n.length()==1) r.setSeason("一二三四五六七八九十".indexOf(n)+1); }
        }
        if(!normalBatch&&Pattern.compile("(?i)(?:\\b(?:SP|OVA|OAD|NCOP|NCED|OP|ED|PV)\\b|总集篇|總集篇|特别篇|特別篇)").matcher(s).find()) {
            if(s.matches("(?is).*\\b(?:NCOP|NCED|OP|ED|PV)\\b.*")) r.setChapterType("EXTRA");
            else r.setChapterType("SPECIAL");
        }
        Matcher resolution=Pattern.compile("(?i)(2160|1080|720|480)[pi]\\b").matcher(s);
        if(resolution.find()) r.setResolution(Integer.parseInt(resolution.group(1)));
        else if(s.matches("(?is).*\\b4K\\b.*")) r.setResolution(2160);
        Matcher group=Pattern.compile("^[\\[【]([^\\]】]{1,100})[\\]】]").matcher(s);
        if(group.find()) {
            String g=group.group(1);
            if(!g.matches("(?i).*(?:\\d{3,4}p|CHS|CHT|简|簡|繁|中字).*"))
                r.getGroups().addAll(Arrays.asList(g.split("\\s*(?:&|＆|\\+|×)\\s*")));
        }
        if(s.matches("(?is).*(?:\\b(?:CHS|SC|GB)\\b|简体|簡體|简日|簡日|简繁|簡繁|简中|簡中|简字|简内|简外).*$")) r.getLanguages().add("CHS");
        if(s.matches("(?is).*(?:\\b(?:CHT|TC|BIG5)\\b|繁体|繁體|繁日|简繁|簡繁|繁中|繁字|繁内|繁外).*$")) r.getLanguages().add("CHT");
        if(s.matches("(?is).*(?:\\b(?:JPN|JAP|JP)\\b|简日|簡日|繁日|简日繁|簡日繁|中日|日文字幕).*$")) r.getLanguages().add("JPN");
        if(s.contains("简日繁")||s.contains("簡日繁")) r.getLanguages().addAll(List.of("CHS","CHT","JPN"));
        if(s.matches("(?is).*(?:双语|雙語|同屏|bilingual).*$")) r.setSubtitleLayout("BILINGUAL");
        if(s.matches("(?is).*(?:硬字幕|内嵌|內嵌|hardsub).*$")) r.setSubtitlePackaging("HARD");
        else if(s.matches("(?is).*(?:外挂|外掛|external).*$")) r.setSubtitlePackaging("EXTERNAL");
        else if(s.matches("(?is).*(?:内封|內封|softsub).*$")) r.setSubtitlePackaging("SOFT");
        if(s.matches("(?is).*\\b(?:HEVC|H[ .]?265|X265)\\b.*")) r.setCodec("HEVC");
        else if(s.matches("(?is).*\\b(?:AVC|H[ .]?264|X264)\\b.*")) r.setCodec("AVC");
        else if(s.matches("(?is).*\\bAV1\\b.*")) r.setCodec("AV1");
        if(s.matches("(?is).*\\b(?:BD|BDRIP|BLURAY|BLU-RAY)\\b.*")) r.setSourceType("BD");
        else if(s.matches("(?is).*\\bWEB(?:RIP|DL|-DL)?\\b.*")) r.setSourceType("WEB");
        else if(s.matches("(?is).*\\bDVD(?:RIP)?\\b.*")) r.setSourceType("DVD");
        Matcher format=Pattern.compile("(?i)(?:\\.|\\[|\\s)(mkv|mp4|avi|webm|ts)(?:\\]|\\s|$)").matcher(s);
        if(format.find()) r.setContainer(format.group(1).toLowerCase(Locale.ROOT));
        Matcher revision=Pattern.compile("(?i)\\bv([1-9]\\d?)\\b").matcher(s);
        r.setCorrection(revision.find()?Integer.parseInt(revision.group(1)):s.contains("修正")||s.contains("修复")?2:1);
        r.getEvidence().add(new Evidence(source,"releaseTitle",s.substring(0,Math.min(s.length(),12000))));
        return r;
    }
    public static String number(String n) {
        if(n==null||n.length()>20) throw new IllegalArgumentException("章节编号无效");
        var value=new java.math.BigDecimal(n).stripTrailingZeros();
        if(value.signum()<0||value.compareTo(java.math.BigDecimal.valueOf(100000))>0||value.scale()>6) throw new IllegalArgumentException("章节编号无效");
        return value.toPlainString();
    }
    public static String canonicalHash(String value) {
        if(value==null) return null;
        Matcher m=Pattern.compile("(?i)(?:urn:btih:)?([0-9a-f]{40})(?:&|$)").matcher(value);
        if(m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        Matcher b32=Pattern.compile("(?i)(?:urn:btih:)([A-Z2-7]{32})(?:&|$)").matcher(value);
        if(b32.find()) return java.util.HexFormat.of().formatHex(cn.hutool.core.codec.Base32.decode(b32.group(1).toUpperCase(Locale.ROOT)));
        return null;
    }
}
