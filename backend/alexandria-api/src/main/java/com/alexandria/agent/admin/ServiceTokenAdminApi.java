package com.alexandria.agent.admin;

import com.alexandria.agent.admin.dto.CreateServiceTokenRequest;
import com.alexandria.agent.admin.dto.CreateServiceTokenResponse;
import com.alexandria.agent.admin.dto.ServiceTokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

/**
 * Admin REST API contract for minting, listing, and revoking service tokens — the scoped,
 * non-interactive credentials headless agents use to authenticate against the agent-facing API
 * (resolve/fetch, MCP). Lets deployers manage agent credentials without hand-writing SQL.
 *
 * <p>All operations are organization-scoped and restricted to organization administrators. The
 * plaintext secret is returned only once, from {@link #create}; thereafter only non-secret
 * metadata is exposed.</p>
 */
@RequestMapping("/api/agent/v1/service-tokens")
public interface ServiceTokenAdminApi {

    /**
     * Mints a new service token. The response is the only place the plaintext secret is ever
     * revealed; the server persists only its SHA-256 hash and a display prefix.
     */
    @PostMapping
    ResponseEntity<CreateServiceTokenResponse> create(@Valid @RequestBody CreateServiceTokenRequest request);

    /**
     * Lists the calling organization's service tokens (metadata only, no secrets), most recently
     * created first.
     */
    @GetMapping
    ResponseEntity<List<ServiceTokenResponse>> list();

    /**
     * Revokes a service token by id. Idempotent: revoking an already-revoked token succeeds.
     * A token belonging to another organization is treated as not found.
     */
    @DeleteMapping("/{tokenId}")
    ResponseEntity<Void> revoke(@PathVariable UUID tokenId);
}
