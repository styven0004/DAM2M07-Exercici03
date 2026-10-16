package com.project;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;

public class Controller implements Initializable {

    // Model configuration (Agents IETI)
    private static final String MODEL_NAME = "Qwen38";
    private static final String MODEL_URL = "https://agents.ieti.site/v1/chat/completions";
    private static final String MODEL_KEY = "ieti_sk_k33fAWVP8NWWZ8U4WxTJ7AbwBEkcxsz9zQrMItCEso8"; // Replace with your actual API key

    // Model configuration (Ollama local, compatible amb el protocol OpenAI)
    // private static final String MODEL_NAME = "qwen3-vl";
    // private static final String MODEL_URL = "http://localhost:11434/v1/chat/completions";
    // private static final String MODEL_KEY = "ollama";

    private static final String BOT_NAME = "Xat IETI";
    private static final String USER_NAME = "You";
    private static final String DEFAULT_IMAGE_PROMPT = "Describe this image";

    private static final double AVATAR_COLUMN_WIDTH = 64;
    private static final double THUMB_SIZE = 180;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private VBox chatBox;

    @FXML
    private HBox attachmentBar;

    @FXML
    private ImageView attachmentThumb;

    @FXML
    private Label attachmentName;

    @FXML
    private Button buttonAttach, buttonRemoveAttachment, buttonSend;

    @FXML
    private TextField textInput;

    // --- Imatge pendent d'enviar ---
    private record Attachment(String name, String base64, String mime, Image thumb) { }

    private Attachment pendingImage = null;

    // --- Resposta del bot que s'està construint ---
    private static class Reply {
        final VBox content;
        final Label body = new Label();
        final StringBuilder text = new StringBuilder();
        Timeline thinking;

        Reply(VBox content) {
            this.content = content;
        }
    }

    private Reply currentReply = null;

    // --- Estat de la conversa i de la xarxa ---
    private final List<JSONObject> history = new ArrayList<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    // Identificador de la petició activa: si no coincideix, el resultat s'ignora
    private final AtomicLong activeRequest = new AtomicLong(0);

    private CompletableFuture<?> currentFuture;
    private volatile InputStream currentInputStream;
    private Future<?> streamReadingTask;

    private boolean running = false;
    private boolean stickToBottom = true;

    private Image yetiImage;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        yetiImage = new Image(getClass().getResourceAsStream("/assets/yeti.png"));

