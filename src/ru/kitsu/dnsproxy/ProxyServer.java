package ru.kitsu.dnsproxy;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import ru.kitsu.dnsproxy.parser.DNSMessage;
import ru.kitsu.dnsproxy.parser.DNSParseException;

/**
 * Proxy server that forwards requests to upstreams
 * 
 * @author Alexey Borzenkov
 * 
 */
public class ProxyServer {
	// For debugging, print requests and responses
	private static final boolean DEBUG = true;
	// Maximum message should be 512 bytes
	// We accept up to 16384 bytes just in case
	private static final int MAX_PACKET_SIZE = 16384;
	// Maximum expected number of outgoing packets buildup
	private static final int MAX_PACKETS = 8192;
	// Maximum expected number of processing ops buildup
	private static final int MAX_PROCESSING = 16384;
	// Maximum expected number of logged requests buildup
	private static final int MAX_LOGGED = 8192;
	// Date format in a log filename
	private static final SimpleDateFormat logNameDateFormat = new SimpleDateFormat(
			"yyyy-MM-dd-HH-mm");

	private final BlockingQueue<Callable<Void>> incoming = new ArrayBlockingQueue<>(
			MAX_PROCESSING);
	private final BlockingQueue<ProxyResponse> outgoing = new ArrayBlockingQueue<>(
			MAX_PACKETS);
	private final PriorityQueue<ProxyRequest> inflight = new PriorityQueue<>(
			11, new ProxyRequest.DeadlineComparator());
	private final BlockingQueue<ProxyRequest> logged = new ArrayBlockingQueue<>(
			MAX_LOGGED);

	private final InetSocketAddress addr;
	private final DatagramChannel socket;
	private final Thread processingThread;
	private final Thread receiveThread;
	private final Thread sendThread;
	private final Thread logThread;
	private final Thread statsThread;
	private final List<UpstreamServer> upstreams = new ArrayList<>();
	private final Class<UpstreamServerFilterComparator> upstreamComparatorClass;

