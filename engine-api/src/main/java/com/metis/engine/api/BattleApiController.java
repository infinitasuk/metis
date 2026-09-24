package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.metis.engine.core.Action;
import com.metis.engine.core.ActionResult;
import com.metis.engine.core.AuthoritativeGameEngine;
import com.metis.engine.core.BoardState;
import com.metis.engine.core.GameDefinition;
import com.metis.engine.core.GameSetup;
import com.metis.engine.core.GameState;
import com.metis.engine.core.LegalAction;
import com.metis.engine.core.Player;
import com.metis.engine.api.BattleRepository.BattleSession;
import com.metis.engine.api.BattleRepository.BattleSessionDetail;
import com.metis.engine.api.BattleRepository.SessionEvent;
import com.metis.engine.api.BattleRepository.SessionPlayer;
import com.metis.engine.api.BattleRepository.SessionUnit;
import com.metis.engine.api.BattleRepository.FleetSummary;
import com.metis.engine.api.BattleRepository.FleetUnit;
import com.metis.engine.api.BattleRepository.SessionBoard;
import com.metis.engine.api.BattleRepository.SessionTurn;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BattleApiController {

    private final BattleRepository battle;
    private final BattleTemplateRepository templates;
    private final FleetPresetRepository fleetPresets;
    private final DefinitionRepository definitions;
    private final ObjectMapper objectMapper;
    private final AuthoritativeGameEngine engine;
    private final String engineVersion;
    private final String apiVersion;
    private final int eventSchemaVersion;
    private final String gitSha;
    private final AuthenticationContext authentication;

    public BattleApiController(BattleRepository battle, BattleTemplateRepository templates,
                               FleetPresetRepository fleetPresets,
                               DefinitionRepository definitions, ObjectMapper objectMapper,
                               AuthenticationContext authentication,
                               @Value("${metis.engine.version:0.1.0-SNAPSHOT}") String engineVersion,
                               @Value("${metis.api.version:v1}") String apiVersion,
                               @Value("${metis.event.schema-version:1}") int eventSchemaVersion,
                               @Value("${metis.git.sha:unknown}") String gitSha) {
        this.battle = battle;
        this.templates = templates;
        this.fleetPresets = fleetPresets;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
        this.authentication = authentication;
        this.engine = new AuthoritativeGameEngine();
        this.engineVersion = engineVersion;
        this.apiVersion = apiVersion;
        this.eventSchemaVersion = eventSchemaVersion;
        this.gitSha = gitSha;
    }

    @GetMapping("/meta")
    public Map<String, Object> metadata() {
        return Map.of("engineVersion", engineVersion, "apiVersion", apiVersion,
                "eventSchemaVersion", eventSchemaVersion, "gitSha", gitSha);
    }

    @GetMapping("/definitions/{definitionId}/engine")
    public GameDefinition engineDefinition(@PathVariable long definitionId) {
        DefinitionRepository.DefinitionVersion version = definitions.findVersion(definitionId)
                .orElseThrow(() -> new ApiNotFoundException("Definition " + definitionId + " does not exist"));
        if (!"published".equals(version.status())) {
            throw new ApiNotFoundException("Definition " + definitionId + " is not published");
        }
        return definitions.findEngineDefinition(definitionId)
                .orElseThrow(() -> new ApiNotFoundException("Definition " + definitionId + " does not exist"));
    }

    @GetMapping("/sessions")
    public List<BattleSession> sessions(@RequestParam(required = false) Long gameId,
                                        @RequestParam(required = false) String status,
                                        HttpServletRequest request) {
        return battle.findVisibleSessions(gameId, status, authentication.require(request));
    }

    @GetMapping("/templates")
    public List<BattleTemplateRepository.BattleTemplate> templates(@RequestParam(required = false) Long gameId) {
        return templates.findTemplates(gameId);
    }

    @GetMapping("/templates/{templateId}")
    public BattleTemplateRepository.BattleTemplate template(@PathVariable long templateId) {
        return templates.findTemplate(templateId).orElseThrow(() ->
                new ApiNotFoundException("Template " + templateId + " does not exist"));
    }

    @GetMapping("/fleet-presets")
    public List<FleetPresetRepository.FleetPreset> fleetPresets(@RequestParam long gameId,
                                                                @RequestParam(required = false) Long factionId,
                                                                HttpServletRequest request) {
        return fleetPresets.findPresets(authentication.require(request).userId(), gameId, factionId);
    }

    @PostMapping("/games/{gameId}/fleet-presets")
    public ResponseEntity<Map<String, Long>> createFleetPreset(@PathVariable long gameId,
                                                               @RequestBody FleetPresetRequest request,
                                                               HttpServletRequest httpRequest) {
        MetisUser user = authentication.require(httpRequest);
        long presetId = fleetPresets.create(user.userId(), gameId, request.gameVersionId(), request.fleetFormatId(),
                request.factionId(), request.name(), request.pointsTotal(), request.units());
        return ResponseEntity.created(URI.create("/api/v1/fleet-presets/" + presetId))
                .body(Map.of("fleetPresetId", presetId));
    }

    @DeleteMapping("/fleet-presets/{presetId}")
    public ResponseEntity<Void> deleteFleetPreset(@PathVariable long presetId, HttpServletRequest request) {
        fleetPresets.delete(authentication.require(request).userId(), presetId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/games/{gameId}/sessions")
    public ResponseEntity<BattleSessionDetail> createSession(@PathVariable long gameId,
                                                              @RequestBody CreateSessionRequest request,
                                                              HttpServletRequest httpRequest) {
        MetisUser user = authentication.require(httpRequest);
        if (request.templateId() != null) {
            BattleTemplateRepository.BattleTemplate template = templates.findTemplate(request.templateId())
                    .orElseThrow(() -> new ApiNotFoundException("Template " + request.templateId() + " does not exist"));
            if (template.gameId() != gameId) {
                throw new ApiConflictException("Template does not belong to game " + gameId);
            }
        }
        long sessionId = battle.createSession(gameId, request.name(), request.gameModeId(), request.scenarioId(),
                request.fleetFormatId(), request.visibility(), engineVersion, apiVersion, eventSchemaVersion);
        battle.assignSessionOwner(sessionId, user.userId());
        if (request.templateId() != null) {
            templates.initializeRunMetadata(sessionId, request.templateId(), aiVersion());
        }
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

    @PostMapping("/sessions/{sessionId}/start")
    @Transactional
    public BattleSessionDetail startSession(@PathVariable long sessionId,
                                            @RequestBody StartSessionRequest request) {
        BattleSessionDetail session = getSession(sessionId);
        if (!"setup".equals(session.status())) {
            throw new ApiConflictException("Session " + sessionId + " is not in setup");
        }
        if (request == null || request.boardCode() == null || request.width() == null || request.height() == null) {
            throw new IllegalArgumentException("boardCode, width and height are required");
        }
        GameDefinition definition = definitions
                .findEngineDefinition(session.gameVersionId())
                .orElseThrow(() -> new ApiNotFoundException("Definition for session " + sessionId + " does not exist"));
        if (definition.turnStructure() == null) throw new ApiConflictException("Configure a turn structure on a definition revision before starting a new battle");
        List<SessionPlayer> players = battle.findPlayers(sessionId);
        if (battle.findSeats(sessionId).stream().anyMatch(seat -> "human".equals(seat.playerKind()) && seat.userId() == null)) {
            throw new ApiConflictException("Assign each human seat to a user before starting");
        }
        List<Player> enginePlayers = players.stream()
                .map(player -> new Player(player.sessionPlayerId(), player.seatNumber(), player.playerKind(),
                        player.factionName()))
                .toList();
        BoardState board = new BoardState(request.boardCode(), request.width().doubleValue(), request.height().doubleValue());
        GameSetup setup = new GameSetup(enginePlayers.size(),
                (int) enginePlayers.stream().filter(player -> "cpu".equals(player.kind())).count(),
                enginePlayers, board, request.options());
        definitions.captureSessionDefinition(sessionId, definition);
        GameState state = engine.createInitialState(sessionId, definition, setup);
        state = engine.applyAction(definition, state, new Action("start_game", 0, null)).resultingState();
        persistState(sessionId, session.stateVersion(), state, state.events().getLast());
        deployStoredFleets(sessionId, definition, state, board);
        return getSession(sessionId);
    }

    private void deployStoredFleets(long sessionId, GameDefinition definition, GameState state, BoardState board) {
        for (SessionPlayer player : battle.findPlayers(state.sessionId())) {
            int unitIndex = 0;
            for (BattleRepository.FleetSummary fleet : battle.findFleets(state.sessionId()).stream()
                .filter(item -> item.sessionPlayerId() == player.sessionPlayerId()).toList()) {
                for (FleetUnit unit : battle.findFleetUnits(fleet.fleetId())) {
                    boolean hasLayout = unit.positionX() != null && unit.positionY() != null
                            && unit.positionX().doubleValue() >= 0 && unit.positionX().doubleValue() <= 100
                            && unit.positionY().doubleValue() >= 0 && unit.positionY().doubleValue() <= 100;
                    double x = hasLayout
                            ? deploymentX(board, player.seatNumber(), unit.positionX().doubleValue())
                            : player.seatNumber() == 1
                                ? 180 + unitIndex * 120
                                : board.width() - 180 - unitIndex * 120;
                    double y = hasLayout
                            ? deploymentY(board, unit.positionY().doubleValue())
                            : Math.min(board.height() - 100, 180 + unitIndex * 120);
                    ActionResult result = engine.applyAction(definition, state, new Action("deploy_unit",
                            player.sessionPlayerId(), Map.of("unitId", unit.unitInstanceId(),
                                    "unitCode", String.valueOf(unit.unitVariantId()),
                                    "positionX", x, "positionY", y,
                                    "facing", player.seatNumber() == 1 ? 0 : 180)));
                    if (!result.accepted()) {
                        throw new ApiConflictException("Could not deploy a roster unit: " + result.code());
                    }
                    state = result.resultingState();
                    persistState(sessionId, state.stateVersion() - 1, state, state.events().getLast());
                    battle.updateUnit(unit.unitInstanceId(), unit.unitName(),
                            java.math.BigDecimal.valueOf(x), java.math.BigDecimal.valueOf(y),
                            java.math.BigDecimal.valueOf(player.seatNumber() == 1 ? 0 : 180), "ready", null);
                    unitIndex++;
                }
            }
        }
    }

    private double deploymentX(BoardState board, int seatNumber, double layoutX) {
        double edge = board.width() * (120.0 / 1800.0);
        double depth = board.width() * (600.0 / 1800.0) * layoutX / 100;
        return seatNumber == 1 ? edge + depth : board.width() - edge - depth;
    }

    private double deploymentY(BoardState board, double layoutY) {
        double edge = board.height() * (80.0 / 900.0);
        return edge + (board.height() - edge * 2) * layoutY / 100;
    }

    @PostMapping("/sessions/{sessionId}/actions")
    @Transactional
    public ActionResult submitAction(@PathVariable long sessionId, @RequestBody ActionRequest request, HttpServletRequest httpRequest) {
        var owned = battle.controlledPlayers(sessionId, authentication.require(httpRequest).userId());
        long actor = request.actorPlayerId();
        if (actor == 0 && "advance_phase".equals(request.type()) && !owned.isEmpty()) actor = owned.stream().min(Long::compareTo).orElseThrow();
        if (!owned.contains(actor)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "You do not control this seat");
        request = new ActionRequest(request.type(), actor, request.expectedStateVersion(), request.parameters());
        BattleSessionDetail session = getSession(sessionId);
        if (session.state() == null || "{}".equals(session.state().trim())) {
            throw new ApiConflictException("Session " + sessionId + " has not been started");
        }
        try {
            GameState state = objectMapper.readValue(session.state(), GameState.class);
            appendActionLog(sessionId, request, "INFO", "ACTION_RECEIVED",
                    "Action submitted to authoritative engine");
            if (request.expectedStateVersion() == null) {
                appendActionLog(sessionId, request, "WARN", "ACTION_REJECTED",
                        "The state version is required when submitting an action");
                return new ActionResult(false, "STATE_VERSION_REQUIRED",
                        "The state version is required when submitting an action", null);
            }
            if (request.expectedStateVersion() != state.stateVersion()) {
                appendActionLog(sessionId, request, "WARN", "STALE_STATE",
                        "The submitted state version does not match the current game state");
                return new ActionResult(false, "STALE_STATE",
                        "The submitted state version does not match the current game state", null);
            }
            GameDefinition definition;
            try { definition = definitions.findSessionDefinition(sessionId, session.gameVersionId()).orElse(null); }
            catch (IllegalStateException error) { return new ActionResult(false, "SNAPSHOT_INCOMPATIBLE", error.getMessage(), null); }
            if (definition == null) return new ActionResult(false, "LEGACY_SNAPSHOT", "This battle has no captured starting definition. Restart as a new battle to use current rules and corrected profiles.", null);
            if (state.sessionId() != sessionId || state.definitionVersionId() != definition.definitionVersionId()) {
                return new ActionResult(false, "STATE_DEFINITION_MISMATCH", "Recorded state does not match the captured battle definition", null);
            }
            ActionResult result = engine.applyAction(definition, state,
                    new Action(request.type(), request.actorPlayerId(), request.parameters()));
            appendActionLog(sessionId, request, result.accepted() ? "INFO" : "WARN",
                    result.accepted() ? "ACTION_ACCEPTED" : "ACTION_REJECTED", result.message());
            if (result.accepted()) {
                persistState(sessionId, state.stateVersion(), result.resultingState(),
                        result.resultingState().events().getLast());
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored session state is invalid", exception);
        }
    }

    @GetMapping("/sessions/{sessionId}/state")
    public GameState state(@PathVariable long sessionId) {
        BattleSessionDetail session = getSession(sessionId);
        if (session.state() == null || "{}".equals(session.state().trim())) {
            throw new ApiConflictException("Session " + sessionId + " has not been started");
        }
        try {
            return objectMapper.readValue(session.state(), GameState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored session state is invalid", exception);
        }
    }

    @GetMapping("/sessions/{sessionId}/legal-actions")
    public List<LegalAction> legalActions(@PathVariable long sessionId, HttpServletRequest request) {
        BattleSessionDetail session = getSession(sessionId);
        GameState state = state(sessionId);
        GameDefinition definition;
        try { definition = definitions.findSessionDefinition(sessionId, session.gameVersionId()).orElse(null); }
        catch (IllegalStateException error) { return List.of(); }
        if (definition == null) return List.of();
        var owned = battle.controlledPlayers(sessionId, authentication.require(request).userId());
        return engine.legalActions(definition, state).stream()
                .filter(action -> action.actorPlayerId() == 0 ? !owned.isEmpty() : owned.contains(action.actorPlayerId())).toList();
    }

    @GetMapping("/sessions/{sessionId}/permissions")
    public Map<String,Object> permissions(@PathVariable long sessionId,HttpServletRequest request) {
        var user=authentication.require(request);
        return Map.of("controlledPlayerIds",battle.controlledPlayers(sessionId,user.userId()),"canManage",battle.canEditSession(sessionId,user));
    }

    @GetMapping("/sessions/{sessionId}/seats")
    public List<BattleRepository.SeatAssignment> seats(@PathVariable long sessionId) { return battle.findSeats(sessionId); }

    @PostMapping("/sessions/{sessionId}/seats/{playerId}")
    public List<BattleRepository.SeatAssignment> assignSeat(@PathVariable long sessionId,@PathVariable long playerId,@RequestBody SeatRequest request) {
        if(battle.findPlayer(playerId).filter(player->player.sessionId()==sessionId).isEmpty()) throw new ApiNotFoundException("Seat does not belong to this session");
        battle.assignSeat(playerId,request.userId()); return battle.findSeats(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/compatibility")
    public Map<String, Object> compatibility(@PathVariable long sessionId) {
        var session = getSession(sessionId);
        try {
            var definition = definitions.findSessionDefinition(sessionId, session.gameVersionId());
            return Map.of("compatible", definition.isPresent(), "code", definition.isPresent() ? "PINNED" : "LEGACY_UNPINNED",
                    "message", definition.isPresent() ? "This battle uses its captured starting definition."
                            : "Starting profiles were not captured for this battle. Recorded damage cannot be distinguished reliably from old profile errors. Restart as a new battle to use corrected profiles; the original history is retained.");
        } catch (IllegalStateException error) {
            return Map.of("compatible", false, "code", "SNAPSHOT_INCOMPATIBLE", "message", error.getMessage());
        }
    }

    @PostMapping("/sessions/{sessionId}/restart")
    @Transactional
    public BattleSessionDetail restart(@PathVariable long sessionId, HttpServletRequest request) {
        var source = getSession(sessionId);
        GameState original = state(sessionId);
        long newId = battle.createSession(source.gameId(), source.sessionName().substring(0, Math.min(95, source.sessionName().length())) + " / restarted",
                source.gameModeId(), source.scenarioId(), source.fleetFormatId(), source.visibility(), engineVersion, apiVersion, eventSchemaVersion);
        battle.assignSessionOwner(newId, authentication.require(request).userId());
        if (!getSession(newId).gameVersionId().equals(source.gameVersionId())) {
            throw new ApiConflictException("The published catalogue revision changed. Build a new roster using the current revision.");
        }
        var playerMapping = new java.util.HashMap<Long,Long>();
        for (var player : battle.findPlayers(sessionId)) {
            long playerId = battle.createPlayer(newId, player.seatNumber(), player.displayName(), player.playerKind(), player.factionId());
            playerMapping.put(player.sessionPlayerId(), playerId);
            var ownership = battle.findSeats(sessionId).stream().filter(seat -> seat.sessionPlayerId() == player.sessionPlayerId()).findFirst();
            if (ownership.isPresent() && ownership.get().userId() != null && "human".equals(player.playerKind())) battle.assignSeat(playerId, ownership.get().userId());
            for (var fleet : battle.findFleets(sessionId)) {
                if (fleet.sessionPlayerId() != player.sessionPlayerId()) continue;
                long fleetId = battle.createFleet(playerId, fleet.fleetFormatId(), fleet.fleetName(), fleet.pointsTotal());
                for (var unit : battle.findFleetUnits(fleet.fleetId())) {
                    long recordedId = unit.unitInstanceId();
                    if (unit.state() != null) {
                        try { recordedId = objectMapper.readTree(unit.state()).path("engineUnitId").asLong(recordedId); }
                        catch (JsonProcessingException error) { throw new ApiConflictException("Cannot read the source roster mapping"); }
                    }
                    long originalUnitId = recordedId;
                    var deployed = original.events().stream().filter(event -> "unit_deployed".equals(event.type())
                            && event.payload().get("unitId") instanceof Number id && id.longValue() == originalUnitId).findFirst();
                    BigDecimal x = null; BigDecimal y = null;
                    if (deployed.isPresent()) {
                        double boardX = ((Number) deployed.get().payload().get("positionX")).doubleValue();
                        double boardY = ((Number) deployed.get().payload().get("positionY")).doubleValue();
                        double startX = deploymentX(original.board(), player.seatNumber(), 0);
                        double endX = deploymentX(original.board(), player.seatNumber(), 100);
                        x = BigDecimal.valueOf(Math.max(0, Math.min(100, (boardX - startX) / (endX - startX) * 100)));
                        double startY = deploymentY(original.board(), 0);
                        double endY = deploymentY(original.board(), 100);
                        y = BigDecimal.valueOf(Math.max(0, Math.min(100, (boardY - startY) / (endY - startY) * 100)));
                    }
                    battle.createUnit(fleetId, unit.unitVariantId(), unit.unitName(), x, y, null, "ready", null);
                }
            }
        }
        for (var board : battle.findBoards(sessionId)) battle.createBoard(newId, board.boardDefinitionId(), board.role(), null);
        var options = new java.util.HashMap<String, Object>();
        if (original.values().get("initialSetupOptions") instanceof Map<?, ?> initialOptions) {
            initialOptions.forEach((key, value) -> options.put(key.toString(), com.metis.engine.core.ScopedData.remapOwners(value, playerMapping)));
        } else for (String key : List.of("distancePerSpeed", "maximumMovementDistance", "maximumRounds", "unlimitedRounds",
                "maximumShipsPerPlayer", "maximumSquadronsPerPlayer", "loadoutProfile", "shortRange", "mediumRange", "longRange")) {
            if (original.values().containsKey(key)) options.put(key, original.values().get(key));
        }
        return startSession(newId, new StartSessionRequest(original.board().boardCode(), BigDecimal.valueOf(original.board().width()),
                BigDecimal.valueOf(original.board().height()), options));
    }

    @GetMapping("/admin/sessions/{sessionId}/engine-logs")
    public List<BattleRepository.EngineLog> engineLogs(
            @PathVariable long sessionId,
            @RequestParam(defaultValue = "0") long afterLogId) {
        requireSession(sessionId);
        return battle.findEngineLogs(sessionId, afterLogId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Transactional
    public ResponseEntity<Void> deleteSession(@PathVariable long sessionId) {
        getSession(sessionId);
        battle.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/players")
    public List<SessionPlayer> players(@PathVariable long sessionId) {
        requireSession(sessionId);
        return battle.findPlayers(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/players")
    @Transactional
    public ResponseEntity<SessionPlayer> createPlayer(@PathVariable long sessionId,
                                                       @RequestBody CreatePlayerRequest request) {
        long playerId = battle.createPlayer(sessionId, request.seatNumber(), request.displayName(),
                request.playerKind(), request.factionId());
        if (request.userId() != null && "human".equals(request.playerKind())) battle.assignSeat(playerId, request.userId());
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

    @PostMapping("/sessions/{sessionId}/invitations")
    @Transactional
    public List<BattleRepository.SessionInvitation> invite(@PathVariable long sessionId,
                                                            @RequestBody InviteRequest request,
                                                            HttpServletRequest httpRequest) {
        MetisUser user = authentication.require(httpRequest);
        if (!battle.canEditSession(sessionId, user)) {
            throw new ApiConflictException("Only the game owner or administrator can invite viewers");
        }
        authentication.users().findActive(request.userId())
                .orElseThrow(() -> new ApiNotFoundException("User " + request.userId() + " does not exist"));
        battle.inviteUser(sessionId, request.userId(), user.userId());
        return battle.findInvitations(sessionId);
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

    @ExceptionHandler(ApiConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ApiConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(AuthoritativeGameEngine.InvalidSetupException.class)
    public ResponseEntity<Map<String, Object>> invalidSetup(AuthoritativeGameEngine.InvalidSetupException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", exception.getMessage(), "violations", exception.validation().violations()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> dataError(DataAccessException exception) {
        if (exception.getMostSpecificCause() instanceof java.sql.SQLException sql
                && "40001".equals(sql.getSQLState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "The session changed; reload the current state and retry"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "The request failed database validation"));
    }

    private void requireSession(long sessionId) {
        if (battle.findSession(sessionId).isEmpty()) {
            throw new ApiNotFoundException("Session " + sessionId + " does not exist");
        }
    }

    private static String json(JsonNode node) {
        return node == null ? null : node.toString();
    }

    private String aiVersion() {
        return gitSha == null || gitSha.isBlank() || "unknown".equals(gitSha) ? "cpu-v1" : gitSha;
    }

    private void persistState(long sessionId, long expectedStateVersion, GameState state,
                              com.metis.engine.core.GameEvent event) {
        try {
            battle.saveEngineState(sessionId, expectedStateVersion, state.status(), state.round(), null,
                    objectMapper.writeValueAsString(state), event.type(), event.actorPlayerId(),
                    objectMapper.writeValueAsString(event.payload()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize engine state", exception);
        }
    }

    private void appendActionLog(long sessionId, ActionRequest request, String level,
                                 String outcome, String message) {
        try {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            context.put("outcome", outcome);
            context.put("actionType", request.type());
            context.put("actorPlayerId", request.actorPlayerId());
            context.put("expectedStateVersion", request.expectedStateVersion());
            // Parameters can contain private choices. They are never copied into diagnostics.
            battle.appendEngineLog(sessionId, level, "HTTP", message,
                    objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize engine log", exception);
        }
    }

    public record CreateSessionRequest(String name, Long gameModeId, Long scenarioId, Long fleetFormatId,
                                       String visibility, Long templateId) {
        public CreateSessionRequest {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A session name is required");
            }
            visibility = visibility == null || visibility.isBlank() ? "public" : visibility;
            if (!"public".equals(visibility) && !"private".equals(visibility)) {
                throw new IllegalArgumentException("Visibility must be public or private");
            }
        }
    }

    public record UpdateSessionRequest(String status, Integer currentRound,
                                       Long currentPhaseId, JsonNode state) {}

    public record CreatePlayerRequest(int seatNumber, String displayName,
                                      String playerKind, Long factionId, Long userId) {}

    public record SeatRequest(long userId) {}

    public record UpdatePlayerRequest(String displayName, String playerKind,
                                      Long factionId, Boolean ready, JsonNode state) {}

    public record CreateFleetRequest(Long fleetFormatId, String fleetName, Integer pointsTotal) {}

    public record FleetPresetRequest(long gameVersionId, Long fleetFormatId, long factionId,
                                     String name, Integer pointsTotal,
                                     List<FleetPresetRepository.PresetUnitRequest> units) {}

    public record UpdateFleetRequest(String fleetName, Integer pointsTotal, JsonNode state) {}

    public record CreateUnitRequest(long unitVariantId, String unitName, BigDecimal positionX,
                                    BigDecimal positionY, BigDecimal facing, String status, JsonNode state) {}

    public record UpdateUnitRequest(String unitName, BigDecimal positionX, BigDecimal positionY,
                                    BigDecimal facing, String status, JsonNode state) {}

    public record CreateBoardRequest(long boardDefinitionId, String role, JsonNode state) {}

    public record UpdateBoardRequest(String role, JsonNode state) {}

    public record StartSessionRequest(String boardCode, java.math.BigDecimal width,
                                      java.math.BigDecimal height, Map<String, Object> options) {}

    public record ActionRequest(String type, long actorPlayerId, Long expectedStateVersion,
                                Map<String, Object> parameters) {}

    public record CreateTurnRequest(int roundNumber, Long phaseId, Long activePlayerId) {}

    public record UpdateTurnRequest(Boolean ended, JsonNode state) {}

    public record AppendEventRequest(String type, Long actorPlayerId, JsonNode payload) {}

    public record InviteRequest(long userId) {}

    private static final class ApiNotFoundException extends RuntimeException {
        private ApiNotFoundException(String message) {
            super(message);
        }
    }

    private static final class ApiConflictException extends RuntimeException {
        private ApiConflictException(String message) {
            super(message);
        }
    }
}
