package ani.rss.entity.dto;

import ani.rss.entity.MikanBgm;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.Set;

/** Scores resolved after a Mikan list has already been rendered. */
@Data
@Accessors(chain = true)
public class MikanScoreResponse {
    private Map<String, MikanBgm> scores;
    private Set<String> subscribedBgmIds;
}
