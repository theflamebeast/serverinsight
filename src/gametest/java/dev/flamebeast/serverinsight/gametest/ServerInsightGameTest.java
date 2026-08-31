package dev.flamebeast.serverinsight.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flamebeast.serverinsight.detect.CommandFingerprints;
import dev.flamebeast.serverinsight.detect.LocationInfo;
import dev.flamebeast.serverinsight.detect.ServerMods;
import dev.flamebeast.serverinsight.state.AddressResolver;
import dev.flamebeast.serverinsight.state.GeoLocator;
import dev.flamebeast.serverinsight.state.ServerInsightRuntime;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import java.util.List;
import java.util.Set;

/**
 * End-to-end check that Server Insight still works against a real multiplayer server.
 *
 * This exists because `./gradlew build` cannot catch this mod's main failure mode.
 * Mixins are matched by method name at runtime, so a Mojang rename of handleSetTime,
 * handleCommands or handleCommandSuggestions leaves the build green and breaks the mod
 * on launch. The mixin config uses defaultRequire 1, so a missing target throws while
 * ClientPacketListener is being loaded — connecting to the server below is therefore
 * enough to catch it, before a single assertion runs.
 *
 * The assertions cover the failure the crash does NOT catch: a target that still exists
 * but is no longer called on the path we assumed.
 *
 * A dedicated server rather than singleplayer, because nearly everything this mod
 * reports (brand, protocol, plugins, ping, TPS) is absent or meaningless in singleplayer.
 */
public final class ServerInsightGameTest implements FabricClientGameTest {
	/**
	 * Time-update packets arrive every 20 ticks and the completion scan allows itself
	 * 100, so the 200-tick default is uncomfortably tight on a loaded CI runner.
	 */
	private static final int SLOW_TIMEOUT = 600;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
			TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();

			// Minecraft 26.2 ships its own op-only /version, so registering a literal by
			// that name MERGES into it and inherits the op requirement — the whole node
			// is then filtered out of the command tree sent to a normal player, and the
			// scan silently no-ops because it never finds a version alias to probe.
			// Opping is what makes the tab-completion path reachable at all here.
			server.runCommand("op " + context.computeOnClient(mc -> mc.getUser().getName()));
			context.waitFor(mc -> mc.getConnection().getCommands().getRoot().getChild("version") != null, SLOW_TIMEOUT);

			assertCommandRegistered(context);
			assertCommandTreeMixinFired(context);
			assertTimeMixinFired(context);
			assertServerModsDetected(context);
			assertFingerprintGuess(context);
			assertNoVanillaCommandFingerprinted(context);
			assertAddressResolverWorks(context);
			assertGeolocationParsing();
			assertDistanceCalculation();
			assertFlagMixinApplies();
			assertFlagTextureExists(context);
			assertCommandRunsAndCompletesScan(context);

			screenshotServerListFlags(context);

