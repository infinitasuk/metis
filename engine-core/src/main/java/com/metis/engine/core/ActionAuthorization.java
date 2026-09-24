package com.metis.engine.core;

/** Shared turn ownership check; independent of phase names and game mechanics.
 * Reaction/simultaneous actions need their own explicit authorization policy.
 */
public final class ActionAuthorization {
    private ActionAuthorization() {}

    public static boolean isActivePlayer(GameState state, long actorPlayerId) {
        return state != null && state.activePlayerId() != null
                && state.activePlayerId() == actorPlayerId
                && state.players().stream().anyMatch(player -> player.playerId() == actorPlayerId);
    }
}
