package ru.kitsu.dnsproxy;

import java.util.List;
import java.util.stream.Collectors;
import ru.kitsu.dnsproxy.parser.DNSQuestion;

public class UpstreamServerFilterComparatorImpl implements UpstreamServerFilterComparator {

    private ProxyRequest request;

    @Override
    public List<UpstreamServer> filter(List<UpstreamServer> upstreamServers, ProxyRequest request) {
        this.request = request;
        List<UpstreamServer> matchingServers = serversMatchingRequest(upstreamServers);
        List<UpstreamServer> validServers = !matchingServers.isEmpty()?
            matchingServers :
            // use only servers without specified prefix
            upstreamServers.stream()
                .filter( upstreamServer -> upstreamServer.getUpstreamConfig().getSuffix().isEmpty() )
                .collect(Collectors.toList());

        return validServers.stream()
            .sorted( this::sortByMatchAndPriority )
            .collect(Collectors.toList());
    }

    private int sortByMatchAndPriority(UpstreamServer serverA, UpstreamServer serverB) {
        UpstreamConfig serverAConfig = serverA.getUpstreamConfig();
        UpstreamConfig serverBConfig = serverB.getUpstreamConfig();

        int order = 0;
        for (DNSQuestion q : request.getMessage().getQuestions()) {
            boolean serverAMatchesUrl = q.getName().endsWith(serverAConfig.getSuffix());
            boolean serverBMatchesUrl = q.getName().endsWith(serverBConfig.getSuffix());

            if (serverAMatchesUrl && !serverBMatchesUrl) {
                order--;
            } else if (serverBMatchesUrl && !serverAMatchesUrl) {
                order++;
            } else if (serverAMatchesUrl && serverBMatchesUrl) {
                order += compareNumberOfDots( serverAConfig, serverBConfig );
            }
        }

        return 0 != order?
            order :
            Integer.compare(serverAConfig.getIndex(), serverBConfig.getIndex());
    }

    private int compareNumberOfDots(UpstreamConfig serverAConfig, UpstreamConfig serverBConfig) {
        int aDots = serverAConfig.getSuffix().split("\\.").length;
        int bDots = serverBConfig.getSuffix().split("\\.").length;
        if (aDots == bDots) {
            return Integer.compare(serverAConfig.getIndex(), serverBConfig.getIndex());
        } else {
            return Integer.compare(bDots, aDots);
        }
    }

    private List<UpstreamServer> serversMatchingRequest(List<UpstreamServer> upstreams) {
        return upstreams.stream()
            .filter( upstreamServer -> !upstreamServer.getUpstreamConfig().getSuffix().isEmpty() )
            .filter( upstreamServer -> {
                for (DNSQuestion q : request.getMessage().getQuestions()) {
                    if (q.getName().endsWith( upstreamServer.getUpstreamConfig().getSuffix() )) {
                        return true;
                    }
                }
                return false;
            })
            .collect(Collectors.toList());
    }

}
