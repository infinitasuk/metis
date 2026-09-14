package com.metis.engine.api;

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

    @ExceptionHandler(DefinitionNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(DefinitionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> dataError(DataAccessException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMostSpecificCause().getMessage()));
    }

    public record DefinitionRequest(String code, String name, String sourceRevision) {}

    private static final class DefinitionNotFoundException extends RuntimeException {
        private DefinitionNotFoundException(String message) {
            super(message);
        }
    }
}
