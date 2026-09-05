package com.alexandria.agent.mcp;

import com.alexandria.agent.ResolverService;
import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.agent.render.FetchResult;
import com.alexandria.auth.AlexandriaUserDetails;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * JSON-RPC 2.0 dispatcher for the MCP endpoint. Handles {@code initialize}, {@code tools/list}, and
 * {@code tools/call} for the resolver's two tools, delegating to {@link ResolverService} with the
 * caller's authenticated identity so ACL and governance are enforced per identity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String RESOLVE_TOOL = "resolve_skill";
    private static final String FETCH_TOOL = "fetch_skill";

    // JSON-RPC error codes.
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final ResolverService resolverService;
    // A dedicated mapper for JSON-RPC assembly; independent of the web layer's HTTP mapper.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode handle(JsonNode request, AlexandriaUserDetails identity) {
        JsonNode idNode = request.get("id");
        String method = request.path("method").asText("");

        try {
            return switch (method) {
                case "initialize" -> success(idNode, initializeResult());
                case "tools/list" -> success(idNode, toolsListResult());
                case "tools/call" -> handleToolCall(idNode, request.path("params"), identity);
                case "notifications/initialized", "ping" -> success(idNode, objectMapper.createObjectNode());
                default -> error(idNode, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(idNode, INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            log.warn("MCP request failed for method '{}': {}", method, e.getMessage());
            return error(idNode, INTERNAL_ERROR, e.getMessage());
        }
    }

    private JsonNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "alexandria-skill-resolver");
        serverInfo.put("version", "1.0");
        return result;
    }

    private JsonNode toolsListResult() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(resolveToolDefinition());
        tools.add(fetchToolDefinition());
        return result;
    }

    private ObjectNode resolveToolDefinition() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", RESOLVE_TOOL);
        tool.put("description",
                "Find the most relevant governed AI engineering skill(s) for the current coding task. "
                + "Call this before implementing a non-trivial task to check whether an approved, "
                + "reusable workflow already exists. Returns a short ranked list of candidates with "
                + "identity, governance (lifecycle/risk/visibility), and match reasons, but NOT full "
                + "instructions. After choosing a candidate, call fetch_skill with its skillVersionId "
                + "to get the usable instructions. Results respect the caller's access control.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Natural-language description of the task to find a skill for.");
        ObjectNode tool2 = props.putObject("tool");
        tool2.put("type", "string");
        tool2.put("description", "Preferred AI tool identifier, e.g. github_copilot, cursor, claude_code.");
        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum number of candidates to return (default 5, max 25).");
        schema.putArray("required").add("query");
        return tool;
    }

    private ObjectNode fetchToolDefinition() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", FETCH_TOOL);
        tool.put("description",
                "Fetch the full, usable instructions for one skill version chosen from resolve_skill "
                + "results. Returns the skill as an Agent Skills SKILL.md document by default (or "
                + "structured JSON with format=alexandria_native). Denied for blocked skills; "
                + "deprecated skills are returned with a warning. Respects the caller's access control.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode versionId = props.putObject("skillVersionId");
        versionId.put("type", "string");
        versionId.put("description", "The skillVersionId from a resolve_skill candidate's ref.");
        ObjectNode toolParam = props.putObject("tool");
        toolParam.put("type", "string");
        toolParam.put("description", "Optional preferred tool variant; falls back to canonical instructions.");
        ObjectNode format = props.putObject("format");
        format.put("type", "string");
        format.put("description", "Output format: agent_skill (default) or alexandria_native.");
        schema.putArray("required").add("skillVersionId");
        return tool;
    }

    private JsonNode handleToolCall(JsonNode idNode, JsonNode params, AlexandriaUserDetails identity) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        return switch (name) {
            case RESOLVE_TOOL -> callResolve(idNode, arguments, identity);
            case FETCH_TOOL -> callFetch(idNode, arguments, identity);
            default -> error(idNode, INVALID_PARAMS, "Unknown tool: " + name);
        };
    }

    private JsonNode callResolve(JsonNode idNode, JsonNode arguments, AlexandriaUserDetails identity) {
        String query = arguments.path("query").asText("");
        Integer limit = arguments.has("limit") && arguments.get("limit").isInt()
                ? arguments.get("limit").asInt() : null;
        String tool = arguments.path("tool").isMissingNode() ? null : arguments.path("tool").asText(null);

        var context = tool == null ? null
                : new com.alexandria.agent.dto.ResolveContext(null, null, tool, null);
        ResolveResponse response = resolverService.resolve(
                new ResolveRequest(query, context, null, null, limit), identity);

        return success(idNode, toolResult(response, /*isError*/ false));
    }

    private JsonNode callFetch(JsonNode idNode, JsonNode arguments, AlexandriaUserDetails identity) {
        String versionIdRaw = arguments.path("skillVersionId").asText("");
        UUID versionId;
        try {
            versionId = UUID.fromString(versionIdRaw);
        } catch (IllegalArgumentException e) {
            return error(idNode, INVALID_PARAMS, "skillVersionId must be a valid UUID");
        }
        String tool = arguments.path("tool").isMissingNode() ? null : arguments.path("tool").asText(null);
        String format = arguments.path("format").isMissingNode() ? "agent_skill"
                : arguments.path("format").asText("agent_skill");

        FetchResult result = resolverService.fetch(versionId, tool, format, "mcp", identity);
        // agent_skill returns a String (markdown); native returns a DTO — serialize both as text.
        String text = result.body() instanceof String s ? s : writeJson(result.body());
        return success(idNode, textToolResult(text));
    }

    /** Wraps a resolve response as MCP tool result content (structured + serialized text). */
    private ObjectNode toolResult(Object structured, boolean isError) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", writeJson(structured));
        result.set("structuredContent", objectMapper.valueToTree(structured));
        result.put("isError", isError);
        return result;
    }

    private ObjectNode textToolResult(String text) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        result.put("isError", false);
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize MCP tool result", e);
        }
    }

    private ObjectNode success(JsonNode idNode, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode == null ? objectMapper.nullNode() : idNode);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode idNode, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode == null ? objectMapper.nullNode() : idNode);
        ObjectNode err = response.putObject("error");
        err.put("code", code);
        err.put("message", message == null ? "error" : message);
        return response;
    }
}
