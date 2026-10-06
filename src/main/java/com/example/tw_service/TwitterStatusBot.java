package com.example.tw_service;




import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;



@Component
public class TwitterStatusBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final String botToken;
    private final TelegramClient telegramClient;
    private final TwitterCheckService twitterCheckService;

    public TwitterStatusBot(
            @Value("${telegram.bot.token}") String botToken,
            TwitterCheckService twitterCheckService) {
        this.botToken = botToken;
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.twitterCheckService = twitterCheckService;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String messageText = update.getMessage().getText().trim();
        long chatId = update.getMessage().getChatId();

        // Parse only the first token as the command.
        // Examples:
        //   /user Ravenm4lvl5
        //   /user@IGL_TW_BOT_SERVICE_BOT Ravenm4lvl5
        String[] parts = messageText.split("\\s+");
        if (parts.length == 0) {
            return;
        }

        String command = parts[0].toLowerCase(java.util.Locale.ROOT);
        int atIndex = command.indexOf('@');
        if (atIndex > 0) {
            command = command.substring(0, atIndex);
        }

        if (command.equals("/start")) {
            String welcomeMsg = "👋 **Twitter Account Status Checker Bot**\n\n" +
                    "Dono options me se chunie:\n\n" +
                    "1️⃣ **Username Check:**\n`/user <username>`\n*Example:* `/user elonmusk`\n\n" +
                    "2️⃣ **Email Check:**\n`/email <email>`\n*Example:* `/email test@gmail.com`";
            sendTextMessage(chatId, welcomeMsg);
            return;
        }

        if (command.equals("/user")) {
            if (parts.length < 2 || parts[1].isBlank()) {
                sendTextMessage(chatId, "⚠️ Please provide a username!\n*Example:* `/user elonmusk`");
                return;
            }

            String username = parts[1].trim();
            sendTextMessage(chatId, "🔍 Checking status for @" + username.replace("@", "") + "...");

            String result = twitterCheckService.checkByUsername(username);
            sendTextMessage(chatId, result);
            return;
        }

        if (command.equals("/email")) {
            if (parts.length < 2 || parts[1].isBlank()) {
                sendTextMessage(chatId, "⚠️ Please provide an email!\n*Example:* `/email abc@gmail.com`");
                return;
            }

            String result = twitterCheckService.checkByEmail(parts[1].trim());
            sendTextMessage(chatId, result);
            return;
        }

        sendTextMessage(chatId,
                "💡 Kripya sahi command bhejain:\n" +
                "• `/user <username>`\n" +
                "• `/email <email>`");
    }

   private void sendTextMessage(long chatId, String text) {
    SendMessage message = SendMessage.builder()
            .chatId(chatId)
            .text(text)
            .parseMode("Markdown") // Ya HTML mode use kar sakte hain
            .build();
    try {
        telegramClient.execute(message);
    } catch (TelegramApiException e) {
        // Agar Markdown parsing me fir bhi error aaye, toh plain text bhej do
        SendMessage plainMessage = SendMessage.builder()
                .chatId(chatId)
                .text(text.replaceAll("[_*`\\[\\]]", ""))
                .build();
        try {
            telegramClient.execute(plainMessage);
        } catch (TelegramApiException ex) {
            ex.printStackTrace();
        }
    }
}
}