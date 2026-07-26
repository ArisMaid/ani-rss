package ani.rss.commons;

/** Canonical local-fork project identity used by update and user-facing links. */
public final class ProjectMetadata {
    public static final String REPOSITORY = "ArisMaid/ani-rss";
    public static final String GITHUB_URL = "https://github.com/" + REPOSITORY;
    public static final String RELEASES_URL = GITHUB_URL + "/releases";
    public static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";

    private ProjectMetadata() {
    }
}
