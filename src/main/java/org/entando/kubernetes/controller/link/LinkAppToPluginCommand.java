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

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.support.client.ServiceClient;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.creators.IngressPathCreator;
import org.entando.kubernetes.controller.support.creators.ServiceCreator;
import org.entando.kubernetes.model.AbstractServerStatus;
import org.entando.kubernetes.model.WebServerStatus;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.plugin.Permission;

public class LinkAppToPluginCommand {

    private static final String COMPONENT_MANAGER_QUALIFIER = "de";
    private final EntandoAppPluginLink entandoAppPluginLink;
    private final IngressPathCreator ingressCreator;
    private final EntandoLinkedPluginIngressing entandoLinkedPluginIngressing;
    private final ServiceCreator<?> serviceCreator;
    private final WebServerStatus status = new WebServerStatus("link");

    //TODO fix ServiceCreator not to assume an EntandoDeploymentSpec
    @SuppressWarnings("rawtypes")
    public LinkAppToPluginCommand(EntandoAppPluginLink entandoAppPluginLink, EntandoLinkedPluginIngressing entandoLinkedPluginIngressing) {
        this.entandoAppPluginLink = entandoAppPluginLink;
        //TODO fix this problem. Links do not have EntandoDeploymentSpecs
        this.serviceCreator = new ServiceCreator(entandoAppPluginLink,
                entandoLinkedPluginIngressing.getEntandoPluginDeploymentResult().getService());
        this.ingressCreator = new IngressPathCreator(entandoAppPluginLink);
        this.entandoLinkedPluginIngressing = entandoLinkedPluginIngressing;
    }

    public void execute(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient) {
        Service service = prepareReachableService(k8sClient.services());
        status.setServiceStatus(service.getStatus());
        k8sClient.entandoResources().updateStatus(entandoAppPluginLink, status);
        Ingress ingress = addMissingIngressPaths(k8sClient, service);
        status.setIngressStatus(ingress.getStatus());
        k8sClient.entandoResources().updateStatus(entandoAppPluginLink, status);
        grantAppAccessToPlugin(k8sClient, keycloakClient);
        //TODO wait for result - when new ingress path is available
    }

    private void grantAppAccessToPlugin(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient) {
        EntandoApp entandoApp = this.entandoLinkedPluginIngressing.getEntandoApp();
        String pluginClientId = entandoAppPluginLink.getSpec().getEntandoPluginName() + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER;
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources()
                .findKeycloak(entandoLinkedPluginIngressing.getEntandoApp());
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.assignRoleToClientServiceAccount(
                KeycloakName.ofTheRealm(entandoApp.getSpec()),
                entandoAppPluginLink.getSpec().getEntandoAppName() + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER,
                new Permission(pluginClientId,
                        KubeUtils.ENTANDO_APP_ROLE)
        );
        keycloakClient.assignRoleToClientServiceAccount(
                KeycloakName.ofTheRealm(entandoApp.getSpec()),
                entandoAppPluginLink.getSpec().getEntandoAppName() + "-" + COMPONENT_MANAGER_QUALIFIER,
                new Permission(pluginClientId,
                        KubeUtils.ENTANDO_APP_ROLE)
        );
    }

    private Ingress addMissingIngressPaths(SimpleK8SClient<?> k8sClient, Service service) {
        return ingressCreator.addMissingHttpPaths(k8sClient.ingresses(), entandoLinkedPluginIngressing,
                entandoLinkedPluginIngressing.getEntandoAppDeploymentResult().getIngress(), service);
    }

    private Service prepareReachableService(ServiceClient services) {
        if (getAppServiceNamespace().equals(getPluginServiceNamespace())) {
            return serviceCreator.getService();
        } else {
            return serviceCreator.newDelegatingService(services, entandoLinkedPluginIngressing);
        }
    }

    private String getPluginServiceNamespace() {
        return this.entandoLinkedPluginIngressing.getEntandoAppDeploymentResult().getService().getMetadata().getNamespace();
    }

    private String getAppServiceNamespace() {
        return this.entandoLinkedPluginIngressing.getEntandoPluginDeploymentResult().getService().getMetadata().getNamespace();
    }

    public AbstractServerStatus getStatus() {
        return status;
    }
}
