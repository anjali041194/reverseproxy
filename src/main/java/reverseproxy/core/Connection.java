package reverseproxy.core;

import io.netty.channel.Channel;
import reverseproxy.conf.ReverseProxyConfig.Server;

public class Connection {

    private final Server server;

    private final Channel channel;

    public Connection(Server server, Channel channel) {
        this.server = server;
        this.channel = channel;
    }

    public Server getServer() {
        return server;
    }

    public Channel getChannel() {
        return channel;
    }
}
