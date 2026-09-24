package com.metis.engine.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class FleetPresetRepository {

    private final JdbcTemplate jdbc;

    public FleetPresetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FleetPreset> findPresets(long ownerUserId, long gameId, Long factionId) {
        return queryPresets(" fp.owner_user_id = ? AND fp.game_id = ? "
                        + "AND (CAST(? AS bigint) IS NULL OR fp.faction_id = ?) ",
                ownerUserId, gameId, factionId, factionId);
    }

    public Optional<FleetPreset> findPreset(long ownerUserId, long presetId) {
        return queryPresets(" fp.owner_user_id = ? AND fp.fleet_preset_id = ? ", ownerUserId, presetId)
                .stream().findFirst();
    }

    @Transactional
    public long create(long ownerUserId, long gameId, long gameVersionId, Long fleetFormatId,
                       long factionId, String name, Integer pointsTotal, List<PresetUnitRequest> units) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A fleet name is required");
        if (units == null || units.isEmpty()) throw new IllegalArgumentException("A fleet needs at least one unit");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM game_engine.faction
                    WHERE faction_id = ? AND game_id = ? AND game_version_id = ?
                )
                """, Boolean.class, factionId, gameId, gameVersionId))) {
            throw new IllegalArgumentException("The selected faction is not part of this game definition");
        }
        Integer formatLimit = null;
        if (fleetFormatId != null) {
            formatLimit = jdbc.query("""
                    SELECT ff.points_limit
                    FROM game_engine.fleet_format ff
                    JOIN game_engine.game_mode gm ON gm.game_mode_id = ff.game_mode_id
                    WHERE ff.fleet_format_id = ? AND gm.game_id = ? AND gm.game_version_id = ?
                    """, rs -> rs.next() ? rs.getObject("points_limit", Integer.class) : null,
                    fleetFormatId, gameId, gameVersionId);
            if (formatLimit == null) throw new IllegalArgumentException("The selected fleet format is not part of this game definition");
        }

        int calculatedPoints = 0;
        for (PresetUnitRequest unit : units) {
            if (unit == null || unit.unitVariantId() <= 0 || unit.quantity() <= 0) {
                throw new IllegalArgumentException("Fleet units must have a positive variant and quantity");
            }
            Integer pointsCost = jdbc.query("""
                    SELECT uvp.points_cost
                    FROM game_engine.unit_variant_points uvp
                    JOIN game_engine.unit_variant uv ON uv.unit_variant_id = uvp.unit_variant_id
                    JOIN game_engine.unit_type ut ON ut.unit_type_id = uv.unit_type_id
                    JOIN game_engine.unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id
                    JOIN game_engine.faction f ON f.faction_id = uc.faction_id
                    WHERE uvp.game_version_id = ? AND uvp.unit_variant_id = ? AND f.faction_id = ?
                    """, rs -> rs.next() ? rs.getInt("points_cost") : null,
                    gameVersionId, unit.unitVariantId(), factionId);
            if (pointsCost == null) throw new IllegalArgumentException("A fleet unit has no point cost in this game definition");
            calculatedPoints += pointsCost * unit.quantity();
            normalizedLayout(unit.layoutX());
            normalizedLayout(unit.layoutY());
        }
        if (formatLimit != null && calculatedPoints > formatLimit) {
            throw new IllegalArgumentException("Fleet exceeds the " + formatLimit + " point limit");
        }

        long presetId = jdbc.queryForObject("""
                INSERT INTO game_instance.fleet_preset
                    (owner_user_id, game_id, game_version_id, fleet_format_id, faction_id,
                     preset_name, points_total)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING fleet_preset_id
                """, Long.class, ownerUserId, gameId, gameVersionId, fleetFormatId, factionId,
                name.trim(), calculatedPoints);

        for (PresetUnitRequest unit : units) {
            double layoutX = normalizedLayout(unit.layoutX());
            double layoutY = normalizedLayout(unit.layoutY());
            for (int quantity = 0; quantity < unit.quantity(); quantity++) {
                int inserted = jdbc.update("""
                        INSERT INTO game_instance.fleet_preset_unit
                            (fleet_preset_id, unit_variant_id, unit_name, quantity, layout_x, layout_y)
                        SELECT ?, uv.unit_variant_id, uv.unit_variant_name, 1, ?, ?
                        FROM game_engine.unit_variant uv
                        JOIN game_engine.unit_type ut ON ut.unit_type_id = uv.unit_type_id
                        JOIN game_engine.unit_classification uc
                            ON uc.unit_classification_id = ut.unit_classification_id
                        JOIN game_engine.faction f ON f.faction_id = uc.faction_id
                        WHERE uv.unit_variant_id = ?
                          AND f.faction_id = ?
                          AND f.game_id = ?
                          AND f.game_version_id = ?
                        """, presetId, Math.min(100, layoutX + quantity * 2), Math.min(100, layoutY + quantity * 2),
                        unit.unitVariantId(), factionId, gameId, gameVersionId);
                if (inserted != 1) throw new IllegalArgumentException("A fleet unit is not valid for the selected faction");
            }
        }
        return presetId;
    }

    @Transactional
    public void delete(long ownerUserId, long presetId) {
        int deleted = jdbc.update("DELETE FROM game_instance.fleet_preset WHERE fleet_preset_id = ? AND owner_user_id = ?",
                presetId, ownerUserId);
        if (deleted == 0) throw new IllegalArgumentException("Fleet preset does not exist");
    }

    private List<FleetPreset> queryPresets(String filter, Object... args) {
        Map<Long, PresetBuilder> presets = new LinkedHashMap<>();
        jdbc.query("""
                SELECT fp.fleet_preset_id, fp.game_id, fp.game_version_id, fp.fleet_format_id,
                       fp.faction_id, f.faction_name, fp.preset_name, fp.points_total,
                       fpu.unit_variant_id, fpu.unit_name, fpu.quantity,
                       fpu.layout_x, fpu.layout_y
                FROM game_instance.fleet_preset fp
                JOIN game_engine.faction f ON f.faction_id = fp.faction_id
                LEFT JOIN game_instance.fleet_preset_unit fpu
                    ON fpu.fleet_preset_id = fp.fleet_preset_id
                WHERE """ + filter + """
                ORDER BY fp.updated_at DESC, fp.fleet_preset_id DESC, fpu.unit_variant_id
                """, (rs, rowNum) -> {
            long presetId = rs.getLong("fleet_preset_id");
            PresetBuilder preset = presets.get(presetId);
            if (preset == null) {
                preset = new PresetBuilder(presetId, rs.getLong("game_id"), rs.getLong("game_version_id"),
                        rs.getObject("fleet_format_id", Long.class), rs.getLong("faction_id"),
                        rs.getString("faction_name"), rs.getString("preset_name"),
                        rs.getObject("points_total", Integer.class));
                presets.put(presetId, preset);
            }
            Long variantId = rs.getObject("unit_variant_id", Long.class);
            if (variantId != null) preset.units.add(new FleetPresetUnit(variantId,
                    rs.getString("unit_name"), rs.getInt("quantity"),
                    rs.getDouble("layout_x"), rs.getDouble("layout_y")));
            return null;
        }, args);
        return presets.values().stream().map(PresetBuilder::build).toList();
    }

    private static final class PresetBuilder {
        private final long presetId;
        private final long gameId;
        private final long gameVersionId;
        private final Long fleetFormatId;
        private final long factionId;
        private final String factionName;
        private final String presetName;
        private final Integer pointsTotal;
        private final List<FleetPresetUnit> units = new ArrayList<>();

        private PresetBuilder(long presetId, long gameId, long gameVersionId, Long fleetFormatId,
                              long factionId, String factionName, String presetName, Integer pointsTotal) {
            this.presetId = presetId;
            this.gameId = gameId;
            this.gameVersionId = gameVersionId;
            this.fleetFormatId = fleetFormatId;
            this.factionId = factionId;
            this.factionName = factionName;
            this.presetName = presetName;
            this.pointsTotal = pointsTotal;
        }

        private FleetPreset build() {
            return new FleetPreset(presetId, gameId, gameVersionId, fleetFormatId, factionId,
                    factionName, presetName, pointsTotal, List.copyOf(units));
        }
    }

    public record PresetUnitRequest(long unitVariantId, int quantity, Double layoutX, Double layoutY) {}

    public record FleetPreset(long presetId, long gameId, long gameVersionId, Long fleetFormatId,
                              long factionId, String factionName, String presetName, Integer pointsTotal,
                              List<FleetPresetUnit> units) {}

    public record FleetPresetUnit(long unitVariantId, String unitName, int quantity,
                                  double layoutX, double layoutY) {}

    private static double normalizedLayout(Double value) {
        double normalized = value == null ? 50 : value;
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 100) {
            throw new IllegalArgumentException("Fleet unit layout must be between 0 and 100");
        }
        return normalized;
    }
}
