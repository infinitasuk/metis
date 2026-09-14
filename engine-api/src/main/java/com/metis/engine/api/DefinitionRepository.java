package com.metis.engine.api;

import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.CallableStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class DefinitionRepository {

    private final JdbcTemplate jdbc;

    public DefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DefinitionVersion> findVersions(Long gameId) {
        return jdbc.query("SELECT * FROM game_engine.fn_definition_version_list(?)", (rs, rowNum) ->
                new DefinitionVersion(
                        rs.getLong("game_version_id"), rs.getLong("game_id"),
                        rs.getString("version_code"), rs.getString("version_name"),
                        rs.getString("lifecycle_status"), rs.getObject("release_date", LocalDate.class),
                        rs.getString("source_revision"), rs.getString("source_name"),
                        rs.getString("source_url"), rs.getString("published_at"),
                        rs.getString("created_at"), rs.getString("updated_at"),
                        rs.getBoolean("is_valid"), rs.getString("validation_errors")
                ), gameId);
    }

    public Optional<DefinitionVersion> findVersion(long definitionId) {
        return findVersions(null).stream()
                .filter(version -> version.definitionId() == definitionId)
                .findFirst();
    }

    public ValidationResult validate(long definitionId) {
        return jdbc.query("SELECT * FROM game_engine.fn_definition_version_validate(?)", rs -> rs.next()
                ? new ValidationResult(rs.getBoolean("is_valid"), rs.getString("errors"))
                : new ValidationResult(false, "[]"), definitionId);
    }

    public long createVersion(long gameId, String code, String name, String sourceRevision) {
        return jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(
                    "{call game_engine.sp_definition_version_create(?,?,?,?,?)}");
            statement.setLong(1, gameId);
            statement.setString(2, code);
            statement.setString(3, name);
            setNullableString(statement, 4, sourceRevision);
            statement.registerOutParameter(5, Types.BIGINT);
            return statement;
        }, (CallableStatementCallback<Long>) statement -> {
            statement.execute();
            return statement.getLong(5);
        });
    }

    public void updateVersion(long definitionId, String code, String name, String sourceRevision) {
        execute("{call game_engine.sp_definition_version_update(?,?,?,?)}", statement -> {
            statement.setLong(1, definitionId);
            statement.setString(2, code);
            statement.setString(3, name);
            setNullableString(statement, 4, sourceRevision);
        });
    }

    public void publishVersion(long definitionId) {
        execute("{call game_engine.sp_definition_version_publish(?)}",
                statement -> statement.setLong(1, definitionId));
    }

    public void retireVersion(long definitionId) {
        execute("{call game_engine.sp_definition_version_retire(?)}",
                statement -> statement.setLong(1, definitionId));
    }

    public void updateObject(long definitionId, long objectId, String name, String description,
                             String displayText, String data, String sourceReference) {
        jdbc.update("CALL game_engine.sp_definition_object_update(?,?,?,?,?,?,?)", definitionId, objectId,
                name, description, displayText, data, sourceReference);
    }

    private void execute(String call, StatementConfigurer configurer) {
        jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(call);
            configurer.configure(statement);
            return statement;
        }, (CallableStatementCallback<Void>) statement -> {
            statement.execute();
            return null;
        });
    }

    private static void setNullableString(CallableStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    @FunctionalInterface
    private interface StatementConfigurer {
        void configure(CallableStatement statement) throws java.sql.SQLException;
    }

    public record DefinitionVersion(long definitionId, long gameId, String code, String name,
                                    String status, LocalDate releaseDate, String sourceRevision,
                                    String sourceName, String sourceUrl, String publishedAt,
                                    String createdAt, String updatedAt, boolean valid,
                                    String validationErrors) {}

    public record ValidationResult(boolean valid, String errors) {}
}
