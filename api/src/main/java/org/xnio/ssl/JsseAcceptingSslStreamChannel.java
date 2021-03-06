/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2010 Red Hat, Inc. and/or its affiliates.
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

package org.xnio.ssl;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.xnio.OptionMap;
import org.xnio.Pool;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.ConnectedSslStreamChannel;
import org.xnio.channels.ConnectedStreamChannel;

/**
 * Accepting channel for ConnectedSslStreamChannels
 * 
 * @author <a href="mailto:flavia.rainone@jboss.com">Flavia Rainone</a>
 *
 */
final class JsseAcceptingSslStreamChannel extends AbstractAcceptingSslChannel<ConnectedSslStreamChannel, ConnectedStreamChannel> {

    JsseAcceptingSslStreamChannel(final SSLContext sslContext, final AcceptingChannel<? extends ConnectedStreamChannel> tcpServer, final OptionMap optionMap, final Pool<ByteBuffer> socketBufferPool, final Pool<ByteBuffer> applicationBufferPool, final boolean startTls) {
        super(sslContext, tcpServer, optionMap, socketBufferPool, applicationBufferPool, startTls);
    }

    @Override
    public ConnectedSslStreamChannel accept(ConnectedStreamChannel tcpChannel, SSLEngine engine) {
            return new JsseConnectedSslStreamChannel(tcpChannel, engine, socketBufferPool, applicationBufferPool, startTls);
    }
}
