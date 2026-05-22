# Map Mod Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add map-mod support behind an Airicraft-owned abstraction, starting with JourneyMap for waypoint read/write and map image capture.

**Architecture:** Airicraft core defines a stable map-domain interface and bridge/tool contracts. Optional map-mod adapters live in separate compat subprojects, register through a small core bridge/registry, and expose JourneyMap or Xaero-specific APIs only inside adapter code. Start with JourneyMap because its Fabric API exposes soft-dependency plugin loading, waypoint CRUD, and async map tile image requests; keep Xaero as a later adapter behind the same interface.

**Tech Stack:** Fabric 1.21.8, Yarn mappings in core Airicraft, Java 21, JourneyMap API v2.0.0 Fabric compile-only dependency, Gradle compat subproject, localhost bridge JSON endpoints, OpenAI-compatible planner tool calls, PNG image encoding through `NativeImage`/`ImageIO`. Integration smoke must run JourneyMap with the existing REI compat adapter present so the optional-mod path is validated with multiple providers loaded together.

---

## Research Decision

Pick JourneyMap first.

JourneyMap has a documented plugin-style soft dependency. Its API guide says mods should compile against the API, avoid bundling API classes, and expose a Fabric `journeymap` entrypoint. `IClientAPI` exposes `addWaypoint`, `removeWaypoint`, `getAllWaypoints`, `getWaypoint`, `getWaypoints`, `removeAllWaypoints`, waypoint groups, minimap state, UI state, and `requestMapTile(...)`. The tile request returns a `NativeImage` asynchronously and is capped at 512x512, so Airicraft can compose a worldmap image from one or more tiles without screen automation.

Xaero is still valuable later, but its public developer docs are less directly aligned with the required interface. The docs show a Fabric dependency path and rich waypoint/minimap features, but not an equally explicit public API for full waypoint CRUD plus map image extraction. Treat Xaero as phase 2 after the abstract interface is proven with JourneyMap.

## Target Airicraft Contract

Use these stable concepts everywhere outside adapter code:

```java
public interface MapIntegrationProvider {
	String id();
	boolean available();
	MapCapabilities capabilities();
	List<MapWaypoint> listWaypoints(MapWaypointQuery query);
	MapWaypoint upsertWaypoint(MapWaypointWrite request);
	boolean deleteWaypoint(String waypointId);
	CompletableFuture<MapImageCapture> captureMap(MapImageRequest request);
}
```

`MapCapabilities` should advertise `READ_WAYPOINTS`, `WRITE_WAYPOINTS`, `DELETE_WAYPOINTS`, `MINIMAP_IMAGE`, and `WORLDMAP_IMAGE`. JourneyMap should initially support waypoint read/write/delete and worldmap tile capture. Minimap image can be implemented as either a one-tile map-centered capture or a first-person screenshot crop only if JourneyMap does not provide a minimap render image; expose that limitation as capability metadata.

## Public Surface

Bridge endpoints:

```text
GET    /v1/map/status
GET    /v1/map/waypoints
POST   /v1/map/waypoints
DELETE /v1/map/waypoints?id=<id>
POST   /v1/map/image
```

Wrapper CLI:

```text
airicraft map status
airicraft map waypoints list [--provider <id>] [--dimension <id>]
airicraft map waypoints set --name <name> --x <x> --y <y> --z <z> [--dimension <id>] [--color <hex>] [--id <id>]
airicraft map waypoints delete --waypoint-id <id>
airicraft map image --kind worldmap --output <png> [--radius-chunks <1-8>] [--zoom <0-8>] [--grid]
```

Planner tools:

```text
inspect_map_waypoints
set_map_waypoint
delete_map_waypoint
take_map_look
```

`take_map_look` should attach the PNG to the planner follow-up using the existing native image tool path, not base64 text in a tool result.

---

