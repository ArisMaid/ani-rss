package ani.rss.commons;

import com.github.promeg.pinyinhelper.PinyinMapDict;
import java.util.Map;

public class CustomPinyinMapDict extends PinyinMapDict {
    @Override public Map<String,String[]> mapping() {
        return Map.of("重庆",new String[]{"CHONG","QING"},"重启",new String[]{"CHONG","QI"},
                "重回",new String[]{"CHONG","HUI"},"重生",new String[]{"CHONG","SHENG"},
                "重来",new String[]{"CHONG","LAI"},"调教",new String[]{"TIAO","JIAO"});
    }
}
