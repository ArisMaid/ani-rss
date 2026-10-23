package ani.rss.upgrade;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Reads bounded metadata evidence; never rewrites an adopted library file. */
public final class ExistingMetadataEvidence {
    public record Context(String bangumiId,String text,Set<String> groups) {}
    private ExistingMetadataEvidence() {}

    public static Context read(Path video,Path root,boolean independentSubject) throws IOException {
        String base=video.getFileName().toString().replaceFirst("\\.[^.]+$","");
        List<Path> candidates=List.of(video.resolveSibling(base+".nfo"),video.getParent().resolve("season.nfo"),video.getParent().resolve("tvshow.nfo"));
        String bangumi=null; StringBuilder evidence=new StringBuilder(); Set<String> groups=new TreeSet<>();
        for(int index=0;index<candidates.size();index++) {
            Path nfo=candidates.get(index); if(!Files.exists(nfo,LinkOption.NOFOLLOW_LINKS)) continue;
            new LibraryPathMappingService().requireSafe(nfo,root,true);
            if(Files.size(nfo)>4*1024*1024) throw new IOException("EXISTING_NFO_TOO_LARGE");
            try(var input=Files.newInputStream(nfo)) {
                var factory=DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities",false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                Element element=factory.newDocumentBuilder().parse(input).getDocumentElement();
                if(index<2||independentSubject) {
                    NodeList ids=element.getElementsByTagName("uniqueid");
                    for(int i=0;i<ids.getLength();i++) {
                        Element id=(Element)ids.item(i); if(!Set.of("bangumi","bgm").contains(id.getAttribute("type").toLowerCase(Locale.ROOT))) continue;
                        String value=id.getTextContent().strip(); if(!value.matches("\\d+")) continue;
                        if(bangumi!=null&&!bangumi.equals(value)) throw new IOException("EXISTING_NFO_PROVIDER_CONFLICT"); bangumi=value;
                    }
                }
                for(String tag:List.of("originalfilename","releasegroup","subtitle","language","codec","source")) {
                    NodeList nodes=element.getElementsByTagName(tag);
                    for(int i=0;i<Math.min(16,nodes.getLength());i++) {
                        String value=nodes.item(i).getTextContent().strip(); if(value.isBlank()) continue;
                        if("releasegroup".equals(tag)&&value.length()<=100) for(String group:value.split("\\s*(?:&|＆|×|\\+)\\s*")) if(!group.isBlank()&&groups.size()<8) groups.add(group);
                        evidence.append(tag).append(": ").append(value,0,Math.min(value.length(),1000)).append('\n');
                    }
                }
            } catch(IOException error) { throw error; }
            catch(Exception error) { throw new IOException("EXISTING_NFO_INVALID",error); }
        }
        return new Context(bangumi,evidence.substring(0,Math.min(12000,evidence.length())),groups);
    }
}