### Task 1: Core Map Domain Types

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationProvider.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapCapabilities.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapWaypoint.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapWaypointQuery.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapWaypointWrite.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapImageRequest.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapImageCapture.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/integration/map/MapDomainTypesTest.java`

- [ ] **Step 1: Write the failing type-contract tests**

```java
@Test
void waypointIdsAreStableProviderScopedIds() {
	MapWaypoint waypoint = new MapWaypoint(
		"journeymap",
		"jm-guid-1",
		"Home",
		"minecraft:overworld",
		1,
		64,
		2,
		0x3366ff,
		true,
		true,
		true
	);
	assertEquals("journeymap", waypoint.providerId());
	assertEquals("jm-guid-1", waypoint.id());
	assertEquals("minecraft:overworld", waypoint.dimension());
}

@Test
void imageCaptureDefensivelyCopiesPngBytes() {
	byte[] bytes = new byte[] {1, 2, 3};
	MapImageCapture capture = new MapImageCapture("journeymap", "worldmap", "png", 16, 16, 123L, bytes);
	bytes[0] = 9;
	assertArrayEquals(new byte[] {1, 2, 3}, capture.imageBytes());
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapDomainTypesTest`

Expected: compile failure because the map-domain records and interface do not exist.

- [ ] **Step 3: Add records and provider interface**

Use Java records with defensive copying for `MapImageCapture.imageBytes()`. Keep fields plain strings and ints at this boundary so bridge, CLI, planner, and adapters do not leak JourneyMap/Xaero types.

- [ ] **Step 4: Run the test and commit**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapDomainTypesTest`

Commit:

```bash
git add src/client/java/ai/moeru/airicraft/agent/integration/map src/test/java/ai/moeru/airicraft/agent/integration/map/MapDomainTypesTest.java
git commit -m "feat(map): add map integration domain types"
```

### Task 2: Provider Registry And Optional Adapter Bridge

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationRegistry.java`
- Create: `src/test/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationRegistryTest.java`

- [ ] **Step 1: Write registry tests**

```java
@Test
void availableProviderPrefersFirstAvailableRegisteredAdapter() {
	StubMapProvider unavailable = new StubMapProvider("journeymap", false);
	StubMapProvider available = new StubMapProvider("xaero", true);
	MapIntegrationRegistry registry = MapIntegrationRegistry.of(unavailable, available);
	assertEquals("xaero", registry.preferred().orElseThrow().id());
}

@Test
void duplicateProviderIdsFailFast() {
	StubMapProvider first = new StubMapProvider("journeymap", true);
	StubMapProvider second = new StubMapProvider("journeymap", true);
	assertThrows(IllegalArgumentException.class, () -> MapIntegrationRegistry.of(first, second));
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapIntegrationRegistryTest`

Expected: compile failure because `MapIntegrationRegistry` does not exist.

- [ ] **Step 3: Implement the registry**

Implement immutable registration with:

```java
public static MapIntegrationRegistry of(MapIntegrationProvider... providers)
public List<MapIntegrationProvider> providers()
public Optional<MapIntegrationProvider> provider(String id)
public Optional<MapIntegrationProvider> preferred()
public List<MapIntegrationProvider> availableProviders()
```

Provider id rules: normalize by exact lowercase id, reject blank ids, reject duplicates.

- [ ] **Step 4: Run tests and commit**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapIntegrationRegistryTest`

Commit:

```bash
git add src/client/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationRegistry.java src/test/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationRegistryTest.java
git commit -m "feat(map): add map provider registry"
```

### Task 3: Core Map Bridge Singleton For Compat Modules

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationBridge.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationBridgeTest.java`

- [ ] **Step 1: Write tests for unavailable fallback and adapter registration**

```java
@Test
void startsUnavailable() {
	MapIntegrationBridge.clearProviders();
	assertTrue(MapIntegrationBridge.registry().availableProviders().isEmpty());
	assertTrue(MapIntegrationBridge.statusPayload().contains("available=false"));
}

@Test
void registeredProviderIsVisible() {
	MapIntegrationBridge.clearProviders();
	MapIntegrationBridge.setProviders(List.of(new StubMapProvider("journeymap", true)));
	assertEquals("journeymap", MapIntegrationBridge.registry().preferred().orElseThrow().id());
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapIntegrationBridgeTest`

Expected: compile failure because `MapIntegrationBridge` does not exist.

- [ ] **Step 3: Implement `AtomicReference<MapIntegrationRegistry>`**

Mirror the REI bridge pattern, but allow a list of providers instead of a single backend. `clearProviders()` resets to an empty registry. `setProviders(...)` validates through `MapIntegrationRegistry.of(...)`.

- [ ] **Step 4: Run tests and commit**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapIntegrationBridgeTest`

Commit:

```bash
git add src/client/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationBridge.java src/test/java/ai/moeru/airicraft/agent/integration/map/MapIntegrationBridgeTest.java
git commit -m "feat(map): add map adapter bridge"
```

### Task 4: JourneyMap Compat Subproject

**Files:**
- Modify: `settings.gradle`
- Modify: `gradle.properties`
- Create: `compat/journeymap/build.gradle`
- Create: `compat/journeymap/src/main/resources/fabric.mod.json`
- Create: `compat/journeymap/src/client/java/ai/moeru/airicraft/compat/journeymap/AiricraftJourneyMapPlugin.java`
- Create: `compat/journeymap/src/client/java/ai/moeru/airicraft/compat/journeymap/JourneyMapIntegrationProvider.java`
- Create: `scripts/compat-journeymap`

- [ ] **Step 1: Add Gradle inclusion gate**

In `settings.gradle`, add:

```groovy
if (gradle.startParameter.projectProperties.get("airicraft.includeJourneyMapCompat") == "true") {
	include("compat:journeymap")
}
```

- [ ] **Step 2: Add version properties**

In `gradle.properties`, add:

```properties
journeymap_api_version=2.0.0-1.21.1-SNAPSHOT
journeymap_mod_version=1.21.8-6.0.0-beta.52+fabric
```

- [ ] **Step 3: Create compat build file**

Use the REI compat build as the template, but depend on JourneyMap:

```groovy
repositories {
	maven {
		name = "JourneyMap"
		url = uri("https://maven.blamejared.com")
	}
	maven {
		name = "Modrinth"
		url = uri("https://api.modrinth.com/maven")
	}
}

dependencies {
	clientCompileOnly rootProject.sourceSets.main.output
	clientCompileOnly rootProject.sourceSets.client.output
	modCompileOnlyApi "info.journeymap:journeymap-api-fabric:${project.journeymap_api_version}"
	modRuntimeOnly "maven.modrinth:journeymap:${project.journeymap_mod_version}"
}
```

Also add sync/run tasks named `syncJourneyMapCompatMods`, `syncJourneyMapCompatLibraries`, and `runClientJourneyMapCompat`, using `.airicraft-compat/journeymap`. These tasks are JourneyMap-owned, but the smoke profile must include REI when `:compat:rei` is present:

```groovy
def reiCompatProject = rootProject.findProject(":compat:rei")

tasks.register("syncJourneyMapCompatMods", Sync) {
	dependsOn rootProject.tasks.named("remapJar")
	dependsOn tasks.named("remapJar")
	if (reiCompatProject != null) {
		dependsOn reiCompatProject.tasks.named("remapJar")
	}

	from(rootProject.tasks.named("remapJar").flatMap { it.archiveFile })
	from(tasks.named("remapJar").flatMap { it.archiveFile })
	if (reiCompatProject != null) {
		from(reiCompatProject.tasks.named("remapJar").flatMap { it.archiveFile })
		from(reiCompatProject.configurations.reiCompatMods)
	}
	from(configurations.journeyMapCompatMods)
	into(journeyMapCompatModsDir)
}
```

`runClientJourneyMapCompat` should mirror the same mod set, so the live smoke client contains `airicraft`, `airicraft-journeymap-compat`, `journeymap`, `airicraft-rei-compat`, `roughlyenoughitems`, Architectury, and Cloth Config.

- [ ] **Step 4: Create Fabric metadata**

`compat/journeymap/src/main/resources/fabric.mod.json` must declare:

```json
{
  "schemaVersion": 1,
  "id": "airicraft-journeymap-compat",
  "version": "${version}",
  "name": "Airicraft JourneyMap Compatibility",
  "environment": "client",
  "entrypoints": {
    "journeymap": [
      "ai.moeru.airicraft.compat.journeymap.AiricraftJourneyMapPlugin"
    ]
  },
  "depends": {
    "fabricloader": ">=0.18.4",
    "minecraft": "~1.21.8",
    "java": ">=21",
    "fabric-api": "*",
    "airicraft": "*",
    "journeymap": "*"
  }
}
```

- [ ] **Step 5: Implement JourneyMap plugin registration**

`AiricraftJourneyMapPlugin` implements `journeymap.api.v2.client.IClientPlugin`, stores `IClientAPI`, and calls:

```java
MapIntegrationBridge.setProviders(List.of(new JourneyMapIntegrationProvider(jmAPI)));
```

`getModId()` returns `airicraft`.

- [ ] **Step 6: Add script**

Create `scripts/compat-journeymap` mirroring `scripts/compat-rei`, with commands `setup|sync|run|dry-run|config|mods|list`. Every Gradle invocation in this script must include both compat gates:

```bash
./gradlew -Pairicraft.includeJourneyMapCompat=true -Pairicraft.includeReiCompat=true ...
```

This keeps REI loaded during JourneyMap integration smoke and catches registry/tool collisions early.

- [ ] **Step 7: Run dry-run and compile**

Run:

```bash
source .envrc && ./gradlew -Pairicraft.includeJourneyMapCompat=true -Pairicraft.includeReiCompat=true :compat:journeymap:runClientJourneyMapCompat --dry-run
source .envrc && ./gradlew -Pairicraft.includeJourneyMapCompat=true -Pairicraft.includeReiCompat=true :compat:journeymap:compileJava :compat:journeymap:compileClientJava :compat:rei:compileJava :compat:rei:compileClientJava
```

Commit:

```bash
git add settings.gradle gradle.properties compat/journeymap scripts/compat-journeymap
git commit -m "feat(journeymap): add compat adapter shell"
```

### Task 5: JourneyMap Waypoint Adapter

**Files:**
- Modify: `compat/journeymap/src/client/java/ai/moeru/airicraft/compat/journeymap/JourneyMapIntegrationProvider.java`
- Test by compile: `compat:journeymap:compileClientJava`

- [ ] **Step 1: Implement waypoint conversion**

Use `IClientAPI.getAllWaypoints()` and `IClientAPI.getAllWaypoints(ResourceKey)` for reads. Convert each `Waypoint` to `MapWaypoint` with:

```text
providerId = "journeymap"
id = waypoint.getGuid()
name = waypoint.getName()
dimension = waypoint.getPrimaryDimension()
x/y/z = waypoint.getX()/getY()/getZ()
color = waypoint.getColor()
enabled = waypoint.isEnabled()
showOnMap = waypoint.showOnMap()
showInWorld = waypoint.showInWorld()
```

- [ ] **Step 2: Implement upsert**

For writes, create a new waypoint with `WaypointFactory.createWaypoint("airicraft", new BlockPos(x, y, z), dimensionKey, true)` if `request.id()` is blank. If `request.id()` is present, fetch with `IClientAPI.getWaypoint("airicraft", id)` and mutate name, position, color, enabled, and visibility flags.

- [ ] **Step 3: Implement delete**

Fetch with `IClientAPI.getWaypoint("airicraft", waypointId)`. Return false if missing. Otherwise call `IClientAPI.removeWaypoint("airicraft", waypoint)`.

- [ ] **Step 4: Compile and commit**

Run:

```bash
source .envrc && ./gradlew -Pairicraft.includeJourneyMapCompat=true :compat:journeymap:compileClientJava
```

Commit:

```bash
git add compat/journeymap/src/client/java/ai/moeru/airicraft/compat/journeymap/JourneyMapIntegrationProvider.java
git commit -m "feat(journeymap): adapt waypoint access"
```

### Task 6: JourneyMap Worldmap Image Capture

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapImageEncoder.java`
- Modify: `compat/journeymap/src/client/java/ai/moeru/airicraft/compat/journeymap/JourneyMapIntegrationProvider.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/integration/map/MapImageEncoderTest.java`

- [ ] **Step 1: Write PNG encoder test**

```java
@Test
void encodesBufferedImageAsPngCapture() {
	BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	image.setRGB(0, 0, 0xff00ff00);
	MapImageCapture capture = MapImageEncoder.encode("journeymap", "worldmap", image, 100L);
	assertEquals("png", capture.format());
	assertEquals(4, capture.width());
	assertTrue(capture.imageBytes().length > 8);
}
```

- [ ] **Step 2: Implement `MapImageEncoder`**

Encode `BufferedImage` to PNG using `ImageIO.write`. Throw `BridgeUnavailableException("map_capture_failed", "Failed to encode map image")` on failure.

- [ ] **Step 3: Implement single-tile worldmap capture first**

For `MapImageRequest(kind="worldmap")`, calculate a JourneyMap tile around the player’s current chunk. Call:

```java
jmAPI.requestMapTile("airicraft", dimension, mapType, startChunk, endChunk, chunkY, zoom, showGrid, callback);
```

Convert the returned `NativeImage` to `BufferedImage`, encode with `MapImageEncoder`, and complete a `CompletableFuture<MapImageCapture>`. If callback receives null, return a failed future with `BridgeUnavailableException("map_unavailable", "JourneyMap map tile is unavailable")`.

- [ ] **Step 4: Add composition only after single tile works**

For `radiusChunks > 16`, request multiple adjacent 512x512 tiles and stitch into one image. Cap final image to 1024x1024 to control planner token cost. Reject larger requests with `invalid_request`.

- [ ] **Step 5: Run tests and compile compat**

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapImageEncoderTest
source .envrc && ./gradlew -Pairicraft.includeJourneyMapCompat=true :compat:journeymap:compileClientJava
```

Commit:

```bash
git add src/client/java/ai/moeru/airicraft/agent/integration/map/MapImageEncoder.java src/test/java/ai/moeru/airicraft/agent/integration/map/MapImageEncoderTest.java compat/journeymap/src/client/java/ai/moeru/airicraft/compat/journeymap/JourneyMapIntegrationProvider.java
git commit -m "feat(journeymap): capture worldmap image"
```

### Task 7: Bridge Endpoints

**Files:**
- Modify: `src/client/java/ai/moeru/airicraft/ModBridgeServer.java`
- Test: add focused cases to existing bridge test file if present, otherwise create `src/test/java/ai/moeru/airicraft/ModBridgeServerMapTest.java`

- [ ] **Step 1: Add route registration**

Add contexts:

```java
httpServer.createContext("/v1/map/status", exchange -> handleJson(exchange, this::createMapStatusResponse));
httpServer.createContext("/v1/map/waypoints", this::handleMapWaypoints);
httpServer.createContext("/v1/map/image", this::handleMapImage);
```

- [ ] **Step 2: Implement status payload**

Return:

```json
{
  "available": true,
  "providers": [
    {"id":"journeymap","available":true,"capabilities":["READ_WAYPOINTS","WRITE_WAYPOINTS","DELETE_WAYPOINTS","WORLDMAP_IMAGE"]}
  ],
  "preferredProvider": "journeymap"
}
```

- [ ] **Step 3: Implement waypoint handlers**

Use method dispatch: `GET` list, `POST` upsert, `DELETE` delete. Return `503 map_provider_unavailable` when no provider is available. Return `400 invalid_request` for missing name/coordinates on upsert or missing id on delete.

- [ ] **Step 4: Implement image handler**

`POST /v1/map/image` accepts `kind`, `provider`, `radiusChunks`, `zoom`, `grid`, `dimension`. Return same image payload shape as `/v1/camera/screenshot`: `format`, `width`, `height`, `capturedAtMs`, `imageBase64`.

- [ ] **Step 5: Run bridge tests and commit**

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests '*Map*Test' --tests '*ModBridgeServer*Test'
```

Commit:

```bash
git add src/client/java/ai/moeru/airicraft/ModBridgeServer.java src/test/java/ai/moeru/airicraft
git commit -m "feat(map): expose map bridge endpoints"
```

### Task 8: Wrapper CLI

**Files:**
- Modify: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/MinecraftTransport.java`
- Modify: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/HttpBridgeTransport.java`
- Modify: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
- Test: `wrapper/src/test/java/ai/moeru/airicraft/wrapper/AiricraftCliMainTest.java`

- [ ] **Step 1: Add transport methods**

Add methods:

```java
Map<String, Object> mapStatus();
Map<String, Object> listMapWaypoints(String providerId, String dimension);
Map<String, Object> setMapWaypoint(Map<String, Object> request);
Map<String, Object> deleteMapWaypoint(String waypointId);
CapturedImage captureMapImage(Map<String, Object> request);
```

- [ ] **Step 2: Add CLI tests**

Test success output starts with:

```text
status: ok
command: map waypoints list
```

Test screenshot-like image command writes PNG bytes and prints `output:`.

- [ ] **Step 3: Implement CLI commands**

Follow the existing `camera screenshot` and `highlights` command patterns. Use deterministic plain text output and exit code `3` for bridge transport failures, `4` for map domain failures.

- [ ] **Step 4: Run wrapper tests and commit**

Run:

```bash
source .envrc && ./gradlew wrapper:test --tests ai.moeru.airicraft.wrapper.AiricraftCliMainTest
```

Commit:

```bash
git add wrapper/src/main/java/ai/moeru/airicraft/wrapper wrapper/src/test/java/ai/moeru/airicraft/wrapper/AiricraftCliMainTest.java
git commit -m "feat(cli): add map commands"
```

### Task 9: Planner Tools

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/integration/map/MapPlannerToolProvider.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/llm/PlannerOrchestrator.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/shell/PlannerShellFactory.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/integration/map/MapPlannerToolProviderTest.java`
- Test: add image outcome test to `src/test/java/ai/moeru/airicraft/agent/llm/PlannerOrchestratorTest.java`

- [ ] **Step 1: Add provider tool tests**

Assert tool schemas contain `inspect_map_waypoints`, `set_map_waypoint`, `delete_map_waypoint`, and `take_map_look` only when a map provider is available.

- [ ] **Step 2: Implement text tools**

`inspect_map_waypoints` returns compact text:

```text
Tool result for inspect_map_waypoints: provider=journeymap, waypoints=[{id=..., name="Home", dim=minecraft:overworld, pos=1,64,2}]
```

`set_map_waypoint` and `delete_map_waypoint` return stable success/error text.

- [ ] **Step 3: Implement image-capable provider result**

Current provider tools only return text. Add a minimal extension:

```java
public sealed interface PlannerProviderToolResult permits TextProviderToolResult, ImageProviderToolResult {}
```

Then add a default adapter so existing providers still return text. Route `ImageProviderToolResult` in `PlannerOrchestrator` to `ImageToolExecutionOutcome`.

- [ ] **Step 4: Wire registry**

Change `PlannerShellFactory` from:

```java
PlannerToolRegistry.of(new ReiRecipeSearchToolProvider())
```

to:

```java
PlannerToolRegistry.of(
	new ReiRecipeSearchToolProvider(),
	new MapPlannerToolProvider(MapIntegrationBridge::registry)
)
```

- [ ] **Step 5: Run planner tests and commit**

Run:

```bash
source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.integration.map.MapPlannerToolProviderTest --tests ai.moeru.airicraft.agent.llm.PlannerOrchestratorTest
```

Commit:

```bash
git add src/client/java/ai/moeru/airicraft/agent/integration/map src/client/java/ai/moeru/airicraft/agent/llm src/client/java/ai/moeru/airicraft/agent/shell src/test/java/ai/moeru/airicraft/agent
git commit -m "feat(planner): add map tools"
```

### Task 10: Live Compat Smoke

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Document the compat launcher**

Add a section parallel to REI:

```text
JourneyMap compat smoke:
- Use scripts/compat-journeymap run, not plain runClient.
- Keep REI enabled during this smoke. The launcher passes both -Pairicraft.includeJourneyMapCompat=true and -Pairicraft.includeReiCompat=true.
- Jar cache ignored: .airicraft-compat/journeymap/
- Verify live: mod list has airicraft + journeymap + airicraft-journeymap-compat + roughlyenoughitems + airicraft-rei-compat; airicraft map status says available: true; planner still advertises search_recipes.
```

- [ ] **Step 2: Run production-style compat client**

Run:

```bash
source .envrc && scripts/compat-journeymap setup
source .envrc && scripts/compat-journeymap run
```

Expected synced jars include both map and recipe compat jars:

```text
.airicraft-compat/journeymap/mods/airicraft-*.jar
.airicraft-compat/journeymap/mods/airicraft-journeymap-compat-*.jar
.airicraft-compat/journeymap/mods/airicraft-rei-compat-*.jar
.airicraft-compat/journeymap/mods/RoughlyEnoughItems-*.jar
.airicraft-compat/journeymap/mods/journeymap-*.jar
```

- [ ] **Step 3: Verify bridge and CLI**

In another shell:

```bash
source .envrc && wrapper/build/install/airicraft/bin/airicraft status
source .envrc && wrapper/build/install/airicraft/bin/airicraft map status
source .envrc && wrapper/build/install/airicraft/bin/airicraft map waypoints set --name "Airicraft Test" --x 0 --y 80 --z 0 --dimension minecraft:overworld
source .envrc && wrapper/build/install/airicraft/bin/airicraft map waypoints list
source .envrc && wrapper/build/install/airicraft/bin/airicraft map image --kind worldmap --output /tmp/airicraft-worldmap.png
```

Expected:

```text
status: ok
command: map status
available: true
preferredProvider: journeymap
```

The PNG at `/tmp/airicraft-worldmap.png` must exist and be non-empty.

- [ ] **Step 4: Verify both optional planner tool families**

Ask the agent in-game: “mark this base as Airicraft test on the map, then look at the world map.” Verify debug journal records `set_map_waypoint` and `take_map_look`, and that the follow-up has `image attached`.

Then ask: “search REI for oak planks recipes.” Verify debug journal records `search_recipes` and the response contains a REI-backed tool result. This confirms the new map provider did not regress the existing provider registry or REI adapter while both optional mods are loaded.

- [ ] **Step 5: Run final test set and commit docs**

Run:

```bash
source .envrc && ./gradlew test wrapper:test
source .envrc && ./gradlew -Pairicraft.includeJourneyMapCompat=true -Pairicraft.includeReiCompat=true :compat:journeymap:compileClientJava :compat:rei:compileClientJava
```

Commit:

```bash
git add README.md AGENTS.md
git commit -m "docs: document JourneyMap compat smoke"
```

## Self-Review

- Spec coverage: waypoint read/write/delete is covered by Tasks 1, 5, 7, 8, and 9. Worldmap image read is covered by Tasks 6, 7, 8, and 9. Minimap image is treated as a capability decision because JourneyMap exposes map tiles, not a direct minimap framebuffer API; the first implementation should expose a centered map tile as `worldmap` and only advertise `MINIMAP_IMAGE` if a real minimap image path is proven. Combined optional-mod smoke with REI is covered by Tasks 4 and 10.
- Placeholder scan: no forbidden placeholder patterns found.
- Type consistency: provider id is `journeymap`; planner tool names are `inspect_map_waypoints`, `set_map_waypoint`, `delete_map_waypoint`, and `take_map_look`; bridge endpoints are under `/v1/map`.