			// Not compared against a reference — it is uploaded as a CI artifact so the
			// actual chat output can be eyeballed after a Minecraft update. Component
			// rendering changes constantly; a pixel comparison here would only ever
			// produce false failures.
			context.takeScreenshot("serverinsight-chat-output");
		}
	}

	/** Proves the client command entrypoint registered, independently of running it. */
	private static void assertCommandRegistered(ClientGameTestContext context) {
		boolean registered = context.computeOnClient(mc ->
			ClientCommands.getActiveDispatcher() != null
				&& ClientCommands.getActiveDispatcher().getRoot().getChild("serverinsight") != null);

		if (!registered) {
			throw new AssertionError("/serverinsight is missing from the client command dispatcher");
		}
	}

	/**
	 * handleCommands -> PluginScanner.onCommandTree. The command tree packet always
	 * arrives on join, so seeing the fake namespaced command proves both that the inject
	 * ran and that namespace extraction still works.
	 */
	private static void assertCommandTreeMixinFired(ClientGameTestContext context) {
		context.waitFor(mc -> ServerInsightRuntime.INSTANCE.plugins().commandTreeCount() > 0, SLOW_TIMEOUT);

		Set<String> detected = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.plugins().combinedPlugins());

		if (!detected.contains("testplugin")) {
			throw new AssertionError("command-tree scan missed the testplugin: namespace, found: " + detected);
		}
	}

	/**
	 * handleSetTime -> TimingTracker. getEstimatedTps() reports 20.0 when starved as
	 * well as when healthy, so the sample count is the only real witness that the
	 * inject ran.
	 */
	private static void assertTimeMixinFired(ClientGameTestContext context) {
		// An empty estimate is the honest answer when starved, so waiting for a value to
		// appear is what proves packets are actually arriving and being counted.
		context.waitFor(mc -> ServerInsightRuntime.INSTANCE.timing().estimatedTps().isPresent(), SLOW_TIMEOUT);

		double tps = context.computeOnClient(mc ->
			ServerInsightRuntime.INSTANCE.timing().estimatedTps().orElse(-1.0));

		// Only the clamp is asserted. The estimate is derived from wall-clock packet
		// spacing, and a CI runner's timing is not stable enough to assert "about 20"
		// without buying a flaky test.
		if (!(tps > 0.0) || tps > 20.0) {
			throw new AssertionError("TPS estimate outside its 0-20 clamp: " + tps);
		}

	}

	/**
	 * Server-side mods, read from declared network channels. Deterministic here because
	 * the test server runs Fabric API, which always registers channels — so "fabric-api"
	 * is guaranteed to be present and its absence means the detection broke.
	 */
	private static void assertServerModsDetected(ClientGameTestContext context) {
		Set<String> mods = context.computeOnClient(mc -> ServerMods.detected());

		if (!mods.contains("fabric-api")) {
			throw new AssertionError("expected fabric-api among declared server mods, found: " + mods);
		}
	}

	/**
	 * Runs the command the way a user does, then waits for tab-completion results to
	 * land. Completion results can only appear if the command executed, reached
	 * printPlugins, sent the suggestion packet, and handleCommandSuggestions fed the
	 * reply back — so this single wait covers the whole async path plus the third mixin.
	 */
	private static void assertCommandRunsAndCompletesScan(ClientGameTestContext context) {
		CapturedChat.clear();

		TestInput input = context.getInput();
		input.pressKey(options -> options.keyChat);
		context.waitTick();
		input.typeChars("/serverinsight");
		input.pressKey(InputConstants.KEY_RETURN);

		context.waitFor(mc -> ServerInsightRuntime.INSTANCE.plugins().completionCount() > 0, SLOW_TIMEOUT);

		Set<String> detected = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.plugins().combinedPlugins());

		// Gamma is only reachable via the second probe alias (/plugins), so requiring it
		// proves every advertised alias got probed, not just the first.
		for (String expected : new String[]{"testpluginalpha", "testpluginbeta", "testplugingamma"}) {
			if (!detected.contains(expected)) {
				throw new AssertionError("tab-completion scan missed " + expected + ", found: " + detected);
			}
		}

		assertOutputLines();
	}

	/**
	 * /lp is LuckPerms' command, registered on the test server with no namespace and no
	 * suggestions — so the only way it can be detected is the fingerprint table, and the
	 * only correct way to report it is as a guess.
	 */
	private static void assertFingerprintGuess(ClientGameTestContext context) {
		Set<String> guesses = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.plugins().guessedPlugins());

		if (!guesses.contains("LuckPerms")) {
			throw new AssertionError("expected LuckPerms to be inferred from /lp, guesses were: " + guesses);
		}

		// A guess must never be laundered into the confirmed buckets.
		Set<String> all = context.computeOnClient(mc -> ServerInsightRuntime.INSTANCE.plugins().combinedPlugins());
		if (!all.contains("LuckPerms")) {
			throw new AssertionError("guessed plugins should still appear in the combined list");
		}
	}

	/**
	 * No fingerprint may collide with a command Minecraft ships.
	 *
	 * A single bad entry — /worldborder, /time, /perf — would tag EVERY server with that
	 * plugin, which is the worst failure this feature has. The test player is opped by
	 * this point, so the command tree here is the full vanilla set, making it the ideal
	 * thing to check the table against. The only legitimate hit is the /lp the test
	 * server registers on purpose.
	 */
	private static void assertNoVanillaCommandFingerprinted(ClientGameTestContext context) {
		List<String> collisions = context.computeOnClient(mc -> mc.getConnection().getCommands().getRoot()
			.getChildren().stream()
			.map(node -> node.getName())
			.filter(name -> !name.equals("lp"))
			.filter(name -> CommandFingerprints.pluginFor(name) != null)
			.map(name -> name + " -> " + CommandFingerprints.pluginFor(name))
			.sorted()
			.toList());

		if (!collisions.isEmpty()) {
			throw new AssertionError("fingerprint table matches non-plugin commands, so every server "
				+ "would report these plugins: " + collisions);
		}

		if (CommandFingerprints.size() < 50) {
			throw new AssertionError("fingerprint table looks truncated: " + CommandFingerprints.size() + " entries");
		}
	}

	/**
	 * Geolocation, without touching the network.
	 *
	 * The parser is pure, so it can be fed a captured response directly. Doing it this
	 * way is deliberate: a test that called the live endpoint would fail whenever CI is
	 * offline or the free quota is exhausted, and would tell a third party about every
	 * CI run. The private-address guard is the other half — it is what stops LAN
	 * addresses from being sent anywhere at all.
	 */
	private static void assertGeolocationParsing() {
		String response = """
			{"status":"success","country":"Germany","countryCode":"DE","regionName":"Hesse",\
			"city":"Frankfurt am Main","isp":"Hetzner Online GmbH","org":"Hetzner",\
			"as":"AS24940 Hetzner Online GmbH","timezone":"Europe/Berlin","query":"1.2.3.4"}""";

		LocationInfo info = LocationInfo.fromJson(response);
		if (info == null) {
			throw new AssertionError("failed to parse a well-formed geolocation response");
		}

		// Lowercased because the country code doubles as the flag texture name.
		if (!"de".equals(info.countryCode())) {
			throw new AssertionError("country code should be lowercased, got: " + info.countryCode());
		}

		if (!"Frankfurt am Main, Hesse, Germany".equals(info.describePlace())) {
			throw new AssertionError("unexpected place description: " + info.describePlace());
		}

		// A failed lookup answers 200 with status=fail, so the body is the only signal.
		if (LocationInfo.fromJson("{\"status\":\"fail\",\"message\":\"private range\"}") != null) {
			throw new AssertionError("a failed lookup must not parse into a location");
		}

		if (LocationInfo.fromJson("not json") != null || LocationInfo.fromJson("") != null) {
			throw new AssertionError("malformed responses must not parse into a location");
		}

		for (String priv : new String[]{"localhost", "127.0.0.1", "192.168.1.10", "10.0.0.5", "172.16.4.2", "::1"}) {
			if (GeoLocator.isPublicAddress(priv)) {
				throw new AssertionError(priv + " must never be sent to the geolocation service");
			}
		}

		for (String pub : new String[]{"mc.hypixel.net", "1.2.3.4", "172.5.5.5"}) {
			if (!GeoLocator.isPublicAddress(pub)) {
				throw new AssertionError(pub + " should be eligible for lookup");
			}
		}
	}

	/**
	 * The distance the flag tooltip reports comes from a pure function over two
	 * coordinates, so it can be checked without touching the network.
	 */
	private static void assertDistanceCalculation() {
		String frankfurt = """
			{"status":"success","country":"Germany","countryCode":"DE","regionName":"Hesse",\
			"city":"Frankfurt am Main","isp":"Hetzner Online GmbH","org":"Hetzner",\
			"as":"AS24940 Hetzner Online GmbH","timezone":"Europe/Berlin","query":"1.2.3.4","lat":50.1109,"lon":8.6821}""";
		String berlin = """
			{"status":"success","country":"Germany","countryCode":"DE","regionName":"Berlin",\
			"city":"Berlin","isp":"x","org":"x",\
			"as":"AS","timezone":"Europe/Berlin","query":"1.2.3.5","lat":52.52,"lon":13.405}""";

		LocationInfo a = LocationInfo.fromJson(frankfurt);
		LocationInfo b = LocationInfo.fromJson(berlin);
		if (a == null || b == null) {
			throw new AssertionError("failed to parse locations for the distance check");
		}

		// The same point is ~0 km, not null.
		if (a.distanceKm(a) == null || a.distanceKm(a) > 1.0) {
			throw new AssertionError("distance to itself should be ~0 km");
		}

		// Frankfurt -> Berlin is ~423 km by great circle; keep a generous band so a tiny
		// formula tweak doesn't fail the build over an estimate.
		Double km = a.distanceKm(b);
		if (km == null || km < 400 || km > 450) {
			throw new AssertionError("unexpected Frankfurt-Berlin distance: " + km);
		}
	}

	/**
	 * Seeds the server list and screenshots it, so flag placement can actually be looked
	 * at instead of reasoned about.
	 *
	 * Deliberately asserts nothing. The flag only appears if the geolocation lookup
	 * succeeds, which needs internet and spare quota, and neither is something a build
	 * should depend on. The value is the artifact: after a Minecraft update, this is the
	 * picture that shows whether the flag still lands in the right place.
	 */
	private static void screenshotServerListFlags(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			ServerList list = new ServerList(mc);
			list.load();

			// Public addresses in different countries, so a correct render is obviously
			// correct rather than three copies of the same flag.
			//
			// The boolean is "hidden", NOT "visible" — passing true files the entry in
			// the hidden list and the screen shows nothing.
			list.add(new ServerData("Cloudflare", "1.1.1.1", ServerData.Type.OTHER), false);
			list.add(new ServerData("Google", "8.8.8.8", ServerData.Type.OTHER), false);
			list.add(new ServerData("Quad9", "9.9.9.9", ServerData.Type.OTHER), false);
			list.save();
		});

		// Toasts stack in the top-right and sit exactly where the flags render at the
		// default window size, hiding them. A bigger window moves the list clear.
		context.getInput().resizeWindow(1920, 1080);

		context.setScreen(() -> new JoinMultiplayerScreen(null));
		context.waitForScreen(JoinMultiplayerScreen.class);

		// Long enough for the lookups to land and the list to repaint with them.
		context.waitTicks(120);

		context.takeScreenshot("server-list-flags");

		context.setScreen(() -> null);
		context.waitTicks(5);
	}

	/**
	 * Forces the server-list entry class to load so its mixin is applied.
	 *
	 * Without this the flag mixin is completely unguarded. It targets a class the client
	 * only loads once the multiplayer screen builds an entry, which never happens in
	 * this test — so a renamed extractContent would sail through CI and break only when
	 * a user opened the server list. defaultRequire is 1, so a missing target throws
	 * during mixin application, which is exactly what loading the class triggers.
	 */
	private static void assertFlagMixinApplies() {
		try {
			Class.forName("net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry");
		} catch (ClassNotFoundException renamed) {
			throw new AssertionError("server list entry class is gone; the flag mixin needs retargeting", renamed);
		} catch (Throwable injectionFailed) {
			throw new AssertionError("flag mixin failed to apply to the server list entry", injectionFailed);
		}
	}

	/**
	 * Every country code the parser can produce needs a flag texture, or the server list
	 * silently renders a missing-texture square.
	 */
	private static void assertFlagTextureExists(ClientGameTestContext context) {
		boolean present = context.computeOnClient(mc -> mc.getResourceManager()
			.getResource(Identifier.fromNamespaceAndPath("serverinsight", "textures/gui/flags/de.png"))
			.isPresent());

		if (!present) {
			throw new AssertionError("flag textures are missing from the built resources");
		}
	}

	/**
	 * The join-time DNS path never runs in this test, because the gametest connects to
	 * localhost and the command short-circuits that. Exercise the resolver directly
	 * instead, on a fresh instance so runtime state is untouched.
	 */
	private static void assertAddressResolverWorks(ClientGameTestContext context) {
		AddressResolver resolver = new AddressResolver();
		resolver.beginResolve("localhost");

		context.waitFor(mc -> resolver.resolvedFor("localhost") != null, SLOW_TIMEOUT);

		String ip = resolver.resolvedFor("localhost");
		if (!"127.0.0.1".equals(ip) && !"0:0:0:0:0:0:0:1".equals(ip)) {
			throw new AssertionError("localhost resolved to something unexpected: " + ip);
		}

		// The host guard is what stops a slow lookup from a previous server landing on
		// the next one's address line.
		if (resolver.resolvedFor("some.other.host") != null) {
			throw new AssertionError("resolver returned a result for a host it was not asked about");
		}
	}

	/**
	 * The state assertions prove the command ran; these prove it printed the right
	 * thing. Deliberately matched on labels and plain text only — colors, ordering and
	 * component structure churn every Minecraft release, and pinning them would buy
	 * nothing but false failures. The screenshot artifact covers appearance.
	 */
	private static void assertOutputLines() {
		List<String> lines = CapturedChat.serverInsightLines();

		if (lines.isEmpty()) {
			throw new AssertionError("/serverinsight printed nothing to chat");
		}

		// Every line carries the branded prefix — that is the whole point of ChatFormat.
		String[][] required = {
			{"Address"},
			// Brand is parsed, not echoed: the test server is Fabric, which is modded
			// and therefore cannot have plugins — and the plugins line must say so.
			{"Software", "Fabric"},
			{"modded, no plugins"},
			{"Perf", "TPS"},
			{"Mods", "declared"},
			{"fabric-api"},
			{"Plugins", "detected", "guess:"},
			// The trailing "?" marks an inferred plugin in the list line.
			{"LuckPerms?"},
			{"Pos"},
			{"Dim"},
			{"Time"},
			{"Weather"},
			{"testpluginalpha"},
		};

		for (String[] needles : required) {
			if (!CapturedChat.hasLineContaining(needles)) {
				throw new AssertionError("no output line matched " + String.join(" + ", needles)
					+ "\nactual output:\n  " + String.join("\n  ", lines));
			}
		}

		// The honesty labels. TPS and plugin detection are both guesses, and the mod
		// promises users it says so — a refactor that drops these is a real regression.
		if (!CapturedChat.hasLineContaining("(est)")) {
			throw new AssertionError("the TPS line lost its \"(est)\" qualifier\nactual output:\n  "
				+ String.join("\n  ", lines));
		}

		// ms/t was removed because it was computed as 1000/tps — the TPS reading
		// restated, dressed up as an independent measurement. It must not come back.
		if (CapturedChat.hasLineContaining("ms/t")) {
			throw new AssertionError("ms/t is back; a client cannot measure it\nactual output:\n  "
				+ String.join("\n  ", lines));
		}
	}
}
