package ani.rss.commons;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.FIFOCache;
import lombok.Synchronized;

public class CacheUtils {
    private static final FIFOCache<Object, Object> CACHE = CacheUtil.newFIFOCache(1024 * 8);

    @Synchronized("CACHE")
    @SuppressWarnings("unchecked")
    public static <V> V get(Object key) {
        return (V) CACHE.get(key);
    }

    @Synchronized("CACHE")
    public static void put(Object key, Object object) {
        CACHE.put(key, object);
    }

    @Synchronized("CACHE")
    public static void put(Object key, Object object, long timeout) {
        CACHE.put(key, object, timeout);
    }

    @Synchronized("CACHE")
    public static boolean containsKey(Object key) {
        return CACHE.containsKey(key);
    }

    @Synchronized("CACHE")
    public static void remove(Object key) {
        CACHE.remove(key);
    }
}
