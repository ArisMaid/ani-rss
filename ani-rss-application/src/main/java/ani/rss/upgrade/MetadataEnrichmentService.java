package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Missing-only metadata. A refresh failure never changes a committed media result. */
@Service
public class MetadataEnrichmentService {
    private final UpgradeRepository repository;
    private final LibraryPathMappingService paths;
    private final EmbyLibraryIndexService emby;
    public MetadataEnrichmentService(UpgradeRepository repository,LibraryPathMappingService paths,EmbyLibraryIndexService emby) { this.repository=repository; this.paths=paths; this.emby=emby; }
    public void enrich(Target target,Job operation) throws Exception {
        Set<Path> nfos=new LinkedHashSet<>();
        List<Map<String,String>> updates=new ArrayList<>();
        for(Version v:repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class)) {
            if(!"VIDEO".equals(v.getKind())||!"AUTOMATIC_UPGRADE".equals(v.getOrigin())) continue;
            Path video=Path.of(v.getPath());
            updates.add(Map.of("Path",emby.remotePath(video),"UpdateType",v.getEmbyItemId()==null?"Created":"Modified"));
            boolean seasonFolder=target.getEmbySeriesId()!=null&&target.getSeason()!=null||video.getParent().getFileName().toString().matches("(?i)Season[ ._-]*\\d+");
            nfos.add(SubjectEpisodeResolver.isMovie(target)?video.resolveSibling(video.getFileName().toString().replaceFirst("\\.[^.]+$","")+".nfo"):video.getParent().resolve(seasonFolder?"season.nfo":"tvshow.nfo"));
        }
        for(Path nfo:nfos) {
            Path root=target.getOutputDirectory()==null?Path.of(target.getOutputRoot()):UpgradeCommitService.allowedRoot(Path.of(target.getOutputDirectory()),ConfigUtil.snapshot().getUpgrade());
            paths.requireSafe(nfo,root,false); supplementNfo(nfo,target,operation.getId());
        }
        for(Job completed:repository.all("upgrade_jobs",target.getId(),"COMPLETED",Job.class)) {
            if(!"UPGRADE".equals(completed.getKind())) continue;
            for(JobFile file:repository.all("upgrade_job_files",completed.getId(),"DELETED",JobFile.class)) if("DELETE_OLD".equals(file.getAction())) {
                Version old=repository.get("upgrade_versions",file.getVersionId(),Version.class).orElse(null);
                updates.add(Map.of("Path",old!=null&&old.getRemotePath()!=null?old.getRemotePath():emby.remotePath(Path.of(file.getSource())),"UpdateType","Deleted"));
            }
        }
        emby.reportChanges(updates.stream().distinct().toList());
        Set<String> refresh=new LinkedHashSet<>();
        if(target.getEmbySeriesId()!=null) refresh.add(target.getEmbySeriesId());
        for(Version v:repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class)) if(v.getEmbyItemId()!=null) refresh.add(v.getEmbyItemId());
        for(String item:refresh) emby.refresh(item);
        repository.event(operation.getId(),"METADATA_ENRICHED","已补充缺失字段并请求 Emby 定向刷新；已有字段与锁定值保留");
    }
    public static List<String> supplementNfo(Path nfo,Target target,String operationId) throws Exception {
        if(Files.exists(nfo)&&Files.size(nfo)>4*1024*1024) throw new IOException("NFO_TOO_LARGE");
        var factory=DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        Document document;
        if(Files.exists(nfo)) try(var in=Files.newInputStream(nfo)) { document=factory.newDocumentBuilder().parse(in); }
        else { document=factory.newDocumentBuilder().newDocument(); document.appendChild(document.createElement(SubjectEpisodeResolver.isMovie(target)?"movie":nfo.getFileName().toString().equals("season.nfo")?"season":"tvshow")); }
        Element root=document.getDocumentElement();
        if("true".equalsIgnoreCase(text(root,"lockdata"))) return List.of();
        NodeList ids=root.getElementsByTagName("uniqueid");
        for(int i=0;i<ids.getLength();i++) { Element id=(Element)ids.item(i); if("bangumi".equalsIgnoreCase(id.getAttribute("type"))&&!id.getTextContent().isBlank()&&!target.getSubjectId().equals(id.getTextContent().trim())) throw new IOException("NFO_PROVIDER_ID_CONFLICT"); }
        List<String> changes=new ArrayList<>();
        fill(document,root,"title",target.getTitle(),changes); fill(document,root,"originaltitle",target.getOriginalTitle(),changes);
        fill(document,root,"plot",target.getSummary(),changes); fill(document,root,"premiered",target.getPremiereDate(),changes);
        fill(document,root,"thumb",target.getCover(),changes); fill(document,root,"director",target.getCredits().get("导演"),changes);
        fill(document,root,"credits",target.getCredits().get("脚本"),changes); fill(document,root,"studio",target.getCredits().get("动画制作"),changes);
        boolean hasBgm=false; for(int i=0;i<ids.getLength();i++) if("bangumi".equalsIgnoreCase(((Element)ids.item(i)).getAttribute("type"))) hasBgm=true;
        if(!hasBgm) { Element id=document.createElement("uniqueid"); id.setAttribute("type","bangumi"); id.setTextContent(target.getSubjectId()); root.appendChild(id); changes.add("bangumi"); }
        if(changes.isEmpty()) return changes;
        Path temporary=nfo.resolveSibling(nfo.getFileName()+".metadata-"+operationId);
        var transformers=TransformerFactory.newInstance(); transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        Transformer transformer=transformers.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8"); transformer.setOutputProperty(OutputKeys.INDENT,"yes");
        try(var out=Files.newOutputStream(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)) { transformer.transform(new DOMSource(document),new StreamResult(out)); }
        try { Files.move(temporary,nfo,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,nfo,StandardCopyOption.REPLACE_EXISTING); }
        return changes;
    }
    private static String text(Element root,String tag) { NodeList nodes=root.getElementsByTagName(tag); return nodes.getLength()==0?"":nodes.item(0).getTextContent(); }
    private static void fill(Document doc,Element root,String tag,String value,List<String> changes) {
        if(value==null||value.isBlank()||!text(root,tag).isBlank()) return;
        if(Arrays.asList(text(root,"lockedfields").split("[|,]")).stream().anyMatch(tag::equalsIgnoreCase)) return;
        NodeList nodes=root.getElementsByTagName(tag); Element element=nodes.getLength()==0?doc.createElement(tag):(Element)nodes.item(0);
        element.setTextContent(value); if(element.getParentNode()==null) root.appendChild(element); changes.add(tag);
    }
}
