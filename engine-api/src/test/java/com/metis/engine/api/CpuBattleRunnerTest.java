package com.metis.engine.api;

import com.metis.engine.core.BoardState;
import com.metis.engine.core.GameDefinition;
import com.metis.engine.core.GameState;
import com.metis.engine.core.Player;
import com.metis.engine.core.UnitDefinition;
import com.metis.engine.core.UnitState;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuBattleRunnerTest {

    @Test
    void placesCpuFleetsOnOppositeLongEdges() {
        BoardState board = new BoardState("standard", 1800, 900);

        assertEquals(180, CpuBattleRunner.deploymentX(board, 0));
        assertEquals(1620, CpuBattleRunner.deploymentX(board, 1));
    }

    @Test
    void spreadsCpuFleetAcrossDeploymentRowsAndDepthLanes() {
        BoardState board = new BoardState("standard", 1800, 900);

        assertNotEquals(CpuBattleRunner.deploymentY(board, 0), CpuBattleRunner.deploymentY(board, 1));
        assertNotEquals(CpuBattleRunner.deploymentX(board, 0, 0), CpuBattleRunner.deploymentX(board, 0, 4));
        assertTrue(CpuBattleRunner.deploymentX(board, 0, 4) > CpuBattleRunner.deploymentX(board, 0, 0));
        assertTrue(CpuBattleRunner.deploymentX(board, 1, 4) < CpuBattleRunner.deploymentX(board, 1, 0));
        assertEquals(200, CpuBattleRunner.deploymentX(board, 0, 4)
                - CpuBattleRunner.deploymentX(board, 0, 0));
    }

    @Test
    void calculatesDistinctApproachLanesAndFacing() {
        BoardState board = new BoardState("standard", 1800, 900);
        UnitState target = new UnitState(20, 2, "target", 1500, 450, 180, "ready");

        assertNotEquals(CpuBattleRunner.approachY(board, target, 1),
                CpuBattleRunner.approachY(board, target, 2));
        assertEquals(0, CpuBattleRunner.movementFacing(0, 0, 100, 0));
        assertEquals(90, CpuBattleRunner.movementFacing(0, 0, 0, 100));
    }

    @Test
    void choosesAnUnusedProfileInsteadOfAlwaysUsingTheFirstDefinition() {
        List<UnitDefinition> profiles = List.of(
                new UnitDefinition("first", "rebel", 100),
                new UnitDefinition("second", "rebel", 100));
        List<UnitState> deployed = List.of(new UnitState(1, 10, "first", 0, 0, 0, "ready"));

        UnitDefinition selected = CpuBattleRunner.chooseDeploymentProfile(profiles, deployed, 10,
                bound -> bound - 1);

        assertEquals("second", selected.code());
    }

    @Test
    void honorsConfiguredShipAndSquadronLimits() {
        Player player = new Player(10, 1, "cpu", "rebel");
        GameDefinition definition = new GameDefinition(1, "test", "Test", 1, "v1", 1, 2, 0, 2,
                List.of("rebel"), List.of(
                        new UnitDefinition("ship-a", "rebel", 100, 100, 1, 5, 1, 0, 0, "Ship A", "Ship"),
                        new UnitDefinition("ship-b", "rebel", 100, 100, 1, 5, 1, 0, 0, "Ship B", "Ship"),
                        new UnitDefinition("squadron-a", "rebel", 100, 100, 1, 5, 1, 0, 0, "Squadron A", "Squadron")));
        GameState state = new GameState(1, 1, "running", 0, "deployment", null, 0,
                List.of(player), List.of(
                        new UnitState(1, 10, "ship-a", 0, 0, 0, "ready"),
                        new UnitState(2, 10, "ship-b", 0, 0, 0, "ready"),
                        new UnitState(3, 10, "ship-a", 0, 0, 0, "ready")),
                new BoardState("standard", 1800, 900), List.of(),
                Map.of("maximumShipsPerPlayer", 3, "maximumSquadronsPerPlayer", 1));

        assertEquals(List.of("squadron-a"), CpuBattleRunner.deploymentCandidates(definition, state, player)
                .stream().map(UnitDefinition::code).toList());
    }
}
