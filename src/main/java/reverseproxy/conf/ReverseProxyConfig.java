package reverseproxy.conf;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reverseproxy.util.AntPathMatcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ReverseProxyConfig {

    private static final Logger logger = LoggerFactory.getLogger(ReverseProxyConfig.class);

    private static final String UPSTREAM_POOL_PREFIX = "http://";

    private static final String AUTO = "auto";

    private static final int DEFAULT_HTTP_PORT = 80;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @JsonProperty("listen")
    private int listen;

    @JsonProperty("keepalive_timeout")
    private int keepaliveTimeout;

    @JsonProperty("worker_threads")
    private String workerThreads;
    private int workers;

    @JsonProperty("worker_connections")
    private int workerConnections;

    @JsonProperty("servers")
    private Map<String, List<Location>> servers;

    @JsonProperty("upstreams")
    private Map<String, Upstream> upstreams;

    private Map<String, List<Server>> us = new HashMap<>();

    public void parse(String path) throws ConfigException {
        File configFile = new File(path);

        logger.info("Reading configuration from: " + configFile);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            if (!configFile.exists()) {
                throw new IllegalArgumentException(configFile.toString() + " file is missing");
            }

            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            parseConfig(mapper.readValue(new File(path), ReverseProxyConfig.class));
        } catch (IOException e) {
            throw new ConfigException("Error processing " + path, e);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Error processing " + path, e);
        }
    }

    public void parseConfig(ReverseProxyConfig reverseProxyConfig) throws ConfigException {
        listen = reverseProxyConfig.listen;
        keepaliveTimeout = reverseProxyConfig.keepaliveTimeout;
        workerConnections = reverseProxyConfig.workerConnections;
        workerThreads = reverseProxyConfig.workerThreads;

        if (AUTO.equalsIgnoreCase(workerThreads)) {
            workers = Runtime.getRuntime().availableProcessors();
        } else {
            try {
                workers = Integer.parseInt(workerThreads);
            } catch (NumberFormatException e) {
                throw new ConfigException("worker_threads invalid", e);
            }
        }

        upstreams = new HashMap<>();
        for (Entry<String, Upstream> entry : reverseProxyConfig.upstreams.entrySet()) {
            upstreams.put(UPSTREAM_POOL_PREFIX + entry.getKey(), entry.getValue());
        }

        servers = new HashMap<>();
        if (DEFAULT_HTTP_PORT != listen) {
            for (Entry<String, List<Location>> entry : reverseProxyConfig.servers.entrySet()) {
                servers.put(entry.getKey() + ":" + listen, entry.getValue());
            }
        } else {
            servers = reverseProxyConfig.servers;
        }

        List<String> hosts;
        List<Server> servers;
        for (Entry<String, Upstream> upstreamEntry : upstreams.entrySet()) {
            hosts = upstreamEntry.getValue().servers();
            if (CollectionUtils.isEmpty(hosts)) {
                continue;
            }
            servers = new ArrayList<>(1 << 2);
            for (String host : hosts) {
                servers.add(new Server(host, upstreamEntry.getValue().keepAlive()));
            }
            us.put(upstreamEntry.getKey(), servers);
        }
    }

    public int listen() {
        return listen;
    }

    public int keepaliveTimeout() {
        return keepaliveTimeout;
    }

    public int workerThreads() {
        return workers;
    }

    public int workerConnections() {
        return workerConnections;
    }

    public Map<String, List<Server>> upstreams() {
        return us;
    }

    public String proxyPass(String serverName, String uri) {
        List<Location> locations = servers.get(serverName);
        if (CollectionUtils.isEmpty(locations)) {
            return null;
        }
        for (Location location : locations) {
            if (pathMatcher.match(location.path(), uri)) {
                return location.proxypass();
            }
        }
        return null;
    }

    public static class ConfigException extends Exception {

        public ConfigException(String msg, Exception e) {
            super(msg, e);
        }
    }

    static class Location {

        @JsonProperty("path")
        private String path;

        @JsonProperty("proxy_pass")
        private String proxypass;

        public String path() {
            return path;
        }

        public String proxypass() {
            return proxypass;
        }
    }

    static class Upstream {
        // the maximum number of idle keepalive connections to upstream servers
        // that are preserved in the cache of each worker process
        @JsonProperty("keepalive")
        private int keepalive;

        @JsonProperty("servers")
        private List<String> servers;

        public int keepAlive() {
            return keepalive;
        }

        public List<String> servers() {
            return servers;
        }
    }

    public static class Server {

        private int keepalive;

        private String ip;

        private int port;

        public Server(String host, int keepalive) {
            this.keepalive = keepalive;
            int pidx = host.lastIndexOf(':');
            if (pidx >= 0) {
                // otherwise : is at the end of the string, ignore
                if (pidx < host.length() - 1) {
                    this.port = Integer.parseInt(host.substring(pidx + 1));
                }
                this.ip = host.substring(0, pidx);
            }
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        public int getKeepalive() {
            return keepalive;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((ip == null) ? 0 : ip.hashCode());
            result = prime * result + keepalive;
            result = prime * result + port;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Server other = (Server) obj;
            if (ip == null) {
                if (other.ip != null)
                    return false;
            } else if (!ip.equals(other.ip))
                return false;
            if (keepalive != other.keepalive)
                return false;
            if (port != other.port)
                return false;
            return true;
        }
    }
}
