package ai.moeru.airicraft.wrapper;

import java.util.Map;

interface MinecraftTransport {
	Map<String, Object> request(String method, String path, Object body);

	default Map<String, Object> get(String path) {
		return request("GET", path, null);
	}

	default Map<String, Object> post(String path, Object body) {
		return request("POST", path, body);
	}

	default Map<String, Object> delete(String path, Object body) {
		return request("DELETE", path, body);
	}
}
