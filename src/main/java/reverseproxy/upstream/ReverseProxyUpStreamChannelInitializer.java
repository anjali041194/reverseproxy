package reverseproxy.upstream;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import reverseproxy.conf.ReverseProxyConfig.Server;

public class ReverseProxyUpStreamChannelInitializer extends ChannelInitializer<Channel> {

    private final Server server;

    private final String proxyPass;

    public ReverseProxyUpStreamChannelInitializer(Server server, String proxyPass) {
        this.server = server;
        this.proxyPass = proxyPass;
    }

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new HttpClientCodec());
        pipeline.addLast(new HttpObjectAggregator(512 * 1024));
        pipeline.addLast(new UpStreamHandler(server, proxyPass));
    }
}
