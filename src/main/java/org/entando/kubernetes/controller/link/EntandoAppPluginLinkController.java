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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.ExposedService;
import org.entando.kubernetes.controller.IngressingDeployCommand;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkSpec;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoAppPluginLinkController extends AbstractDbAwareController<EntandoAppPluginLinkSpec, EntandoAppPluginLink> {

    @Inject
    public EntandoAppPluginLinkController(KubernetesClient kubernetesClient) {
        super(kubernetesClient);
    }

    public EntandoAppPluginLinkController(SimpleK8SClient<?> k8sClient,
            SimpleKeycloakClient keycloakClient) {
        super(k8sClient, keycloakClient);
    }

    public EntandoAppPluginLinkController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoAppPluginLink newEntandoAppPluginLink) {
        EntandoLinkedPluginIngressing entandoLinkedPluginIngressing = prepareEntandoPluginIngressing(newEntandoAppPluginLink);
        LinkAppToPluginCommand linkAppToPluginCommand = new LinkAppToPluginCommand(newEntandoAppPluginLink,
                entandoLinkedPluginIngressing);
        linkAppToPluginCommand.execute(k8sClient, keycloakClient);
        k8sClient.entandoResources().updateStatus(newEntandoAppPluginLink, linkAppToPluginCommand.getStatus());
    }

    private EntandoLinkedPluginIngressing prepareEntandoPluginIngressing(EntandoAppPluginLink newEntandoAppPluginLink) {
        EntandoAppPluginLinkSpec spec = newEntandoAppPluginLink.getSpec();
        EntandoApp entandoApp = k8sClient.entandoResources()
                .loadEntandoApp(spec.getEntandoAppNamespace().orElse(newEntandoAppPluginLink.getMetadata().getNamespace()),
                        spec.getEntandoAppName());
        EntandoPlugin entandoPlugin = k8sClient.entandoResources()
                .loadEntandoPlugin(spec.getEntandoPluginNamespace().orElse(newEntandoAppPluginLink.getMetadata().getNamespace()),
                        spec.getEntandoPluginName());
        k8sClient.pods().waitForPod(entandoPlugin.getMetadata().getNamespace(), IngressingDeployCommand.DEPLOYMENT_LABEL_NAME,
                entandoPlugin.getMetadata().getName() + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER);
        ExposedService entandoAppDeploymentResult = k8sClient.entandoResources().loadExposedService(entandoApp);
        ExposedService entandoPluginDeploymentResult = k8sClient.entandoResources().loadExposedService(entandoPlugin);
        return new EntandoLinkedPluginIngressing(entandoApp, entandoPlugin, entandoAppDeploymentResult, entandoPluginDeploymentResult);
    }

}
