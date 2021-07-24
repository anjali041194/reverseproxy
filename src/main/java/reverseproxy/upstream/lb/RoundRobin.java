package reverseproxy.upstream.lb;

import reverseproxy.conf.ReverseProxyConfig.Server;

import java.util.concurrent.atomic.AtomicInteger;

interface ServerChooser {
    Server next();
}

public class RoundRobin implements ServerChooser {

    private final ServerChooser inner;

    public RoundRobin(Server[] servers) {
        inner = ServerChooserFactory.INSTANCE.newChooser(servers);
    }

    @Override
    public Server next() {
        return inner.next();
    }

    static class ServerChooserFactory {

        public static final ServerChooserFactory INSTANCE = new ServerChooserFactory();

        private ServerChooserFactory() {
        }

        private static boolean isPowerOfTwo(int val) {
            return (val & -val) == val;
        }

        public ServerChooser newChooser(Server[] servers) {
            if (isPowerOfTwo(servers.length)) {
                return new PowerOfTwoEventExecutorChooser(servers);
            } else {
                return new GenericEventExecutorChooser(servers);
            }
        }

        private static final class PowerOfTwoEventExecutorChooser implements ServerChooser {
            private final AtomicInteger idx = new AtomicInteger();
            private final Server[] servers;

            PowerOfTwoEventExecutorChooser(Server[] servers) {
                this.servers = servers;
            }

            public Server next() {
                return servers[idx.getAndIncrement() & servers.length - 1];
            }
        }

        private static final class GenericEventExecutorChooser implements ServerChooser {
            private final AtomicInteger idx = new AtomicInteger();
            private final Server[] servers;

            GenericEventExecutorChooser(Server[] servers) {
                this.servers = servers;
            }

            public Server next() {
                return servers[Math.abs(idx.getAndIncrement() % servers.length)];
            }
        }
    }
}