        // Auto-scroll al final mentre arriba text, tret que l'usuari hagi pujat
        // Només es desactiva quan l'usuari puja amb la roda/trackpad; es reactiva en tornar al final
        scrollPane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() > 0) {
                stickToBottom = false;
            }
        });
        scrollPane.vvalueProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0.98) {
                stickToBottom = true;
            }
        });
        chatBox.heightProperty().addListener((obs, o, n) -> {
            if (stickToBottom) {
                Platform.runLater(() -> scrollPane.setVvalue(1.0));
            }
        });

        textInput.textProperty().addListener((obs, o, n) -> updateSendButton());
        updateSendButton();

        Platform.runLater(() -> textInput.requestFocus());
    }

    // --- UI actions ---

    @FXML
    private void onSend(ActionEvent event) {
        if (running) {
            stopRequest();
        } else {
            sendMessage();
        }
    }

    @FXML
    private void onEnter(ActionEvent event) {
        if (!running) {
            sendMessage();
        }
    }

    @FXML
    private void onAttach(ActionEvent event) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose an image");

        // JavaFX només sap decodificar aquests formats (necessari per a la miniatura)
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Images",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"
            )
        );

        File initialDir = new File(System.getProperty("user.dir"), "data");

        if (!initialDir.isDirectory()) {
            initialDir = new File(System.getProperty("user.dir"));
        }

        if (initialDir.exists() && initialDir.isDirectory()) {
            fc.setInitialDirectory(initialDir);
        }

        File file = fc.showOpenDialog(buttonAttach.getScene().getWindow());

        if (file == null) {
            return;
        }

        // Llegir el fitxer i codificar-lo en base64 fora del fil de la UI
        Task<Attachment> task = new Task<>() {
            @Override
            protected Attachment call() throws Exception {
                byte[] bytes = Files.readAllBytes(file.toPath());

                Image thumb = new Image(new ByteArrayInputStream(bytes), 360, 360, true, true);

                if (thumb.isError()) {
                    throw new IOException("Unsupported or corrupt image: " + file.getName());
                }

                return new Attachment(
                    file.getName(),
                    Base64.getEncoder().encodeToString(bytes),
                    getMimeType(file),
                    thumb
                );
            }
        };

        task.setOnSucceeded(e -> {
            pendingImage = task.getValue();
            attachmentThumb.setImage(pendingImage.thumb());
            attachmentName.setText(pendingImage.name());
            attachmentBar.setVisible(true);
            attachmentBar.setManaged(true);
            updateSendButton();
            textInput.requestFocus();
        });

        task.setOnFailed(e -> {
            task.getException().printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR, "Error reading image: " + getErrorMessage(task.getException()));
            alert.showAndWait();
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onRemoveAttachment(ActionEvent event) {
        clearAttachment();
    }

    // --- Enviar un missatge ---

    private void sendMessage() {
        String text = textInput.getText().trim();
        Attachment attachment = pendingImage;

        if (text.isEmpty() && attachment == null) {
            return;
        }

        if (text.isEmpty()) {
            text = DEFAULT_IMAGE_PROMPT;
        }

        textInput.clear();
        clearAttachment();

        stickToBottom = true;

        addUserMessage(text, attachment);
        history.add(buildUserMessage(text, attachment));

        Reply reply = addBotMessage();
        currentReply = reply;

        long id = activeRequest.incrementAndGet();
        setRunning(true);

        if (attachment != null) {
            // Imatge: resposta completa, mentrestant "thinking..."
            startThinking(reply);
            executeCompleteRequest(id, reply);
        } else {
            // Text: resposta en streaming
            executeStreamRequest(id, reply);
        }
    }

    private JSONObject buildUserMessage(String text, Attachment attachment) {
        if (attachment == null) {
            return new JSONObject()
                .put("role", "user")
                .put("content", text);
        }

        JSONArray content = new JSONArray()
            .put(
                new JSONObject()
                    .put("type", "text")
                    .put("text", text)
            )
            .put(
                new JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        new JSONObject().put(
                            "url",
                            "data:" + attachment.mime() + ";base64," + attachment.base64()
                        )
                    )
            );

        return new JSONObject()
            .put("role", "user")
            .put("content", content);
    }

    private HttpRequest buildRequest(boolean stream) {
        JSONObject body = new JSONObject()
            .put("model", MODEL_NAME)
            .put("messages", new JSONArray(history))
            .put("stream", stream)
            .put("reasoning_effort", "none");

        return HttpRequest.newBuilder()
            .uri(URI.create(MODEL_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + MODEL_KEY)
            .timeout(java.time.Duration.ofMinutes(5))
            .POST(BodyPublishers.ofString(body.toString()))
            .build();
    }

    // --- Text request (stream) ---

    private void executeStreamRequest(long id, Reply reply) {
        HttpRequest request = buildRequest(true);

        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofInputStream()
        );

        currentFuture = future;

        future.thenAccept(response -> {
            if (!isActive(id)) {
                closeQuietly(response.body());
                return;
            }

            int code = response.statusCode();

            if (code < 200 || code >= 300) {
                String bodyStr = readAll(response.body());
                String message = extractOpenAIError(bodyStr);

                if (message == null || message.isBlank()) {
                    message = "HTTP " + code + ": " + bodyStr;
                }

                final String toShow = message;
                Platform.runLater(() -> finishWithError(id, reply, toShow));
                return;
            }

            currentInputStream = response.body();
            streamReadingTask = executorService.submit(() -> handleStreamResponse(id, reply, response.body()));

        }).exceptionally(e -> {
            if (isActive(id)) {
                e.printStackTrace();
                Platform.runLater(() -> finishWithError(id, reply, "Request failed: " + getErrorMessage(e)));
            }

            return null;
        });
    }

    private void handleStreamResponse(long id, Reply reply, InputStream in) {
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
            )
        ) {
            String line;

            while (isActive(id) && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();

                if (data.equals("[DONE]")) break;

                JSONObject jsonResponse = new JSONObject(data);
                JSONArray choices = jsonResponse.optJSONArray("choices");

                if (choices == null || choices.length() == 0) continue;

                JSONObject delta = choices
                    .getJSONObject(0)
                    .optJSONObject("delta");

                if (delta == null) continue;

                String chunk = delta.optString("content", "");

                if (chunk.isEmpty()) continue;

                Platform.runLater(() -> {
                    if (isActive(id)) {
                        appendToReply(reply, chunk);
                    }
                });
            }

            if (isActive(id)) {
                Platform.runLater(() -> finishWithSuccess(id, reply));
            }

        } catch (Exception e) {
            if (isActive(id)) {
                e.printStackTrace();
                Platform.runLater(() ->
                    finishWithError(id, reply, "Error during streaming: " + getErrorMessage(e))
                );
            }
        }
    }

    // --- Image request (resposta completa) ---

    private void executeCompleteRequest(long id, Reply reply) {
        HttpRequest request = buildRequest(false);

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        currentFuture = future;

        future.thenAccept(response -> {
            if (!isActive(id)) return;

            int code = response.statusCode();
            String bodyStr = response.body();

            if (code >= 200 && code < 300) {
                String message = extractOpenAIResponse(bodyStr);
                Platform.runLater(() -> {
                    if (isActive(id)) {
                        replaceReplyText(reply, message);
                        finishWithSuccess(id, reply);
                    }
                });
            } else {
                String message = extractOpenAIError(bodyStr);

                if (message == null || message.isBlank()) {
                    message = "HTTP " + code + ": " + bodyStr;
                }

                final String toShow = message;
                Platform.runLater(() -> finishWithError(id, reply, toShow));
            }

        }).exceptionally(e -> {
            if (isActive(id)) {
                e.printStackTrace();
                Platform.runLater(() -> finishWithError(id, reply, "Request failed: " + getErrorMessage(e)));
            }

            return null;
        });
    }

    // --- Final de la petició ---

    private boolean isActive(long id) {
        return activeRequest.get() == id;
    }

    private void finishWithSuccess(long id, Reply reply) {
        if (!isActive(id)) return;

        stopThinking(reply);

        String answer = reply.text.toString();

        if (answer.isBlank()) {
            reply.body.setText("(empty response)");
            reply.body.getStyleClass().add("error");
            removeLastHistoryEntry();
        } else {
            history.add(
                new JSONObject()
                    .put("role", "assistant")
                    .put("content", answer)
            );
        }

        endRequest();
    }

    private void finishWithError(long id, Reply reply, String message) {
        if (!isActive(id)) return;

        stopThinking(reply);

        reply.text.setLength(0);
        reply.body.setText(message);
        reply.body.getStyleClass().add("error");

        // La petició ha fallat: es treu de l'historial perquè es pugui reintentar
        removeLastHistoryEntry();

        endRequest();
    }

    private void endRequest() {
        activeRequest.incrementAndGet();
        currentFuture = null;
        currentInputStream = null;
        streamReadingTask = null;
        currentReply = null;
        setRunning(false);
    }

    // --- Aturar la petició ---

    private void stopRequest() {
        if (!running) return;

        // Invalida la petició activa: qualsevol resultat posterior s'ignora
        activeRequest.incrementAndGet();

        cancelNetwork();

        Reply reply = currentReply;

        if (reply != null) {
            stopThinking(reply);

            String partial = reply.text.toString();

            if (partial.isBlank()) {
                reply.body.setText("Request stopped.");
                reply.body.getStyleClass().add("note");
                removeLastHistoryEntry();
            } else {
                history.add(
                    new JSONObject()
                        .put("role", "assistant")
                        .put("content", partial)
                );

                Label note = new Label("Stopped");
                note.getStyleClass().add("note");
                reply.content.getChildren().add(note);
            }
        }

        currentFuture = null;
        currentInputStream = null;
        streamReadingTask = null;
        currentReply = null;

        setRunning(false);
    }

    private void cancelNetwork() {
        try {
            if (currentInputStream != null) {
                System.out.println("Cancelling InputStream");
                currentInputStream.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (streamReadingTask != null) {
            System.out.println("Cancelling StreamReadingTask");
            streamReadingTask.cancel(true);
        }

        if (currentFuture != null && !currentFuture.isDone()) {
            System.out.println("Cancelling HTTP request");
            currentFuture.cancel(true);
        }
    }

    private void removeLastHistoryEntry() {
        // Treu el missatge d'usuari sense resposta per mantenir l'alternança user/assistant
        if (!history.isEmpty()) {
            history.remove(history.size() - 1);
        }
    }

    // --- Construcció de missatges a la UI ---

    private void addUserMessage(String text, Attachment attachment) {
        Label name = new Label(USER_NAME);
        name.getStyleClass().add("sender");

        VBox content = new VBox(6, name);

        if (attachment != null) {
            content.getChildren().add(createThumbnail(attachment.thumb()));
        }

        content.getChildren().add(createBody(text));

        Region spacer = new Region();
        spacer.setMinWidth(AVATAR_COLUMN_WIDTH);
        spacer.setPrefWidth(AVATAR_COLUMN_WIDTH);

        chatBox.getChildren().add(createRow(spacer, content));
    }

    private Reply addBotMessage() {
        ImageView avatar = new ImageView(yetiImage);
        avatar.setFitWidth(56);
        avatar.setPreserveRatio(true);
        avatar.setSmooth(true);

        StackPane avatarBox = new StackPane(avatar);
        avatarBox.setAlignment(Pos.TOP_LEFT);
        avatarBox.setMinWidth(AVATAR_COLUMN_WIDTH);
        avatarBox.setPrefWidth(AVATAR_COLUMN_WIDTH);
        avatarBox.setMaxWidth(AVATAR_COLUMN_WIDTH);

        Label name = new Label(BOT_NAME);
        name.getStyleClass().add("sender");

        VBox content = new VBox(6, name);
        Reply reply = new Reply(content);

        reply.body.setWrapText(true);
        reply.body.setMaxWidth(Double.MAX_VALUE);
        reply.body.getStyleClass().add("message-text");
        content.getChildren().add(reply.body);

        chatBox.getChildren().add(createRow(avatarBox, content));

        return reply;
    }

    private HBox createRow(javafx.scene.Node left, VBox content) {
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox row = new HBox(16, left, content);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(0));

        return row;
    }

    private Label createBody(String text) {
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("message-text");
        return body;
    }

    private ImageView createThumbnail(Image image) {
        double w = image.getWidth();
        double h = image.getHeight();
        double scale = Math.min(THUMB_SIZE / w, THUMB_SIZE / h);

        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(w * scale);
        view.setFitHeight(h * scale);

        Rectangle clip = new Rectangle(w * scale, h * scale);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        view.setClip(clip);

        return view;
    }

    // --- Text de la resposta i animació "thinking..." ---

    private void appendToReply(Reply reply, String chunk) {
        stopThinking(reply);
        reply.text.append(chunk);
        reply.body.setText(reply.text.toString());
    }

    private void replaceReplyText(Reply reply, String message) {
        stopThinking(reply);
        reply.text.setLength(0);
        reply.text.append(message);
        reply.body.setText(message);
    }

    private void startThinking(Reply reply) {
        reply.body.getStyleClass().add("thinking");
        reply.body.setText("thinking.");

        int[] dots = {1};

        reply.thinking = new Timeline(
            new KeyFrame(Duration.millis(450), e -> {
                dots[0] = dots[0] % 3 + 1;
                reply.body.setText("thinking" + ".".repeat(dots[0]));
            })
        );

        reply.thinking.setCycleCount(Timeline.INDEFINITE);
        reply.thinking.play();
    }

    private void stopThinking(Reply reply) {
        if (reply.thinking != null) {
            reply.thinking.stop();
            reply.thinking = null;
            reply.body.getStyleClass().remove("thinking");
            reply.body.setText("");
        }
    }

    // --- Imatge pendent ---

    private void clearAttachment() {
        pendingImage = null;
        attachmentThumb.setImage(null);
        attachmentName.setText("");
        attachmentBar.setVisible(false);
        attachmentBar.setManaged(false);
        updateSendButton();
    }

    // --- UI state ---

    private void setRunning(boolean value) {
        running = value;
        updateSendButton();
    }

    private void updateSendButton() {
        boolean canSend = !textInput.getText().isBlank() || pendingImage != null;

        buttonSend.getStyleClass().remove("active");

        if (running) {
            // Mentre hi ha una petició en marxa, el botó serveix per aturar-la
            Rectangle square = new Rectangle(12, 12, Color.WHITE);
            square.setArcWidth(3);
            square.setArcHeight(3);
            buttonSend.setGraphic(square);
            buttonSend.setDisable(false);
            buttonSend.getStyleClass().add("active");
        } else {
            SVGPath arrow = new SVGPath();
            arrow.setContent("M12 4l-7 7 1.41 1.41L11 7.83V20h2V7.83l4.59 4.58L19 11z");
            arrow.setFill(Color.WHITE);
            buttonSend.setGraphic(arrow);
            buttonSend.setDisable(!canSend);

            if (canSend) {
                buttonSend.getStyleClass().add("active");
            }
        }
    }

    // --- OpenAI response helpers ---

    private String extractOpenAIResponse(String bodyStr) {
        try {
            JSONObject response = new JSONObject(bodyStr);
            JSONArray choices = response.optJSONArray("choices");

            if (choices != null && choices.length() > 0) {
                JSONObject message = choices
                    .getJSONObject(0)
                    .optJSONObject("message");

                if (message != null) {
                    String content = message.optString("content", "");

                    if (!content.isBlank()) {
                        return content;
                    }
                }
            }

            String error = extractOpenAIError(bodyStr);

            if (error != null) {
                return error;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return bodyStr != null && !bodyStr.isBlank()
            ? bodyStr
            : "(empty)";
    }

    private String extractOpenAIError(String bodyStr) {
        try {
            JSONObject response = new JSONObject(bodyStr);
            JSONObject error = response.optJSONObject("error");

            if (error != null) {
                return "Error: " + error.optString(
                    "message",
                    error.toString()
                );
            }

        } catch (Exception ignore) { /* TODO: manage exception */}

        return null;
    }

    private String getErrorMessage(Throwable e) {
        Throwable cause = e;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (cause.getMessage() != null) {
            return cause.getMessage();
        }

        return cause.getClass().getSimpleName();
    }

    private String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Exception ignore) { /* nothing to do */ }
    }

    // --- Image MIME type ---

    private String getMimeType(File file) {
        try {
            String mimeType = Files.probeContentType(file.toPath());

            if (mimeType != null && mimeType.startsWith("image/")) {
                return mimeType;
            }

        } catch (Exception ignore) { /* TODO: manage exception */}

        String name = file.getName().toLowerCase();

        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";

        return "application/octet-stream";
    }
}
