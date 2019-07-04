package ru.kitsu.dnsproxy;

/**
 * Parsed upstream configuration line
 * 
 * @author Alexey Borzenkov
 * 
 */
public class UpstreamConfig {
	
	private static int SEQUENCE = 1;
	
	private final String suffix;
	private final String host;
	private final int port;
	private final int index;

	private UpstreamConfig(String suffix, String host, int port) {
		this.suffix = suffix;
		this.host = host;
		this.port = port;
		this.index = SEQUENCE++;
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}
	
	public String getSuffix() {
		return suffix;
	}
	
	public int getIndex() {
		return index;
	}

	public static UpstreamConfig createConfig(String prefix, String host) {
		int port;
		int index = host.lastIndexOf(':');
		if (index != -1) {
			port = Integer.parseInt(host.substring(index + 1));
			host = host.substring(0, index);
		} else {
			port = 53;
		}
		return new UpstreamConfig(prefix, host, port);
	}
	
	@Override
	public String toString() {
		return String.format("config { suffix: %s, host: %s}", suffix, host);
	}
}
