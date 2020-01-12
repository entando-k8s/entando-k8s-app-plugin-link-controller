package org.entando.kubernetes.controller.link.inprocesstests;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.IngressingDeployable;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class FakeDeployable implements IngressingDeployable<ServiceDeploymentResult> {

    private final EntandoCustomResource resource;
    private final List<DeployableContainer> containers;

    public FakeDeployable(EntandoCustomResource resource) {
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
                return KubeUtils.DEFAULT_SERVER_QUALIFIER;
            }

            @Override
            public int getPort() {
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
        return resource.getMetadata().getName() + "-" + KubeUtils.DEFAULT_INGRESS_SUFFIX;
    }

    @Override
    public String getIngressNamespace() {
        return resource.getMetadata().getNamespace();
    }

    @Override
    public String getNameQualifier() {
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public EntandoCustomResource getCustomResource() {
        return resource;
    }

    @Override
    public ServiceDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new ServiceDeploymentResult(service, ingress);
    }
}
