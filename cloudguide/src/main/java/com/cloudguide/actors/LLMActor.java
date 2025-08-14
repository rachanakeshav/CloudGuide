/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.actors;

/**
 *
 * @author rachanakeshav
 */
import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import com.cloudguide.CborSerializable;
import com.typesafe.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

public class LLMActor {

    public interface Command extends CborSerializable {
    }

    public static final class AskLLM implements Command {

        public final String prompt;
        public final ActorRef<LLMResponse> replyTo;

        public AskLLM(String p, ActorRef<LLMResponse> r) {
            this.prompt = p;
            this.replyTo = r;
        }
    }

    public static final class ClassifyTopic implements Command {

        public final String text;
        public final ActorRef<LLMResponse> replyTo;

        public ClassifyTopic(String t, ActorRef<LLMResponse> r) {
            this.text = t;
            this.replyTo = r;
        }
    }

    public record LLMResponse(String text) implements CborSerializable {

    }

//    private record DoChat(String prompt, ActorRef<LLMResponse> replyTo, int attempt) implements Command {
//
//    }
    // Shared service key
    public static final ServiceKey<Command> SERVICE_KEY = ServiceKey.create(Command.class, "llm-service");

    private static final String SYSTEM_ANSWER_PROMPT
            = "You are CloudGuide, focused ONLY on cloud topics (AWS, Azure, GCP, cloud pricing, "
  + "architecture, DevOps/SRE, Kubernetes, serverless, storage, networking). "
  + "Be concise and practical. If a user asks a non-cloud question, politely decline and steer them back by saying "
  + "\"Sorry I do not have that information, I can help with cloud topics like AWS/Azure/GCP, pricing, and architecture.\" "
  + "Do not end sentences abruptly."
  + "Remember: Do not answer non-cloud related questions";

    private static final String SYSTEM_GATE_PROMPT
            = "You are a strict classifier for CloudGuide. Respond with only one word: "
            + "ALLOW if the user question is about cloud computing (AWS, Azure, GCP, cloud pricing, "
            + "architecture, DevOps/SRE, Kubernetes, serverless, cloud storage, networking). "
            + "Otherwise respond with DECLINE for non-cloud related questions. No punctuation, no explanations.";

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx -> {
            ctx.getSystem().receptionist().tell(Receptionist.register(SERVICE_KEY, ctx.getSelf()));

            Config conf = ctx.getSystem().settings().config();
            final ObjectMapper mapper = new ObjectMapper();
            final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

            // Backend: env > config > default
            final String backend = System.getenv().getOrDefault("LLM_BACKEND",
                    conf.hasPath("cloudguide.llm.backend") ? conf.getString("cloudguide.llm.backend") : "openai").toLowerCase();

            // OpenAI settings
            final String oaBase = conf.hasPath("cloudguide.openai.base-url")
                    ? conf.getString("cloudguide.openai.base-url") : "https://api.openai.com/v1";
            final String oaModel = conf.hasPath("cloudguide.openai.model")
                    ? conf.getString("cloudguide.openai.model") : "gpt-4o-mini";
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getProperty("OPENAI_API_KEY", "");
            }
            final String openaiKey = apiKey;
            final String oaEndpoint = oaBase + "/chat/completions";
            final int MAX_RETRIES = 2; // for OpenAI only

            // Ollama settings
            final String olBase = conf.hasPath("cloudguide.ollama.base-url")
                    ? conf.getString("cloudguide.ollama.base-url") : "http://localhost:11434";
            final String olModel = System.getenv().getOrDefault("OLLAMA_MODEL",
                    conf.hasPath("cloudguide.ollama.model") ? conf.getString("cloudguide.ollama.model") : "llama3");
            final String olEndpoint = olBase + "/api/chat";

            ctx.getLog().info("LLMActor backend={}", backend);

