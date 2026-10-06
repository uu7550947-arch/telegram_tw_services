package com.example.tw_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TwitterCheckService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,15}$");
    private static final Pattern NEXT_DATA_PATTERN = Pattern.compile(
            "<script[^>]*id=[\\\"']__NEXT_DATA__[\\\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TwitterCheckService() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Best-effort public X/Twitter account status check.
     * IMPORTANT: X does not provide a public unauthenticated endpoint that guarantees
     * suspended-vs-active status. Therefore UNKNOWN is intentionally returned when
     * the evidence is insufficient instead of falsely calling an account suspended.
     */
    public String checkByUsername(String username) {
        String cleanUsername = normalizeUsername(username);

        if (!USERNAME_PATTERN.matcher(cleanUsername).matches()) {
            return "❌ *Invalid Username*\n\n"
                    + "Username sirf letters, numbers aur `_` se hona chahiye (max 15 characters).";
        }

        String safeUsername = escapeMarkdown(cleanUsername);

        CheckResult syndication = checkSyndication(cleanUsername);
        if (syndication.isDefinitive()) {
            return formatResult(safeUsername, syndication);
        }

        // Fallback: inspect the public X profile page.
        CheckResult profile = checkXProfile(cleanUsername);
        if (profile.isDefinitive()) {
            return formatResult(safeUsername, profile);
        }

        // If one source gave a useful non-definitive result, expose that instead of
        // incorrectly saying suspended.
        if (profile.status() != Status.UNKNOWN) {
            return formatResult(safeUsername, profile);
        }
        return formatResult(safeUsername, syndication);
    }

    private CheckResult checkSyndication(String username) {
        String url = "https://syndication.twitter.com/srv/timeline-profile/screen-name/" + username;

        try {
            var response = restClient.get()
                    .uri(URI.create(url))
                    .header("User-Agent", userAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .retrieve()
                    .toEntity(String.class);

            int code = response.getStatusCode().value();
            String body = response.getBody();

            if (code == 404) {
                return new CheckResult(Status.NOT_FOUND, "X syndication returned HTTP 404.");
            }
            if (code == 403) {
                return new CheckResult(Status.BLOCKED, "X syndication rejected the request (HTTP 403).");
            }
            if (code == 429) {
                return new CheckResult(Status.RATE_LIMITED, "X syndication rate limited the request (HTTP 429).");
            }
            if (code < 200 || code >= 300 || body == null || body.isBlank()) {
                return new CheckResult(Status.UNKNOWN, "Syndication returned HTTP " + code + ".");
            }

            String lower = body.toLowerCase(Locale.ROOT);
            if (containsSuspensionMarker(lower)) {
                return new CheckResult(Status.SUSPENDED, "Suspension marker found in syndication response.");
            }

            JsonNode nextData = extractNextData(body);
            if (nextData != null) {
                JsonNode entries = nextData.path("props").path("pageProps").path("timeline").path("entries");
                if (entries.isArray() && entries.size() > 0) {
                    return new CheckResult(Status.ACTIVE, "Public timeline entries were returned.");
                }

                // __NEXT_DATA__ exists but no timeline. This is NOT proof of suspension.
                return new CheckResult(Status.UNKNOWN, "X returned profile data without timeline entries.");
            }

            // The endpoint responded successfully, but its HTML format changed.
            return new CheckResult(Status.UNKNOWN, "X returned HTML but __NEXT_DATA__ was not found.");

        } catch (RestClientResponseException e) {
            return classifyHttpException(e);
        } catch (Exception e) {
            return new CheckResult(Status.UNKNOWN, "Syndication connection/parsing error: " + safeMessage(e));
        }
    }

    private CheckResult checkXProfile(String username) {
        String url = "https://x.com/" + username;

        try {
            var response = restClient.get()
                    .uri(URI.create(url))
                    .header("User-Agent", userAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .retrieve()
                    .toEntity(String.class);

            int code = response.getStatusCode().value();
            String body = response.getBody() == null ? "" : response.getBody();
            String lower = body.toLowerCase(Locale.ROOT);

            if (code == 404) {
                return new CheckResult(Status.NOT_FOUND, "X profile returned HTTP 404.");
            }
            if (code == 403) {
                return new CheckResult(Status.BLOCKED, "X profile request was forbidden (HTTP 403).");
            }
            if (code == 429) {
                return new CheckResult(Status.RATE_LIMITED, "X profile request was rate limited (HTTP 429).");
            }
            if (code < 200 || code >= 300) {
                return new CheckResult(Status.UNKNOWN, "X profile returned HTTP " + code + ".");
            }

            if (containsStrongNotFoundMarker(lower)) {
                return new CheckResult(Status.NOT_FOUND, "X profile contains an account-not-found marker.");
            }
            if (containsSuspensionMarker(lower)) {
                return new CheckResult(Status.SUSPENDED, "X profile contains a suspension marker.");
            }
            if (containsProtectedMarker(lower)) {
                return new CheckResult(Status.PROTECTED, "X profile appears protected/restricted.");
            }

            Matcher titleMatcher = TITLE_PATTERN.matcher(body);
            if (titleMatcher.find()) {
                String title = titleMatcher.group(1).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                if (title.contains("doesn't exist") || title.contains("does not exist")) {
                    return new CheckResult(Status.NOT_FOUND, "X page title says the account does not exist.");
                }
                if (title.contains("suspended")) {
                    return new CheckResult(Status.SUSPENDED, "X page title indicates suspension.");
                }
            }

            // A normal X profile page is useful evidence, but we don't claim 100% active
            // because X can serve shell/consent pages without profile data.
            if (lower.contains("x.com/" + username.toLowerCase(Locale.ROOT))
                    || lower.contains("twitter.com/" + username.toLowerCase(Locale.ROOT))) {
                return new CheckResult(Status.ACTIVE, "Public X profile page was reachable.");
            }

            return new CheckResult(Status.UNKNOWN, "X returned a page but no reliable account marker was found.");

        } catch (RestClientResponseException e) {
            return classifyHttpException(e);
        } catch (Exception e) {
            return new CheckResult(Status.UNKNOWN, "X profile connection/parsing error: " + safeMessage(e));
        }
    }

    private JsonNode extractNextData(String html) {
        Matcher matcher = NEXT_DATA_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        try {
            return objectMapper.readTree(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private CheckResult classifyHttpException(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        int code = status.value();
        if (code == 404) return new CheckResult(Status.NOT_FOUND, "HTTP 404.");
        if (code == 403) return new CheckResult(Status.BLOCKED, "HTTP 403.");
        if (code == 429) return new CheckResult(Status.RATE_LIMITED, "HTTP 429.");
        return new CheckResult(Status.UNKNOWN, "HTTP " + code + ".");
    }

    private boolean containsStrongNotFoundMarker(String text) {
        return text.contains("this account doesn\'t exist")
                || text.contains("this account doesn't exist")
                || text.contains("account does not exist")
                || text.contains("page doesn\'t exist")
                || text.contains("page doesn't exist");
    }

    private boolean containsSuspensionMarker(String text) {
        return text.contains("account suspended")
                || text.contains("account is suspended")
                || text.contains("account has been suspended")
                || text.contains("this account is suspended");
    }

    private boolean containsProtectedMarker(String text) {
        return text.contains("these posts are protected")
                || text.contains("posts are protected")
                || text.contains("protected account");
    }

    private String formatResult(String username, CheckResult result) {
        return switch (result.status()) {
            case ACTIVE -> "🟢 *STATUS: ACTIVE / PUBLIC*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "✅ X profile publicly reachable hai.\n\n"
                    + "ℹ️ Result: " + result.reason();

            case SUSPENDED -> "🔴 *STATUS: SUSPENDED*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "❌ X response mein suspension indication mila.\n\n"
                    + "ℹ️ Reason: " + result.reason();

            case NOT_FOUND -> "🔴 *STATUS: NOT FOUND*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "❌ Account/profile nahi mila.\n\n"
                    + "ℹ️ Reason: " + result.reason();

            case PROTECTED -> "🟣 *STATUS: PROTECTED / RESTRICTED*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "🔒 Profile public timeline provide nahi kar raha.\n\n"
                    + "ℹ️ Ye suspended hone ka proof nahi hai.";

            case BLOCKED -> "🟠 *STATUS: REQUEST BLOCKED*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "⚠️ X ne request ko reject kiya (HTTP 403).\n\n"
                    + "ℹ️ Is response se account ko suspended declare nahi kiya ja sakta.";

            case RATE_LIMITED -> "🟡 *STATUS: RATE LIMITED*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "⚠️ X ne requests temporarily limit kar di hain (HTTP 429).\n\n"
                    + "ℹ️ Thodi der baad dobara try karein.";

            case UNKNOWN -> "⚪ *STATUS: UNKNOWN / COULD NOT VERIFY*\n\n"
                    + "👤 Username: @" + username + "\n"
                    + "⚠️ X se response mila, lekin active/suspended ka reliable proof nahi mila.\n\n"
                    + "ℹ️ Reason: " + result.reason();
        };
    }

    public String checkByEmail(String email) {
        if (email == null || email.isBlank() || !email.trim().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            return "❌ Invalid Email Format! Sahi email address enter karein.";
        }

        String safeEmail = escapeMarkdown(email.trim());
        return "ℹ️ *Email Query Result:*\n\n"
                + "Email: `" + safeEmail + "`\n\n"
                + "⚠️ X security policies ke kaaran email se direct account status verify nahi kiya ja sakta.\n\n"
                + "💡 Username se check karein:\n`/user <username>`";
    }

    private String normalizeUsername(String username) {
        if (username == null) return "";
        return username.trim().replaceFirst("^@+", "");
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("[", "\\[");
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    }

    private enum Status {
        ACTIVE, SUSPENDED, NOT_FOUND, PROTECTED, BLOCKED, RATE_LIMITED, UNKNOWN
    }

    private record CheckResult(Status status, String reason) {
        boolean isDefinitive() {
            return status == Status.ACTIVE
                    || status == Status.SUSPENDED
                    || status == Status.NOT_FOUND
                    || status == Status.PROTECTED
                    || status == Status.BLOCKED
                    || status == Status.RATE_LIMITED;
        }
    }
}
