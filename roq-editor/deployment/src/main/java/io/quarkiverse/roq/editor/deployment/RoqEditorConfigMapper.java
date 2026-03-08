package io.quarkiverse.roq.editor.deployment;

import java.util.HashMap;
import java.util.Map;

import io.quarkiverse.roq.editor.runtime.devui.RoqEditorConfig;

/**
 * Mapper to convert RoqEditorConfig into a Map for the Dev UI.
 */
public class RoqEditorConfigMapper {

    /**
     * Maps the RoqEditorConfig to a format suitable for the Dev UI build-time data.
     *
     * @param editorConfig the editor configuration
     * @return a map of configuration values
     */
    public static Map<String, Object> mapConfig(RoqEditorConfig editorConfig) {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("page-markup", editorConfig.pageMarkup().toString());
        configMap.put("doc-markup", editorConfig.docMarkup().toString());

        configMap.put("visual-editor", Map.of(
                "enabled", editorConfig.visualEditor().enabled(),
                "safe", editorConfig.visualEditor().safe()));

        configMap.put("suggested-path", Map.of(
                "enabled", editorConfig.suggestedPath().enabled()));

        Map<String, Object> sync = new HashMap<>();
        sync.put("enabled", editorConfig.sync().enabled());

        sync.put("auto-sync", Map.of(
                "enabled", editorConfig.sync().autoSync().enabled(),
                "interval-seconds", editorConfig.sync().autoSync().intervalSeconds()));

        sync.put("auto-publish", Map.of(
                "enabled", editorConfig.sync().autoPublish().enabled(),
                "interval-seconds", editorConfig.sync().autoPublish().intervalSeconds()));

        sync.put("commit-message", Map.of(
                "template", editorConfig.sync().commitMessage().template()));

        configMap.put("sync", sync);

        return configMap;
    }
}
