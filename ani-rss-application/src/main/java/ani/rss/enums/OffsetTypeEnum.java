package ani.rss.enums;

import ani.rss.offset.AutoOffset;
import ani.rss.offset.BaseOffset;
import ani.rss.offset.BgmOffset;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OffsetTypeEnum {
    AUTO_OFFSET(AutoOffset.class),
    BGM_OFFSET(BgmOffset.class);

    private final Class<? extends BaseOffset> baseOffsetClass;
}
