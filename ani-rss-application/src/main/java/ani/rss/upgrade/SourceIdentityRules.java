package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.Target;
import java.util.*;

/** Search hints are weaker than an explicit provider subject binding. */
public final class SourceIdentityRules {
    private SourceIdentityRules() {}
    public static List<String> aliases(Target target) {
        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(String name:List.of(Objects.toString(target.getTitle(),""),Objects.toString(target.getOriginalTitle(),"")))
            if(!name.isBlank()) names.add(name);
        for(String name:target.getAliases()) if(name!=null&&!name.isBlank()) names.add(name);
        return names.stream().limit(8).toList();
    }
    public static boolean titleMatches(Target target,String title) {
        String normalized=SubjectEpisodeResolver.normalized(title);
        Integer observed=new ReleaseAnalysisService().parse(title,target.getSubjectId(),"IDENTITY").getSeason();
        Integer expected=SubjectEpisodeResolver.storySeason(target);
        if(observed!=null&&expected!=null&&!observed.equals(expected)
                &&!SubjectEpisodeResolver.seasonLabelVariant(target,new ReleaseAnalysisService().parse(title,target.getSubjectId(),"IDENTITY"))) return false;
        return aliases(target).stream().anyMatch(name->{
            String key=SubjectEpisodeResolver.normalized(name);
            if(key.length()<2) return false;
            if(key.matches("[a-z0-9]+")&&name.trim().split("\\s+").length==1) {
                // A standalone work title is usable; a word inside another title is not.
                String body=Objects.toString(title,"").replaceAll("^[\\[【].*?[\\]】]\\s*","")
                        .replaceAll("[\\[【(].*?[\\]】)]","")
                        .replaceAll("(?i)\\s+-\\s*\\d+(?:v\\d+)?\\s*$","");
                return key.equals(SubjectEpisodeResolver.normalized(body));
            }
            return normalized.contains(key);
        });
    }
    public static boolean bound(UpgradeModels.Release release) {
        return release.getEvidence().stream().anyMatch(e->"identity".equals(e.getField())
                &&Set.of("PROVIDER_SUBJECT_BINDING","USER_CORRECTION").contains(e.getSource()));
    }
}