            return Behaviors.receive(Command.class)
                    .onMessage(ClassifyTopic.class, msg -> {
                        ObjectNode payload = mapper.createObjectNode();
                        payload.put("model", olModel);

                        ArrayNode messages = payload.putArray("messages");
                        ObjectNode sys = mapper.createObjectNode();
                        sys.put("role", "system");
                        sys.put("content", SYSTEM_GATE_PROMPT);
                        messages.add(sys);

                        ObjectNode user = mapper.createObjectNode();
                        user.put("role", "user");
                        user.put("content", msg.text);
                        messages.add(user);

                        payload.put("stream", false);
                        var opts = payload.putObject("options");
                        opts.put("num_predict", 200);
                        opts.put("temperature", 0.1);
                        opts.put("num_ctx", 4096);

                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(olEndpoint)) // Ollama endpoint
                                .timeout(Duration.ofSeconds(90))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                                .build();

                        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                                .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .whenComplete((resp, err) -> {
                                    if (err != null || resp.statusCode() != 200) {
                                        // fail-open so users aren’t blocked by flakiness
                                        msg.replyTo.tell(new LLMResponse("ALLOW"));
                                        return;
                                    }
                                    try {
                                        // Ollama response: { "message": { "role":"assistant","content":"..." }, ... }
                                        String raw = mapper.readTree(resp.body())
                                                .path("message").path("content").asText("").trim();
                                        String decision = "ALLOW";
                                        if ("DECLINE".equalsIgnoreCase(raw)) {
                                            decision = "DECLINE";
                                        } else if ("ALLOW".equalsIgnoreCase(raw)) {
                                            decision = "ALLOW";
                                        } else {
                                            decision = raw.toUpperCase().contains("DECLINE") ? "DECLINE" : "ALLOW";
                                        }
                                        msg.replyTo.tell(new LLMResponse(decision));
                                    } catch (Exception e) {
                                        msg.replyTo.tell(new LLMResponse("ALLOW"));
                                    }
                                });

                        return Behaviors.same();
                    })
                    .onMessage(AskLLM.class, msg -> {
                        ObjectNode payload = mapper.createObjectNode();
                        payload.put("model", olModel);

                        ArrayNode messages = payload.putArray("messages");
                        ObjectNode sys = mapper.createObjectNode();
                        sys.put("role", "system");
                        sys.put("content", SYSTEM_ANSWER_PROMPT);
                        messages.add(sys);

                        ObjectNode user = mapper.createObjectNode();
                        user.put("role", "user");
                        user.put("content", msg.prompt);
                        messages.add(user);

                        payload.put("stream", false);
                        var opts = payload.putObject("options");
                        opts.put("num_predict", 200);
                        opts.put("temperature", 0.2);
                        opts.put("num_ctx", 4096);

                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(olEndpoint)) // Ollama endpoint
                                .timeout(Duration.ofSeconds(120))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                                .build();

                        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                                .orTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                                .whenComplete((resp, err) -> {
                                    if (err != null) {
                                        msg.replyTo.tell(new LLMResponse("LLM error: " + err.getMessage()));
                                        return;
                                    }
                                    if (resp.statusCode() != 200) {
                                        msg.replyTo.tell(new LLMResponse("LLM HTTP " + resp.statusCode()));
                                        return;
                                    }
                                    try {
                                        String text = mapper.readTree(resp.body())
                                                .path("message").path("content").asText("");
                                        msg.replyTo.tell(new LLMResponse(text.isBlank() ? "LLM empty response" : text));
                                    } catch (Exception e) {
                                        msg.replyTo.tell(new LLMResponse("LLM parse error"));
                                    }
                                });

                        return Behaviors.same();
                    })
                    //                    .onMessage(DoChat.class, msg -> {
                    //                        ObjectNode payload = mapper.createObjectNode();
                    //                        payload.put("model", oaModel);
                    //                        ArrayNode messages = payload.putArray("messages");
                    //
                    //                        ObjectNode sys = mapper.createObjectNode();
                    //                        sys.put("role", "system");
                    //                        sys.put("content", SYSTEM_GATE_PROMPT);
                    //                        messages.add(sys);
                    //
                    //                        ObjectNode user = mapper.createObjectNode();
                    //                        user.put("role", "user");
                    //                        user.put("content", msg.prompt());
                    //                        messages.add(user);
                    //
                    //                        payload.put("temperature", 0.3);
                    //
                    //                        String body;
                    //                        try {
                    //                            body = mapper.writeValueAsString(payload);
                    //                        } catch (Exception e) {
                    //                            msg.replyTo().tell(new LLMResponse("LLM error (payload build)"));
                    //                            return Behaviors.same();
                    //                        }
                    //
                    //                        HttpRequest req = HttpRequest.newBuilder()
                    //                                .uri(URI.create(oaEndpoint))
                    //                                .timeout(Duration.ofSeconds(15))
                    //                                .header("Authorization", "Bearer " + openaiKey)
                    //                                .header("Content-Type", "application/json")
                    //                                .POST(HttpRequest.BodyPublishers.ofString(body))
                    //                                .build();
                    //
                    //                        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    //                                .orTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    //                                .whenComplete((resp, err) -> {
                    //                                    if (err != null) {
                    //                                        msg.replyTo().tell(new LLMResponse("LLM error: " + err.getMessage()));
                    //                                        return;
                    //                                    }
                    //
                    //                                    int sc = resp.statusCode();
                    //                                    String respBody = resp.body();
                    //
                    //                                    if (sc == 200) {
                    //                                        try {
                    //                                            JsonNode root = mapper.readTree(respBody);
                    //                                            String text = root.path("choices").path(0).path("message").path("content").asText("");
                    //                                            if (text.isBlank()) {
                    //                                                text = "LLM empty response";
                    //                                            }
                    //                                            msg.replyTo().tell(new LLMResponse(text));
                    //                                        } catch (Exception parse) {
                    //                                            msg.replyTo().tell(new LLMResponse("LLM parse error"));
                    //                                        }
                    //                                        return;
                    //                                    }
                    //
                    //                                    // Try to extract OpenAI error shape
                    //                                    String errType = "", errMsg = "";
                    //                                    try {
                    //                                        JsonNode er = mapper.readTree(respBody).path("error");
                    //                                        errType = er.path("type").asText("");
                    //                                        errMsg = er.path("message").asText("");
                    //                                    } catch (Exception ignore) {
                    //                                    }
                    //
                    //                                    if (sc == 429 && msg.attempt() <= (1 + MAX_RETRIES)) {
                    //                                        long delayMillis = retryDelayMillis(resp, msg.attempt());
                    //                                        ctx.getLog().warn("LLM 429 ({}). Retrying {}/{} in {} ms",
                    //                                                errType.isEmpty() ? "rate_limited" : errType, msg.attempt(), (1 + MAX_RETRIES), delayMillis);
                    //                                        ctx.getSystem().scheduler().scheduleOnce(
                    //                                                Duration.ofMillis(delayMillis),
                    //                                                () -> ctx.getSelf().tell(new DoChat(msg.prompt(), msg.replyTo(), msg.attempt() + 1)),
                    //                                                ctx.getExecutionContext()
                    //                                        );
                    //                                        return;
                    //                                    }
                    //
                    //                                    if (sc == 401) {
                    //                                        msg.replyTo().tell(new LLMResponse("LLM HTTP 401 (check API key)"));
                    //                                    } else if (sc == 429) {
                    //                                        String text = "insufficient_quota".equals(errType)
                    //                                                ? "LLM 429 insufficient_quota (add billing/credits)"
                    //                                                : "LLM HTTP 429 (rate limited)";
                    //                                        msg.replyTo().tell(new LLMResponse(text));
                    //                                    } else {
                    //                                        String text = "LLM HTTP " + sc + (errMsg.isEmpty() ? "" : (": " + errMsg));
                    //                                        msg.replyTo().tell(new LLMResponse(text));
                    //                                    }
                    //                                });
                    //
                    //                        return Behaviors.same();
                    //                    })
                    .build();
        });
    }
}
