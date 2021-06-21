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

package org.entando.kubernetes.controller.link.support;

import io.fabric8.kubernetes.client.KubernetesClient;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.SupportProducer;

@ApplicationScoped
public class LinkSupportProducer extends SupportProducer {

    private final SimpleK8SClient<?> simpleClient;
    private final SimpleKeycloakClient keycloakClient;

    @Inject
    public LinkSupportProducer(KubernetesClient kubernetesClient, SimpleKeycloakClient keycloakClient) {
        this.simpleClient = new DefaultSimpleK8SClient(kubernetesClient);
        this.keycloakClient = keycloakClient;
    }

    @Produces
    public DeploymentLinker deploymentLinker() {
        return new InProcessDeploymentLinker(this.simpleClient, this.keycloakClient);
    }
}
