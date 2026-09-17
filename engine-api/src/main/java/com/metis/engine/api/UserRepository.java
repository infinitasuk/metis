package com.metis.engine.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MetisUser> findActiveUsers() {
        return jdbc.query("SELECT user_id, username, display_name, role_code "
                        + "FROM game_instance.app_user WHERE active ORDER BY user_id",
                (rs, rowNum) -> new MetisUser(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role_code")));
    }

    public Optional<MetisUser> findActive(long userId) {
        return jdbc.query("SELECT user_id, username, display_name, role_code "
                        + "FROM game_instance.app_user WHERE user_id = ? AND active",
                rs -> rs.next()
                        ? Optional.of(new MetisUser(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role_code")))
                        : Optional.empty(), userId);
    }
}
