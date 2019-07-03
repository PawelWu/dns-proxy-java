package ru.kitsu.dnsproxy;

import java.util.List;

public interface UpstreamServerFilterComparator {

    List<UpstreamServer> filter(List<UpstreamServer> upstreamServers, ProxyRequest request);

}
