package ani.rss.upgrade;

import java.util.Objects;

/** Only connection fields may be overridden by discovery and self-test requests. */
public record AiConnectionDraft(String aiBaseUrl, String aiApiKey, String aiModel, boolean clearAiKey) {
    public static AiConnectionDraft from(UpgradeSettings settings) {
        return new AiConnectionDraft(settings.getAiBaseUrl(), settings.getAiApiKey(), settings.getAiModel(), settings.isClearAiKey());
    }

    public boolean usesSavedConnection() {
        return aiBaseUrl == null && aiApiKey == null && aiModel == null && !clearAiKey;
    }

    public void applyTo(UpgradeSettings target, UpgradeSettings saved) {
        String base = Objects.toString(aiBaseUrl, saved.getAiBaseUrl());
        String key = Objects.toString(aiApiKey, "").strip();
        if (clearAiKey) {
            key = "";
        } else if (key.isEmpty() && saved.getAiApiKey() != null && !saved.getAiApiKey().isBlank()) {
            if (!baseForComparison(base).equals(baseForComparison(saved.getAiBaseUrl()))) {
                throw new IllegalArgumentException("更换 AI 服务地址时，请重新输入 API Key，或明确清除原密钥");
            }
            key = saved.getAiApiKey();
        }
        String model = Objects.toString(aiModel, Objects.toString(saved.getAiModel(), "")).strip();
        if (key.chars().anyMatch(Character::isISOControl) || model.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("API Key 和模型 ID 不可包含换行或控制字符");
        target.setAiBaseUrl(Objects.toString(base, "").strip());
        target.setAiModel(model);
        target.setAiApiKey(key);
        target.setClearAiKey(false);
    }

    private static String baseForComparison(String value) {
        return Objects.toString(value, "").strip().replaceFirst("/+$", "");
    }

    @Override public String toString() { return "AiConnectionDraft"; }
}
