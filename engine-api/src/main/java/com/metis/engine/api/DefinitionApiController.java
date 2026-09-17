package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class DefinitionApiController {

    private final DefinitionRepository definitions;

    public DefinitionApiController(DefinitionRepository definitions) {
        this.definitions = definitions;
    }

    @GetMapping("/games/{gameId}/definitions")
    public List<DefinitionRepository.DefinitionVersion> definitions(@PathVariable long gameId) {
        return definitions.findVersions(gameId);
    }

    @GetMapping("/definitions/{definitionId}")
    public DefinitionRepository.DefinitionVersion definition(@PathVariable long definitionId) {
        return definitions.findVersion(definitionId).orElseThrow(() ->
                new DefinitionNotFoundException("Definition " + definitionId + " does not exist"));
    }

    @PostMapping("/games/{gameId}/definitions")
    public ResponseEntity<DefinitionRepository.DefinitionVersion> create(
            @PathVariable long gameId, @RequestBody DefinitionRequest request) {
        long definitionId = definitions.createVersion(gameId, request.code(), request.name(), request.sourceRevision());
        return ResponseEntity.created(URI.create("/api/v1/admin/definitions/" + definitionId))
                .body(definition(definitionId));
    }

    @PatchMapping("/definitions/{definitionId}")
    public DefinitionRepository.DefinitionVersion update(@PathVariable long definitionId,
                                                         @RequestBody DefinitionRequest request) {
        definitions.updateVersion(definitionId, request.code(), request.name(), request.sourceRevision());
        return definition(definitionId);
    }

    @PostMapping("/definitions/{definitionId}/validate")
    public DefinitionRepository.ValidationResult validate(@PathVariable long definitionId) {
        definition(definitionId);
        return definitions.validate(definitionId);
    }

    @GetMapping("/definitions/{definitionId}/audit")
    public List<DefinitionRepository.DefinitionAudit> audit(@PathVariable long definitionId) {
        definition(definitionId);
        return definitions.findAudit(definitionId);
    }

    @PostMapping("/definitions/{definitionId}/publish")
    public DefinitionRepository.DefinitionVersion publish(@PathVariable long definitionId) {
        definitions.publishVersion(definitionId);
        return definition(definitionId);
    }

    @PostMapping("/definitions/{definitionId}/retire")
    public DefinitionRepository.DefinitionVersion retire(@PathVariable long definitionId) {
        definitions.retireVersion(definitionId);
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/objects/{objectId}")
    public DefinitionRepository.DefinitionVersion updateObject(@PathVariable long definitionId,
                                                                @PathVariable long objectId,
                                                                @RequestBody ObjectUpdateRequest request) {
        definition(definitionId);
        definitions.updateObject(definitionId, objectId, request.name(), request.description(),
                request.displayText(), json(request.objectData()), json(request.sourceReference()));
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/game")
    public DefinitionRepository.DefinitionVersion updateGame(@PathVariable long definitionId,
                                                             @RequestBody GameUpdateRequest request) {
        definition(definitionId);
        definitions.updateGame(definitionId, request.name(), request.description(), request.minPlayers(),
                request.maxPlayers(), request.minCpu(), request.maxCpu());
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/factions/{factionId}")
    public DefinitionRepository.DefinitionVersion updateFaction(@PathVariable long definitionId,
                                                                 @PathVariable long factionId,
                                                                 @RequestBody NamedContentRequest request) {
        definition(definitionId);
        definitions.updateFaction(definitionId, factionId, request.name(), request.description());
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/rules/{ruleId}")
    public DefinitionRepository.DefinitionVersion updateRule(@PathVariable long definitionId,
                                                              @PathVariable long ruleId,
                                                              @RequestBody ConfiguredContentRequest request) {
        definition(definitionId);
        definitions.updateRule(definitionId, ruleId, request.name(), request.description(), json(request.configuration()));
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/modes/{modeId}")
    public DefinitionRepository.DefinitionVersion updateMode(@PathVariable long definitionId,
                                                              @PathVariable long modeId,
                                                              @RequestBody ConfiguredContentRequest request) {
        definition(definitionId);
        definitions.updateMode(definitionId, modeId, request.name(), request.description(), json(request.configuration()));
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/phases/{phaseId}")
    public DefinitionRepository.DefinitionVersion updatePhase(@PathVariable long definitionId,
                                                               @PathVariable long phaseId,
                                                               @RequestBody ConfiguredContentRequest request) {
        definition(definitionId);
        definitions.updatePhase(definitionId, phaseId, request.name(), request.description(), json(request.configuration()));
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/actions/{actionId}")
    public DefinitionRepository.DefinitionVersion updateAction(@PathVariable long definitionId,
                                                                @PathVariable long actionId,
                                                                @RequestBody ConfiguredContentRequest request) {
        definition(definitionId);
        definitions.updateAction(definitionId, actionId, request.name(), request.description(), json(request.configuration()));
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/scenarios/{scenarioId}")
    public DefinitionRepository.DefinitionVersion updateScenario(@PathVariable long definitionId,
                                                                  @PathVariable long scenarioId,
                                                                  @RequestBody ConfiguredContentRequest request) {
        definition(definitionId);
        definitions.updateScenario(definitionId, scenarioId, request.name(), request.description(), json(request.configuration()));
        return definition(definitionId);
    }

    @PatchMapping("/definitions/{definitionId}/boards/{boardId}")
    public DefinitionRepository.DefinitionVersion updateBoard(@PathVariable long definitionId,
                                                               @PathVariable long boardId,
                                                               @RequestBody BoardUpdateRequest request) {
        definition(definitionId);
        definitions.updateBoard(definitionId, boardId, request.name(), request.width(), request.height(),
                request.coordinateSystem(), json(request.configuration()));
        return definition(definitionId);
    }

    @ExceptionHandler(DefinitionNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(DefinitionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> dataError(DataAccessException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMostSpecificCause().getMessage()));
    }

    public record DefinitionRequest(String code, String name, String sourceRevision) {}

    public record ObjectUpdateRequest(String name, String description, String displayText,
                                      JsonNode objectData, JsonNode sourceReference) {}

    public record GameUpdateRequest(String name, String description, int minPlayers, int maxPlayers,
                                    int minCpu, int maxCpu) {}

    public record NamedContentRequest(String name, String description) {}

    public record ConfiguredContentRequest(String name, String description, JsonNode configuration) {}

    public record BoardUpdateRequest(String name, java.math.BigDecimal width, java.math.BigDecimal height,
                                     String coordinateSystem, JsonNode configuration) {}

    private static String json(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private static final class DefinitionNotFoundException extends RuntimeException {
        private DefinitionNotFoundException(String message) {
            super(message);
        }
    }
}
