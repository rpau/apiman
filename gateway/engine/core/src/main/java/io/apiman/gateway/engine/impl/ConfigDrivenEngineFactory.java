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
package io.apiman.gateway.engine.impl;

import io.apiman.common.logging.ApimanLoggerFactory;
import io.apiman.common.logging.IApimanLogger;
import io.apiman.common.logging.IDelegateFactory;
import io.apiman.common.util.crypt.IDataEncrypter;
import io.apiman.gateway.engine.EngineConfigTuple;
import io.apiman.gateway.engine.IApiRequestPathParser;
import io.apiman.gateway.engine.IComponentRegistry;
import io.apiman.gateway.engine.IConnectorFactory;
import io.apiman.gateway.engine.IEngineConfig;
import io.apiman.gateway.engine.IGatewayInitializer;
import io.apiman.gateway.engine.IMetrics;
import io.apiman.gateway.engine.IPluginRegistry;
import io.apiman.gateway.engine.IRegistry;
import io.apiman.gateway.engine.async.AsyncInitialize;
import io.apiman.gateway.engine.async.AsyncResultImpl;
import io.apiman.gateway.engine.async.IAsyncResultHandler;
import io.apiman.gateway.engine.policy.IPolicyFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating the engine, obviously.
 *
 * @author eric.wittmann@redhat.com
 */
public class ConfigDrivenEngineFactory extends AbstractEngineFactory {

    private static final IApimanLogger LOGGER = ApimanLoggerFactory.getLogger(ConfigDrivenEngineFactory.class);

    private final IEngineConfig engineConfig;
    private IAsyncResultHandler<Void> handler;
    private boolean failed = false;
    private final AtomicInteger asyncInitializeAwaiting = new AtomicInteger(0);
    private boolean finishedLoading = false;

