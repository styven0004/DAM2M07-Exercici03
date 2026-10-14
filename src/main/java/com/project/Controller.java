package com.project;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.text.Text;
import javafx.event.ActionEvent;
import javafx.application.Platform;
import javafx.fxml.Initializable;
import javafx.stage.FileChooser;

import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.File;
import java.util.Base64;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

public class Controller implements Initializable {

    // Model configuration (Agents IETI)
    private static final String MODEL_NAME = "Qwen36";
    private static final String MODEL_URL = "https://agents.ieti.site/v1/chat/completions";
    private static final String MODEL_KEY = "ieti_sk_k33fAWVP8NWWZ8U4WxTJ7AbwBEkcxsz9zQrMItCEso8"; // Replace with your actual API key

    // Model configuration (Qwen3-VL)
    // private static final String MODEL_NAME = "qwen3-vl";
    // private static final String MODEL_URL = "http://localhost:8080/v1/chat/completions";
    
    @FXML
    private Button buttonCallStream, buttonCallComplete, buttonBreak, buttonPicture;

    @FXML
    private Text textInfo;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private CompletableFuture<HttpResponse<InputStream>> streamRequest;
    private CompletableFuture<HttpResponse<String>> completeRequest;

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    private InputStream currentInputStream;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> streamReadingTask;

