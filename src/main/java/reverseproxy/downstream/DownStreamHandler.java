package reverseproxy.downstream;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reverseproxy.conf.ReverseProxyConfig;
import reverseproxy.conf.ReverseProxyConfig.Server;
import reverseproxy.core.AttributeKeys;
import reverseproxy.core.Connection;
import reverseproxy.core.RequestContext;
import reverseproxy.upstream.ReverseProxyUpStreamChannelInitializer;
import reverseproxy.upstream.lb.RoundRobin;
import reverseproxy.upstream.lb.RoundRobinFactory;

import java.util.Iterator;
import java.util.LinkedList;

@ChannelHandler.Sharable
public class DownStreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(DownStreamHandler.class);

    private static final int MAX_ATTEMPTS = 3;

    private final ReverseProxyConfig config;

    private final RoundRobinFactory robinFactory;

    public DownStreamHandler(ReverseProxyConfig config, RoundRobinFactory robinFactory) {
        this.config = config;
        this.robinFactory = robinFactory;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        final Channel downstream = ctx.channel();

        boolean keepAlived = HttpUtil.isKeepAlive(request);
        HttpHeaders requestHeaders = request.headers();

        // get Host header
        String serverName = requestHeaders.get(HttpHeaderNames.HOST);
        // get proxy_pass
        String proxyPass = config.proxyPass(serverName, request.uri());

        // get roundRobin
        RoundRobin roundRobin = null;
        Server server = null;
        if (null == proxyPass || null == (roundRobin = robinFactory.roundRobin(proxyPass))
                || null == (server = roundRobin.next())) {
            // return 404
            notFound(ctx, keepAlived);
            return;
        }

        // rewrite http request(keep alive to upstream)
        request.setProtocolVersion(HttpVersion.HTTP_1_1);
        requestHeaders.remove(HttpHeaderNames.CONNECTION);

        // increase refCount
        request.retain();
        // proxy request
        proxy(server, proxyPass, downstream, request, keepAlived, MAX_ATTEMPTS);
    }

    public void proxy(Server server, String proxyPass, Channel downstream, FullHttpRequest request, boolean keepAlived,
                      int maxAttempts) {
        // get connection from cache
        Connection connection = getConn(server, proxyPass);
        if (null == connection) {// need create an new connection
            createConnAndSendRequest(downstream, server, proxyPass, request, keepAlived, maxAttempts);
        } else {// use the cached connection
            setContextAndRequest(server, proxyPass, request, connection.getChannel(), downstream, keepAlived, false,
                    maxAttempts);
        }
    }

    public void createConnAndSendRequest(Channel downstream, Server server, String proxyPass, FullHttpRequest request,
                                         boolean keepAlived, int maxAttempts) {
        Bootstrap b = new Bootstrap();
        b.group(downstream.eventLoop());
        b.channel(downstream.getClass());

        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        // default is pooled direct
        // ByteBuf(io.netty.util.internal.PlatformDependent.DIRECT_BUFFER_PREFERRED)
        b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        // 32kb(for massive long connections, See
        // http://www.infoq.com/cn/articles/netty-million-level-push-service-design-points)
        // 64kb(RocketMq remoting default value)
        b.option(ChannelOption.SO_SNDBUF, 32 * 1024);
        b.option(ChannelOption.SO_RCVBUF, 32 * 1024);
        // temporary settings, need more tests
        b.option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024));
        // default is true, reduce thread context switching
        b.option(ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP, true);

        b.handler(new ReverseProxyUpStreamChannelInitializer(server, proxyPass));

        ChannelFuture connectFuture = b.connect(server.getIp(), server.getPort());
        connectFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    setContextAndRequest(server, proxyPass, request, future.channel(), downstream, keepAlived, true,
                            maxAttempts);
                } else {
                    if (maxAttempts > 0) {
                        proxy(server, proxyPass, downstream, request, keepAlived, maxAttempts - 1);
                    } else {
                        request.release();
                        downstream.writeAndFlush(RequestContext.errorResponse(), downstream.voidPromise());
                    }
                }
            }
        });
    }

    public Connection getConn(Server server, String proxyPass) {
        LinkedList<Connection> conns = RequestContext.keepAlivedConntions(proxyPass);
        Connection connection = null;
        Connection tmp = null;

        for (Iterator<Connection> it = conns.iterator(); it.hasNext(); ) {
            tmp = it.next();
            // find the matched keepalived connection
            if (server.equals(tmp.getServer())) {
                it.remove();
                connection = tmp;
                break;
            }
        }
        return connection;
    }

    public void notFound(ChannelHandlerContext ctx, boolean keepAlived) {
        if (keepAlived) {
            ctx.writeAndFlush(RequestContext.notfoundResponse(), ctx.voidPromise());
        } else {
            ctx.writeAndFlush(RequestContext.notfoundResponse()).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void setContextAndRequest(Server server, String proxyPass, FullHttpRequest request, Channel upstream,
                                     Channel downstream, boolean keepAlived, final boolean newConn, final int maxAttempts) {
        // set request context
        upstream.attr(AttributeKeys.DOWNSTREAM_CHANNEL_KEY).set(downstream);
        upstream.attr(AttributeKeys.KEEP_ALIVED_KEY).set(keepAlived);

        request.retain();
        upstream.writeAndFlush(request).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    if (maxAttempts > 0) {
                        proxy(server, proxyPass, downstream, request, keepAlived, maxAttempts - 1);
                    } else {
                        downstream.writeAndFlush(RequestContext.errorResponse(), downstream.voidPromise());
                        logger.error(String.format("%s upstream channel[%s] write to backed fail",
                                newConn ? "new" : "cached", future.channel()), future.cause());
                    }
                } else {
                    request.release();
                }
            }
        });
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        logger.warn(String.format("downstream channel[%s] writability changed, isWritable: %s", ctx.channel(),
                ctx.channel().isWritable()));
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.warn(String.format("downstream channel[%s] inactive", ctx.channel()));
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error(String.format("downstream channel[%s] exceptionCaught", ctx.channel()), cause);
    }
}
