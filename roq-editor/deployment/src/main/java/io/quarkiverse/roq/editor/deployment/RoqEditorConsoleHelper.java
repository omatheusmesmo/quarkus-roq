package io.quarkiverse.roq.editor.deployment;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.deployment.console.ConsoleCommand;
import io.quarkus.deployment.console.ConsoleStateManager;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.deployment.devmode.IdeHelper;

/**
 * Helper to setup the Roq Editor console shortcuts.
 */
public class RoqEditorConsoleHelper {

    private static volatile ConsoleStateManager.ConsoleContext context;

    /**
     * Sets up the console shortcut for opening the Roq Editor.
     *
     * @param rp HTTP root path
     * @param np Non-application root path
     */
    public static synchronized void setupConsole(HttpRootPathBuildItem rp, NonApplicationRootPathBuildItem np) {
        if (context == null) {
            context = ConsoleStateManager.INSTANCE.createContext("Roq");
        }
        Config config = ConfigProvider.getConfig();
        String host = config.getOptionalValue("quarkus.http.host", String.class).orElse("localhost");
        boolean isInsecureDisabled = config.getOptionalValue("quarkus.http.insecure-requests", String.class)
                .map("disabled"::equals)
                .orElse(false);

        String port = isInsecureDisabled
                ? config.getOptionalValue("quarkus.http.ssl-port", String.class).orElse("8443")
                : config.getOptionalValue("quarkus.http.port", String.class).orElse("8080");

        String protocol = isInsecureDisabled ? "https" : "http";
        context.reset(new ConsoleCommand('c', "Open the Roq Editor in a browser", null,
                () -> IdeHelper.openBrowser(rp, np, protocol, "/q/dev-ui/quarkus-roq-editor/roq-editor", host, port)));
    }
}
