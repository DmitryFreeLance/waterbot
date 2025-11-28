// src/main/java/com/example/waterbot/WaterBotApplication.java
package com.example.waterbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class WaterBotApplication {

    private static final Logger log = LoggerFactory.getLogger(WaterBotApplication.class);

    public static void main(String[] args) {
        String token = Config.env("BOT_TOKEN", "8191373714:AAEL9MtEaMfDOIRib65DJM7QAcyBWz4vjF4");
        String username = Config.env("BOT_USERNAME", "ZhivayaVodaa_bot");
        String dbFile = Config.env("DB_FILE", "bot.db");
        String mediaDir = Config.env("MEDIA_DIR", "media");
        long spamInterval = Config.envLong("CALLBACK_SPAM_INTERVAL_MS", 2000L);

        if (token.equals("YOUR_TELEGRAM_BOT_TOKEN")) {
            log.warn("BOT_TOKEN не задан! Установите реальный токен через переменные окружения.");
        }

        try (Database db = new Database(dbFile)) {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

            WaterBot bot = new WaterBot(token, username, mediaDir, db, spamInterval);
            botsApi.registerBot(bot);

            log.info("WaterBot запущен. Username: @{}, DB: {}, MEDIA_DIR: {}",
                    username, dbFile, mediaDir);
        } catch (TelegramApiException e) {
            log.error("Ошибка запуска Telegram бота", e);
        }
    }
}