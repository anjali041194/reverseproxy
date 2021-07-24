package reverseproxy.upstream.lb;

import reverseproxy.conf.ReverseProxyConfig;
import reverseproxy.conf.ReverseProxyConfig.Server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class RoundRobinFactory {

    private final Map<String, RoundRobin> robinMap = new HashMap<>();

    public void init(ReverseProxyConfig config) {
        Map<String, List<Server>> upstreams = config.upstreams();
        if (null == upstreams || upstreams.isEmpty()) {
            return;
        }

        for (Entry<String, List<Server>> upstreamEntry : upstreams.entrySet()) {
            robinMap.put(upstreamEntry.getKey(), new RoundRobin(upstreamEntry.getValue().toArray(new Server[]{})));
        }
    }

    public RoundRobin roundRobin(String proxypass) {
        return robinMap.get(proxypass);
    }
}
