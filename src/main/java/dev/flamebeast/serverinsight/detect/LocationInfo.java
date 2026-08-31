package dev.flamebeast.serverinsight.detect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Where a server is hosted, as reported by the geolocation lookup.
 *
 * Every field is best-effort: the lookup answers for the IP the hostname resolves to,
 * which for anything behind a proxy, CDN or anycast network is the edge, not the
 * machine running the game. The output has to present this as "where the address
 * points", not "where the server is".
 *
 * @param countryCode lowercase ISO 3166-1 alpha-2, which is also the flag texture name
 */
public record LocationInfo(
	String countryCode,
	String countryName,
	String regionName,
	String cityName,
	String isp,
	String org,
	String asName,
	String timezone,
	String queriedIp,
	Double latitude,
	Double longitude
) {
	/**
	 * Parses one geolocation response. Pure and side-effect free so it can be tested
	 * without touching the network.
	 *
	 * @return the parsed location, or null if the response was malformed or reported a
	 *         failed lookup (a private address, a reserved range, an unknown host)
	 */
	public static LocationInfo fromJson(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}

		try {
			if (!(JsonParser.parseString(json) instanceof JsonObject object)) {
				return null;
			}

			// The API answers 200 with {"status":"fail"} for private ranges and bad
			// hosts, so the body is the only place a failure shows up.
			if (!object.has("status") || !"success".equals(string(object, "status"))) {
				return null;
			}

			String countryCode = string(object, "countryCode");
			if (countryCode == null || countryCode.length() != 2) {
				return null;
			}

			return new LocationInfo(
				countryCode.toLowerCase(Locale.ROOT),
				string(object, "country"),
				string(object, "regionName"),
				string(object, "city"),
				string(object, "isp"),
				string(object, "org"),
				string(object, "as"),
				string(object, "timezone"),
				string(object, "query"),
				number(object, "lat"),
				number(object, "lon")
			);
		} catch (Exception malformed) {
			return null;
		}
	}

	private static String string(JsonObject object, String key) {
		if (!object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}

		String value = object.get(key).getAsString();
		return value.isBlank() ? null : value;
	}

	private static Double number(JsonObject object, String key) {
		if (!object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}

		try {
			return object.get(key).getAsDouble();
		} catch (NumberFormatException notNumeric) {
			return null;
		}
	}

	/**
	 * Great-circle distance from this location to another, in kilometres. Null when either
	 * location is missing its coordinates — the API normally returns them, but a reply
	 * without them must not crash the tooltip.
	 */
	public Double distanceKm(LocationInfo other) {
		if (other == null || latitude == null || longitude == null
			|| other.latitude == null || other.longitude == null) {
			return null;
		}

		double lat1 = Math.toRadians(latitude);
		double lon1 = Math.toRadians(longitude);
		double lat2 = Math.toRadians(other.latitude);
		double lon2 = Math.toRadians(other.longitude);

		// Haversine over a spherical Earth. Plenty for "how far is the server" — the
		// geolocation data is itself a rough estimate.
		double dLat = lat2 - lat1;
		double dLon = lon2 - lon1;
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return 6371.0 * c; // mean Earth radius, km
	}

	/** "Frankfurt, Hesse, Germany", skipping whatever the lookup did not return. */
	public String describePlace() {
		StringBuilder out = new StringBuilder();

		for (String part : new String[]{cityName, regionName, countryName}) {
			if (part == null) {
				continue;
			}

			if (!out.isEmpty()) {
				out.append(", ");
			}

			out.append(part);
		}

		return out.isEmpty() ? countryCode.toUpperCase(Locale.ROOT) : out.toString();
	}
}