    private volatile boolean isFirst = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setButtonsIdle();
    }

    // --- UI actions ---

    @FXML
    private void callStream(ActionEvent event) {
        textInfo.setText("");
        setButtonsRunning();
        isCancelled.set(false);

        executeTextRequest("Why is the sky blue?", true);
    }

    @FXML
    private void callComplete(ActionEvent event) {
        textInfo.setText("");
        setButtonsRunning();
        isCancelled.set(false);

        executeTextRequest("Tell me a haiku.", false);
    }

    @FXML
    private void callPicture(ActionEvent event) {
        textInfo.setText("");
        setButtonsRunning();
        isCancelled.set(false);

        FileChooser fc = new FileChooser();
        fc.setTitle("Choose an image");

        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                "Images",
                "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"
            )
        );

        File initialDir = new File(System.getProperty("user.dir"));

        if (initialDir.exists() && initialDir.isDirectory()) {
            fc.setInitialDirectory(initialDir);
        }

        File file = fc.showOpenDialog(buttonPicture.getScene().getWindow());

        if (file == null) {
            textInfo.setText("No file selected.");
            setButtonsIdle();
            return;
        }

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                byte[] bytes = Files.readAllBytes(file.toPath());
                return Base64.getEncoder().encodeToString(bytes);
            }
        };

        task.setOnSucceeded(e -> {
            String base64Image = task.getValue();
            String mimeType = getMimeType(file);

            executeImageRequest(
                "Describe what's in this picture",
                base64Image,
                mimeType
            );
        });

        task.setOnFailed(e -> {
            task.getException().printStackTrace();
            textInfo.setText("Error reading image.");
            setButtonsIdle();
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void callBreak(ActionEvent event) {
        isCancelled.set(true);

        cancelStreamRequest();
        cancelCompleteRequest();

        textInfo.setText("Request cancelled.");
        setButtonsIdle();
    }

    // --- Text request ---

    private void executeTextRequest(String prompt, boolean stream) {
        JSONArray messages = new JSONArray().put(
            new JSONObject()
                .put("role", "user")
                .put("content", prompt)
        );

        JSONObject body = new JSONObject()
            .put("model", MODEL_NAME)
            .put("messages", messages)
            .put("stream", stream)
            .put("reasoning_effort", "none");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(MODEL_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + MODEL_KEY)
            .POST(BodyPublishers.ofString(body.toString()))
            .build();

        if (stream) {
            textInfo.setText("Wait stream ... " + prompt);
            isFirst = true;

            streamRequest = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            ).thenApply(response -> {
                int code = response.statusCode();

                if (code < 200 || code >= 300) {
                    try {
                        String bodyStr = new String(
                            response.body().readAllBytes(),
                            StandardCharsets.UTF_8
                        );

                        String message = extractOpenAIError(bodyStr);

                        if (message == null || message.isBlank()) {
                            message = "HTTP " + code + ": " + bodyStr;
                        }

                        final String toShow = message;

                        Platform.runLater(() -> {
                            textInfo.setText(toShow);
                            setButtonsIdle();
                        });

                    } catch (Exception e) {
                        e.printStackTrace();

                        Platform.runLater(() -> {
                            textInfo.setText("HTTP error: " + code);
                            setButtonsIdle();
                        });
                    }

                    return response;
                }

                currentInputStream = response.body();
                streamReadingTask = executorService.submit(this::handleStreamResponse);

                return response;

            }).exceptionally(e -> {
                if (!isCancelled.get()) {
                    e.printStackTrace();

                    Platform.runLater(() -> {
                        textInfo.setText("Request failed: " + getErrorMessage(e));
                        setButtonsIdle();
                    });
                }

                return null;
            });

        } else {
            textInfo.setText("Wait complete ...");

            completeRequest = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
            ).thenApply(response -> {
                int code = response.statusCode();
                String bodyStr = response.body();
                String message;

                if (code >= 200 && code < 300) {
                    message = extractOpenAIResponse(bodyStr);
                } else {
                    message = extractOpenAIError(bodyStr);

                    if (message == null || message.isBlank()) {
                        message = "HTTP " + code + ": " + bodyStr;
                    }
                }

                final String toShow = message;

                Platform.runLater(() -> {
                    textInfo.setText(toShow);
                    setButtonsIdle();
                });

                return response;

            }).exceptionally(e -> {
                if (!isCancelled.get()) {
                    e.printStackTrace();

                    Platform.runLater(() -> {
                        textInfo.setText("Request failed: " + getErrorMessage(e));
                        setButtonsIdle();
                    });
                }

                return null;
            });
        }
    }

    // --- Image request ---

    private void executeImageRequest(String prompt, String base64Image, String mimeType) {
        textInfo.setText("Analyzing picture ...");

        JSONArray content = new JSONArray()
            .put(
                new JSONObject()
                    .put("type", "text")
                    .put("text", prompt)
            )
            .put(
                new JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        new JSONObject().put(
                            "url",
                            "data:" + mimeType + ";base64," + base64Image
                        )
                    )
            );

        JSONArray messages = new JSONArray().put(
            new JSONObject()
                .put("role", "user")
                .put("content", content)
        );

        JSONObject body = new JSONObject()
            .put("model", MODEL_NAME)
            .put("messages", messages)
            .put("stream", false)
            .put("reasoning_effort", "none");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(MODEL_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + MODEL_KEY)
            .POST(BodyPublishers.ofString(body.toString()))
            .build();

        completeRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            int code = response.statusCode();
            String bodyStr = response.body();
            String message;

            if (code >= 200 && code < 300) {
                message = extractOpenAIResponse(bodyStr);
            } else {
                message = extractOpenAIError(bodyStr);

                if (message == null || message.isBlank()) {
                    message = "HTTP " + code + ": " + bodyStr;
                }
            }

            final String toShow = message;

            Platform.runLater(() -> {
                textInfo.setText(toShow);
                setButtonsIdle();
            });

            return response;

        }).exceptionally(e -> {
            if (!isCancelled.get()) {
                e.printStackTrace();

                Platform.runLater(() -> {
                    textInfo.setText("Request failed: " + getErrorMessage(e));
                    setButtonsIdle();
                });
            }

            return null;
        });
    }

    // --- Stream reader ---

    private void handleStreamResponse() {
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(currentInputStream, StandardCharsets.UTF_8)
            )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (isCancelled.get()) break;
                if (line.isBlank()) continue;
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();

                if (data.equals("[DONE]")) break;

                JSONObject jsonResponse = new JSONObject(data);
                JSONArray choices = jsonResponse.optJSONArray("choices");

                if (choices == null || choices.isEmpty()) continue;

                JSONObject delta = choices
                    .getJSONObject(0)
                    .optJSONObject("delta");

                if (delta == null) continue;

                String chunk = delta.optString("content", "");

                if (chunk.isEmpty()) continue;

                if (isFirst) {
                    Platform.runLater(() -> textInfo.setText(chunk));
                    isFirst = false;
                } else {
                    Platform.runLater(() ->
                        textInfo.setText(textInfo.getText() + chunk)
                    );
                }
            }

        } catch (Exception e) {
            if (!isCancelled.get()) {
                e.printStackTrace();

                Platform.runLater(() ->
                    textInfo.setText(
                        "Error during streaming: " + getErrorMessage(e)
                    )
                );
            }

        } finally {
            try {
                if (currentInputStream != null) currentInputStream.close();
            } catch (Exception ignore) { /* TODO: manage exception */}

            Platform.runLater(this::setButtonsIdle);
        }
    }

    // --- OpenAI response helpers ---

    private String extractOpenAIResponse(String bodyStr) {
        try {
            JSONObject response = new JSONObject(bodyStr);
            JSONArray choices = response.optJSONArray("choices");

            if (choices != null && !choices.isEmpty()) {
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

    // --- Cancel requests ---

    private void cancelStreamRequest() {
        if (streamRequest != null && !streamRequest.isDone()) {
            try {
                if (currentInputStream != null) {
                    System.out.println("Cancelling InputStream");
                    currentInputStream.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println("Cancelling StreamRequest");

            if (streamReadingTask != null) {
                streamReadingTask.cancel(true);
            }

            streamRequest.cancel(true);
        }
    }

    private void cancelCompleteRequest() {
        if (completeRequest != null && !completeRequest.isDone()) {
            System.out.println("Cancelling CompleteRequest");
            completeRequest.cancel(true);
        }
    }

    // --- UI state ---

    private void setButtonsRunning() {
        buttonCallStream.setDisable(true);
        buttonCallComplete.setDisable(true);
        buttonPicture.setDisable(true);
        buttonBreak.setDisable(false);
    }

    private void setButtonsIdle() {
        buttonCallStream.setDisable(false);
        buttonCallComplete.setDisable(false);
        buttonPicture.setDisable(false);
        buttonBreak.setDisable(true);

        streamRequest = null;
        completeRequest = null;
    }
}