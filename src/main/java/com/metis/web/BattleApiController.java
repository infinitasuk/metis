package com.metis.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.metis.web.BattleRepository.BattleSession;
import com.metis.web.BattleRepository.BattleSessionDetail;
import com.metis.web.BattleRepository.SessionEvent;
import com.metis.web.BattleRepository.SessionPlayer;
import com.metis.web.BattleRepository.SessionUnit;
import com.metis.web.BattleRepository.FleetSummary;
import com.metis.web.BattleRepository.FleetUnit;
import com.metis.web.BattleRepository.SessionBoard;
import com.metis.web.BattleRepository.SessionTurn;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BattleApiController {

    private final BattleRepository battle;

    public BattleApiController(BattleRepository battle) {
        this.battle = battle;
    }

    @GetMapping("/sessions")
    public List<BattleSession> sessions(@RequestParam(required = false) Long gameId,
                                        @RequestParam(required = false) String status) {
        return battle.findSessions(gameId, status);
    }

    @PostMapping("/games/{gameId}/sessions")
    public ResponseEntity<BattleSessionDetail> createSession(@PathVariable long gameId,
                                                              @RequestBody CreateSessionRequest request) {
        long sessionId = battle.createSession(gameId, request.name(), request.gameModeId(), request.scenarioId(), request.fleetFormatId());
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + sessionId))
                .body(getSession(sessionId));
    }

    @GetMapping("/sessions/{sessionId}")
    public BattleSessionDetail getSession(@PathVariable long sessionId) {
        return battle.findSession(sessionId).orElseThrow(() ->
                new ApiNotFoundException("Session " + sessionId + " does not exist"));
    }

    @PatchMapping("/sessions/{sessionId}")
    public BattleSessionDetail updateSession(@PathVariable long sessionId,
                                             @RequestBody UpdateSessionRequest request) {
        battle.updateSession(sessionId, request.status(), request.currentRound(),
                request.currentPhaseId(), json(request.state()));
        return getSession(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable long sessionId) {
        battle.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/players")
    public List<SessionPlayer> players(@PathVariable long sessionId) {
        requireSession(sessionId);
        return battle.findPlayers(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/players")
    public ResponseEntity<SessionPlayer> createPlayer(@PathVariable long sessionId,
                                                       @RequestBody CreatePlayerRequest request) {
        long playerId = battle.createPlayer(sessionId, request.seatNumber(), request.displayName(),
                request.playerKind(), request.factionId());
        SessionPlayer player = battle.findPlayers(sessionId).stream()
                .filter(item -> item.sessionPlayerId() == playerId)
                .findFirst()
                .orElseThrow(() -> new ApiNotFoundException("Player " + playerId + " was not created"));
        return ResponseEntity.created(URI.create("/api/v1/players/" + playerId)).body(player);
    }

    @PatchMapping("/players/{playerId}")
    public SessionPlayer updatePlayer(@PathVariable long playerId,
                                      @RequestBody UpdatePlayerRequest request) {
        battle.updatePlayer(playerId, request.displayName(), request.playerKind(), request.factionId(),
                request.ready(), json(request.state()));
        return battle.findPlayer(playerId)
                .orElseThrow(() -> new ApiNotFoundException("Player " + playerId + " does not exist"));
    }

    @DeleteMapping("/players/{playerId}")
    public ResponseEntity<Void> deletePlayer(@PathVariable long playerId) {
        battle.deletePlayer(playerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/units")
    public List<SessionUnit> units(@PathVariable long sessionId) {
        requireSession(sessionId);
        return battle.findUnits(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/fleets")
    public List<FleetSummary> fleets(@PathVariable long sessionId) {
        requireSession(sessionId);
        return battle.findFleets(sessionId);
    }

    @PostMapping("/players/{playerId}/fleets")
    public ResponseEntity<Map<String, Long>> createFleet(@PathVariable long playerId,
                                                         @RequestBody CreateFleetRequest request) {
        long fleetId = battle.createFleet(playerId, request.fleetFormatId(), request.fleetName(), request.pointsTotal());
        return ResponseEntity.created(URI.create("/api/v1/fleets/" + fleetId)).body(Map.of("fleetId", fleetId));
    }

    @PatchMapping("/fleets/{fleetId}")
    public ResponseEntity<Void> updateFleet(@PathVariable long fleetId, @RequestBody UpdateFleetRequest request) {
        battle.updateFleet(fleetId, request.fleetName(), request.pointsTotal(), json(request.state()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/fleets/{fleetId}")
    public ResponseEntity<Void> deleteFleet(@PathVariable long fleetId) {
        battle.deleteFleet(fleetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/fleets/{fleetId}/units")
    public List<FleetUnit> fleetUnits(@PathVariable long fleetId) {
        return battle.findFleetUnits(fleetId);
    }

    @PostMapping("/fleets/{fleetId}/units")
    public ResponseEntity<Map<String, Long>> createUnit(@PathVariable long fleetId,
                                                        @RequestBody CreateUnitRequest request) {
        long unitId = battle.createUnit(fleetId, request.unitVariantId(), request.unitName(),
                request.positionX(), request.positionY(), request.facing(), request.status(), json(request.state()));
        return ResponseEntity.created(URI.create("/api/v1/units/" + unitId)).body(Map.of("unitInstanceId", unitId));
    }

    @PatchMapping("/units/{unitId}")
    public ResponseEntity<Void> updateUnit(@PathVariable long unitId, @RequestBody UpdateUnitRequest request) {
        battle.updateUnit(unitId, request.unitName(), request.positionX(), request.positionY(),
                request.facing(), request.status(), json(request.state()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/units/{unitId}")
    public ResponseEntity<Void> deleteUnit(@PathVariable long unitId) {
        battle.deleteUnit(unitId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/boards")
    public List<SessionBoard> boards(@PathVariable long sessionId) {
        requireSession(sessionId);
        return battle.findBoards(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/boards")
    public ResponseEntity<Map<String, Long>> createBoard(@PathVariable long sessionId,
                                                          @RequestBody CreateBoardRequest request) {
        requireSession(sessionId);
        long boardId = battle.createBoard(sessionId, request.boardDefinitionId(), request.role(), json(request.state()));
        return ResponseEntity.created(URI.create("/api/v1/boards/" + boardId)).body(Map.of("sessionBoardId", boardId));
    }

    @PatchMapping("/boards/{boardId}")
    public ResponseEntity<Void> updateBoard(@PathVariable long boardId, @RequestBody UpdateBoardRequest request) {
        battle.updateBoard(boardId, request.role(), json(request.state()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/boards/{boardId}")
    public ResponseEntity<Void> deleteBoard(@PathVariable long boardId) {
        battle.deleteBoard(boardId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/turns")
    public List<SessionTurn> turns(@PathVariable long sessionId) {
        requireSession(sessionId);
        return battle.findTurns(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/turns")
    public ResponseEntity<Map<String, Long>> createTurn(@PathVariable long sessionId,
                                                         @RequestBody CreateTurnRequest request) {
        requireSession(sessionId);
        long turnId = battle.createTurn(sessionId, request.roundNumber(), request.phaseId(), request.activePlayerId());
        return ResponseEntity.created(URI.create("/api/v1/turns/" + turnId)).body(Map.of("turnId", turnId));
    }

    @PatchMapping("/turns/{turnId}")
    public ResponseEntity<Void> updateTurn(@PathVariable long turnId, @RequestBody UpdateTurnRequest request) {
        battle.updateTurn(turnId, request.ended(), json(request.state()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/turns/{turnId}")
    public ResponseEntity<Void> deleteTurn(@PathVariable long turnId) {
        battle.deleteTurn(turnId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/events")
    public List<SessionEvent> events(@PathVariable long sessionId,
                                     @RequestParam(defaultValue = "0") long afterSequence) {
        requireSession(sessionId);
        return battle.findEvents(sessionId, afterSequence);
    }

    @PostMapping("/sessions/{sessionId}/events")
    public ResponseEntity<SessionEvent> appendEvent(@PathVariable long sessionId,
                                                    @RequestBody AppendEventRequest request) {
        requireSession(sessionId);
        long eventId = battle.appendEvent(sessionId, request.type(), request.actorPlayerId(), json(request.payload()));
        SessionEvent event = battle.findEvent(eventId).orElseThrow(() ->
                new ApiNotFoundException("Event " + eventId + " was not created"));
        return ResponseEntity.created(URI.create("/api/v1/events/" + eventId)).body(event);
    }

    @ExceptionHandler(ApiNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ApiNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> dataError(DataAccessException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMostSpecificCause().getMessage()));
    }

    private void requireSession(long sessionId) {
        if (battle.findSession(sessionId).isEmpty()) {
            throw new ApiNotFoundException("Session " + sessionId + " does not exist");
        }
    }

    private static String json(JsonNode node) {
        return node == null ? null : node.toString();
    }

    public record CreateSessionRequest(String name, Long gameModeId, Long scenarioId, Long fleetFormatId) {}

    public record UpdateSessionRequest(String status, Integer currentRound,
                                       Long currentPhaseId, JsonNode state) {}

    public record CreatePlayerRequest(int seatNumber, String displayName,
                                      String playerKind, Long factionId) {}

    public record UpdatePlayerRequest(String displayName, String playerKind,
                                      Long factionId, Boolean ready, JsonNode state) {}

    public record CreateFleetRequest(Long fleetFormatId, String fleetName, Integer pointsTotal) {}

    public record UpdateFleetRequest(String fleetName, Integer pointsTotal, JsonNode state) {}

    public record CreateUnitRequest(long unitVariantId, String unitName, BigDecimal positionX,
                                    BigDecimal positionY, BigDecimal facing, String status, JsonNode state) {}

    public record UpdateUnitRequest(String unitName, BigDecimal positionX, BigDecimal positionY,
                                    BigDecimal facing, String status, JsonNode state) {}

    public record CreateBoardRequest(long boardDefinitionId, String role, JsonNode state) {}

    public record UpdateBoardRequest(String role, JsonNode state) {}

    public record CreateTurnRequest(int roundNumber, Long phaseId, Long activePlayerId) {}

    public record UpdateTurnRequest(Boolean ended, JsonNode state) {}

    public record AppendEventRequest(String type, Long actorPlayerId, JsonNode payload) {}

    private static final class ApiNotFoundException extends RuntimeException {
        private ApiNotFoundException(String message) {
            super(message);
        }
    }
}
