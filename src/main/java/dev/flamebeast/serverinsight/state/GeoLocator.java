package dev.flamebeast.serverinsight.state;

import dev.flamebeast.serverinsight.ServerInsightClient;
import dev.flamebeast.serverinsight.detect.LocationInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up where a server address is hosted, asynchronously and at most once per host.
 *
 * This is the only part of the mod that talks to anything other than the Minecraft
 * server, and it is worth being deliberate about: each lookup tells a third party that
 * somebody is interested in that address. So it only ever runs for addresses the user
 * is actually looking at, results are cached for the whole session, and failures are
 * cached too so a bad host is never retried in a render loop.
 *
 * The free geolocation endpoint allows 45 requests per minute per client and answers
 * 429 past that, so requests are throttled below the limit and a 429 parks everything
 * until the cooldown expires. The server list can hold far more entries than the quota.
 */
public final class GeoLocator {
	private GeoLocator() {
	}

	/** Plain HTTP: the free tier of this endpoint does not serve HTTPS. */
	private static final String ENDPOINT = "http://ip-api.com/json/";

	private static final String FIELDS =
		"?fields=status,message,country,countryCode,regionName,city,isp,org,as,timezone,query,lat,lon";

	private static final int MAX_REQUESTS_PER_WINDOW = 40;
	private static final long WINDOW_MILLIS = 60_000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final Map<String, LocationInfo> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	/** The client's own location, for the distance shown in the flag tooltip. */
	private static volatile LocationInfo local;
	private static volatile boolean localAttempted;

	/** Timestamps of recent requests, for the sliding-window throttle. */
	private static final Deque<Long> RECENT = new ArrayDeque<>();

	private static volatile long cooldownUntilMillis = 0L;

	/**
	 * Cached location for a host, kicking off a lookup if this is the first time it has
	 * been asked for.
	 *
	 * Safe to call every frame — that is the point. Returns null while the lookup is in
	 * flight, and permanently for a host that failed or was never eligible.
	 */
	public static LocationInfo lookup(String host) {
		if (host == null || host.isBlank()) {
			return null;
		}

		String key = host.toLowerCase(Locale.ROOT);

		LocationInfo cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		if (FAILED.contains(key) || !isPublicAddress(key)) {
			return null;
		}

		if (IN_FLIGHT.contains(key) || !claimRequestSlot()) {
			return null;
		}

		if (!IN_FLIGHT.add(key)) {
			return null;
		}

		CompletableFuture
			.supplyAsync(() -> fetch(key))
			.whenComplete((info, error) -> {
				if (info != null) {
					CACHE.put(key, info);
				} else {
					// Remember the miss. Without this a host the API cannot place would
					// be re-requested on every frame the server list is open.
					FAILED.add(key);
				}

				IN_FLIGHT.remove(key);
			});

		return null;
	}

	/**
	 * The client's own location, cached for the session.
	 *
	 * The distance feature needs somewhere to measure from, and the only way to get the
	 * client's location is to ask the same endpoint "where is me" (ip-api answers a
	 * request with no host with the caller's own location). That is one extra lookup
	 * beyond the servers the user is looking at, and it necessarily reports the user's
	 * own IP to the endpoint — worth knowing, since this is otherwise a purely passive
	 * mod. It is fired at most once per session: a failure is remembered so an offline
	 * or rate-limited start does not re-request on every frame the server list is open.
	 *
	 * Safe to call every frame, like {@link #lookup(String)}.
	 */
	public static LocationInfo localLocation() {
		if (local != null) {
			return local;
		}

		if (localAttempted || !claimRequestSlot()) {
			return null;
		}

		localAttempted = true;
		CompletableFuture
			.supplyAsync(() -> fetch(""))
			.whenComplete((info, error) -> {
				if (info != null) {
					local = info;
				}
			});

		return null;
	}

	private static LocationInfo fetch(String host) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT + host + FIELDS))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", "ServerInsight")
				.GET()
				.build();

			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 429) {
				// Past quota. Park every lookup rather than hammering a limiter that is
				// already saying no.
				long retryAfter = response.headers().firstValue("X-Ttl")
					.map(value -> parseSeconds(value) * 1000L)
					.orElse(WINDOW_MILLIS);
				cooldownUntilMillis = System.currentTimeMillis() + retryAfter;
				ServerInsightClient.LOGGER.warn("Geolocation rate limited, pausing lookups for {} ms", retryAfter);
				return null;
			}

			if (response.statusCode() != 200) {
				return null;
			}

			return LocationInfo.fromJson(response.body());
		} catch (Exception failed) {
			// Offline, DNS failure, timeout, blocked by a firewall. All the same here:
			// the flag simply does not appear.
			return null;
		}
	}

	private static long parseSeconds(String value) {
		try {
			return Math.max(1L, Long.parseLong(value.trim()));
		} catch (NumberFormatException e) {
			return WINDOW_MILLIS / 1000L;
		}
	}

	/** False when the throttle or an active cooldown says this request must not go out. */
	private static synchronized boolean claimRequestSlot() {
		long now = System.currentTimeMillis();

		if (now < cooldownUntilMillis) {
			return false;
		}

		while (!RECENT.isEmpty() && now - RECENT.peekFirst() > WINDOW_MILLIS) {
			RECENT.removeFirst();
		}

		if (RECENT.size() >= MAX_REQUESTS_PER_WINDOW) {
			return false;
		}

		RECENT.addLast(now);
		return true;
	}

	/**
	 * Skips anything the lookup could never place. Sending a LAN address to a public
	 * geolocation service leaks the shape of someone's home network for a guaranteed
	 * "private range" answer.
	 */
	public static boolean isPublicAddress(String host) {
		if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".localhost")) {
			return false;
		}

		if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.")
			|| host.startsWith("169.254.") || host.startsWith("0.")) {
			return false;
		}

		// 172.16.0.0/12
		if (host.startsWith("172.")) {
			int dot = host.indexOf('.', 4);
			if (dot > 4) {
				try {
					int second = Integer.parseInt(host.substring(4, dot));
					if (second >= 16 && second <= 31) {
						return false;
					}
				} catch (NumberFormatException notNumeric) {
					// A hostname that merely starts with "172." — treat as public.
				}
			}
		}

		return !host.equals("::1") && !host.startsWith("fe80:") && !host.startsWith("fc") && !host.startsWith("fd");
	}
}
