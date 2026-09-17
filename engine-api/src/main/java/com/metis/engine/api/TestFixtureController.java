package com.metis.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.Action;
import com.metis.engine.core.ActionResult;
import com.metis.engine.core.AuthoritativeGameEngine;
import com.metis.engine.core.BoardState;
import com.metis.engine.core.GameDefinition;
import com.metis.engine.core.GameSetup;
import com.metis.engine.core.GameState;
import com.metis.engine.core.Player;
import com.metis.engine.core.UnitDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Loads a stable, isolated set of sessions for repeatable automated testing. */
@RestController
@RequestMapping("/api/v1/test-fixtures")
public class TestFixtureController {

    private static final List<FixtureSpec> FIXTURES = buildFixtures();

    private final BattleRepository battle;
    private final BattleTemplateRepository templates;
    private final DefinitionRepository definitions;
    private final ObjectMapper objectMapper;
    private final AuthoritativeGameEngine engine = new AuthoritativeGameEngine(bound -> 0);
    private final String engineVersion;
    private final String apiVersion;
    private final int eventSchemaVersion;

    public TestFixtureController(BattleRepository battle, BattleTemplateRepository templates,
                                 DefinitionRepository definitions,
                                 ObjectMapper objectMapper,
                                 @Value("${metis.engine.version:0.1.0-SNAPSHOT}") String engineVersion,
                                 @Value("${metis.api.version:v1}") String apiVersion,
                                 @Value("${metis.event.schema-version:1}") int eventSchemaVersion) {
        this.battle = battle;
        this.templates = templates;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
        this.engineVersion = engineVersion;
        this.apiVersion = apiVersion;
        this.eventSchemaVersion = eventSchemaVersion;
    }

    @PostMapping("/games/{gameId}")
    @Transactional
    public ResponseEntity<TestFixtureResponse> load(@PathVariable long gameId,
                                                     @RequestBody(required = false) LoadRequest request) {
        List<LoadedFixture> loaded = new ArrayList<>();
        for (FixtureSpec fixture : FIXTURES) {
            BattleRepository.BattleSession existing = battle.findSessions(gameId, null).stream()
                    .filter(session -> fixture.name().equals(session.sessionName()))
                    .findFirst().orElse(null);
            BattleRepository.BattleSessionDetail session = existing == null
                    ? createFixture(gameId, fixture)
                    : battle.findSession(existing.sessionId()).orElseThrow();
            BattleTemplateRepository.CanonicalSetup setup = templates.findCanonicalSetup(requiredVersion(session))
                    .orElseThrow(() -> new IllegalStateException("Canonical setup is unavailable for test fixture " + fixture.key()));
            templates.configureSession(session.sessionId(), setup);
            session = battle.findSession(session.sessionId()).orElseThrow();
            ensureTemplate(gameId, fixture, session, setup);
            loaded.add(new LoadedFixture(fixture.key(), fixture.ordinal(), session.sessionId(),
                    session.sessionName(), session.sessionCode(), session.status(),
                    URI.create("/api/v1/sessions/" + session.sessionId()).toString()));
        }
        return ResponseEntity.ok(new TestFixtureResponse("metis-authoritative-v1", gameId, loaded));
    }

    private void ensureTemplate(long gameId, FixtureSpec fixture, BattleRepository.BattleSessionDetail session,
                                BattleTemplateRepository.CanonicalSetup setup) {
        List<BattleRepository.SessionPlayer> players = battle.findPlayers(session.sessionId());
        List<BattleTemplateRepository.TemplatePlayer> templatePlayers = players.stream()
                .map(player -> new BattleTemplateRepository.TemplatePlayer(player.seatNumber(), player.displayName(),
                        player.playerKind(), player.factionId(), player.factionName(),
                        "Fleet " + player.seatNumber(), 0))
                .toList();
        long templateId = templates.upsertTemplate(new BattleTemplateRepository.TemplateSpec(
                gameId, fixture.key(), fixture.name(), "CPU versus CPU regression battle template",
                session.gameVersionId(), setup.gameModeId(), setup.scenarioId(), setup.fleetFormatId(),
                setup.boardCode(), setup.boardWidth(), setup.boardHeight(), setup.options(),
                templatePlayers, session.sessionId()));
        templates.assignSessionTemplate(session.sessionId(), templateId);
        templates.setSourceMetadata(session.sessionId(), "cpu-fixture-v1");
    }

    private BattleRepository.BattleSessionDetail createFixture(long gameId, FixtureSpec fixture) {
        long sessionId = battle.createSession(gameId, fixture.name(), null, null, null,
                "private", engineVersion, apiVersion, eventSchemaVersion);
        BattleRepository.BattleSessionDetail session = battle.findSession(sessionId).orElseThrow();
        BattleTemplateRepository.CanonicalSetup setup = templates.findCanonicalSetup(requiredVersion(session))
                .orElseThrow(() -> new IllegalStateException("Canonical setup is unavailable for test fixture " + fixture.key()));
        templates.configureSession(sessionId, setup);
        session = battle.findSession(sessionId).orElseThrow();
        GameDefinition definition = definitions.findEngineDefinition(requiredVersion(session)).orElseThrow(
                () -> new IllegalStateException("No published engine definition for test fixture " + fixture.key()));
        List<DefinitionRepository.Faction> factions = definitions.findFactions(definition.definitionVersionId());
        if (factions.size() < 2) {
            throw new IllegalStateException("At least two factions are required for test fixtures");
        }
        battle.createPlayer(sessionId, 1, factions.get(0).factionName(), "cpu", factions.get(0).factionId());
        battle.createPlayer(sessionId, 2, factions.get(1).factionName(), "cpu", factions.get(1).factionId());
        List<BattleRepository.SessionPlayer> players = battle.findPlayers(sessionId);
        if (setup.boardDefinitionId() > 0) battle.createBoard(sessionId, setup.boardDefinitionId(), "primary", null);
        if (setup.fleetFormatId() > 0) {
            for (BattleRepository.SessionPlayer player : players) {
                battle.createFleet(player.sessionPlayerId(), setup.fleetFormatId(),
                        "Fleet " + player.seatNumber(), setup.pointsLimit());
            }
        }
        startFixture(sessionId, fixture, definition, setup);
        return battle.findSession(sessionId).orElseThrow();
    }

