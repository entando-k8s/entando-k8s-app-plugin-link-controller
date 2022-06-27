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

import static java.lang.String.format;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import java.util.Collections;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.link.support.DeploymentLinker;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.model.common.EntandoControllerFailure;
import org.entando.kubernetes.model.common.ServerStatus;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command
public class EntandoAppPluginLinkController implements Runnable {

    private final KubernetesClientForControllers k8sClient;
    private final DeploymentLinker linker;
    private SerializedEntandoResource entandoPlugin;
    private SerializedEntandoResource entandoApp;

    @Inject
    public EntandoAppPluginLinkController(KubernetesClientForControllers k8sClient, DeploymentLinker linker) {
        this.k8sClient = k8sClient;
        this.linker = linker;
    }

    public static void main(String ...args) {
        System.out.println("Running main method");
        Quarkus.run(args);
    }

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    @Override
    public void run() {
        EntandoAppPluginLink appPluginLink = (EntandoAppPluginLink) k8sClient
                .resolveCustomResourceToProcess(Collections.singletonList(EntandoAppPluginLink.class));
        try {
            appPluginLink = this.k8sClient.deploymentStarted(appPluginLink);
            this.entandoApp = k8sClient.loadCustomResource(appPluginLink.getApiVersion(),
                    "EntandoApp",
                    appPluginLink.getSpec().getEntandoAppNamespace().orElse(appPluginLink.getMetadata().getNamespace()),
                    appPluginLink.getSpec().getEntandoAppName());
            this.entandoApp = k8sClient.waitForCompletion(entandoApp, EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds() * 2);
            this.entandoApp.getStatus()
                    .findFailedServerStatus()
                    .flatMap(ServerStatus::getEntandoControllerFailure)
                    .ifPresent(s -> {
                        throw toException(s, entandoApp);
                    });
            this.entandoPlugin = k8sClient.loadCustomResource(appPluginLink.getApiVersion(),
                    "EntandoPlugin",
                    appPluginLink.getSpec().getEntandoPluginNamespace().orElse(appPluginLink.getMetadata().getNamespace()),
                    appPluginLink.getSpec().getEntandoPluginName());
            this.entandoPlugin = k8sClient.waitForCompletion(entandoPlugin, EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds() * 2);
            this.entandoPlugin.getStatus()
                    .findFailedServerStatus()
                    .flatMap(ServerStatus::getEntandoControllerFailure)
                    .ifPresent(s -> {
                        throw toException(s, entandoPlugin);
                    });

            // linkable for canonical ingress
            final AppToPluginLinkable linkable = new AppToPluginLinkable(appPluginLink);
            linkable.setTargetPathOnSourceIngress(
                    entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).orElseThrow(IllegalStateException::new)
                            .getWebContexts().get(NameUtils.DEFAULT_SERVER_QUALIFIER));

            // linkable for custom ingress
            AppToPluginLinkable customIngressLinkable = null;
            if (entandoPlugin.getSpec().containsKey("customIngressPath")) {
                customIngressLinkable = linkable.clone();
                customIngressLinkable
                        .setTargetPathOnSourceIngress((String) entandoPlugin.getSpec().get("customIngressPath"));
            }

            ServerStatus status = linker.link(linkable, customIngressLinkable);
            appPluginLink = k8sClient.updateStatus(appPluginLink, status);
            appPluginLink = k8sClient.deploymentEnded(appPluginLink);
        } catch (Exception e) {
            appPluginLink = k8sClient.deploymentFailed(appPluginLink, e, NameUtils.MAIN_QUALIFIER);
        }
        appPluginLink.getStatus().findFailedServerStatus().flatMap(ServerStatus::getEntandoControllerFailure).ifPresent(s -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), s.getDetailMessage());
        });
    }

    private EntandoControllerException toException(EntandoControllerFailure s, SerializedEntandoResource resource) {
        return new EntandoControllerException(resource, format("The %s %s/%s has not been deployed successfully:%n %s", resource.getKind(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName(), s.getDetailMessage()));
    }

}
