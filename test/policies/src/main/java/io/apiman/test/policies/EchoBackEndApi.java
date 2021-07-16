/*
 * Copyright 2015 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apiman.test.policies;

import io.apiman.gateway.engine.beans.ApiRequest;
import io.apiman.gateway.engine.beans.ApiResponse;
import io.apiman.test.common.mock.EchoResponse;

import java.io.IOException;
import java.util.Date;

import org.apache.commons.codec.digest.DigestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The default policy test backend API.
 *
 * @author eric.wittmann@redhat.com
 */
@SuppressWarnings("nls")
public class EchoBackEndApi implements IPolicyTestBackEndApi {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static long counter = 0;

    /**
     * @see io.apiman.test.policies.IPolicyTestBackEndApi#invoke(io.apiman.gateway.engine.beans.ApiRequest, byte[])
     */
    @Override
    public PolicyTestBackEndApiResponse invoke(ApiRequest request, byte[] requestBody) {
        try {
            EchoResponse echoResponse = new EchoResponse();
            if (requestBody != null) {
                echoResponse.setBodyLength(new Long(requestBody.length));
                echoResponse.setBodySha1(DigestUtils.sha1Hex(requestBody));
            }
            echoResponse.setCounter(counter++);
            echoResponse.setHeaders(request.getHeaders());
            echoResponse.setMethod(request.getType());
            echoResponse.setResource(request.getDestination());
            echoResponse.setUri("urn:" + request.getDestination());

            ApiResponse apiResponse = new ApiResponse();

            String errorCode = request.getHeaders().get("X-Echo-ErrorCode");
            if (errorCode != null) {
                int ec = Integer.parseInt(errorCode);
                String errorMsg = request.getHeaders().get("X-Echo-ErrorMessage");
                apiResponse.setCode(ec);
                apiResponse.setMessage(errorMsg);
            } else {
                apiResponse.setCode(200);
                apiResponse.setMessage("OK");
            }
            apiResponse.getHeaders().put("Date", new Date().toString());
            apiResponse.getHeaders().put("Server", "apiman.policy-test");
            apiResponse.getHeaders().put("Content-Type", "application/json");

            String responseBody = normalize(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(echoResponse));
            apiResponse.getHeaders().put("Content-Length", String.valueOf(responseBody.length()));

            PolicyTestBackEndApiResponse response = new PolicyTestBackEndApiResponse(apiResponse, responseBody);
            return response;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Normalize newlines across platforms.
     * @param output
     */
    private static String normalize(String output) {
        return output.replace("\r\n", "\n");
    }

}
