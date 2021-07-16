/*
 * Copyright 2014 JBoss Inc
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
package io.apiman.gateway.platforms.servlet.connectors;

import io.apiman.common.config.options.BasicAuthOptions;
import io.apiman.common.logging.ApimanLoggerFactory;
import io.apiman.common.logging.IApimanLogger;
import io.apiman.common.util.ApimanPathUtils;
import io.apiman.gateway.engine.IApiConnection;
import io.apiman.gateway.engine.IApiConnectionResponse;
import io.apiman.gateway.engine.IConnectorConfig;
import io.apiman.gateway.engine.async.AsyncResultImpl;
import io.apiman.gateway.engine.async.IAsyncHandler;
import io.apiman.gateway.engine.async.IAsyncResultHandler;
import io.apiman.gateway.engine.auth.RequiredAuthType;
import io.apiman.gateway.engine.beans.Api;
import io.apiman.gateway.engine.beans.ApiRequest;
import io.apiman.gateway.engine.beans.ApiResponse;
import io.apiman.gateway.engine.beans.exceptions.ConnectorException;
import io.apiman.gateway.engine.handler.ErrorHandler;
import io.apiman.gateway.engine.impl.DefaultPolicyErrorWriter;
import io.apiman.gateway.engine.io.ByteBuffer;
import io.apiman.gateway.engine.io.IApimanBuffer;
import io.apiman.gateway.platforms.servlet.GatewayThreadContext;
import io.apiman.gateway.platforms.servlet.connectors.ok.OkUrlFactory;
import io.apiman.gateway.platforms.servlet.connectors.ssl.SSLSessionStrategy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import com.squareup.okhttp.OkHttpClient;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

/**
 * Models a live connection to a back end API.
 *
 * @author eric.wittmann@redhat.com
 */
public class HttpApiConnection implements IApiConnection, IApiConnectionResponse {

    private static final IApimanLogger LOGGER = ApimanLoggerFactory.getLogger(DefaultPolicyErrorWriter.class);

    private final ApiRequest request;
    private final Api api;
    private final RequiredAuthType requiredAuthType;
    private final SSLSessionStrategy sslStrategy;
    private final IAsyncResultHandler<IApiConnectionResponse> responseHandler;

    private boolean connected;
    private HttpURLConnection connection;
    private OutputStream outputStream;

    private IAsyncHandler<IApimanBuffer> bodyHandler;
    private IAsyncHandler<Void> endHandler;

    private ApiResponse response;

    private final OkHttpClient client;
    private final boolean hasDataPolicy;
    private boolean isError = false;

    private final IConnectorConfig connectorConfig;

    /**
     * Constructor.
     *
     * @param client the http client to use
     * @param sslStrategy the SSL strategy
     * @param request the request
     * @param api the API
     * @param requiredAuthType the authorization type
     * @param hasDataPolicy if policy chain contains data policies
     * @param connectorConfig the dynamic connector configuration as possibly modified by policies
     * @param handler the result handler
     * @throws ConnectorException when unable to connect
     */
    public HttpApiConnection(OkHttpClient client, ApiRequest request, Api api,
            RequiredAuthType requiredAuthType, SSLSessionStrategy sslStrategy,
            boolean hasDataPolicy, IConnectorConfig connectorConfig,
            IAsyncResultHandler<IApiConnectionResponse> handler) throws ConnectorException {
        this.client = client;
        this.request = request;
        this.api = api;
        this.requiredAuthType = requiredAuthType;
        this.sslStrategy = sslStrategy;
        this.hasDataPolicy = hasDataPolicy;
        this.connectorConfig = connectorConfig;
        this.responseHandler = handler;

        try {
            connect();
        } catch (Exception e) {
            // Sometimes it's possible that multiple exceptions end up coming through. Ignore secondary ones.
            if (!isError) {
                handler.handle(AsyncResultImpl.<IApiConnectionResponse> create(e));
                isError = true;
            }
        }
    }

