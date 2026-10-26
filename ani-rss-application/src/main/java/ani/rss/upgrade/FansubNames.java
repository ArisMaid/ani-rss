package ani.rss.upgrade;

import java.text.Normalizer;
import java.util.*;

public final class FansubNames {
    private FansubNames() {}
    public static List<String> split(String value) {
        if(value==null) return List.of();
        return Arrays.stream(value.split("\\s*(?:&|＆|\\+|×)\\s*"))
                .map(String::strip).filter(s->!s.isEmpty()).distinct().toList();
    }
    public static String key(String name) {
        return Normalizer.normalize(name,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
    public static String canonical(String name,Map<String,String> aliases) {
        String key=key(name);
        for(var alias:aliases.entrySet()) if(key(alias.getKey()).equals(key)) return key(alias.getValue());
        return key;
    }
    public static String entry(String name,Map<String,String> aliases) {
        return String.join(" & ",split(name).stream().map(n->canonical(n,aliases)).distinct().sorted().toList());
    }
}
