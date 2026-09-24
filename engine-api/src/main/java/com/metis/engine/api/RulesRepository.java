package com.metis.engine.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Types;
import java.util.*;

/** Persistence only. Records returned here contain authoritative/private data. */
@Repository
public class RulesRepository {
    private final JdbcTemplate jdbc;
    public RulesRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Definition(long id,String key,String revision,long version,String lifecycle,String source,String fingerprint,String compiler,String runtime,String modules,String resolved,String resolution) {
        public String executable() { return resolved==null?source:resolved; }
    }
    public record Session(UUID id,long definitionId,long owner,String visibility,String source,String fingerprint,String compiler,String runtime,
                          String initial,String checkpoint,long version,boolean paused,String resolution,int cpuLimit,int cpuSteps,String cpuError) { }
    public record Seat(String playerId,long userId,int number,String kind) { }
    public record SessionSummary(UUID id,long definitionId,String name,String status,boolean paused,long version,boolean canManage,String error) { }
    public record Receipt(String hash,String response,int status) { }
    public record Transition(long version,String commit,boolean paused) { }
    public record Catalogue(long id,long gameId,long sourceVersion,String pack,String mode,String name,long definitionId,String fingerprint,String snapshot,String scope) { }
    private static final RowMapper<Definition> DEFINITION=(rs,n)->new Definition(rs.getLong("definition_id"),rs.getString("definition_key"),rs.getString("revision"),rs.getLong("edit_version"),rs.getString("lifecycle"),rs.getString("source_json"),rs.getString("fingerprint"),rs.getString("compiler_version"),rs.getString("runtime_version"),rs.getString("modules_json"),rs.getString("resolved_json"),rs.getString("resolution_json"));
    private static final RowMapper<Session> SESSION=(rs,n)->new Session(rs.getObject("session_id",UUID.class),rs.getLong("definition_id"),rs.getLong("owner_user_id"),rs.getString("visibility"),rs.getString("captured_definition"),rs.getString("fingerprint"),rs.getString("compiler_version"),rs.getString("runtime_version"),rs.getString("initial_checkpoint"),rs.getString("checkpoint_json"),rs.getLong("state_version"),rs.getBoolean("paused"),rs.getString("captured_resolution"),rs.getInt("cpu_limit"),rs.getInt("cpu_steps"),rs.getString("cpu_error"));
    public List<Definition> definitions() { return jdbc.query("SELECT * FROM game_engine.fn_rules_definition_list()",DEFINITION); }
    public Optional<Definition> definition(long id,boolean lock) { return jdbc.query("SELECT * FROM game_engine."+(lock?"fn_rules_definition_lock":"fn_rules_definition_get")+"(?)",DEFINITION,id).stream().findFirst(); }
    public long createDefinition(String source,long user) {
        return jdbc.execute(connection->{var call=connection.prepareCall("{call game_engine.sp_rules_definition_create(?,?,?)}");call.setString(1,source);call.setLong(2,user);call.setNull(3,Types.BIGINT);call.registerOutParameter(3,Types.BIGINT);return call;},(org.springframework.jdbc.core.CallableStatementCallback<Long>)call->{call.execute();return call.getLong(3);});
    }
    public void updateDefinition(long id,long version,String source) { call("game_engine.sp_rules_definition_update",id,version,source); }
    public Optional<Definition> published(String key,String revision) { return jdbc.query("SELECT * FROM game_engine.fn_rules_definition_published(?,?)",DEFINITION,key,revision).stream().findFirst(); }
    public void publish(long id,long version,String source,String resolved,String fingerprint,String compiler,String runtime,String modules,String resolution) { call("game_engine.sp_rules_definition_publish_resolved",id,version,source,resolved,fingerprint,compiler,runtime,modules,resolution); }
    public Optional<Session> session(UUID id,boolean lock) { return jdbc.query("SELECT * FROM game_instance."+(lock?"fn_rules_session_lock":"fn_rules_session_get")+"(?)",SESSION,id).stream().findFirst(); }
    public List<Seat> seats(UUID id) { return jdbc.query("SELECT * FROM game_instance.fn_rules_seats(?)",(rs,n)->new Seat(rs.getString("player_id"),rs.getLong("user_id"),rs.getInt("seat_number"),rs.getString("kind")),id); }
    public boolean canView(UUID id,MetisUser user) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT game_instance.fn_rules_can_view(?,?,?)",Boolean.class,id,user.userId(),user.admin())); }
    public void createSession(UUID id,long definition,long owner,String visibility,String initial,String seats,String viewers,int cpuLimit) { call("game_instance.sp_rules_session_create_v2",id,definition,owner,visibility,initial,seats,viewers,cpuLimit); }
    public List<SessionSummary> sessions(MetisUser user) { return jdbc.query("SELECT * FROM game_instance.fn_rules_session_list(?,?)",(rs,n)->new SessionSummary(rs.getObject("session_id",UUID.class),rs.getLong("definition_id"),rs.getString("definition_key"),rs.getString("status"),rs.getBoolean("paused"),rs.getLong("state_version"),rs.getLong("owner_user_id")==user.userId(),rs.getString("cpu_error")),user.userId(),user.admin()); }
    public List<UUID> cpuQueue() { return jdbc.query("SELECT * FROM game_instance.fn_rules_cpu_queue()",(rs,n)->rs.getObject("session_id",UUID.class)); }
    public String workspaceStamp(UUID id,MetisUser user) { return jdbc.queryForObject("SELECT game_instance.fn_rules_workspace_stamp(?,?,?)",String.class,id,user.userId(),user.admin()); }
    public Optional<Session> claimCpu(UUID id) { return jdbc.query("SELECT * FROM game_instance.fn_rules_cpu_claim(?)",SESSION,id).stream().findFirst(); }
    public void cpuTouch(UUID id,int increment,boolean reset) { call("game_instance.sp_rules_cpu_touch",id,increment,reset); }
    public void quarantineCpu(UUID id,String error) { call("game_instance.sp_rules_cpu_quarantine",id,error); }
    public Optional<Receipt> receipt(UUID id,long user,String actor,String command) { return jdbc.query("SELECT * FROM game_instance.fn_rules_command_get(?,?,?,?)",(rs,n)->new Receipt(rs.getString("request_hash"),rs.getString("response_json"),rs.getInt("http_status")),id,user,actor,command).stream().findFirst(); }
    public void save(UUID id,long expected,String checkpoint,String commit,boolean paused,long user,String actor,String command,String hash,String response,int status) {
        call("game_instance.sp_rules_command_save",id,expected,checkpoint,commit,paused,user,actor,command,hash,response,status);
    }
    public List<Transition> transitions(UUID id,long through) { return jdbc.query("SELECT * FROM game_instance.fn_rules_transitions(?,?)",(rs,n)->new Transition(rs.getLong("state_version"),rs.getString("commit_json"),rs.getBoolean("paused")),id,through); }
    public void delete(UUID id) { call("game_instance.sp_rules_session_delete",id); }
    public List<Catalogue> catalogue() {
        return jdbc.query("SELECT * FROM game_engine.fn_rules_catalogue_list()",(rs,n)->new Catalogue(rs.getLong("entry_id"),rs.getLong("game_id"),rs.getLong("source_version_id"),rs.getString("pack_code"),rs.getString("mode_code"),rs.getString("entry_name"),rs.getLong("definition_id"),rs.getString("source_fingerprint"),rs.getString("source_snapshot"),rs.getString("scope_json")));
    }
    public boolean lockCatalogue(long version) { return jdbc.queryForObject("SELECT game_engine.fn_rules_catalogue_lock(?)",Long.class,version)!=null; }
    public String catalogueSource(long version,String names) { return jdbc.queryForObject("SELECT game_engine.fn_rules_catalogue_source(?,?)",String.class,version,names); }
    public void registerCatalogue(long version,String pack,String mode,String name,long definition,String fingerprint,String snapshot,String scope) {
        call("game_engine.sp_rules_catalogue_register",version,pack,mode,name,definition,fingerprint,snapshot,scope);
    }
    private void call(String procedure,Object...values) {
        jdbc.execute(connection->{
            var statement=connection.prepareCall("{call "+procedure+"("+String.join(",",Collections.nCopies(values.length,"?"))+")}");
            for(int i=0;i<values.length;i++) {
                Object value=values[i];if(value==null)statement.setNull(i+1,Types.VARCHAR);
                else if(value instanceof String text)statement.setString(i+1,text);else statement.setObject(i+1,value);
            }
            return statement;
        },(org.springframework.jdbc.core.CallableStatementCallback<Void>)statement->{statement.execute();return null;});
    }
}
