/*
* Copyright 2016 Bull Atos.  All Rights Reserved.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* See the NOTICE file distributed with this work for additional information
* regarding copyright ownership.
*/
package alien4cloud.plugin.Janus;

import alien4cloud.paas.IPaaSCallback;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;

/**
 * Information needed for a DEPLOY Task
 */
public class DeployTask extends AlienTask {
    PaaSTopologyDeploymentContext ctx;
    IPaaSCallback<?> callback;

    public DeployTask(PaaSTopologyDeploymentContext ctx, JanusPaaSProvider prov, IPaaSCallback<?> callback) {
        super(prov);
        this.ctx = ctx;
        this.callback = callback;
    }

    public void run() {
        orchestrator.doDeploy(this);
    }
}
