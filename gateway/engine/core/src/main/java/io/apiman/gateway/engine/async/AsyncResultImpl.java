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
package io.apiman.gateway.engine.async;

import java.util.StringJoiner;

/**
 * A simple implementation of the async result interface.  Offers convenient
 * creation of result instances.
 * 
 * @author Marc Savy <msavy@redhat.com>
 * @param <T> A type T to return as result
 */
public class AsyncResultImpl<T> implements IAsyncResult<T> {
    
    private T result;
    private Throwable error;
    
    /**
     * Convenience method for creating an async result.
     * @param result the result
     * @return result of type T
     */
    public static final <T> AsyncResultImpl<T> create(T result) {
        return new AsyncResultImpl<>(result);
    }
    
    /**
     * Convenience method for creating an async result.
     * @param result the result
     * @return result of type T
     */
    public static final <T> AsyncResultImpl<T> create(T result, Class<T> type) {
        return new AsyncResultImpl<>(result);
    }
    
    /**
     * Convenience method for creating an async result.
     * @param t the throwable
     * @return result of type T
     */
    public static final <T> AsyncResultImpl<T> create(Throwable t) {
        return new AsyncResultImpl<>(t);
    }
    
    /**
     * Convenience method for creating an async result.
     * @param t the throwable
     * @param type the type
     * @return result of type T
     */
    public static final <T> AsyncResultImpl<T> create(Throwable t, Class<T> type) {
        return new AsyncResultImpl<>(t);
    }

    /**
     * Constructor.
     * @param result
     */
    private AsyncResultImpl(T result) {
        this.result = result;
    }

    /**
     * Constructor.
     * @param error
     */
    private AsyncResultImpl(Throwable error) {
        this.error = error;
    }

    /**
     * @see io.apiman.gateway.engine.async.IAsyncResult#isSuccess()
     */
    @Override
    public boolean isSuccess() {
        return error == null;
    }

    /**
     * @see io.apiman.gateway.engine.async.IAsyncResult#isError()
     */
    @Override
    public boolean isError() {
        return error != null;
    }

    /**
     * @see io.apiman.gateway.engine.async.IAsyncResult#getResult()
     */
    @Override
    public T getResult() {
        return result;
    }

    /**
     * @see io.apiman.gateway.engine.async.IAsyncResult#getError()
     */
    @Override
    public Throwable getError() {
        return error;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", AsyncResultImpl.class.getSimpleName() + "[", "]")
            .add("result=" + result)
            .add("error=" + error)
            .toString();
    }
}