	private class ProcessingWorker implements Runnable {
		@Override
		public void run() {
			try {
				while (!Thread.interrupted()) {
					final Callable<Void> op;
					final ProxyRequest request = inflight.peek();
					if (request != null) {
						long delay = request.getDeadline() - System.nanoTime();
						log("ProcessingWorker found a request with delay " + delay);
						// Timeout as many requests as we can
						if (delay <= 0) {
							inflight.remove();
							if (request.setFinished()) {
								// Make sure it's cancelled
								for (UpstreamServer userv : upstreams) {
									userv.cancelRequest(request);
								}
								// Send to logging
								logged.put(request);
							}
							continue;
						}
						// Reduce sensitivity to ~1ms
						delay = ((delay + 999999) / 1000000) * 1000000;
						// Don't wait longer than delay
						op = incoming.poll(delay, TimeUnit.NANOSECONDS);
						if (op == null)
							continue;
					} else {
						op = incoming.take();
					}
					op.call();
				}
			} catch (InterruptedException e) {
				// interrupted
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
	}

	private class ReceiveWorker implements Runnable {
		@Override
		public void run() {
			final ByteBuffer buffer = ByteBuffer
					.allocateDirect(MAX_PACKET_SIZE);
			try {
				log("Accepting requests on " + addr);
				while (!Thread.interrupted()) {
					buffer.clear();
					final SocketAddress client;
					try {
						client = socket.receive(buffer);
					} catch (ClosedChannelException e) {
						log("Channel closed by " + e);
						stop();
						break;
					} catch (IOException e) {
						e.printStackTrace();
						continue;
					}
					if (client == null)
						continue; // shouldn't happen, but just in case
					buffer.flip();
					final DNSMessage message;
					try {
						message = DNSMessage.parse(buffer, false);
					} catch (BufferUnderflowException e) {
						continue;
					} catch (DNSParseException e) {
						continue;
					}
					if (message.isResponse())
						continue; // only requests are accepted
					buffer.rewind();
					final byte[] packet = new byte[buffer.limit()];
					buffer.get(packet);
					final ProxyRequest request = new ProxyRequest(client,
							packet, message);
					schedule(new Callable<Void>() {
						@Override
						public Void call() throws InterruptedException {
							if (DEBUG) {
								System.out
										.format("Request from %s: %s\n",
												request.getAddr(),
												request.getMessage());
							}
							inflight.add(request);
							
							try {
								upstreamComparatorClass.newInstance()
									.filter(upstreams, request)
									.forEach( upstreamServer -> {
										try {
											upstreamServer.startRequest(request);
										} catch (InterruptedException e) {
											e.printStackTrace();
											// interrupted
										}}
								);

							} catch (InstantiationException | IllegalAccessException e) {
								throw new RuntimeException(e);
							}
							return null;
						}
					});
				}
			} catch (InterruptedException e) {
				// interrupted
			}
		}
	}

	private class SendWorker implements Runnable {
		@Override
		public void run() {
			final ByteBuffer buffer = ByteBuffer
					.allocateDirect(MAX_PACKET_SIZE);
			try {
				while (!Thread.interrupted()) {
					final ProxyResponse response = outgoing.take();
					final byte[] packet = response.getResponsePacket();
					if (packet.length < 12 || packet.length > MAX_PACKET_SIZE)
						continue;
					buffer.clear();
					buffer.putShort(response.getRequestId());
					buffer.put(packet, 2, packet.length - 2);
					buffer.flip();
					try {
						socket.send(buffer, response.getAddr());
					} catch (ClosedChannelException e) {
						log("Channel closed by " + e);
						stop();
						break;
					} catch (IOException e) {
						e.printStackTrace();
						continue;
					}
				}
			} catch (InterruptedException e) {
				// interrupted
			}
		}
	}

	private class LogWorker implements Runnable {
		@Override
		public void run() {
			try {
				long lastzone = -1;
				PrintStream output = null;
				StringBuilder sb = new StringBuilder();
				while (!Thread.interrupted()) {
					final ProxyRequest request = logged.take();
					// Current nanotime for latency of timed out requests
					final long nanotime = System.nanoTime();
					// Current system timestamp in seconds
					final long timestamp = System.currentTimeMillis() / 1000;
					// Current file zone, changes hourly
					final long zone = timestamp - (timestamp % 3600);
					if (lastzone != zone) {
						if (output != null) {
							output.close();
							output = null;
						}
						try {
							output = new PrintStream(
									new FileOutputStream("resolve-"
											+ zone
											+ "-"
											+ logNameDateFormat
													.format(new Date(
															zone * 1000))
											+ ".log", true));
						} catch (FileNotFoundException e) {
							e.printStackTrace();
							Thread.sleep(1000);
							continue;
						}
						sb.setLength(0);
						sb.append("[time]");
						for (UpstreamServer upstream : upstreams) {
							sb.append("\t");
							sb.append(upstream.getAddr().getAddress()
									.getHostAddress());
							sb.append(":");
							sb.append(upstream.getAddr().getPort());
							sb.append("\t");
							sb.append("(latency)");
						}
						output.println(sb.toString());
						lastzone = zone;
					}
					// All upstream responses
					final List<UpstreamResponse> responses = request
							.getResponses();
					// Start constructing a log line
					sb.setLength(0);
					sb.append(timestamp);
					for (int i = 0; i < upstreams.size(); ++i) {
						final UpstreamServer upstream = upstreams.get(i);
						sb.append("\t");
						UpstreamResponse response = null;
						for (int j = 0; j < responses.size(); ++j) {
							final UpstreamResponse candidate = responses.get(j);
							if (upstream.getAddr().equals(candidate.getAddr())) {
								response = candidate;
								break;
							}
						}
						if (response != null) {
							long delay = (response.getTimestamp() - request
									.getTimestamp()) / 1000000;
							sb.append(response.getMessage().getRcode());
							sb.append("\t");
							sb.append(delay);
							sb.append("ms");
						} else {
							long delay = (nanotime - request.getTimestamp()) / 1000000;
							sb.append("-\t");
							sb.append(delay);
							sb.append("ms");
						}
					}
					output.println(sb.toString());
				}
			} catch (InterruptedException e) {
				// interrupted
			}
		}
	}

	private class StatsWorker implements Runnable {
		@Override
		public void run() {
			try {
				StringBuilder sb = new StringBuilder();
				while (!Thread.interrupted()) {
					Thread.sleep(10000);
					long t0 = System.nanoTime();
					long freeMemory = Runtime.getRuntime().freeMemory();
					long totalMemory = Runtime.getRuntime().totalMemory();
					sb.setLength(0);
					sb.append("Used memory: ");
					sb.append((totalMemory - freeMemory) / 1048576);
					sb.append("/");
					sb.append(totalMemory / 1048576);
					sb.append("MB, ");
					sb.append("Upstreams inflight: ");
					int n;
					int index = 0;
					for (UpstreamServer upstream : upstreams) {
						if (index != 0) {
							sb.append(", ");
						}
						sb.append(upstream.getAddr().getAddress()
								.getHostAddress());
						sb.append(":");
						sb.append(upstream.getAddr().getPort());
						sb.append(": ");
						sb.append(upstream.getInflightCount());
						if ((n = upstream.getParseErrors()) != 0) {
							sb.append("/");
							sb.append(n);
							sb.append("perr");
						}
						if ((n = upstream.getAddrErrors()) != 0) {
							sb.append("/");
							sb.append(n);
							sb.append("aerr");
						}
						++index;
					}
					long t1 = System.nanoTime();
					sb.append(", Check: ");
					sb.append(t1 - t0);
					sb.append("ns");
					log(sb.toString());
				}
			} catch (InterruptedException e) {
				// interrupted
			}
		}
	}

	// package-private
	// schedules op to run on processing thread
	void schedule(Callable<Void> op) throws InterruptedException {
		incoming.put(op);
	}

	private static void log(String line) {
		System.out.format("[%s] %s\n", new Date(), line);
	}

	@SuppressWarnings("unchecked")
	private ProxyServer(String upstreamServerFilterClassName, String host, int port) throws IOException {
		try {
			Class<?> upstreamServerFilterClass = Class.forName(upstreamServerFilterClassName);
			if (!UpstreamServerFilterComparator.class.isAssignableFrom( upstreamServerFilterClass )) {
				throw new IllegalArgumentException("Filter class '" + upstreamServerFilterClassName + "' has to implement interface " + UpstreamServerFilterComparator.class.getName() );
			}
			upstreamComparatorClass = (Class<UpstreamServerFilterComparator>) upstreamServerFilterClass;
		} catch (ClassNotFoundException e) {
			throw new IOException("Cannot instantiate class '" + upstreamServerFilterClassName + "'", e);
		}
		
		if (null==host || host.trim().isEmpty()) {
			addr = new InetSocketAddress(port);
		} else {
			addr = new InetSocketAddress(host, port);
		}
		if (addr.isUnresolved()) {
			throw new IOException("Cannot resolve '" + host + "'");
		}
		socket = DatagramChannel.open(StandardProtocolFamily.INET);
		socket.bind(addr);
		final String prefix = "Proxy " + addr;
		processingThread = new Thread(new ProcessingWorker(), prefix
				+ " processing");
		receiveThread = new Thread(new ReceiveWorker(), prefix + " receive");
		sendThread = new Thread(new SendWorker(), prefix + " send");
		logThread = new Thread(new LogWorker(), prefix + " logging");
		statsThread = new Thread(new StatsWorker(), prefix + " stats");
	}

	public void addUpstream(UpstreamConfig config) throws IOException {
		UpstreamServer upstream = new UpstreamServer(this, config);
		for (UpstreamServer currentUpstream : upstreams) {
			if (upstream.getAddr().equals(currentUpstream.getAddr()))
				throw new IOException(
						"Cannot add upstream with duplicate address "
								+ upstream.getAddr());
		}
		upstreams.add(upstream);
	}

	public void start() {
		for (UpstreamServer upstream : upstreams) {
			upstream.start();
		}
		processingThread.start();
		receiveThread.start();
		sendThread.start();
		logThread.start();
//		statsThread.start();
	}

	public void stop() {
		processingThread.interrupt();
		receiveThread.interrupt();
		sendThread.interrupt();
		logThread.interrupt();
//		statsThread.interrupt();
		for (UpstreamServer upstream : upstreams) {
			upstream.stop();
		}
	}

	// MUST be called on processing thread
	public void onUpstreamResponse(ProxyRequest request,
			UpstreamResponse response) throws InterruptedException {
		if (DEBUG) {
			System.out.format("Response from %s: %s\n", response.getAddr(),
					response.getMessage());
		}
		if (request.isFinished())
			return; // ignore late responses
		int index = request.addResponse(response);
		if (index == 0) {
			// First response is sent to the client
			outgoing.put(new ProxyResponse(request, response));
		}
		if (index == upstreams.size() - 1) {
			// Received last response, finish request
			if (request.setFinished()) {
				// Don't need timeout anymore
				inflight.remove(request);
				// Send to logging
				logged.put(request);
			}
		}
	}

	private static void usage() {
		System.out
				.println("Usage: ProxyServer [-host host] [-port port] -config config");
		System.exit(1);
	}

	public static void main(String[] args) throws IOException  {
		String host = "127.0.0.1";
		String upstreamFilterClassname = "ru.kitsu.dnsproxy.UpstreamServerFilterComparatorImpl";
		int port = 53;
		final List<UpstreamConfig> upstreams = new ArrayList<>();
		for (int i = 0; i < args.length; ++i) {
			switch (args[i]) {
				case "-host":
					if (++i >= args.length)
						usage();
					host = args[i];
					break;
				case "-port":
					if (++i >= args.length)
						usage();
					port = Integer.parseInt(args[i]);
					break;
				case "-filter":
					if (++i >= args.length)
						usage();
					upstreamFilterClassname = args[i];
					break;
				case "-config":
					if (++i >= args.length)
						usage();
					upstreams.addAll( createUpstreamsFromConfig(args[i]) );
					break;
				default:
					usage();
			}

			if (upstreams.isEmpty()) {
				upstreams.add(UpstreamConfig.createConfig("", "8.8.8.8"));
				upstreams.add(UpstreamConfig.createConfig("", "8.8.4.4"));
			}
			ProxyServer server = new ProxyServer(upstreamFilterClassname, host, port);
			for (UpstreamConfig config : upstreams) {
				server.addUpstream(config);
			}
			server.start();
		}
	}

	private static List<UpstreamConfig> createUpstreamsFromConfig(final String filename) throws IOException {
		List<UpstreamConfig> upstreamConfigs = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			new FileInputStream(filename)))) {
			String line;
			while (null != (line = r.readLine())) {
				int index = line.indexOf('#');
				if (index != -1) {
					line = line.substring(0, index);
				}
				line = line.trim();
				if (line.length() == 0) {
					continue;
				}
				String[] split = line.split("\\|");
				int splitIndex = 0;
				String suffix = split.length > 1 ? split[splitIndex++] : "";
				String upstreamHost = split[splitIndex];
				upstreamConfigs.add(UpstreamConfig.createConfig(suffix, upstreamHost));
			}
		}
		return upstreamConfigs;
	}

}
