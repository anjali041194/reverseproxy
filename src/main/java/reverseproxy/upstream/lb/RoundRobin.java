package reverseproxy.upstream.lb;

import reverseproxy.conf.ReverseProxyConfig.Server;

import java.util.concurrent.atomic.AtomicInteger;

interface ServerSelection {
    Server next();
}

public class RoundRobin implements ServerSelection {

    private final ServerSelection inner;

    public RoundRobin(Server[] servers) {
        inner = ServerSelectionFactory.INSTANCE.newSelection(servers);
    }

    @Override
    public Server next() {
        Server server = inner.next();
        System.out.println(server.toString());
        return server;
    }

    static class ServerSelectionFactory {

        public static final ServerSelectionFactory INSTANCE = new ServerSelectionFactory();

        private ServerSelectionFactory() {
        }

        private static boolean isPowerOfTwo(int val) {
            return (val & -val) == val;
        }

        public ServerSelection newSelection(Server[] servers) {
            if (isPowerOfTwo(servers.length)) {
                return new PowerOfTwoEventExecutor(servers);
            } else {
                return new GenericEventExecutor(servers);
            }
        }

        //nested static class
        private static final class PowerOfTwoEventExecutor implements ServerSelection {
            private final AtomicInteger idx = new AtomicInteger();
            private final Server[] servers;

            PowerOfTwoEventExecutor(Server[] servers) {
                this.servers = servers;
            }

            public Server next() {
                return servers[idx.getAndIncrement() & servers.length - 1];
            }
        }

        //nested static class
        private static final class GenericEventExecutor implements ServerSelection {
            private final AtomicInteger idx = new AtomicInteger();
            private final Server[] servers;

            GenericEventExecutor(Server[] servers) {
                this.servers = servers;
            }

            public Server next() {
                return servers[Math.abs(idx.getAndIncrement() % servers.length)];
            }
        }
    }
}