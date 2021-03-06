/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common.http.netty;

import java.util.logging.Level;

import javax.net.ssl.SSLEngine;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.internal.logging.InternalLogLevel;
import io.netty.util.internal.logging.InternalLoggerFactory;

import com.vmware.xenon.common.Operation.SocketContext;
import com.vmware.xenon.common.Utils;

/**
 * This sets up the channel pipeline for the Netty client.
 *
 * The default cause is that we set up a pipeline that handles HTTP/1.1, but if
 * forceHttp2 is true, we set up a pipeline that can be upgraded to HTTP/2.
 *
 */
public class NettyHttpClientRequestInitializer extends ChannelInitializer<SocketChannel> {

    public static final String SSL_HANDLER = "ssl";
    public static final String HTTP1_CODEC = "http1-codec";
    public static final String UPGRADE_HANDLER = "upgrade-handler";
    public static final String UPGRADE_REQUEST = "upgrade-request";
    public static final String AGGREGATOR_HANDLER = "aggregator";
    public static final String XENON_HANDLER = "xenon";
    public static final String EVENT_LOGGER = "event-logger";

    private final NettyChannelPool pool;
    private boolean isHttp2Only = false;
    private boolean debugLogging = false;

    public NettyHttpClientRequestInitializer(
            NettyChannelPool nettyChannelPool,
            boolean isHttp2Only) {
        this.pool = nettyChannelPool;
        this.isHttp2Only = isHttp2Only;
        NettyLoggingUtil.setupNettyLogging();
    }

    /**
     * initChannel is called by Netty when a channel is first used.
     */
    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        ch.config().setAllocator(NettyChannelContext.ALLOCATOR);
        ch.config().setSendBufferSize(NettyChannelContext.BUFFER_SIZE);
        ch.config().setReceiveBufferSize(NettyChannelContext.BUFFER_SIZE);
        if (this.pool.getSSLContext() != null) {
            if (this.isHttp2Only) {
                throw new IllegalArgumentException("HTTP/2 with SSL is not supported");
            }
            SSLEngine engine = this.pool.getSSLContext().createSSLEngine();
            engine.setUseClientMode(true);
            p.addLast(SSL_HANDLER, new SslHandler(engine));
        }

        HttpClientCodec http1_codec = new HttpClientCodec(
                NettyChannelContext.MAX_INITIAL_LINE_LENGTH,
                NettyChannelContext.MAX_HEADER_SIZE,
                NettyChannelContext.MAX_CHUNK_SIZE, false);

        // The HttpClientCodec combines the HttpRequestEncoder and the HttpResponseDecoder, and it
        // also provides a method for upgrading the protocol, which we use to support HTTP/2.
        p.addLast(HTTP1_CODEC, http1_codec);

