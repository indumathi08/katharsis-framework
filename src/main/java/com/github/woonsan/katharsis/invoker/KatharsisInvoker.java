/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.woonsan.katharsis.invoker;

import io.katharsis.dispatcher.RequestDispatcher;
import io.katharsis.errorhandling.exception.KatharsisException;
import io.katharsis.errorhandling.mapper.def.KatharsisExceptionMapper;
import io.katharsis.queryParams.RequestParams;
import io.katharsis.queryParams.RequestParamsBuilder;
import io.katharsis.request.dto.RequestBody;
import io.katharsis.request.path.JsonPath;
import io.katharsis.request.path.PathBuilder;
import io.katharsis.resource.registry.ResourceRegistry;
import io.katharsis.response.BaseResponse;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Scanner;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.woonsan.katharsis.invoker.util.QueryStringUtils;
import com.google.common.net.MediaType;

/**
 * Katharsis dispatcher invoker.
 */
public class KatharsisInvoker {

    private static Logger log = LoggerFactory.getLogger(KatharsisInvoker.class);

    private ObjectMapper objectMapper;
    private ResourceRegistry resourceRegistry;
    private RequestDispatcher requestDispatcher;

    public KatharsisInvoker(ObjectMapper objectMapper, ResourceRegistry resourceRegistry,
                            RequestDispatcher requestDispatcher) {
        this.objectMapper = objectMapper;
        this.resourceRegistry = resourceRegistry;
        this.requestDispatcher = requestDispatcher;
    }

    public void invoke(KatharsisInvokerContext invokerContext) throws ServletException, IOException {
        if (isAcceptableMediaType(invokerContext)) {
            try {
                dispatchRequest(invokerContext);
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }
    }

    private void dispatchRequest(KatharsisInvokerContext invokerContext) throws Exception {
        BaseResponse<?> katharsisResponse = null;

        try {
            JsonPath jsonPath = new PathBuilder(resourceRegistry).buildPath(invokerContext.getRequestPath());

            RequestParams requestParams = createRequestParams(invokerContext);

            String method = invokerContext.getRequestMethod();
            RequestBody requestBody = inputStreamToBody(invokerContext.getRequestEntityStream());

            katharsisResponse = requestDispatcher.dispatchRequest(jsonPath, method, requestParams,
                                                                  requestBody);
        } catch (KatharsisException e) {
            katharsisResponse = new KatharsisExceptionMapper().toErrorResponse(e);
        } finally {
            if (katharsisResponse != null) {
                invokerContext.setResponseStatus(katharsisResponse.getHttpStatus());
                invokerContext.setResponseContentType(JsonApiMediaType.APPLICATION_JSON_API);

                OutputStream os = null;
                BufferedOutputStream bos = null;

                try {
                    os = invokerContext.getResponseOutputStream();
                    bos = new BufferedOutputStream(os);
                    objectMapper.writeValue(bos, katharsisResponse);
                    bos.flush();
                } finally {
                    if (bos != null) {
                        try {
                            bos.close();
                        } catch (IOException ignore) {
                        }
                    }
                    if (os != null) {
                        try {
                            os.close();
                        } catch (IOException ignore) {
                        }
                    }
                }
            } else {
                invokerContext.setResponseStatus(HttpServletResponse.SC_NO_CONTENT);
            }
        }
    }

    private boolean isAcceptableMediaType(KatharsisInvokerContext invokerContext) {
        String acceptHeader = invokerContext.getRequestHeader("Accept");

        if (acceptHeader != null) {
            String [] accepts = acceptHeader.split(",");
            MediaType acceptableType;

            for (String mediaTypeItem : accepts) {
                acceptableType = MediaType.parse(mediaTypeItem.trim());

                if (JsonApiMediaType.isCompatibleMediaType(acceptableType)) {
                    return true;
                }
            }
        }

        return false;
    }

    private RequestParams createRequestParams(KatharsisInvokerContext invokerContext) {
        RequestParamsBuilder requestParamsBuilder = new RequestParamsBuilder(objectMapper);
        Map<String, String> queryParameters =
            QueryStringUtils.parseQueryStringAsSingleValueMap(invokerContext);
        return requestParamsBuilder.buildRequestParams(queryParameters);
    }

    private RequestBody inputStreamToBody(InputStream is) throws IOException {
        if (is == null) {
            return null;
        }

        Scanner s = new Scanner(is).useDelimiter("\\A");
        String requestBody = s.hasNext() ? s.next() : "";

        if (requestBody == null || requestBody.isEmpty()) {
            return null;
        }

        return objectMapper.readValue(requestBody, RequestBody.class);
    }

}
