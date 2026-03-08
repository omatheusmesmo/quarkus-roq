package io.quarkiverse.roq.editor.deployment;

import java.nio.file.Path;
import java.util.List;

import io.quarkiverse.roq.editor.deployment.git.GitSyncService;
import io.quarkiverse.roq.editor.deployment.git.GitSyncServiceImpl;
import io.quarkiverse.roq.editor.runtime.devui.RoqEditorConfig;
import io.quarkiverse.roq.editor.runtime.devui.RoqEditorJsonRPCService;
import io.quarkiverse.roq.frontmatter.runtime.config.RoqSiteConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.logging.LogCleanupFilterBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.dev.spi.DevModeType;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.buildtime.BuildTimeActionBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;

public class RoqEditorProcessor {

    private static final String FEATURE = "roq-editor";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    List<LogCleanupFilterBuildItem> cleanupLoudJGitLogs() {
        return List.of(
                new LogCleanupFilterBuildItem("org.eclipse.jgit.internal.transport.sshd.CachingKeyPairProvider",
                        "Mismatched private key check values"),
                new LogCleanupFilterBuildItem("org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser",
                        "readPrivateKeys"),
                new LogCleanupFilterBuildItem("org.apache.sshd.common.config.keys.FilePasswordProvider",
                        "decode"));
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    AdditionalBeanBuildItem registerAdditionalBeans() {
        return AdditionalBeanBuildItem.unremovableOf(RoqEditorJsonRPCService.class);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    @Produce(ServiceStartBuildItem.class)
    void setupConsole(HttpRootPathBuildItem rp,
            NonApplicationRootPathBuildItem np,
            LaunchModeBuildItem launchModeBuildItem) {
        if (launchModeBuildItem.getDevModeType().orElse(null) != DevModeType.LOCAL) {
            return;
        }
        RoqEditorConsoleHelper.setupConsole(rp, np);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    JsonRPCProvidersBuildItem registerJsonRpcService() {
        return new JsonRPCProvidersBuildItem(RoqEditorJsonRPCService.class);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    CardPageBuildItem createDevUiCard(RoqEditorConfig editorConfig) {
        CardPageBuildItem card = new CardPageBuildItem();

        card.addPage(Page.webComponentPageBuilder()
                .icon("font-awesome-solid:pencil")
                .componentLink("qwc-roq-editor.js")
                .title("Roq Editor"));

        card.addBuildTimeData("markups", List.of("markdown", "asciidoc", "html"));
        card.addBuildTimeData("config", RoqEditorConfigMapper.mapConfig(editorConfig));

        return card;
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    BuildTimeActionBuildItem registerGitActions(RoqEditorConfig editorConfig,
            RoqSiteConfig siteConfig,
            LaunchModeBuildItem launchModeBuildItem,
            CurateOutcomeBuildItem curateOutcomeBuildItem) {

        if (!editorConfig.sync().enabled() || launchModeBuildItem.getDevModeType().orElse(null) != DevModeType.LOCAL) {
            return null;
        }

        Path projectRoot;
        if (curateOutcomeBuildItem.getApplicationModel().getAppArtifact().getWorkspaceModule() != null) {
            projectRoot = curateOutcomeBuildItem.getApplicationModel().getAppArtifact().getWorkspaceModule().getModuleDir()
                    .toPath();
        } else {
            projectRoot = Path.of(".").toAbsolutePath();
        }

        GitSyncService gitService = new GitSyncServiceImpl(editorConfig, siteConfig, projectRoot.toFile());
        return RoqEditorGitBuildActions.register(gitService);
    }
}
