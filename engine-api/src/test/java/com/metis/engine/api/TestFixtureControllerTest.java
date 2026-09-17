package com.metis.engine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.GameDefinition;
import com.metis.engine.core.UnitDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFixtureControllerTest {

    @Test
    void loadsTheSameTenNamedGamesAndPreservesUnrelatedSessions() {
        FakeBattleRepository battle = new FakeBattleRepository();
        FakeTemplateRepository templates = new FakeTemplateRepository();
        battle.createSession(7, "ordinary-session", null, null, null,
                "public", "test", "v1", 1);
        TestFixtureController controller = new TestFixtureController(battle, templates,
                new FakeDefinitionRepository(), new ObjectMapper(), "test", "v1", 1);

        TestFixtureController.TestFixtureResponse first = controller.load(7, null).getBody();
        List<Long> firstIds = first.games().stream().map(TestFixtureController.LoadedFixture::sessionId).toList();
        TestFixtureController.TestFixtureResponse preserved = controller.load(7,
                new TestFixtureController.LoadRequest(false)).getBody();
        List<Long> preservedIds = preserved.games().stream()
                .map(TestFixtureController.LoadedFixture::sessionId).toList();

        assertEquals(10, first.games().size());
        assertEquals(10, templates.specs.size());
        assertTrue(templates.specs.stream().flatMap(spec -> spec.players().stream())
                .allMatch(player -> "cpu".equals(player.playerKind())));
        assertTrue(battle.players.values().stream().flatMap(List::stream)
                .allMatch(player -> "cpu".equals(player.playerKind())));
        assertEquals(firstIds, preservedIds);
        assertEquals(10, first.games().stream()
                .filter(game -> "running".equals(game.status())).count());
        assertEquals(List.of(
                "METIS-TEST-GAME-01", "METIS-TEST-GAME-02", "METIS-TEST-GAME-03",
                "METIS-TEST-GAME-04", "METIS-TEST-GAME-05", "METIS-TEST-GAME-06",
                "METIS-TEST-GAME-07", "METIS-TEST-GAME-08", "METIS-TEST-GAME-09",
                "METIS-TEST-GAME-10"), first.games().stream()
                .map(TestFixtureController.LoadedFixture::sessionName).toList());
        assertEquals(11, battle.sessions.size());

        TestFixtureController.TestFixtureResponse reset = controller.load(7, null).getBody();
        List<Long> resetIds = reset.games().stream()
                .map(TestFixtureController.LoadedFixture::sessionId).toList();

        assertEquals(10, reset.games().size());
        assertEquals(firstIds, resetIds);
        assertTrue(battle.sessions.values().stream()
                .anyMatch(session -> "ordinary-session".equals(session.sessionName())));
        assertEquals(11, battle.sessions.size());
    }

    private static final class FakeDefinitionRepository extends DefinitionRepository {
        private final GameDefinition definition = new GameDefinition(7, "test", "Test", 1,
                "v1", 2, 2, 2, 2, List.of("rebel", "empire"), List.of(
                new UnitDefinition("rebel-ship", "rebel", 3, 300, 1, 5),
                new UnitDefinition("empire-ship", "empire", 3, 300, 1, 5)));

        private FakeDefinitionRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public Optional<GameDefinition> findEngineDefinition(long definitionId) {
            return definitionId == 1 ? Optional.of(definition) : Optional.empty();
        }

        @Override
        public List<Faction> findFactions(long definitionId) {
            return List.of(new Faction(101, "rebel"), new Faction(102, "empire"));
        }
    }

    private static final class FakeTemplateRepository extends BattleTemplateRepository {
        private final List<TemplateSpec> specs = new ArrayList<>();

        private FakeTemplateRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public long upsertTemplate(TemplateSpec spec) {
            specs.removeIf(existing -> existing.key().equals(spec.key()));
            specs.add(spec);
            return spec.key().hashCode();
        }

        @Override
        public void assignSessionTemplate(long sessionId, long templateId) {
        }

        @Override
        public java.util.Optional<CanonicalSetup> findCanonicalSetup(long gameVersionId) {
            return java.util.Optional.of(new CanonicalSetup(0, 0, 0, 0,
                    "test-board", 1800, 900, 0, Map.of("maximumRounds", 6)));
        }

        @Override
        public void configureSession(long sessionId, CanonicalSetup setup) {
        }

        @Override
        public void setSourceMetadata(long sessionId, String aiVersion) {
        }
    }

    private static final class FakeBattleRepository extends BattleRepository {
        private final Map<Long, BattleSessionDetail> sessions = new LinkedHashMap<>();
        private final Map<Long, List<SessionPlayer>> players = new LinkedHashMap<>();
        private long nextSessionId = 100;
        private long nextPlayerId = 1000;

        private FakeBattleRepository() {
            super(null);
        }

        @Override
        public List<BattleSession> findSessions(Long gameId, String status) {
            return sessions.values().stream()
                    .filter(session -> gameId == null || session.gameId() == gameId)
                    .filter(session -> status == null || status.equals(session.status()))
                    .map(session -> new BattleSession(session.sessionId(), session.gameId(), session.gameName(),
                            session.sessionName(), session.sessionCode(), session.visibility(), null, null, null, null, 0,
                            session.status(),
                            session.currentRound(), players.getOrDefault(session.sessionId(), List.of()).size(),
                            session.createdAt(), session.updatedAt()))
                    .toList();
        }

        @Override
        public Optional<BattleSessionDetail> findSession(long sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public long createSession(long gameId, String name, Long gameModeId, Long scenarioId,
                                  Long fleetFormatId, String visibility, String engineVersion,
                                  String apiVersion, int eventSchemaVersion) {
            long sessionId = nextSessionId++;
            sessions.put(sessionId, detail(sessionId, gameId, name, "setup", "{}", 0));
            players.put(sessionId, new ArrayList<>());
            return sessionId;
        }

        @Override
        public long createPlayer(long sessionId, int seatNumber, String displayName,
                                 String playerKind, Long factionId) {
            long playerId = nextPlayerId++;
            String faction = factionId == 101 ? "rebel" : "empire";
            players.get(sessionId).add(new SessionPlayer(playerId, sessionId, seatNumber,
                    displayName, playerKind, factionId, faction, false, "{}"));
            return playerId;
        }

        @Override
        public void saveEngineState(long sessionId, long expectedStateVersion, String status,
                                    int currentRound, Long currentPhaseId, String stateJson,
                                    String eventType, Long actorPlayerId, String payloadJson) {
            BattleSessionDetail old = sessions.get(sessionId);
            sessions.put(sessionId, new BattleSessionDetail(old.sessionId(), old.gameId(), old.gameName(),
                    old.gameVersionId(), old.gameVersionCode(), old.gameModeId(), old.scenarioId(),
                    old.fleetFormatId(), old.sessionName(), old.sessionCode(), old.visibility(), old.ownerUserId(),
                    null, null, null, 0, status,
                    currentRound, currentPhaseId, stateJson, expectedStateVersion + 1,
                    old.engineVersion(), old.apiVersion(), old.eventSchemaVersion(), old.createdAt(), old.updatedAt()));
        }

        @Override
        public List<SessionPlayer> findPlayers(long sessionId) {
            return players.getOrDefault(sessionId, List.of());
        }

        @Override
        public void deleteSession(long sessionId) {
            sessions.remove(sessionId);
            players.remove(sessionId);
        }

        private BattleSessionDetail detail(long sessionId, long gameId, String name,
                                           String status, String state, long stateVersion) {
            return new BattleSessionDetail(sessionId, gameId, "Test", 1L, "v1", null, null,
                    null, name, "SIM-" + sessionId, "private", null, null, null, null, 0, status, 0, null, state,
                    stateVersion, "test", "v1", 1, null, null);
        }
    }
}
