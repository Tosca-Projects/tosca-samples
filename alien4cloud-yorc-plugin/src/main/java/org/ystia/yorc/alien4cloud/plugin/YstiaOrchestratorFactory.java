/**
 * Copyright 2018 Bull S.A.S. Atos Technologies - Bull, Rue Jean Jaures, B.P.68, 78340, Les Clayes-sous-Bois, France.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ystia.yorc.alien4cloud.plugin;

import alien4cloud.model.orchestrators.ArtifactSupport;
import alien4cloud.model.orchestrators.locations.LocationSupport;
import alien4cloud.orchestrators.plugin.IOrchestratorPluginFactory;
import com.google.common.collect.Maps;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * Factory for Yorc implementation of orchestrator instance.
 */
@Component("yorc-orchestrator-factory")
public class YstiaOrchestratorFactory implements IOrchestratorPluginFactory<YstiaOrchestrator, ProviderConfig> {
    public static final String OPENSTACK = "OpenStack";
    public static final String SLURM = "Slurm";
    public static final String KUBERNETES = "Kubernetes";
    public static final String AWS = "AWS";
    public static final String HOSTS_POOL = "HostsPool";
    private final Map<String, PropertyDefinition> deploymentProperties = Maps.newHashMap();
    @Resource
    private BeanFactory beanFactory;

    @Override
    public YstiaOrchestrator newInstance() {
        return beanFactory.getBean(YstiaOrchestrator.class);
    }

    @Override
    public void destroy(YstiaOrchestrator instance) {
        // nothing specific, the plugin will be garbaged collected when all references are lost.
    }

    @Override
    public ProviderConfig getDefaultConfiguration() {
        return new ProviderConfig();
    }

    @Override
    public Class<ProviderConfig> getConfigurationType() {
        return ProviderConfig.class;
    }

    @Override
    public LocationSupport getLocationSupport() {
        return new LocationSupport(true, new String[]{AWS, OPENSTACK, SLURM, KUBERNETES, HOSTS_POOL});
    }

    @Override
    public ArtifactSupport getArtifactSupport() {
        // support all type of implementations artifacts
        return new ArtifactSupport(new String[]{"tosca.artifacts.Implementation.Python",
                "tosca.artifacts.Implementation.Bash", "tosca.artifacts.Implementation.Ansible", "tosca.artifacts.Deployment.Image.Container.Docker", "tosca.artifacts.Deployment.Image.Container.Docker.Kubernetes", "yorc.artifacts.Deployment.SlurmJob"});
    }

    @Override
    public Map<String, PropertyDefinition> getDeploymentPropertyDefinitions() {
        return this.deploymentProperties;
    }

    //    @Override
    public String getType() {
        return "Ystia Orchestrator";
    }

}
