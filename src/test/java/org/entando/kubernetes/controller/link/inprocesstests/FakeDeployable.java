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

package org.entando.kubernetes.controller.link.inprocesstests;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.IngressingContainer;
import org.entando.kubernetes.controller.spi.deployable.IngressingDeployable;
import org.entando.kubernetes.controller.spi.result.ExposedDeploymentResult;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoIngressingDeploymentSpec;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class FakeDeployable<S extends EntandoIngressingDeploymentSpec> implements IngressingDeployable<ExposedDeploymentResult, S> {

    private final EntandoBaseCustomResource<S> resource;
    private final List<DeployableContainer> containers;

    public FakeDeployable(EntandoBaseCustomResource<S> resource) {
        this.resource = resource;
        this.containers = Arrays.asList(new IngressingContainer() {
            @Override
            public String getWebContextPath() {
                return resource instanceof EntandoApp ? "/entando-de-app" : ((EntandoPlugin) resource).getSpec().getIngressPath();
            }

            @Override
            public Optional<String> getHealthCheckPath() {
                return Optional.of(getWebContextPath() + "/actuator/health");
            }

            @Override
            public String determineImageToUse() {
                return "entando/dummy";
            }

            @Override
            public String getNameQualifier() {
                return NameUtils.DEFAULT_SERVER_QUALIFIER;
            }

            @Override
            public int getPrimaryPort() {
                return getPortForIngressPath();
            }

            @Override
            public List<EnvVar> getEnvironmentVariables() {
                return Collections.emptyList();
            }

            @Override
            public int getPortForIngressPath() {
                return resource instanceof EntandoApp ? 8080 : 8081;

            }
        });
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public String getIngressName() {
        return resource.getMetadata().getName() + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX;
    }

    @Override
    public String getIngressNamespace() {
        return resource.getMetadata().getNamespace();
    }

    @Override
    public String getNameQualifier() {
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public EntandoBaseCustomResource<S> getCustomResource() {
        return resource;
    }

    @Override
    public ExposedDeploymentResult<ExposedDeploymentResult<?>> createResult(
            Deployment deployment,
            Service service,
            Ingress ingress,
            Pod pod) {
        return new ExposedDeploymentResult<>(pod, service, ingress);
    }
}
