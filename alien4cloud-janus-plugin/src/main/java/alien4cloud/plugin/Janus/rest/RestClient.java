/*
* Copyright 2016 Bull Atos.  All Rights Reserved.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* See the NOTICE file distributed with this work for additional information
* regarding copyright ownership.
*/
package alien4cloud.plugin.Janus.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import alien4cloud.plugin.Janus.ProviderConfig;
import alien4cloud.plugin.Janus.rest.Response.ErrorsResponse;
import alien4cloud.plugin.Janus.rest.Response.EventResponse;
import alien4cloud.plugin.Janus.rest.Response.JanusError;
import alien4cloud.plugin.Janus.rest.Response.LogResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

public class RestClient {

    private static RestClient instance;
    private ProviderConfig providerConfiguration;
    private static ObjectMapper objectMapper;
    private static final String CHARSET = "UTF-8";

    // Default long pooling duration on Janus endpoints is 5min
    private static final long SOCKET_TIMEOUT = 300060;
    private static final long CONNECTION_TIMEOUT = 10000;


    public static synchronized RestClient getInstance() {
        if (instance == null) {
            instance = new RestClient();
            RestClient.initObjectMapper();
            Unirest.setTimeouts(CONNECTION_TIMEOUT, SOCKET_TIMEOUT);
        }
        return instance;
    }

    public void setProviderConfiguration(ProviderConfig providerConfiguration) {
        this.providerConfiguration = providerConfiguration;
    }

    private static void initObjectMapper() {
        RestClient.objectMapper = new ObjectMapper() {
            private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper
                    = new com.fasterxml.jackson.databind.ObjectMapper();

            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return jacksonObjectMapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            public String writeValue(Object value) {
                try {
                    return jacksonObjectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        Unirest.setObjectMapper(RestClient.objectMapper);
    }

    public String postTopologyToJanus() throws Exception {
        final InputStream stream;

        stream = new FileInputStream(new File("topology.zip"));
        final byte[] bytes = new byte[stream.available()];
        stream.read(bytes);
        stream.close();

        HttpResponse<JsonNode> postResponse = Unirest.post(providerConfiguration.getUrlJanus() + "/deployments")
                .header("accept", "application/json")
                .header("Content-Type", "application/zip")
                .body(bytes)
                .asJson();


        if (!postResponse.getStatusText().equals("Created")) {
            throw new Exception("Janus returned an error ?");
        }

        return postResponse.getHeaders().getFirst("Location");
    }

    public String getStatusFromJanus(String deploymentUrl) throws Exception {
        HttpResponse<JsonNode> res = Unirest.get(providerConfiguration.getUrlJanus() + deploymentUrl)
                .header("accept", "application/json")
                .asJson();


        JSONObject obj = res.getBody().getObject();

        if (!obj.has("status")) {
            throw new Exception("getStatusFromJanus : Janus returned an error");
        }

        return obj.getString("status");
    }


    public LogResponse getLogFromJanus(String deploymentUrl, int index) throws Exception {
        HttpResponse<JsonNode> logRes =
                Unirest.get(providerConfiguration.getUrlJanus() + deploymentUrl + "/" + "logs?index=" + index + "&filter=")
                        .header("accept", "application/json")
                        .asJson();
        this.checkRestErrors(logRes);
        return objectMapper.readValue(new String(IOUtils.toByteArray(logRes.getRawBody()), CHARSET), LogResponse.class);
    }

    public EventResponse getEventFromJanus(String deploymentUrl, int index) throws Exception {
        HttpResponse<JsonNode> eventResponse =
                Unirest.get(providerConfiguration.getUrlJanus() + deploymentUrl + "/" + "events?index=" + index + "&filter=")
                        .header("accept", "application/json")
                        .asJson();
        this.checkRestErrors(eventResponse);
        return objectMapper.readValue(new String(IOUtils.toByteArray(eventResponse.getRawBody()), CHARSET), EventResponse.class);
    }

    public String undeployJanus(String deploymentUrl) throws UnirestException {
        return Unirest.delete(providerConfiguration.getUrlJanus() + deploymentUrl)
                .header("accept", "application/json")
                .asJson()
                .getStatusText();

    }

    private static boolean isStatusCodeOk(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private void checkRestErrors(HttpResponse<?> httpResponse) throws Exception {
        if (!isStatusCodeOk(httpResponse.getStatus())) {
            ErrorsResponse errors =
                    this.objectMapper.readValue(new String(IOUtils.toByteArray(httpResponse.getRawBody()), CHARSET), ErrorsResponse.class);
            JanusError error = errors.getErrors().iterator().next();
            throw JanusRestException.fromJanusError(error);
        }
    }
}
