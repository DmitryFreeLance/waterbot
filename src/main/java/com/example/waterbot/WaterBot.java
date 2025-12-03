package com.example.waterbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class WaterBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(WaterBot.class);

    // лимиты Telegram
    private static final int MAX_MESSAGE_LENGTH = 4000;  // 4096 - небольшой запас
    private static final int MAX_CAPTION_LENGTH = 1024;

    // задержка между отправками сообщений (п.4 – ~2 секунды)
    private static final long MESSAGE_DELAY_MS = 2000L;

    // callback data
    private static final String CB_ADMIN_PANEL = "MENU_ADMIN_PANEL";
    private static final String CB_FULL_CLEANSE = "MENU_10_FULL_CLEANSE";
    private static final String CB_WATER_FACTS = "MENU_1_WATER_FACTS";
    private static final String CB_46_REASONS = "MENU_2_46_REASONS";
    private static final String CB_DEHYDRATION = "MENU_3_DEHYDRATION";
    private static final String CB_QUALITY_FULL = "MENU_4_QUALITY_FULL";
    private static final String CB_LIVE_WATER = "MENU_5_LIVE_WATER";
    private static final String CB_PROMO = "MENU_6_PROMO";
    private static final String CB_HEALTH_FORM = "MENU_8_HEALTH_FORM";
    private static final String CB_CONSULTATION = "MENU_9_CONSULTATION";
    private static final String CB_BACK_TO_MENU = "BACK_TO_MENU";

    private static final String CORAL_URL_HTML =
            "https://ru.coral.club/shop/koral-mayn-silver.html?offer=2200&amp;REF_CODE=365272872010";

    private final String botUsername;
    private final String mediaDir;
    private final Database database;
    private final long callbackSpamIntervalMs;
    private final Set<Long> adminIds;

    public WaterBot(String botToken,
                    String botUsername,
                    String mediaDir,
                    Database database,
                    long callbackSpamIntervalMs) {
        super(botToken);
        this.botUsername = botUsername;
        this.mediaDir = mediaDir;
        this.database = database;
        this.callbackSpamIntervalMs = callbackSpamIntervalMs;
        this.adminIds = loadAdminIds();
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Error handling update", e);
        }
    }

    private Set<Long> loadAdminIds() {
        Set<Long> set = new HashSet<>();
        String raw = Config.env("ADMIN_IDS", "").trim();
        if (raw.isEmpty()) {
            return set;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                set.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("Invalid admin id in ADMIN_IDS: {}", s);
            }
        }
        return set;
    }

    private boolean isAdmin(long chatId) {
        return adminIds.contains(chatId);
    }

    private void handleMessage(Message message) throws TelegramApiException {
        if (!message.hasText()) {
            return;
        }
        String text = message.getText().trim();
        long chatId = message.getChatId();

        if (text.startsWith("/send")) {
            handleAdminSend(chatId, text);
            return;
        }
        if ("/all".equals(text)) {
            handleAdminAll(chatId);
            return;
        }

        if ("/start".equals(text)) {
            long now = System.currentTimeMillis();
            boolean already = database.hasUserStarted(chatId);
            Long lastStartAt = database.getLastStartAt(chatId);

            database.saveStart(chatId, message.getFrom() != null ? message.getFrom().getUserName() : null);

            // первый /start
            if (!already || lastStartAt == null) {
                sendStartFirstTime(chatId);
                return;
            }

            // если второй /start прилетел сразу же (двойной старт кнопкой) — игнорируем
            if (lastStartAt != null && now - lastStartAt < 2000) {
                return;
            }

            // обычный повторный /start
            sendStartAgain(chatId);
        } else {
            // любое другое сообщение — просто покажем меню
            SendMessage msg = new SendMessage();
            msg.setChatId(Long.toString(chatId));
            msg.setText("Пожалуйста, воспользуйтесь меню ниже 👇");
            msg.setReplyMarkup(mainMenuKeyboard(chatId));
            safeExecute(msg);
        }
    }

    private void handleAdminSend(long chatId, String text) throws TelegramApiException {
        if (!isAdmin(chatId)) {
            SendMessage msg = new SendMessage();
            msg.setChatId(Long.toString(chatId));
            msg.setText("Эта команда доступна только администраторам.");
            safeExecute(msg);
            return;
        }

        String payload = text.length() > 5 ? text.substring(5).trim() : "";
        if (payload.isEmpty()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(Long.toString(chatId));
            msg.setText("Используйте: /send ваш текст для рассылки");
            safeExecute(msg);
            return;
        }

        List<Long> recipients = database.getAllActiveChatIds();
        int sent = 0;
        for (Long id : recipients) {
            if (id == null) continue;
            SendMessage out = new SendMessage();
            out.setChatId(Long.toString(id));
            out.setText(payload);
            out.setParseMode(ParseMode.HTML);
            safeExecute(out);
            sent++;
        }

        SendMessage done = new SendMessage();
        done.setChatId(Long.toString(chatId));
        done.setText("Рассылка отправлена " + sent + " пользователям.");
        safeExecute(done);
    }

    private void handleAdminAll(long chatId) throws TelegramApiException {
        if (!isAdmin(chatId)) {
            SendMessage msg = new SendMessage();
            msg.setChatId(Long.toString(chatId));
            msg.setText("Эта команда доступна только администраторам.");
            safeExecute(msg);
            return;
        }

        int count = database.countActiveUsers();
        SendMessage msg = new SendMessage();
        msg.setChatId(Long.toString(chatId));
        msg.setText("Активных пользователей в боте: " + count);
        safeExecute(msg);
    }

    private void handleCallback(CallbackQuery callbackQuery) throws TelegramApiException {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        String callbackId = callbackQuery.getId();
        long now = System.currentTimeMillis();

        // антиспам по коллбэкам
        if (database.isCallbackSpam(chatId, data, now, callbackSpamIntervalMs)) {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackId);
            answer.setText("Пожалуйста, не нажимайте так часто 🙂");
            answer.setShowAlert(false);
            safeExecute(answer);
            return;
        }

        database.saveCallbackUsage(chatId, data, now);

        // обязательно отвечаем на callback, чтобы не висел прогресс-бар
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        answer.setShowAlert(false);
        safeExecute(answer);

        switch (data) {
            case CB_WATER_FACTS -> sendWaterFacts(chatId);
            case CB_46_REASONS -> send46Reasons(chatId);
            case CB_DEHYDRATION -> sendDehydration(chatId);
            case CB_QUALITY_FULL -> sendQualityFull(chatId);
            case CB_LIVE_WATER -> sendLiveWater(chatId);
            case CB_PROMO -> sendPromo(chatId);
            case CB_HEALTH_FORM -> sendHealthForm(chatId);
            case CB_CONSULTATION -> sendConsultation(chatId);
            case CB_FULL_CLEANSE -> sendFullCleanse(chatId);
            case CB_ADMIN_PANEL -> sendAdminPanel(chatId);
            case CB_BACK_TO_MENU -> sendMainMenu(chatId);
            default -> {
                SendMessage msg = new SendMessage();
                msg.setChatId(Long.toString(chatId));
                msg.setText("Неизвестная команда. Показываю меню 👇");
                msg.setReplyMarkup(mainMenuKeyboard(chatId));
                safeExecute(msg);
            }
        }
    }

    private void sendAdminPanel(long chatId) throws TelegramApiException {
        if (!isAdmin(chatId)) {
            SendMessage msg = new SendMessage();
            msg.setChatId(Long.toString(chatId));
            msg.setText("Эта панель доступна только администраторам.");
            safeExecute(msg);
            return;
        }

        String text = """
                ⚙️ <b>Админ панель</b>
                
                /send &lt;текст&gt; — отправить мгновенную рассылку всем активным пользователям.
                /all — показать количество активных пользователей.
                """;

        SendMessage msg = new SendMessage();
        msg.setChatId(Long.toString(chatId));
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        msg.setReplyMarkup(backToMenuKeyboard());
        safeExecute(msg);
    }

    private void sendFullCleanse(long chatId) throws TelegramApiException {
        // одно видео + ваш текст + кнопка "Домик"
        sendVideo(chatId, "41.MP4", Content.FULL_CLEANSE_TEXT, true);
    }

    // ------------- /start -------------

    private void sendStartFirstTime(long chatId) throws TelegramApiException {
        // ОДНО сообщение: фото 1.jpg + приветственный текст + главное меню
        String fileName = "1.jpg";
        String cacheKey = "photo:" + fileName;

        SendPhoto photo = new SendPhoto();
        photo.setChatId(Long.toString(chatId));
        photo.setCaption(Content.START_TEXT);
        photo.setParseMode(ParseMode.HTML);
        photo.setReplyMarkup(mainMenuKeyboard(chatId));

        String cachedId = database.getMediaFileId(cacheKey);
        if (cachedId != null) {
            photo.setPhoto(new InputFile(cachedId));
            safeExecute(photo);
            return;
        }

        File file = new File(mediaDir, fileName);
        photo.setPhoto(new InputFile(file));
        Message msg = safeExecute(photo);
        if (msg != null && msg.getPhoto() != null && !msg.getPhoto().isEmpty()) {
            PhotoSize best = msg.getPhoto().get(msg.getPhoto().size() - 1);
            database.saveMediaFileId(cacheKey, best.getFileId());
        }
    }

    private void sendStartAgain(long chatId) throws TelegramApiException {
        sendStartFirstTime(chatId);
    }

    private void sendMainMenu(long chatId) throws TelegramApiException {
        sendStartFirstTime(chatId);
    }

    private InlineKeyboardMarkup mainMenuKeyboard(long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(singleButtonRow("💧 Вода. Интересные факты", CB_WATER_FACTS));
        rows.add(singleButtonRow("📋 46 причин пить воду", CB_46_REASONS));
        rows.add(singleButtonRow("🤒 Болезни обезвоживания", CB_DEHYDRATION));
        rows.add(singleButtonRow("🧪 Качество воды", CB_QUALITY_FULL));
        rows.add(singleButtonRow("🌿 Живая щелочная вода", CB_LIVE_WATER));
        rows.add(singleButtonRow("🎁 Промокод на 20%", CB_PROMO));
        rows.add(singleButtonRow("📊 Анкета по здоровью", CB_HEALTH_FORM));
        rows.add(singleButtonRow("📞 Записаться на консультацию", CB_CONSULTATION));

        // последняя кнопка – переход в Telegram-канал
        InlineKeyboardButton channelButton = new InlineKeyboardButton();
        channelButton.setText("Мой TELEGRAM канал");
        channelButton.setUrl("https://t.me/+IpcgPtRi4jozNzUy");
        List<InlineKeyboardButton> channelRow = new ArrayList<>();
        channelRow.add(channelButton);
        rows.add(channelRow);

        if (isAdmin(chatId)) {
            rows.add(singleButtonRow("⚙️ Админ панель", CB_ADMIN_PANEL));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private List<InlineKeyboardButton> singleButtonRow(String text, String data) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text);
        b.setCallbackData(data);
        row.add(b);
        return row;
    }

    private InlineKeyboardMarkup backToMenuKeyboard() {
        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("🏠 Вернуться в меню");
        back.setCallbackData(CB_BACK_TO_MENU);

        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(back);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    // ------------- Обработчики разделов -------------

    private void sendWaterFacts(long chatId) throws TelegramApiException {
        // (2.jpg) + текст
        sendPhoto(chatId, "2.jpg", Content.WATER_FACTS_1, false);

        // (1.mp4) + текст
        sendVideo(chatId, "1.MP4", Content.WATER_FACTS_BLOOD_VIDEO_TEXT, false);

        // (3.jpg) + текст
        sendPhoto(chatId, "3.jpg", Content.WATER_FACTS_2, false);

        // (2.mp4) + текст
        sendVideo(chatId, "2.MP4", Content.WATER_FACTS_3_VIDEO_TEXT, false);

        // (4.mp4) без текста, с кнопкой "Домик"
        sendVideo(chatId, "4.MP4", null, true);
    }

    private void send46Reasons(long chatId) throws TelegramApiException {
        // Фото + большой текст (если не влезет в caption — остальное уйдёт в отдельные сообщения)
        sendPhoto(chatId, "4.jpg", Content.REASONS_46_TEXT, true);
    }

    private void sendDehydration(long chatId) throws TelegramApiException {
        // (5.mp4) + большой текст
        sendVideo(chatId, "5.MP4", Content.DEHYDRATION_DISEASES_VIDEO_5_TEXT, false);

        // (6.mp4) + текст
        sendVideo(chatId, "6.MP4", Content.DEHYDRATION_DISEASES_VIDEO_6_TEXT, false);

        // (5.jpg) + "Пройдите тест"
        sendPhoto(chatId, "5.jpg", Content.DEHYDRATION_DISEASES_QUIZ_TEXT, false);

        // (7.mp4) + кнопка "Домик"
        sendVideo(chatId, "7.MP4", null, true);
    }

    private void sendQualityFull(long chatId) throws TelegramApiException {
        // Вступительный текст
        sendText(chatId, Content.QUALITY_INTRO, false);

        // (6.jpg) + 6 параметров
        sendPhoto(chatId, "6.jpg", Content.QUALITY_6_PARAMS, false);

        // (8.mp4) + про воду из-под крана
        sendVideo(chatId, "8.MP4", Content.QUALITY_TAP_WATER_TEXT, false);

        // Следующее сообщение (только текст)
        sendText(chatId, Content.QUALITY_NEXT_1, false);

        // (7.jpg) + про кипячёную воду
        sendPhoto(chatId, "7.jpg", Content.QUALITY_KETTLE_TEXT, false);

        // (8.jpg) + про воду в бутылках
        sendPhoto(chatId, "8.jpg", Content.QUALITY_BOTTLED_TEXT, false);

        // Следующее сообщение (только текст)
        sendText(chatId, Content.QUALITY_NEXT_2, false);

        // (9.jpg) + текучесть
        sendPhoto(chatId, "9.jpg", Content.QUALITY_SURFACE_TENSION_TEXT, false);

        // (10.jpg) + примеры натяжения
        sendPhoto(chatId, "10.jpg", Content.QUALITY_SURFACE_TENSION_EXAMPLES, false);

        // (11.jpg) + структура и память
        sendPhoto(chatId, "11.jpg", Content.QUALITY_STRUCTURE_TEXT, false);

        // Следующее сообщение (только текст)
        sendText(chatId, Content.QUALITY_NEXT_3, false);

        // (9.mp4) + текст про фильм
        sendVideo(chatId, "9.MP4", Content.QUALITY_VIDEO_9_TEXT, false);

        // Следующее сообщение (только текст)
        sendText(chatId, Content.QUALITY_NEXT_4, false);

        // (12.jpg) + минерализация
        sendPhoto(chatId, "12.jpg", Content.QUALITY_MINERALIZATION_TEXT, false);

        // (13.jpg) + pH
        sendPhoto(chatId, "13.jpg", Content.QUALITY_PH_TEXT, false);

        // (14.jpg) — просто картинка pH без текста
        sendPhoto(chatId, "14.jpg", null, false);

        // (15.jpg) + ОВП
        sendPhoto(chatId, "15.jpg", Content.QUALITY_OVP_TEXT, false);

        // (10.mp4), (11.mp4)
        sendVideo(chatId, "10.MP4", null, false);
        sendVideo(chatId, "11.MP4", null, false);

        // ИНФО ПРО ЕССЕНТУКИ – КАК ПОСЛЕДНЕЕ СООБЩЕНИЕ С КНОПКОЙ "ВЕРНУТЬСЯ В МЕНЮ" (п.1)
        sendVideo(chatId, "14.MP4", Content.QUALITY_SHORT_ESSE_TEXT, true);
    }

    private void sendLiveWater(long chatId) throws TelegramApiException {
        // HTML-текст, где каждое слово "вода" кликабельно и ведёт на нужный URL
        String html = linkifyWater(Content.LIVE_WATER_CORAL_MAIN_TEXT);

        // (16.jpg) + HTML-текст (с <b> и <a>)
        sendPhotoHtml(chatId, "16.jpg", html, false);

        // Отдельным сообщением даём ссылку на видео
        SendMessage linkMsg = new SendMessage();
        linkMsg.setChatId(Long.toString(chatId));
        linkMsg.setText("Вода японских долгожителей:\nhttps://youtu.be/pO19EG5_fb0?si=IcPR4jQfRb8MQAx5");
        safeExecute(linkMsg);

        // (12.MP4) + текст про соду, БЕЗ кнопки "Домик"
        sendVideo(chatId, "12.MP4", Content.LIVE_WATER_SODA_VIDEO_TEXT, false);

        // ПОСЛЕДНИМ сообщением — (40.MP4) + кнопка "Домик"
        sendVideo(chatId, "40.MP4", null, true);
    }

    private void sendPromo(long chatId) throws TelegramApiException {
        // (17.jpg) + текст + кнопка "Домик"
        sendPhoto(chatId, "17.jpg", Content.PROMO_TEXT, true);
    }

    private void sendHealthForm(long chatId) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(Long.toString(chatId));
        msg.setText(Content.HEALTH_FORM_TEXT);
        msg.setReplyMarkup(backToMenuKeyboard());
        safeExecute(msg);
    }

    private void sendConsultation(long chatId) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(Long.toString(chatId));
        msg.setText(Content.CONSULTATION_TEXT);
        msg.setReplyMarkup(backToMenuKeyboard());
        safeExecute(msg);
    }

    // ------------- Утилиты отправки -------------

    /**
     * Отправка обычного текста с автосплитом по лимиту Telegram.
     * Кнопка "Домик" ставится только на последнем сообщении.
     */
    private void sendText(long chatId, String text, boolean backButton) throws TelegramApiException {
        if (text == null || text.isBlank()) return;

        int length = text.length();
        int offset = 0;
        while (offset < length) {
            int end = Math.min(length, offset + MAX_MESSAGE_LENGTH);
            if (end < length) {
                // стараемся резать по переводу строки или пробелу
                int lastNewLine = text.lastIndexOf('\n', end);
                int lastSpace = text.lastIndexOf(' ', end);
                int split = Math.max(lastNewLine, lastSpace);
                if (split <= offset) {
                    split = end;
                }
                end = split;
            }

            String chunk = text.substring(offset, end).trim();
            if (!chunk.isEmpty()) {
                SendMessage msg = new SendMessage();
                msg.setChatId(Long.toString(chatId));
                msg.setText(chunk);
                msg.setParseMode(ParseMode.HTML);
                // кнопку "Домик" вешаем только на последнюю часть
                if (backButton && end >= length) {
                    msg.setReplyMarkup(backToMenuKeyboard());
                }
                safeExecute(msg);
            }
            offset = end;
        }
    }

    private void sendHtmlText(long chatId, String html, boolean backButton) throws TelegramApiException {
        if (html == null || html.isBlank()) return;

        int length = html.length();
        int offset = 0;
        while (offset < length) {
            int end = Math.min(length, offset + MAX_MESSAGE_LENGTH);
            if (end < length) {
                int lastNewLine = html.lastIndexOf('\n', end);
                int lastSpace = html.lastIndexOf(' ', end);
                int split = Math.max(lastNewLine, lastSpace);
                if (split <= offset) {
                    split = end;
                }
                end = split;
            }

            String chunk = html.substring(offset, end).trim();
            if (!chunk.isEmpty()) {
                SendMessage msg = new SendMessage();
                msg.setChatId(Long.toString(chatId));
                msg.setText(chunk);
                msg.setParseMode(ParseMode.HTML);
                if (backButton && end >= length) {
                    msg.setReplyMarkup(backToMenuKeyboard());
                }
                safeExecute(msg);
            }
            offset = end;
        }
    }

    /**
     * Фото + caption (обычный текст) с кэшированием file_id.
     * Если caption длинный — первая часть идёт в caption, остальное отдельными сообщениями.
     */
    private void sendPhoto(long chatId, String fileName, String caption, boolean backButton) throws TelegramApiException {
        sendPhotoInternal(chatId, fileName, caption, backButton, false);
    }

    /**
     * Фото + caption как HTML (для блока с "водой" + ссылками).
     */
    private void sendPhotoHtml(long chatId, String fileName, String htmlCaption, boolean backButton) throws TelegramApiException {
        sendPhotoInternal(chatId, fileName, htmlCaption, backButton, true);
    }

    private void sendPhotoInternal(long chatId,
                                   String fileName,
                                   String caption,
                                   boolean backButton,
                                   boolean html) throws TelegramApiException {
        String cacheKey = "photo:" + fileName;

        String captionFirst = null;
        String captionRest = null;
        if (caption != null && !caption.isBlank()) {
            if (caption.length() <= MAX_CAPTION_LENGTH) {
                captionFirst = caption;
            } else {
                int end = Math.min(caption.length(), MAX_CAPTION_LENGTH);
                int lastNewLine = caption.lastIndexOf('\n', end);
                int lastSpace = caption.lastIndexOf(' ', end);
                int split = Math.max(lastNewLine, lastSpace);
                if (split <= 0) {
                    split = end;
                }
                captionFirst = caption.substring(0, split).trim();
                captionRest = caption.substring(split).trim();
            }
        }

        SendPhoto photo = new SendPhoto();
        photo.setChatId(Long.toString(chatId));
        if (captionFirst != null && !captionFirst.isBlank()) {
            photo.setCaption(captionFirst);
            photo.setParseMode(ParseMode.HTML);
        }

        // Кнопку "Домик" на фото ставим только если НЕТ остатка текста
        if (backButton && (captionRest == null || captionRest.isBlank())) {
            photo.setReplyMarkup(backToMenuKeyboard());
        }

        // 1. Пробуем отправить по file_id из кэша
        String cachedId = database.getMediaFileId(cacheKey);
        if (cachedId != null) {
            photo.setPhoto(new InputFile(cachedId));
            safeExecute(photo);
        } else {
            // 2. Отправляем файл с диска, сохраняем file_id
            File file = new File(mediaDir, fileName);
            photo.setPhoto(new InputFile(file));

            Message msg = safeExecute(photo);
            if (msg != null && msg.getPhoto() != null && !msg.getPhoto().isEmpty()) {
                PhotoSize best = msg.getPhoto().get(msg.getPhoto().size() - 1);
                String newFileId = best.getFileId();
                database.saveMediaFileId(cacheKey, newFileId);
            }
        }

        // Если текст не влез в caption — отправляем остаток как обычный (или HTML) текст
        if (captionRest != null && !captionRest.isBlank()) {
            if (html) {
                sendHtmlText(chatId, captionRest, backButton);
            } else {
                sendText(chatId, captionRest, backButton);
            }
        }
    }

    /**
     * Видео + caption (обычный текст) с кэшированием file_id.
     * Если caption длинный — остаток текста уйдёт отдельным сообщением.
     */
    private void sendVideo(long chatId, String fileName, String caption, boolean backButton) throws TelegramApiException {
        String cacheKey = "video:" + fileName;

        String captionFirst = null;
        String captionRest = null;
        if (caption != null && !caption.isBlank()) {
            if (caption.length() <= MAX_CAPTION_LENGTH) {
                captionFirst = caption;
            } else {
                int end = Math.min(caption.length(), MAX_CAPTION_LENGTH);
                int lastNewLine = caption.lastIndexOf('\n', end);
                int lastSpace = caption.lastIndexOf(' ', end);
                int split = Math.max(lastNewLine, lastSpace);
                if (split <= 0) {
                    split = end;
                }
                captionFirst = caption.substring(0, split).trim();
                captionRest = caption.substring(split).trim();
            }
        }

        SendVideo video = new SendVideo();
        video.setChatId(Long.toString(chatId));
        if (captionFirst != null && !captionFirst.isBlank()) {
            video.setCaption(captionFirst);
        }

        video.setParseMode(ParseMode.HTML);

        // Кнопку "Домик" на видео ставим только если НЕТ остатка текста
        if (backButton && (captionRest == null || captionRest.isBlank())) {
            video.setReplyMarkup(backToMenuKeyboard());
        }

        // 1. Пробуем отправить по file_id
        String cachedId = database.getMediaFileId(cacheKey);
        if (cachedId != null) {
            video.setVideo(new InputFile(cachedId));
            safeExecute(video);
        } else {
            // 2. Отправляем файл с диска, кэшируем file_id
            File file = new File(mediaDir, fileName);
            video.setVideo(new InputFile(file));

            Message msg = safeExecute(video);
            if (msg != null && msg.getVideo() != null) {
                String newFileId = msg.getVideo().getFileId();
                database.saveMediaFileId(cacheKey, newFileId);
            }
        }

        if (captionRest != null && !captionRest.isBlank()) {
            // остаток текста — отдельными сообщениями
            sendText(chatId, captionRest, backButton);
        }
    }

    private String linkifyWater(String text) {
        // заменяем слово "вода" (в любом регистре) на ссылку
        return text.replaceAll("(?i)\\bвода\\b",
                "вода");
    }

    // универсальный safeExecute для BotApiMethod
    private <T extends Serializable> void safeExecute(org.telegram.telegrambots.meta.api.methods.BotApiMethod<T> method) {
        try {
            execute(method);
            sleepQuietly(MESSAGE_DELAY_MS);
        } catch (TelegramApiException e) {
            log.error("Telegram API error (BotApiMethod)", e);
        }
    }

    // safeExecute для фото — возвращает Message (для получения file_id)
    private Message safeExecute(SendPhoto photo) {
        try {
            Message msg = execute(photo);
            sleepQuietly(MESSAGE_DELAY_MS);
            return msg;
        } catch (TelegramApiException e) {
            log.error("Telegram API error (SendPhoto)", e);
            return null;
        }
    }

    // safeExecute для видео — возвращает Message (для получения file_id)
    private Message safeExecute(SendVideo video) {
        try {
            Message msg = execute(video);
            sleepQuietly(MESSAGE_DELAY_MS);
            return msg;
        } catch (TelegramApiException e) {
            log.error("Telegram API error (SendVideo)", e);
            return null;
        }
    }

    private void safeExecute(AnswerCallbackQuery answer) {
        try {
            // здесь без задержки, чтобы индикатор на кнопке сразу исчезал
            execute(answer);
        } catch (TelegramApiException e) {
            log.error("Telegram API error (AnswerCallbackQuery)", e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}