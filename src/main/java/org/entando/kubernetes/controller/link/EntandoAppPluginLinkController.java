/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.link;

import java.util.Collections;
import javax.inject.Inject;
import org.entando.kubernetes.controller.link.support.DeploymentLinker;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.model.common.ServerStatus;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command
public class EntandoAppPluginLinkController implements Runnable {

    private final KubernetesClientForControllers k8sClient;
    private final DeploymentLinker linker;

    @Inject
    public EntandoAppPluginLinkController(KubernetesClientForControllers k8sClient, DeploymentLinker linker) {
        this.k8sClient = k8sClient;
        this.linker = linker;
    }

    @Override
    public void run() {
        EntandoAppPluginLink appPluginLink = (EntandoAppPluginLink) k8sClient
                .resolveCustomResourceToProcess(Collections.singletonList(EntandoAppPluginLink.class));
        try {

            final AppToPluginLinkable linkable = new AppToPluginLinkable(appPluginLink);
            final SerializedEntandoResource entandoApp = k8sClient.loadCustomResource(appPluginLink.getApiVersion(),
                    "EntandoApp",
                    appPluginLink.getSpec().getEntandoAppNamespace().orElse(appPluginLink.getMetadata().getNamespace()),
                    appPluginLink.getSpec().getEntandoAppName());
            k8sClient.waitForCompletion(entandoApp, EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds());
            final SerializedEntandoResource entandoPlugin = k8sClient.loadCustomResource(appPluginLink.getApiVersion(),
                    "EntandoPlugin",
                    appPluginLink.getSpec().getEntandoPluginNamespace().orElse(appPluginLink.getMetadata().getNamespace()),
                    appPluginLink.getSpec().getEntandoPluginName());
            k8sClient.waitForCompletion(entandoPlugin, EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds());
            linkable.setTargetPathOnSourceIngress(
                    entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).orElseThrow(IllegalStateException::new)
                            .getWebContexts().get(NameUtils.DEFAULT_SERVER_QUALIFIER));
            ServerStatus status = linker.link(linkable);
            appPluginLink = k8sClient.updateStatus(appPluginLink, status);
            appPluginLink = k8sClient.deploymentEnded(appPluginLink);
        } catch (Exception e) {
            appPluginLink = k8sClient.deploymentFailed(appPluginLink, e, NameUtils.MAIN_QUALIFIER);
        }
        appPluginLink.getStatus().findFailedServerStatus().flatMap(ServerStatus::getEntandoControllerFailure).ifPresent(s -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), s.getDetailMessage());
        });
    }

}
