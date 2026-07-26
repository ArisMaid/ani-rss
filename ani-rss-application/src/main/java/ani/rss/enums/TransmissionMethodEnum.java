package ani.rss.enums;

import lombok.Getter;

public enum TransmissionMethodEnum {
    sessionGet("session-get", "session_get"),
    torrentAdd("torrent-add", "torrent_add"),
    torrentGet("torrent-get", "torrent_get"),
    torrentRemove("torrent-remove", "torrent_remove"),
    torrentRenamePath("torrent-rename-path", "torrent_rename_path"),
    torrentSet("torrent-set", "torrent_set"),
    torrentSetLocation("torrent-set-location", "torrent_set_location");

    TransmissionMethodEnum(String legacyValue, String jsonRpcValue) {
        this.legacyValue = legacyValue;
        this.jsonRpcValue = jsonRpcValue;
    }

    @Getter
    private final String legacyValue;

    @Getter
    private final String jsonRpcValue;
}
