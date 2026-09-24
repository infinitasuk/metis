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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads a stable, isolated set of sessions for repeatable automated testing. */
@RestController
@RequestMapping("/api/v1/test-fixtures")
public class TestFixtureController {

    private static final List<FixtureSpec> FIXTURES = buildFixtures();
    private static final List<FixtureSpec> CPU_SUITE = buildCpuSuite();

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
        return loadFixtures(gameId, FIXTURES);
    }

    @PostMapping("/suites/{gameId}")
    @Transactional
    public ResponseEntity<TestFixtureResponse> loadCpuSuite(@PathVariable long gameId) {
        return loadFixtures(gameId, CPU_SUITE);
    }

    @org.springframework.web.bind.annotation.GetMapping("/suites/{gameId}")
    public List<BattleRepository.BattleSession> cpuSuite(@PathVariable long gameId) {
        return battle.findSessions(gameId, null).stream()
                .filter(session -> session.templateName() != null && session.templateName().startsWith("CPU SUITE /"))
                .toList();
    }

    private ResponseEntity<TestFixtureResponse> loadFixtures(long gameId, List<FixtureSpec> fixtures) {
        List<LoadedFixture> loaded = new ArrayList<>();
        for (FixtureSpec fixture : fixtures) {
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
                gameId, fixture.key(), fixture.name(), fixture.description(),
                session.gameVersionId(), setup.gameModeId(), setup.scenarioId(), setup.fleetFormatId(),
                setup.boardCode(), setup.boardWidth(), setup.boardHeight(), fixture.options(setup.options()),
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
        DefinitionRepository.Faction first = faction(factions, fixture.factionOne(), 0);
        DefinitionRepository.Faction second = faction(factions, fixture.factionTwo(), 1);
        battle.createPlayer(sessionId, 1, first.factionName(), "cpu", first.factionId());
        battle.createPlayer(sessionId, 2, second.factionName(), "cpu", second.factionId());
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
                new BoardState(setup.boardCode(), setup.boardWidth(), setup.boardHeight()), fixture.options(setup.options()));
        definitions.captureSessionDefinition(sessionId, definition);
        GameState state = engine.createInitialState(sessionId, definition, gameSetup);
        state = applyAndPersist(definition, state, new Action("start_game", 0, Map.of()));
        for (int index = 0; index < enginePlayers.size(); index++) {
            Player player = enginePlayers.get(index);
            List<UnitDefinition> profiles = selectProfiles(definition, player.factionCode(), fixture.loadout());
            for (int unitIndex = 0; unitIndex < profiles.size(); unitIndex++) {
                UnitDefinition profile = profiles.get(unitIndex);
                state = applyAndPersist(definition, state, new Action("deploy_unit", player.playerId(), Map.of(
                        "unitId", fixture.unitId(index, unitIndex), "unitCode", profile.code(),
                        "positionX", fixture.positionX(index, unitIndex), "positionY", fixture.positionY(index, unitIndex),
                        "facing", index == 0 ? 0 : 180)));
            }
        }
        syncRelationalUnits(sessionId, state);
    }

    private static DefinitionRepository.Faction faction(List<DefinitionRepository.Faction> factions,
                                                        String name, int fallbackIndex) {
        return name == null ? factions.get(fallbackIndex) : factions.stream()
                .filter(item -> name.equals(item.factionName())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Faction " + name + " is unavailable"));
    }

    private static List<UnitDefinition> selectProfiles(GameDefinition definition, String factionName,
                                                        Loadout loadout) {
        List<UnitDefinition> available = definition.unitDefinitions().stream()
                .filter(unit -> unit.factionCode().equals(factionName)).toList();
        List<UnitDefinition> ships = available.stream()
                .filter(unit -> "ship".equalsIgnoreCase(unit.classification())).limit(2).toList();
        List<UnitDefinition> squadrons = available.stream()
                .filter(unit -> "squadron".equalsIgnoreCase(unit.classification())).limit(2).toList();
        List<UnitDefinition> selected = switch (loadout) {
            case SINGLE -> ships.isEmpty() ? available.stream().limit(1).toList() : ships.stream().limit(1).toList();
            case CAPITAL -> ships;
            case SQUADRON -> squadrons;
            case MIXED -> java.util.stream.Stream.concat(ships.stream().limit(1), squadrons.stream().limit(1)).toList();
        };
        if (selected.isEmpty()) throw new IllegalStateException("No " + loadout + " units are available for " + factionName);
        return selected;
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
                    String.format("METIS-TEST-GAME-%02d", ordinal), null, null, Loadout.SINGLE,
                    "CPU versus CPU regression battle template"));
        }
        return List.copyOf(fixtures);
    }

    private static List<FixtureSpec> buildCpuSuite() {
        List<FixtureSpec> suite = new ArrayList<>();
        String[][] pairings = {
                {"Galactic Empire", "Rebel Alliance"},
                {"Galactic Republic", "Separatist Alliance"},
                {"Galactic Empire", "Galactic Republic"},
                {"Rebel Alliance", "Separatist Alliance"}
        };
        int ordinal = 101;
        for (String[] pairing : pairings) {
            for (Loadout loadout : List.of(Loadout.CAPITAL, Loadout.SQUADRON, Loadout.MIXED)) {
                String key = "cpu-suite-" + slug(pairing[0]) + "-vs-" + slug(pairing[1]) + "-" + loadout.name().toLowerCase();
                String name = "CPU SUITE / " + pairing[0] + " vs " + pairing[1] + " / " + loadout.name();
                suite.add(new FixtureSpec(ordinal++, key, name, pairing[0], pairing[1], loadout,
                        "CPU suite battle using " + loadout.name().toLowerCase() + " loadouts"));
            }
        }
        return List.copyOf(suite);
    }

    private static String slug(String value) {
        return value.toLowerCase().replace(" ", "-");
    }

    public record LoadRequest(Boolean resetExisting) {}

    public record TestFixtureResponse(String fixtureSet, long gameId, List<LoadedFixture> games) {}

    public record LoadedFixture(String fixtureKey, int ordinal, long sessionId,
                                String sessionName, String sessionCode, String status,
                                String resource) {}

    private enum Loadout { SINGLE, CAPITAL, SQUADRON, MIXED }

    private record FixtureSpec(int ordinal, String key, String name, String factionOne, String factionTwo,
                               Loadout loadout, String description) {
        private Map<String, Object> options(Map<String, Object> base) {
            Map<String, Object> options = new LinkedHashMap<>(base);
            options.put("loadoutProfile", loadout.name().toLowerCase());
            return options;
        }

        private String boardCode() {
            return "standard_6x3";
        }

        private long unitId(int playerIndex) {
            return unitId(playerIndex, 0);
        }

        private long unitId(int playerIndex, int unitIndex) {
            return ordinal * 1000L + playerIndex * 100L + unitIndex + 1;
        }

        private double positionX(int playerIndex) {
            return positionX(playerIndex, 0);
        }

        private double positionX(int playerIndex, int unitIndex) {
            return playerIndex == 0 ? 200 + ordinal * 2 + unitIndex * 80 : 1600 - ordinal * 2 - unitIndex * 80;
        }

        private double positionY(int playerIndex) {
            return positionY(playerIndex, 0);
        }

        private double positionY(int playerIndex, int unitIndex) {
            return playerIndex == 0 ? 200 + ordinal * 2 + unitIndex * 150 : 700 - ordinal * 2 - unitIndex * 150;
        }
    }
}
