package ani.rss.recovery;

/** Lifecycle of an item ANI-RSS has accepted as an expected download. */
public enum RecoveryState {
    PENDING,
    DEFERRED,
    SUBMITTED,
    SATISFIED,
    RETRY_WAIT,
    SUPERSEDED,
    CANCELLED
}