        if (this.isHttp2Only) {
            try {
                HttpToHttp2ConnectionHandler connectionHandler = makeHttp2ConnectionHandler();
                Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(
                        connectionHandler);
                HttpClientUpgradeHandler upgradeHandler = new HttpClientUpgradeHandler(
                        http1_codec,
                        upgradeCodec,
                        SocketContext.getMaxClientRequestSize());

                p.addLast(UPGRADE_HANDLER, upgradeHandler);
                p.addLast(UPGRADE_REQUEST, new UpgradeRequestHandler());

                // This promise will be triggered when we negotiate the settings.
                // That's important because we can't send user data until the negotiation is done
                ChannelPromise settingsPromise = ch.newPromise();
                p.addLast("settings-handler", new Http2SettingsHandler(settingsPromise));
                ch.attr(NettyChannelContext.SETTINGS_PROMISE_KEY).set(settingsPromise);
                p.addLast(EVENT_LOGGER, new NettyHttp2UserEventLogger(this.debugLogging));
            } catch (Throwable ex) {
                Utils.log(NettyHttpClientRequestInitializer.class,
                        NettyHttpClientRequestInitializer.class.getSimpleName(),
                        Level.WARNING, "Channel Initializer exception: %s", ex);
                throw ex;
            }
        } else {
            // The HttpObjectAggregator is not needed for HTTP/2. For HTTP/1.1 it
            // aggregates the HttpMessage and HttpContent into the FullHttpResponse
            p.addLast(AGGREGATOR_HANDLER,
                    new HttpObjectAggregator(SocketContext.getMaxClientRequestSize()));
        }
        p.addLast(XENON_HANDLER, new NettyHttpServerResponseHandler(this.pool));
    }

    /**
     * This special handler will send a GET to the HTTP/2 server. This will trigger
     * the HTTP/2 settings negotiation, which needs to be done before we send user data.
     * Once we've triggered it, we remove it fro the pipeline.
     */
    private static final class UpgradeRequestHandler extends ChannelHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext context) throws Exception {
            DefaultFullHttpRequest upgradeRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                    HttpMethod.GET, "/");
            context.writeAndFlush(upgradeRequest);
            super.channelActive(context);
            context.pipeline().remove(this);
        }
    }

    /**
     * For HTTP/2 we don't have anything as simple as the HttpClientCodec (at least, not in Netty
     * 5.0alpha 2), so we create the equivalent here.
     *
     * @return
     */
    private HttpToHttp2ConnectionHandler makeHttp2ConnectionHandler() {
        // DefaultHttp2Connection is for client or server. False means "client".
        Http2Connection connection = new DefaultHttp2Connection(false);
        InboundHttp2ToHttpAdapter inboundAdapter = new InboundHttp2ToHttpAdapter.Builder(connection)
                .maxContentLength(NettyChannelContext.MAX_CHUNK_SIZE)
                .propagateSettings(true)
                .build();
        DelegatingDecompressorFrameListener frameListener = new DelegatingDecompressorFrameListener(
                connection, inboundAdapter);

        Http2FrameReader frameReader = makeFrameReader(this.debugLogging);
        Http2FrameWriter frameWriter = makeFrameWriter(this.debugLogging);

        HttpToHttp2ConnectionHandler connectionHandler = new NettyHttpToHttp2Handler(
                connection,
                frameReader,
                frameWriter,
                frameListener);

        // We set the the maximum concurrent HTTP/2 streams. It defaults to Integer.MAX_VALUE
        // so we set it to something a bit smaller, but still with lots of concurrency.
        Http2Settings settings = connectionHandler.decoder().localSettings();
        settings.maxConcurrentStreams(this.pool.getConnectionLimitPerHost());
        try {
            connectionHandler.decoder().localSettings(settings);
        } catch (Http2Exception ex) {
            Utils.log(NettyHttpClientRequestInitializer.class,
                    NettyHttpClientRequestInitializer.class.getSimpleName(),
                    Level.WARNING, "Failed to set maximum HTTP/2 concurrent streams: %s",
                    ex.getMessage());
        }
        return connectionHandler;
    }

    /**
     * Create a frame reader. We set the maximum frame size and optionally add some logging.
     * It's public because it's also used by NettyHttpServerInitializer
     */
    public static Http2FrameReader makeFrameReader(boolean debugLogging) {
        DefaultHttp2FrameReader frameReader = new DefaultHttp2FrameReader();

        try {
            frameReader.maxFrameSize(NettyChannelContext.MAX_HTTP2_FRAME_SIZE);
        } catch (Http2Exception ex) {
            Utils.log(NettyHttpClientRequestInitializer.class,
                    NettyHttpClientRequestInitializer.class.getSimpleName(),
                    Level.WARNING, "Failed to set HTTP/2 maximum frame size to %s: %s",
                    NettyChannelContext.MAX_HTTP2_FRAME_SIZE,
                    ex.getMessage());
        }

        if (debugLogging) {
            Http2FrameLogger logger = new Http2FrameLogger(
                    InternalLogLevel.INFO,
                    InternalLoggerFactory.getInstance(NettyHttpClientRequestInitializer.class));
            return new Http2InboundFrameLogger(frameReader, logger);

        }
        return frameReader;
    }

    /**
     * Create a frame writer. We set the maximum frame size and optionally add some logging.
     * It's public because it's also used by NettyHttpServerInitializer
     */
    public static Http2FrameWriter makeFrameWriter(boolean debugLogging) {
        DefaultHttp2FrameWriter frameWriter = new DefaultHttp2FrameWriter();

        try {
            frameWriter.maxFrameSize(NettyChannelContext.MAX_HTTP2_FRAME_SIZE);
        } catch (Http2Exception ex) {
            Utils.log(NettyHttpClientRequestInitializer.class,
                    NettyHttpClientRequestInitializer.class.getSimpleName(),
                    Level.WARNING, "Failed to set HTTP/2 maximum frame size to %s",
                    NettyChannelContext.MAX_HTTP2_FRAME_SIZE);
        }

        if (debugLogging) {
            Http2FrameLogger logger = new Http2FrameLogger(
                    InternalLogLevel.INFO,
                    InternalLoggerFactory.getInstance(NettyHttpClientRequestInitializer.class));
            return new Http2OutboundFrameLogger(frameWriter, logger);

        }
        return frameWriter;
    }

    /**
     * This handler does just one thing: it informs us (via a promise) that we've received
     * an HTTP/2 settings frame. We do this because when we connect to the HTTP/2 server,
     * we have to wait until the settings have been negotiated before we send data.
     * NettyChannelPool uses this promise.
     *
     */
    private static class Http2SettingsHandler extends SimpleChannelInboundHandler<Http2Settings> {
        private ChannelPromise promise;

        public Http2SettingsHandler(ChannelPromise promise) {
            this.promise = promise;
        }

        @Override
        protected void messageReceived(ChannelHandlerContext ctx, Http2Settings msg)
                throws Exception {
            this.promise.setSuccess();
            ctx.pipeline().remove(this);
        }
    }

}
