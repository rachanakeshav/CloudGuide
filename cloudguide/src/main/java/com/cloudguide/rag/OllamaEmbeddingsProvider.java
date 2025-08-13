/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.rag;

/**
 *
 * @author rachanakeshav
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class OllamaEmbeddingsProvider implements EmbeddingsProvider {
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper mapper = new ObjectMapper();
  private final String endpoint;
  private final String model;

  public OllamaEmbeddingsProvider(String baseUrl, String model) {
    this.endpoint = baseUrl.endsWith("/") ? baseUrl + "api/embeddings" : baseUrl + "/api/embeddings";
    this.model = model;
  }

  @Override public String name() { return "ollama:" + model; }

  @Override public CompletionStage<float[]> embed(String text) {
    return sendOne(text);
  }

  @Override public CompletionStage<List<float[]>> embedBatch(List<String> texts) {
    CompletableFuture<List<float[]>> all = new CompletableFuture<>();
    CompletableFuture.runAsync(() -> {
      try {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
          float[] vec = sendOne(t).toCompletableFuture().join();
          System.out.println("EMBED DIM=" + vec.length);
          out.add(vec);
        }
        all.complete(out);
      } catch (Throwable e) {
        all.completeExceptionally(e);
      }
    });
    return all;
  }

  private CompletionStage<float[]> sendOne(String text) {
    try {
      var payload = mapper.createObjectNode();
      payload.put("model", model);
      payload.put("prompt", text);

      var req = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(60))
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
          .build();

      return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
          .thenApply(resp -> {
            if (resp.statusCode() / 100 != 2) {
              throw new RuntimeException("Ollama embeddings HTTP " + resp.statusCode() + ": " + resp.body());
            }
            try {
              JsonNode root = mapper.readTree(resp.body());
              JsonNode emb = root.get("embedding");
              if (emb == null || !emb.isArray()) {
                JsonNode data = root.get("data");
                if (data != null && data.isArray() && data.size() > 0) {
                  JsonNode maybe = data.get(0).get("embedding");
                  if (maybe != null && maybe.isArray()) emb = maybe;
                }
              }
              if (emb == null || !emb.isArray()) {
                throw new RuntimeException("No 'embedding' array found in Ollama response");
              }
              float[] f = new float[emb.size()];
              for (int i = 0; i < emb.size(); i++) f[i] = (float) emb.get(i).asDouble();
              return f;
            } catch (Exception ex) {
              throw new CompletionException(ex);
            }
          });
    } catch (Exception ex) {
      CompletableFuture<float[]> cf = new CompletableFuture<>();
      cf.completeExceptionally(ex);
      return cf;
    }
  }
}