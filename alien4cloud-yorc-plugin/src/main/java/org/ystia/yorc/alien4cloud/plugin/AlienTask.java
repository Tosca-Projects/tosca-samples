/*
* Copyright 2016 Bull Atos.  All Rights Reserved.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* See the NOTICE file distributed with this work for additional information
* regarding copyright ownership.
*/
package org.ystia.yorc.alien4cloud.plugin;


import org.ystia.yorc.alien4cloud.plugin.rest.RestClient;

public abstract class AlienTask {

    protected YorcPaaSProvider orchestrator;
    protected RestClient restClient;

    /**
     * Constructor
     * @param  provider
     */
    public AlienTask(YorcPaaSProvider provider) {
        this.orchestrator = provider;
        this.restClient = provider.getRestClient();
    }

    /**
     * Run this task
     */
    public abstract void run();
}