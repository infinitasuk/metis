package com.metis.engine.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class RulesFleetPresetRepository {
    public record Preset(long id,long definitionId,int seat,String name,long version,String policy,String units,String snapshot) { }
    private static final RowMapper<Preset> PRESET=(rs,n)->new Preset(rs.getLong("preset_id"),rs.getLong("definition_id"),rs.getInt("source_seat"),
            rs.getString("preset_name"),rs.getLong("edit_version"),rs.getString("policy_code"),rs.getString("units_json"),rs.getString("snapshot_json"));
    private final JdbcTemplate jdbc;
    public RulesFleetPresetRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Preset> list(long owner) { return jdbc.query("SELECT * FROM game_instance.fn_rules_fleet_presets(?)",PRESET,owner); }
    public Optional<Preset> find(long owner,long id) { return jdbc.query("SELECT * FROM game_instance.fn_rules_fleet_preset_get(?,?)",PRESET,owner,id).stream().findFirst(); }
    public long create(long owner,long definition,int seat,String name,String policy,String units,String snapshot) {
        return jdbc.execute(connection->{
            var call=connection.prepareCall("{call game_instance.sp_rules_fleet_preset_create(?,?,?,?,?,?,?,?)}");
            call.setLong(1,owner);call.setLong(2,definition);call.setInt(3,seat);call.setString(4,name);call.setString(5,policy);
            call.setString(6,units);call.setString(7,snapshot);call.setNull(8,Types.BIGINT);call.registerOutParameter(8,Types.BIGINT);return call;
        },(org.springframework.jdbc.core.CallableStatementCallback<Long>)call->{call.execute();return call.getLong(8);});
    }
    public void rename(long owner,long id,long version,String name) { jdbc.update("CALL game_instance.sp_rules_fleet_preset_rename(?,?,?,?)",owner,id,version,name); }
    public void delete(long owner,long id,long version) { jdbc.update("CALL game_instance.sp_rules_fleet_preset_delete(?,?,?)",owner,id,version); }
}
