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

import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.model.common.ServerStatus;

public class InProcessDeploymentLinker implements DeploymentLinker {

    private final SimpleK8SClient<?> client;
    private final SimpleKeycloakClient keycloakClient;

    public InProcessDeploymentLinker(SimpleK8SClient<?> client, SimpleKeycloakClient keycloakClient) {
        this.client = client;
        this.keycloakClient = keycloakClient;
    }

    @Override
    public ServerStatus link(Linkable linkable, Linkable customIngressLinkable, String tenantCode) {
        return new LinkCommand(linkable, customIngressLinkable).execute(client, keycloakClient, tenantCode);
    }
}
