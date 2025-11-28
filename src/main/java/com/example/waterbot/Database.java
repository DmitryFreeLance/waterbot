package com.example.waterbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final String url;

    public Database(String dbFile) {
        this.url = "jdbc:sqlite:" + dbFile;
        init();
    }

    private void init() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    chat_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_start_at INTEGER,
                    last_start_at INTEGER,
                    is_blocked INTEGER DEFAULT 0
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS callback_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id INTEGER NOT NULL,
                    callback_data TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
            """);

            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_callback_chat_data
                ON callback_log (chat_id, callback_data, created_at);
            """);

            // Кэш file_id для медиа (фото/видео и т.п.)
            st.execute("""
                CREATE TABLE IF NOT EXISTS media_cache (
                    media_key TEXT PRIMARY KEY,
                    file_id TEXT NOT NULL
                );
            """);

            log.info("SQLite schema initialized");
        } catch (SQLException e) {
            log.error("Failed to init database", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /**
     * Проверка: запускал ли пользователь /start хотя бы раз.
     */
    public boolean hasUserStarted(long chatId) {
        String sql = "SELECT 1 FROM users WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Error in hasUserStarted", e);
            return false;
        }
    }

    /**
     * Обновление/создание записи о запуске /start.
     */
    public void saveStart(long chatId, String username) {
        long now = System.currentTimeMillis();

        if (hasUserStarted(chatId)) {
            String update = "UPDATE users SET username = ?, last_start_at = ? WHERE chat_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(update)) {

                ps.setString(1, username);
                ps.setLong(2, now);
                ps.setLong(3, chatId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Error updating user start", e);
            }
        } else {
            String insert = """
                INSERT INTO users (chat_id, username, first_start_at, last_start_at, is_blocked)
                VALUES (?, ?, ?, ?, 0)
            """;
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(insert)) {

                ps.setLong(1, chatId);
                ps.setString(2, username);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Error inserting user start", e);
            }
        }
    }

    /**
     * Время последнего /start (для защиты от "двойного" старта).
     */
    public Long getLastStartAt(long chatId) {
        String sql = "SELECT last_start_at FROM users WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("Error in getLastStartAt", e);
        }
        return null;
    }

    /**
     * Проверка спама по коллбэкам: слишком частые повторные нажатия.
     *
     * @return true, если это спам и контент лучше не отправлять.
     */
    public boolean isCallbackSpam(long chatId, String callbackData,
                                  long nowMillis, long minIntervalMillis) {
        String sql = """
            SELECT created_at
            FROM callback_log
            WHERE chat_id = ? AND callback_data = ?
            ORDER BY created_at DESC
            LIMIT 1
        """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, chatId);
            ps.setString(2, callbackData);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long last = rs.getLong(1);
                    return (nowMillis - last) < minIntervalMillis;
                }
                return false;
            }
        } catch (SQLException e) {
            log.error("Error in isCallbackSpam", e);
            return false;
        }
    }

    /**
     * Логирование нажатия callback‑кнопки.
     */
    public void saveCallbackUsage(long chatId, String callbackData, long nowMillis) {
        String sql = "INSERT INTO callback_log (chat_id, callback_data, created_at) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, chatId);
            ps.setString(2, callbackData);
            ps.setLong(3, nowMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error in saveCallbackUsage", e);
        }
    }

    /**
     * Получить file_id по ключу медиа.
     */
    public String getMediaFileId(String mediaKey) {
        String sql = "SELECT file_id FROM media_cache WHERE media_key = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, mediaKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.error("Error in getMediaFileId", e);
        }
        return null;
    }

    /**
     * Сохранить/обновить file_id по ключу медиа.
     */
    public void saveMediaFileId(String mediaKey, String fileId) {
        String sql = """
            INSERT INTO media_cache (media_key, file_id)
            VALUES (?, ?)
            ON CONFLICT(media_key) DO UPDATE SET file_id = excluded.file_id
        """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, mediaKey);
            ps.setString(2, fileId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error in saveMediaFileId", e);
        }
    }

    @Override
    public void close() {
        // Ничего закрывать не нужно, соединения берём по месту
    }
}