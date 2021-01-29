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

package org.entando.kubernetes.controller.link.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.entando.kubernetes.controller.integrationtest.support.IntegrationTestHelperBase;
import org.entando.kubernetes.model.link.DoneableEntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkList;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkOperationFactory;

public class EntandoAppPluginLinkIntegrationTestHelper
        extends IntegrationTestHelperBase<EntandoAppPluginLink, EntandoAppPluginLinkList, DoneableEntandoAppPluginLink> {

    public EntandoAppPluginLinkIntegrationTestHelper(DefaultKubernetesClient client) {
        super(client, EntandoAppPluginLinkOperationFactory::produceAllEntandoAppPluginLinks);
    }

}