    /**
     * Constructor.
     * @param engineConfig the engine config
     */
    public ConfigDrivenEngineFactory(IEngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createPluginRegistry()
     */
    @Override
    protected IPluginRegistry createPluginRegistry() {
        Class<? extends IPluginRegistry> c = engineConfig.getPluginRegistryClass();
        Map<String, String> config = engineConfig.getPluginRegistryConfig();

        return create(c, config);
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createDataEncrypter(io.apiman.gateway.engine.IPluginRegistry)
     */
    @Override
    protected IDataEncrypter createDataEncrypter(IPluginRegistry pluginRegistry) {
        try {
            Class<? extends IDataEncrypter> c = engineConfig.getDataEncrypterClass(pluginRegistry);
            Map<String, String> config = engineConfig.getDataEncrypterConfig();
            IDataEncrypter encrypter = create(c, config);
            return encrypter;
        } catch (RuntimeException e) {
            if ("No IDataEncrypter class configured.".equals(e.getMessage())) { //$NON-NLS-1$
                LOGGER.info("NOTE: No explicit Data Encrypter found.  Falling back to the Default. [" + e.getMessage() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                return new DefaultDataEncrypter();
            } else {
                throw e;
            }
        }
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createRegistry(io.apiman.gateway.engine.IPluginRegistry, io.apiman.common.util.crypt.IDataEncrypter)
     */
    @Override
    protected IRegistry createRegistry(IPluginRegistry pluginRegistry, IDataEncrypter encrypter) {
        Class<? extends IRegistry> c = engineConfig.getRegistryClass(pluginRegistry);
        Map<String, String> config = engineConfig.getRegistryConfig();
        IRegistry registry = create(c, config);
        return new SecureRegistryWrapper(registry, encrypter);
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createComponentRegistry(io.apiman.gateway.engine.IPluginRegistry)
     */
    @Override
    protected IComponentRegistry createComponentRegistry(IPluginRegistry pluginRegistry) {
        return new ConfigDrivenComponentRegistry(engineConfig, pluginRegistry);
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createConnectorFactory(io.apiman.gateway.engine.IPluginRegistry)
     */
    @Override
    protected IConnectorFactory createConnectorFactory(IPluginRegistry pluginRegistry) {
        Class<? extends IConnectorFactory> c = engineConfig.getConnectorFactoryClass(pluginRegistry);
        Map<String, String> config = engineConfig.getConnectorFactoryConfig();
        return create(c, config);
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createPolicyFactory(io.apiman.gateway.engine.IPluginRegistry)
     */
    @Override
    protected IPolicyFactory createPolicyFactory(IPluginRegistry pluginRegistry) {
        Class<? extends IPolicyFactory> c = engineConfig.getPolicyFactoryClass(pluginRegistry);
        Map<String, String> config = engineConfig.getPolicyFactoryConfig();
        return create(c, config);
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createMetrics(io.apiman.gateway.engine.IPluginRegistry)
     */
    @Override
    protected IMetrics createMetrics(IPluginRegistry pluginRegistry) {
        Class<? extends IMetrics> c = engineConfig.getMetricsClass(pluginRegistry);
        Map<String, String> config = engineConfig.getMetricsConfig();
        return create(c, config);
    }

    /**
     * @see io.apiman.gateway.engine.impl.AbstractEngineFactory#createInitializers(io.apiman.gateway.engine.IPluginRegistry)
     */
    @Override
    protected List<IGatewayInitializer> createInitializers(IPluginRegistry pluginRegistry) {
        List<IGatewayInitializer> rval = new ArrayList<>();

        List<EngineConfigTuple<? extends IGatewayInitializer>> initializers = engineConfig.getGatewayInitializers(pluginRegistry);
        for (EngineConfigTuple<? extends IGatewayInitializer> tuple : initializers) {
            IGatewayInitializer initializer = create(tuple.getComponentClass(), tuple.getComponentConfig());
            rval.add(initializer);
        }

        return rval;
    }

    @Override
    protected IDelegateFactory createLoggerFactory(IPluginRegistry pluginRegistry) {
        Class<? extends IDelegateFactory> c = engineConfig.getLoggerFactoryClass(pluginRegistry);
        Map<String, String> config = engineConfig.getLoggerFactoryConfig();
        return create(c, config);
    }


    @Override
    protected IApiRequestPathParser createRequestPathParser(IPluginRegistry pluginRegistry) {
        Class<? extends IApiRequestPathParser> c = engineConfig.getApiRequestPathParserClass(pluginRegistry);
        Map<String, String> config = engineConfig.getApiRequestPathParserConfig();
        return create(c, config);
    }

    /**
     * Creates a new instance of the given type, passing the given config
     * map if possible (if the class has a Map constructor).
     * @param type the type to create
     * @param config config to pass
     * @return a new instance of 'type'
     */
    protected <T> T create(Class<T> type, Map<String, String> config) {
        // Use #doInstantiate so inheriting classes can override it, as #instantiate is static final.
        T instance = doInstantiate(type, config);

        if (instance instanceof AsyncInitialize) {
            asyncInitializeAwaiting.incrementAndGet();
            ((AsyncInitialize) instance).initialize(initResult -> {
                asyncInitializeAwaiting.decrementAndGet();
                if (initResult.isError()) {
                    if (!failed) { // Not already failed before
                        failed = true;
                        if (handler != null)
                            handler.handle(initResult);
                    } else {
                        LOGGER.error("Failure occurred, but error handler was already invoked", initResult.getError().getCause()); //$NON-NLS-1$
                    }
                } else {
                    checkLoadingStatus();
                }
            });
        }

        checkLoadingStatus();
        return instance;
    }

    protected void checkLoadingStatus() {
        if (handler != null && !failed && finishedLoading && asyncInitializeAwaiting.get() == 0) {
            handler.handle(AsyncResultImpl.create((Void) null));
        }
    }

    protected IAsyncResultHandler<Void> getResultHandler() {
        return handler;
    }

    public AbstractEngineFactory setResultHandler(IAsyncResultHandler<Void> handler) {
        this.handler = handler;
        return this;
    }

    protected <T> T doInstantiate(Class<T> type, Map<String, String> config) {
        return instantiate(type, config);
    }

    /**
     * Creates a new instance of the given type, passing the given config
     * map if possible (if the class has a Map constructor).
     * @param type the type to create
     * @param config config to pass
     * @return a new instance of 'type'
     */
    public static final <T> T instantiate(Class<T> type, Map<String, String> config) {
        try {
            Constructor<T> constructor = type.getConstructor(Map.class);
            return constructor.newInstance(config);
        } catch (NoSuchMethodException e) {
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            return type.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void complete() {
        finishedLoading = true;
        checkLoadingStatus();
    }

}