    private void startFixture(long sessionId, FixtureSpec fixture, GameDefinition definition,
                              BattleTemplateRepository.CanonicalSetup setup) {
        List<BattleRepository.SessionPlayer> players = battle.findPlayers(sessionId);
        List<Player> enginePlayers = players.stream()
                .sorted(Comparator.comparingInt(BattleRepository.SessionPlayer::seatNumber))
                .map(player -> new Player(player.sessionPlayerId(), player.seatNumber(), player.playerKind(),
                        player.factionName()))
                .toList();
        GameSetup gameSetup = new GameSetup(2, 2, enginePlayers,
                new BoardState(setup.boardCode(), setup.boardWidth(), setup.boardHeight()), setup.options());
        GameState state = engine.createInitialState(sessionId, definition, gameSetup);
        state = applyAndPersist(definition, state, new Action("start_game", 0, Map.of()));
        for (int index = 0; index < enginePlayers.size(); index++) {
            Player player = enginePlayers.get(index);
            UnitDefinition profile = definition.unitDefinitions().stream()
                    .filter(unit -> unit.factionCode().equals(player.factionCode()))
                    .filter(unit -> "ship".equalsIgnoreCase(unit.classification()))
                    .findFirst()
                    .orElseGet(() -> definition.unitDefinitions().stream()
                            .filter(unit -> unit.factionCode().equals(player.factionCode()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "No test unit is available for " + player.factionCode())));
            state = applyAndPersist(definition, state, new Action("deploy_unit", player.playerId(), Map.of(
                    "unitId", fixture.unitId(index), "unitCode", profile.code(),
                    "positionX", fixture.positionX(index), "positionY", fixture.positionY(index),
                    "facing", index == 0 ? 0 : 180)));
        }
        syncRelationalUnits(sessionId, state);
    }

    private void syncRelationalUnits(long sessionId, GameState state) {
        for (com.metis.engine.core.UnitState unit : state.units()) {
            try {
                long variantId = Long.parseLong(unit.unitCode());
                long fleetId = battle.findFleets(sessionId).stream()
                        .filter(fleet -> fleet.sessionPlayerId() == unit.playerId())
                        .findFirst().map(BattleRepository.FleetSummary::fleetId).orElse(0L);
                if (fleetId > 0) {
                    String stateJson = objectMapper.writeValueAsString(Map.of(
                            "engineUnitId", unit.unitId(), "unit", unit));
                    battle.createUnit(fleetId, variantId,
                            battle.findUnitVariantName(variantId).orElse(unit.unitCode()),
                            java.math.BigDecimal.valueOf(unit.positionX()),
                            java.math.BigDecimal.valueOf(unit.positionY()),
                            java.math.BigDecimal.valueOf(unit.facing()), unit.status(), stateJson);
                }
            } catch (NumberFormatException | JsonProcessingException ignored) {
                // Test doubles use symbolic unit codes and do not have catalog rows.
            }
        }
    }

    private GameState applyAndPersist(GameDefinition definition, GameState state, Action action) {
        ActionResult result = engine.applyAction(definition, state, action);
        if (!result.accepted()) {
            throw new IllegalStateException("Test fixture action failed: " + result.code());
        }
        GameState next = result.resultingState();
        com.metis.engine.core.GameEvent event = next.events().getLast();
        try {
            battle.saveEngineState(next.sessionId(), state.stateVersion(), next.status(), next.round(), null,
                    objectMapper.writeValueAsString(next), event.type(), event.actorPlayerId(),
                    objectMapper.writeValueAsString(event.payload()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize test fixture state", exception);
        }
        return next;
    }

    private static long requiredVersion(BattleRepository.BattleSessionDetail session) {
        if (session.gameVersionId() == null) {
            throw new IllegalStateException("Test fixture session has no active definition version");
        }
        return session.gameVersionId();
    }

    private static List<FixtureSpec> buildFixtures() {
        List<FixtureSpec> fixtures = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 10; ordinal++) {
            fixtures.add(new FixtureSpec(ordinal, String.format("metis-test-game-%02d", ordinal),
                    String.format("METIS-TEST-GAME-%02d", ordinal)));
        }
        return List.copyOf(fixtures);
    }

    public record LoadRequest(Boolean resetExisting) {}

    public record TestFixtureResponse(String fixtureSet, long gameId, List<LoadedFixture> games) {}

    public record LoadedFixture(String fixtureKey, int ordinal, long sessionId,
                                String sessionName, String sessionCode, String status,
                                String resource) {}

    private record FixtureSpec(int ordinal, String key, String name) {
        private String boardCode() {
            return "standard_6x3";
        }

        private long unitId(int playerIndex) {
            return ordinal * 10L + playerIndex + 1;
        }

        private double positionX(int playerIndex) {
            return playerIndex == 0 ? 200 + ordinal * 10 : 1600 - ordinal * 10;
        }

        private double positionY(int playerIndex) {
            return playerIndex == 0 ? 200 + ordinal * 15 : 700 - ordinal * 15;
        }
    }
}
