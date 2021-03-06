// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.

package dsn.rpc.async;

import dsn.base.error_code.error_types;

import dsn.base.rpc_address;
import dsn.operator.client_operator;
import io.netty.channel.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.socket.SocketChannel;

import org.slf4j.Logger;

import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by weijiesun on 17-9-13.
 */
public class ReplicaSession {
    public static class RequestEntry {
        public int sequenceId;
        public dsn.operator.client_operator op;
        public Runnable callback;
        public ScheduledFuture timeoutTask;
    }

    public enum ConnState {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    public ReplicaSession(rpc_address address, String perfCounterTags, EventLoopGroup rpcGroup, int socketTimeout) {
        this.address = address;
        this.perfCounterTags = perfCounterTags;
        this.rpcGroup = rpcGroup;

        final ReplicaSession this_ = this;
        boot = new Bootstrap();
        boot.group(rpcGroup).channel(ClusterManager.getSocketChannelClass())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, socketTimeout)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("ThriftEncoder", new ThriftFrameEncoder());
                        pipeline.addLast("ThriftDecoder", new ThriftFrameDecoder(this_));
                        pipeline.addLast("ClientHandler", new ReplicaSession.DefaultHandler());
                    }
                });
    }

    public int asyncSend(client_operator op, Runnable callbackFunc, long timeoutInMilliseconds) {
        RequestEntry entry = new RequestEntry();
        entry.sequenceId = seqId.getAndIncrement();
        entry.op = op;
        entry.callback = callbackFunc;
        //NOTICE: must make sure the msg is put into the pendingResponse map BEFORE
        //the timer task is scheduled.
        pendingResponse.put(new Integer(entry.sequenceId), entry);
        entry.timeoutTask = addTimer(entry.sequenceId, timeoutInMilliseconds);

        VolatileFields cache = fields;
        if (cache.state == ConnState.CONNECTED) {
            write(entry, cache);
        } else {
            boolean needConnect = false;
            synchronized (pendingSend) {
                cache = fields;
                if (cache.state == ConnState.CONNECTED) {
                    write(entry, cache);
                } else {
                    pendingSend.offer(entry);
                    if (cache.state == ConnState.DISCONNECTED) {
                        cache = new VolatileFields();
                        cache.state = ConnState.CONNECTING;
                        fields = cache;
                        needConnect = true;
                    }
                }
            }
            if (needConnect) {
                doConnect();
            }
        }
        return entry.sequenceId;
    }

    public RequestEntry getAndRemoveEntry(int seqID) {
        return pendingResponse.remove(new Integer(seqID));
    }

    public final String name() { return address.toString(); }

    public final rpc_address getAddress() { return address; }

    private void doConnect() {
        try {
            boot.connect(address.get_ip(), address.get_port()).addListener(
                    new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture) throws Exception {
                            if (channelFuture.isSuccess()) {
                                logger.info("{}: start to async connect to target, wait channel to active", name());
                            } else {
                                logger.warn("{}: try to connect to target failed: ", name(), channelFuture.cause());
                                markSessionDisconnect();
                            }
                        }
                    }
            );
        } catch (UnknownHostException ex) {
            logger.error("invalid address: {}", address.toString());
            assert false;
        }
    }

    private void markSessionConnected(Channel activeChannel) {
        VolatileFields newCache = new VolatileFields();
        newCache.state = ConnState.CONNECTED;
        newCache.nettyChannel = activeChannel;

        synchronized (pendingSend) {
            while (!pendingSend.isEmpty()) {
                RequestEntry e = pendingSend.poll();
                if (pendingResponse.get(new Integer(e.sequenceId)) != null) {
                    write(e, newCache);
                } else {
                    logger.info("{}: {} is removed from pending, perhaps timeout", name(), e.sequenceId);
                }
            }
            fields = newCache;
        }
    }

    private void markSessionDisconnect() {
        VolatileFields cache = fields;
        synchronized (pendingSend) {
            if (cache.state != ConnState.DISCONNECTED) {
                // NOTICE:
                // 1. when a connection is reset, the timeout response
                // is not answered in the order they query
                // 2. It's likely that when the session is disconnecting
                // but the caller of the api query/asyncQuery didn't notice
                // this. In this case, we are relying on the timeout task.
                while (!pendingSend.isEmpty()) {
                    RequestEntry e = pendingSend.poll();
                    tryNotifyWithSequenceID(e.sequenceId, error_types.ERR_SESSION_RESET, false);
                }
                List<RequestEntry> l = new LinkedList<RequestEntry>();
                for (Map.Entry<Integer, RequestEntry> entry : pendingResponse.entrySet()) {
                    l.add(entry.getValue());
                }
                for (RequestEntry e : l) {
                    tryNotifyWithSequenceID(e.sequenceId, error_types.ERR_SESSION_RESET, false);
                }

                cache = new VolatileFields();
                cache.state = ConnState.DISCONNECTED;
                cache.nettyChannel = null;
                fields = cache;
            } else {
                logger.warn("{}: session is closed already", name());
            }
        }
    }

    private void tryNotifyWithSequenceID(
            int seqID,
            error_types errno,
            boolean isTimeoutTask) {
        logger.debug("{}: {} is notified with error {}, isTimeoutTask {}",
                name(), seqID, errno.toString(), isTimeoutTask);
        RequestEntry entry = pendingResponse.remove(new Integer(seqID));
        if (entry != null) {
            if (!isTimeoutTask)
                entry.timeoutTask.cancel(true);
            entry.op.rpc_error.errno = errno;
            entry.callback.run();
        }
        else {
            logger.warn("{}: {} is removed by others, current error {}, isTimeoutTask {}",
                    name(), seqID, errno.toString(), isTimeoutTask);
        }
    }

    private void write(final RequestEntry entry, VolatileFields cache) {
        cache.nettyChannel.writeAndFlush(entry).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                //NOTICE: we never do the connection things, this should be the duty of
                //ChannelHandler, we only notify the request
                if (!channelFuture.isSuccess()) {
                    logger.info("{} write seqid {} failed: ", name(), entry.sequenceId, channelFuture.cause());
                    tryNotifyWithSequenceID(entry.sequenceId, error_types.ERR_TIMEOUT, false);
                }
            }
        });
    }

    private ScheduledFuture addTimer(final int seqID, long timeoutInMillseconds) {
        return rpcGroup.schedule(
                new Runnable() {
                    @Override
                    public void run() {
                        tryNotifyWithSequenceID(seqID, error_types.ERR_TIMEOUT, true);
                    }
                },
                timeoutInMillseconds,
                TimeUnit.MILLISECONDS
        );
    }

    final class DefaultHandler extends SimpleChannelInboundHandler<RequestEntry> {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            logger.warn("Channel {} for session {} is inactive", ctx.channel().toString(), name());
            markSessionDisconnect();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("Channel {} for session {} is active", ctx.channel().toString(), name());
            markSessionConnected(ctx.channel());
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, final RequestEntry msg) {
            logger.debug("{}: handle response with seqid({})", name(), msg.sequenceId);
            if (msg.callback != null) {
                msg.callback.run();
            } else {
                logger.warn("{}: seqid({}) has no callback, just ignore the response", name(), msg.sequenceId);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.warn("got exception in inbound handler {} for session {}: ",
                    ctx.channel().toString(),
                    name(),
                    cause);
            ctx.close();
        }
    }

    // for test
    ConnState getState() {
        return fields.state;
    }

    final private ConcurrentHashMap<Integer, RequestEntry> pendingResponse = new ConcurrentHashMap<Integer, RequestEntry>();
    final private AtomicInteger seqId = new AtomicInteger(0);

    final private Queue<RequestEntry> pendingSend = new LinkedList<RequestEntry>();

    private final static class VolatileFields {
        public ConnState state = ConnState.DISCONNECTED;
        public Channel nettyChannel = null;
    }
    private volatile VolatileFields fields = new VolatileFields();

    private rpc_address address;
    private String perfCounterTags;
    private Bootstrap boot;
    private EventLoopGroup rpcGroup;

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(ReplicaSession.class);
}
