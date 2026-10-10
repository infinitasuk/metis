package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** New-language endpoints return projected DTOs only, never checkpoints or commits. */
@RestController
@RequestMapping("/api/v1")
public class RulesApiController {
    private final RulesService rules;
    private final AuthenticationContext authentication;
    private final RulesCatalogueService catalogue;
    private final RulesFleetPresetService presets;
    public RulesApiController(RulesService rules,AuthenticationContext authentication,RulesCatalogueService catalogue,RulesFleetPresetService presets) { this.rules=rules;this.authentication=authentication;this.catalogue=catalogue;this.presets=presets; }
    @GetMapping("/rules/fleet-presets") public Object presets(HttpServletRequest request) { return presets.list(authentication.require(request)); }
    @GetMapping("/rules/fleet-presets/{id}") public Object preset(@PathVariable long id,HttpServletRequest request) { return presets.get(id,authentication.require(request)); }
    @PostMapping("/rules/fleet-presets") @ResponseStatus(HttpStatus.CREATED)
    public Object savePreset(@RequestBody String body,HttpServletRequest request) { return presets.create(rules.parse(body),authentication.require(request)); }
    @PatchMapping("/rules/fleet-presets/{id}") public Object renamePreset(@PathVariable long id,@RequestBody String body,HttpServletRequest request) { return presets.rename(id,rules.parse(body),authentication.require(request)); }
    @DeleteMapping("/rules/fleet-presets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePreset(@PathVariable long id,@RequestParam long expectedVersion,HttpServletRequest request) { presets.delete(id,expectedVersion,authentication.require(request)); }
    @PostMapping("/rules/fleet-presets/{id}/load") public Object loadPreset(@PathVariable long id,@RequestBody String body,HttpServletRequest request) { return presets.load(id,rules.parse(body),authentication.require(request)); }
    @GetMapping("/rules/catalogue") public Object catalogue(HttpServletRequest request) { authentication.require(request);return catalogue.catalogue(); }
    @GetMapping("/rules/catalogue/{entryId}/roster")
    public Object roster(@PathVariable long entryId,HttpServletRequest request) { authentication.require(request);return catalogue.roster(entryId); }
    @PostMapping("/rules/catalogue/{entryId}/roster/validate")
    public Object validateRoster(@PathVariable long entryId,@RequestBody String body,HttpServletRequest request) { authentication.require(request);return catalogue.validateRoster(entryId,rules.parse(body)); }
    @PostMapping("/admin/rules/catalogue/imports") public Object importCatalogue(@RequestBody String body,HttpServletRequest request) { return catalogue.importCatalogue(rules.parse(body),authentication.require(request)); }
    @PostMapping("/rules/catalogue/{entryId}/sessions") @ResponseStatus(HttpStatus.CREATED)
    public Object createCatalogueSession(@PathVariable long entryId,@RequestBody String body,HttpServletRequest request) { return catalogue.createSession(entryId,rules.parse(body),authentication.require(request)); }
    @GetMapping("/rules/definitions") public Object published(HttpServletRequest request) { return rules.definitions(authentication.require(request),false); }
    @GetMapping("/rules/definitions/{id}/roster") public Object definitionRoster(@PathVariable long id,HttpServletRequest request) { authentication.require(request);return rules.definitionRoster(id); }
    @PostMapping("/rules/definitions/{id}/roster/validate") public Object validateDefinitionRoster(@PathVariable long id,@RequestBody String body,HttpServletRequest request) { authentication.require(request);return rules.validateDefinitionRoster(id,rules.parse(body)); }
    @GetMapping("/rules/sessions") public Object sessions(HttpServletRequest request) { return rules.sessions(authentication.require(request)); }
    @GetMapping("/rules/users") public Object users(HttpServletRequest request) { authentication.require(request);return authentication.users().findActiveUsers(); }
    @GetMapping("/admin/rules/definitions") public Object definitions(HttpServletRequest request) { return rules.definitions(authentication.require(request),true); }
    @GetMapping("/admin/rules/definitions/{id}") public Object definition(@PathVariable long id,HttpServletRequest request) { return rules.definition(id,authentication.require(request)); }
    @GetMapping("/admin/rules/definitions/{id}/resolved") public Object resolved(@PathVariable long id,HttpServletRequest request) { return rules.resolved(id,authentication.require(request)); }
    @PostMapping("/admin/rules/definitions") @ResponseStatus(HttpStatus.CREATED)
    public Object createDefinition(@RequestBody String source,HttpServletRequest request) { return rules.createDefinition(rules.parse(source),authentication.require(request)); }
    @PutMapping("/admin/rules/definitions/{id}") public Object edit(@PathVariable long id,@RequestBody String body,HttpServletRequest request) { var edit=rules.parse(body);return rules.updateDefinition(id,rules.editVersion(edit,true),edit.get("source"),authentication.require(request)); }
    @PostMapping("/admin/rules/definitions/{id}/validate") public Object validate(@PathVariable long id,HttpServletRequest request) {
        var result=rules.validate(id,authentication.require(request));return Map.of("valid",result.valid(),"diagnostics",result.diagnostics());
    }
    @PostMapping("/admin/rules/definitions/{id}/publish") public Object publish(@PathVariable long id,@RequestBody String body,HttpServletRequest request) { return rules.publish(id,rules.editVersion(rules.parse(body),false),authentication.require(request)); }
    @PostMapping("/rules/sessions") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody String body,HttpServletRequest request) { return rules.createSession(rules.parse(body),authentication.require(request)); }
    @GetMapping("/rules/sessions/{id}") public Object session(@PathVariable UUID id,HttpServletRequest request) { return rules.state(id,authentication.require(request)); }
    @GetMapping("/rules/sessions/{id}/state") public Object state(@PathVariable UUID id,HttpServletRequest request) { return rules.state(id,authentication.require(request)); }
    @GetMapping("/rules/sessions/{id}/roster") public Object capturedRoster(@PathVariable UUID id,HttpServletRequest request) { return rules.roster(id,authentication.require(request)); }
    @GetMapping("/rules/sessions/{id}/workspace") public ResponseEntity<?> workspace(@PathVariable UUID id,HttpServletRequest request) {
        var result=rules.workspace(id,authentication.require(request),request.getHeader("If-None-Match"));
        return ResponseEntity.status(result.body()==null?304:200).eTag(result.etag()).body(result.body());
    }
    @GetMapping("/rules/sessions/{id}/events") public Object events(@PathVariable UUID id,HttpServletRequest request) { return rules.state(id,authentication.require(request)).state().events(); }
    @GetMapping("/rules/sessions/{id}/actions") public Object actions(@PathVariable UUID id,HttpServletRequest request) { return rules.actions(id,authentication.require(request)); }
    @PostMapping("/rules/sessions/{id}/preview") public Object preview(@PathVariable UUID id,@RequestBody String body,HttpServletRequest request) { return rules.preview(id,rules.parse(body),authentication.require(request)); }
    @GetMapping("/rules/sessions/{id}/compatibility") public Object compatibility(@PathVariable UUID id,HttpServletRequest request) { return rules.compatibility(id,authentication.require(request)); }
    @GetMapping("/rules/sessions/{id}/history/{version}") public Object history(@PathVariable UUID id,@PathVariable long version,HttpServletRequest request) { return rules.history(id,version,authentication.require(request)); }
    @PostMapping("/rules/sessions/{id}/commands") public ResponseEntity<String> command(@PathVariable UUID id,@RequestBody String body,HttpServletRequest request) {
        var response=rules.command(id,rules.parse(body),authentication.require(request));return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.json());
    }
    @DeleteMapping("/rules/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,HttpServletRequest request) { rules.delete(id,authentication.require(request)); }
    @ExceptionHandler(RulesService.Failure.class) public ResponseEntity<?> failure(RulesService.Failure error) { return ResponseEntity.status(error.status()).body(Map.of("code",error.code(),"diagnostics",error.diagnostics())); }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> storageFailure(DataAccessException error) {
        var cause=error.getMostSpecificCause();String state=cause instanceof java.sql.SQLException sql?sql.getSQLState():"";
        if(Set.of("40001","23505","55000").contains(state))return ResponseEntity.status(409).body(Map.of("code","STORAGE_CONFLICT"));
        if(Set.of("23503","23514","P0001").contains(state))return ResponseEntity.unprocessableEntity().body(Map.of("code","STORAGE_CONSTRAINT"));
        return ResponseEntity.status(503).body(Map.of("code","STORAGE_FAILURE","message","Storage confirmation failed. Retry with the same command identity."));
    }
    @ExceptionHandler({IllegalArgumentException.class,com.metis.engine.core.rules.RuleEvaluationException.class}) public ResponseEntity<?> invalid(RuntimeException error) { return ResponseEntity.unprocessableEntity().body(Map.of("code","RULES_INPUT_INVALID")); }
}
