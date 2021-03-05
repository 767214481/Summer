package com.swingfrog.summer.server;

import com.swingfrog.summer.concurrent.MatchGroupKey;
import com.swingfrog.summer.concurrent.SessionQueueMgr;
import com.swingfrog.summer.concurrent.SessionTokenQueueMgr;
import com.swingfrog.summer.concurrent.SingleQueueMgr;
import com.swingfrog.summer.ioc.ContainerMgr;
import com.swingfrog.summer.protocol.SessionRequest;
import com.swingfrog.summer.server.rpc.RpcClientMgr;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

public abstract class AbstractServerHandler<T> extends SimpleChannelInboundHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractServerHandler.class);
    protected final ServerContext serverContext;
    protected final SessionHandlerGroup sessionHandlerGroup;
    protected final SessionContextGroup sessionContextGroup;

    protected AbstractServerHandler(ServerContext serverContext) {
        this.serverContext = serverContext;
        sessionHandlerGroup = serverContext.getSessionHandlerGroup();
        sessionContextGroup = serverContext.getSessionContextGroup();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        if (serverContext.getConfig().isAllowAddressEnable()) {
            String address = ((InetSocketAddress)ctx.channel().remoteAddress()).getHostString();
            String[] addressList = serverContext.getConfig().getAllowAddressList();
            boolean allow = false;
            for (String s : addressList) {
                if (address.equals(s)) {
                    allow = true;
                    break;
                }
            }
            if (!allow) {
                log.warn("not allow {} connect", address);
                ctx.close();
                return;
            }
            log.info("allow {} connect", address);
        }
        sessionContextGroup.createSession(channel);
        SessionContext sctx = sessionContextGroup.getSessionByChannel(channel);
        if (!sessionHandlerGroup.accept(sctx)) {
            log.warn("not accept client {}", sctx);
            ctx.close();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        SessionContext sctx = sessionContextGroup.getSessionByChannel(channel);
        log.info("added client {}", sctx);
        sessionHandlerGroup.added(sctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        SessionContext sctx = sessionContextGroup.getSessionByChannel(channel);
        if (sctx != null) {
            log.info("removed client {}", sctx);
            sessionHandlerGroup.removed(sctx);
            sessionContextGroup.destroySession(channel);
            RpcClientMgr.get().remove(sctx);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel channel = ctx.channel();
        SessionContext sctx = sessionContextGroup.getSessionByChannel(channel);
        if (cause instanceof TooLongFrameException) {
            sessionHandlerGroup.lengthTooLongMsg(sctx);
        } else {
            log.error(cause.getMessage(), cause);
        }
    }

    protected void submitRunnable(SessionContext sctx, SessionRequest request, Runnable runnable) {
        Method method = RemoteDispatchMgr.get().getMethod(request);
        if (method != null) {
            MatchGroupKey matchGroupKey = ContainerMgr.get().getSingleQueueKey(method);
            if (matchGroupKey != null) {
                if (matchGroupKey.hasKeys()) {
                    Object[] partKeys = new Object[matchGroupKey.getKeys().size()];
                    for (int i = 0; i < matchGroupKey.getKeys().size(); i++) {
                        String key = request.getData().getString(matchGroupKey.getKeys().get(i));
                        if (key == null) {
                            key = "";
                        }
                        partKeys[i] = key;
                    }
                    SingleQueueMgr.get().execute(matchGroupKey.getMainKey(partKeys).intern(), runnable);
                } else {
                    SingleQueueMgr.get().execute(matchGroupKey.getMainKey().intern(), runnable);
                }
                return;
            }
        }
        submitSessionQueue(sctx, runnable);
    }

    protected void submitSessionQueue(SessionContext sctx, Runnable runnable) {
        Object token = sctx.getToken();
        if (token == null) {
            SessionQueueMgr.get().execute(sctx, runnable);
            return;
        }
        SessionTokenQueueMgr.get().execute(token, runnable);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, T t) throws Exception {
        Channel channel = ctx.channel();
        SessionContext sctx = sessionContextGroup.getSessionByChannel(channel);
        long now = System.currentTimeMillis();
        long last = sctx.getLastRecvTime();
        sctx.setLastRecvTime(now);
        if ((now - last) < serverContext.getConfig().getColdDownMs()) {
            sessionHandlerGroup.sendTooFastMsg(sctx);
        }
        recv(ctx.channel(), sctx, t);
    }

    protected abstract void recv(Channel channel, SessionContext sctx, T request) throws Exception;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        SessionContext sctx = sessionContextGroup.getSessionByChannel(channel);
        while (ctx.channel().isActive() && ctx.channel().isWritable() && !sctx.getWaitWriteQueue().isEmpty()) {
            ctx.writeAndFlush(sctx.getWaitWriteQueue().poll());
        }
    }

}
