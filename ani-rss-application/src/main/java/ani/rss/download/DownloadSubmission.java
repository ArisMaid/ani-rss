package ani.rss.download;

import java.io.File;
import java.util.Set;

/** Transport request independent of active subscriptions and their lifecycle. */
public record DownloadSubmission(OwnerKind ownerKind, String ownerId, String operationId, String infoHash,
                                 String saveRoot, String displayName, String magnet, File torrentFile, Set<String> episodes) {
    public enum OwnerKind { SUBSCRIPTION, UPGRADE_TARGET }
    public DownloadSubmission {
        if(ownerKind==null||ownerId==null||ownerId.isBlank()||operationId==null||operationId.isBlank()||saveRoot==null||saveRoot.isBlank())
            throw new IllegalArgumentException("下载请求缺少稳定归属或保存位置");
        episodes=Set.copyOf(episodes);
    }
}
