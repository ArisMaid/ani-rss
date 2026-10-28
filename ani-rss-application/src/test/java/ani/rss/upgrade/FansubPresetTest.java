package ani.rss.upgrade;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FansubPresetTest {
    private final ReleaseAnalysisService parser=new ReleaseAnalysisService();
    private final QualityPolicyEngine engine=new QualityPolicyEngine();
    private QualityPolicyEngine.Policy preferred() { return QualityPolicyPresets.catalog().presets().get(0).policy(); }
    private UpgradeModels.Release release(String group,int resolution,String language) {
        return parser.parse("["+group+"] Example - 01 ["+resolution+"p]["+language+"][MKV]","100","TEST");
    }
    @Test void followsConfirmed1080pSimplifiedAndGroupPreferenceWithOtherGroupsAsFallback() {
        var p=preferred();
        assertEquals(1080,p.getMinimumResolution()); assertEquals(List.of(1080,2160,720,480),p.getResolutions());
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,release("绿茶",1080,"CHS"),release("桜都",2160,"CHS")).verdict());
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,release("樱都字幕组",1080,"CHS"),release("绿茶字幕组",1080,"CHS")).verdict());
        assertEquals(QualityPolicyEngine.Verdict.REJECTED,engine.evaluate(p,release("桜都",720,"CHS"),null).verdict());
        assertEquals(QualityPolicyEngine.Verdict.REJECTED,engine.evaluate(p,release("桜都",1080,"CHT"),null).verdict());
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,release("其他组",1080,"CHS"),null).verdict());
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,release("北宇治",1080,"CHS"),release("其他组",1080,"CHS")).verdict());
    }
    @Test void preservesJointGroupsFromBothTitleAndSingleProviderFieldIgnoringCaseAndWidth() {
        var p=preferred(); var next=release(" SweetSub ＆ LoliHouse ",1080,"CHS");
        assertEquals(Set.of("SweetSub","LoliHouse"),next.getGroups());
        next.setGroups(new TreeSet<>(Set.of("SWEETSUB&lolihouse")));
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,next,release("LoliHouse",1080,"CHS")).verdict());
        p.setBlockedGroups(Set.of("sweetsub"));
        assertEquals(QualityPolicyEngine.Verdict.REJECTED,engine.evaluate(p,next,null).verdict());
    }
    @Test void recognizesObservedMikanBracketResolutionAndSubtitleForms() {
        var r=parser.parse("〖悠哈璃羽字幕社〗[Example][01][WEBRIP 1920x1080 HEVC][MKV 简繁日内封]","100","TEST");
        assertEquals(Set.of("悠哈璃羽字幕社"),r.getGroups()); assertEquals(1080,r.getResolution());
        assertEquals(Set.of("CHS","CHT","JPN"),r.getLanguages());
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(preferred(),r,release("SweetSub",1080,"CHS")).verdict());
        var p=preferred(); p.setAllowedGroups(Set.of("悠哈璃羽"));
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,r,null).verdict());
    }
    @Test void expandedAliasesWorkForJointGroupsAndHardConditions() {
        var p=preferred();
        p.setAllowedGroups(Set.of("喵萌奶茶屋"));
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,release("Nekomoe kissaten & LoliHouse",1080,"CHS"),null).verdict());
        p.setBlockedGroups(Set.of("动漫国字幕组")); p.setAllowedGroups(Set.of());
        assertEquals(QualityPolicyEngine.Verdict.REJECTED,engine.evaluate(p,release("DMG & SweetSub",1080,"CHS"),null).verdict());
        var seen=new HashMap<String,String>();
        for(var g:QualityPolicyPresets.catalog().groups()) {
            var names=new ArrayList<>(g.aliases()); names.add(g.name());
            for(var name:names) {
                var old=seen.put(FansubNames.key(name),g.name());
                assertTrue(old==null || old.equals(g.name()),"Ambiguous alias: "+name);
            }
        }
    }
    @Test void presetEditsCannotMutateCatalogOrOtherDrafts() {
        var catalog=QualityPolicyPresets.catalog(); var preset=catalog.presets().get(0); var draft=preset.policy();
        draft.getGroups().clear(); draft.getGroupAliases().clear(); draft.setMinimumResolution(0);
        assertEquals(6,preset.policy().getGroups().size()); assertEquals(1080,preferred().getMinimumResolution());
        for(var item:catalog.presets()) engine.validate(item.policy());
    }
}
