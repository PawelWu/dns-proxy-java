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

	public UpstreamConfig(String suffix, String host) {
		this(suffix, host, 53);
	}

	public UpstreamConfig(String suffix, String host, int port) {
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

	public static UpstreamConfig parseLine(String prefix, String line) {
		int index = line.lastIndexOf(':');
		if (index != -1) {
			return new UpstreamConfig(prefix, line.substring(0, index),
					Integer.parseInt(line.substring(index + 1)));
		}
		return new UpstreamConfig(prefix, line);
	}
	
	@Override
	public String toString() {
		return String.format("config { suffix: %s, host: %s}", suffix, host);
	}
}