    /**
     * Connects to the back end system.
     */
    private void connect() throws ConnectorException {
        try {
            String endpoint = ApimanPathUtils.join(api.getEndpoint(), request.getDestination());

            if (request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
                String delim = "?"; //$NON-NLS-1$
                for (Entry<String, String> entry : request.getQueryParams()) {
                    endpoint += delim + entry.getKey();
                    if (entry.getValue() != null) {
                        endpoint += "=" + URLEncoder.encode(entry.getValue(), "UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    delim = "&"; //$NON-NLS-1$
                }
            }
            URL url = new URL(endpoint);
            OkUrlFactory factory = new OkUrlFactory(client);
            connection = factory.open(url);

            boolean isSsl = connection instanceof HttpsURLConnection;

            if(requiredAuthType == RequiredAuthType.MTLS && !isSsl) {
                throw new ConnectorException("Mutually authenticating TLS requested, but insecure endpoint protocol was indicated."); //$NON-NLS-1$
            }

            if (requiredAuthType == RequiredAuthType.BASIC) {
                BasicAuthOptions options = new BasicAuthOptions(api.getEndpointProperties());
                if (options.getUsername() != null && options.getPassword() != null) {
                    if (options.isRequireSSL() && !isSsl) {
                        throw new ConnectorException("Endpoint security requested (BASIC auth) but endpoint is not secure (SSL)."); //$NON-NLS-1$
                    }

                    String up = options.getUsername() + ':' + options.getPassword();
                    StringBuilder builder = new StringBuilder();
                    builder.append("Basic "); //$NON-NLS-1$
                    builder.append(Base64.encodeBase64String(up.getBytes()));
                    connection.setRequestProperty("Authorization", builder.toString()); //$NON-NLS-1$
                    connectorConfig.suppressRequestHeader("Authorization"); //$NON-NLS-1$
                }
            }

            if (hasDataPolicy) {
                connectorConfig.suppressRequestHeader("Content-Length"); //$NON-NLS-1$
            }

            if (isSsl) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                SSLSocketFactory socketFactory = sslStrategy.getSocketFactory();
                https.setSSLSocketFactory(socketFactory);
                https.setHostnameVerifier(sslStrategy.getHostnameVerifier());
            }

            setConnectTimeout(connection);
            setReadTimeout(connection);
            if (request.getType().equalsIgnoreCase("PUT") || request.getType().equalsIgnoreCase("POST")) { //$NON-NLS-1$ //$NON-NLS-2$
                connection.setDoOutput(true);
            } else {
                connection.setDoOutput(false);
            }
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod(request.getType());

            // Set the request headers
            for (Entry<String, String> entry : request.getHeaders()) {
                String hkey = entry.getKey();
                String hval = entry.getValue();
                if (!connectorConfig.getSuppressedRequestHeaders().contains(hkey)) {
                    connection.addRequestProperty(hkey, hval);
                }
            }

            // Set or reset mandatory headers
            connection.setRequestProperty("Host", url.getHost() + determinePort(url)); //$NON-NLS-1$
            connection.connect();
            connected = true;
        } catch (IOException error) {
            handleConnectionError(error);
        }
    }

    /**
     * If the endpoint properties includes a connect timeout override, then
     * set it here.
     * @param connection
     */
    private void setConnectTimeout(HttpURLConnection connection) {
        try {
            Map<String, String> endpointProperties = this.api.getEndpointProperties();
            if (endpointProperties.containsKey("timeouts.connect")) { //$NON-NLS-1$
                int connectTimeoutMs = Integer.parseInt(endpointProperties.get("timeouts.connect")); //$NON-NLS-1$
                connection.setConnectTimeout(connectTimeoutMs);
            }
        } catch (Throwable t) {
        }
    }

    /**
     * If the endpoint properties includes a read timeout override, then
     * set it here.
     * @param connection
     */
    private void setReadTimeout(HttpURLConnection connection) {
        try {
            Map<String, String> endpointProperties = this.api.getEndpointProperties();
            if (endpointProperties.containsKey("timeouts.read")) { //$NON-NLS-1$
                int connectTimeoutMs = Integer.parseInt(endpointProperties.get("timeouts.read")); //$NON-NLS-1$
                connection.setReadTimeout(connectTimeoutMs);
            }
        } catch (Throwable t) {
        }
    }

    /**
     * Extracts the port information fromthe given URL.
     * @param url a URL
     * @return the port configured in the URL, or empty string if no port specified
     */
    private String determinePort(URL url) {
        return (url.getPort() == -1) ? "" : ":" + url.getPort(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @see io.apiman.gateway.engine.io.IReadStream#bodyHandler(io.apiman.gateway.engine.async.IAsyncHandler)
     */
    @Override
    public void bodyHandler(IAsyncHandler<IApimanBuffer> bodyHandler) {
        this.bodyHandler = bodyHandler;
    }

    /**
     * @see io.apiman.gateway.engine.io.IReadStream#endHandler(io.apiman.gateway.engine.async.IAsyncHandler)
     */
    @Override
    public void endHandler(IAsyncHandler<Void> endHandler) {
        this.endHandler = endHandler;
    }

    /**
     * @see io.apiman.gateway.engine.io.IReadStream#getHead()
     */
    @Override
    public ApiResponse getHead() {
        return response;
    }

    /**
     * @see io.apiman.gateway.engine.io.IStream#isFinished()
     */
    @Override
    public boolean isFinished() {
        return !connected;
    }

    /**
     * @see io.apiman.gateway.engine.IApiConnection#isConnected()
     */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * @see io.apiman.gateway.engine.io.IAbortable#abort()
     */
    @Override
    public void abort(Throwable t) {
        try {
            if (!connected) {
                throw new IOException("Not connected."); //$NON-NLS-1$
            }
            if (connection != null) {
                try {
                    IOUtils.closeQuietly(outputStream);
                    IOUtils.closeQuietly(connection.getInputStream());
                } catch (Exception e) {}
                try {
                    connected = false;
                    connection.disconnect();
                } catch (Exception e) {}
            }
        } catch (IOException e) {
            // TODO log this error but don't rethrow it
        }
    }

    /**
     * @see io.apiman.gateway.engine.io.IWriteStream#write(io.apiman.gateway.engine.io.IApimanBuffer)
     */
    @Override
    public void write(IApimanBuffer chunk) {
        try {
            if (!connected) {
                throw new IOException("Not connected."); //$NON-NLS-1$
            }
            if (outputStream == null) {
                outputStream = connection.getOutputStream();
            }
            if (chunk instanceof ByteBuffer) {
                byte[] buffer = (byte[]) chunk.getNativeBuffer();
                outputStream.write(buffer, 0, chunk.length());
            } else {
                outputStream.write(chunk.getBytes());
            }
        } catch (IOException e) {
            handleConnectionError(e);
        }
    }

    /**
     * @see io.apiman.gateway.engine.io.IWriteStream#end()
     */
    @Override
    public void end() {
        try {
            IOUtils.closeQuietly(outputStream);
            if (isError) {
                return;
            } else if (!connected) {
                throw new IOException("Not connected."); //$NON-NLS-1$
            }
            outputStream = null;
            // Process the response, convert to an ApiResponse object, and return it
            response = GatewayThreadContext.getApiResponse();

            // Add headers
            for (Entry<String, List<String>> headerEntry : connection.getHeaderFields().entrySet()) {
                String headerName = headerEntry.getKey();
                List<String> headerValues = headerEntry.getValue();

                if (headerName != null && !connectorConfig.getSuppressedResponseHeaders().contains(headerName)) {
                    response.getHeaders().add(headerName, headerValues);
                }
            }

            response.setCode(connection.getResponseCode());
            response.setMessage(connection.getResponseMessage());
            responseHandler.handle(AsyncResultImpl.<IApiConnectionResponse> create(this));
        } catch (Exception e) {
            handleConnectionError(e);
        }
    }

    /**
     * @see io.apiman.gateway.engine.io.ISignalReadStream#transmit()
     */
    @Override
    public void transmit() {
        try {
            if (!connected) {
                throw new IOException("Not connected."); //$NON-NLS-1$
            }
            InputStream is = connection.getInputStream();
            ByteBuffer buffer = new ByteBuffer(2048);
            int numBytes = buffer.readFrom(is);
            while (numBytes != -1) {
                bodyHandler.handle(buffer);
                numBytes = buffer.readFrom(is);
            }
            IOUtils.closeQuietly(is);
            connection.disconnect();
            connected = false;
            endHandler.handle(null);
        } catch (Throwable e) {
            // At this point we're sort of screwed, because we've already sent the response to
            // the originating client - and we're in the process of sending the body data. So
            // I guess the only thing to do is abort() the connection and cross our fingers.
            if (connected) {
                abort(e);
            }
            throw new RuntimeException(e);
        }
    }

    private void handleConnectionError(Exception error) {
        ConnectorException ce = ErrorHandler.handleConnectionError(error);
        LOGGER.error(error.getMessage(), error);
        throw ce;
    }
}
