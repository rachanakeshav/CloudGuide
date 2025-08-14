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
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

public class QdrantVectorDB implements VectorDB {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String base;
    private final String collection;    // cloudguide_docs
    private final int dim;              // embedding dimension 
    private final String distance;      // "Cosine" 
    private final int upsertBatch;
    private final Duration timeout;

    public QdrantVectorDB(String host, int port, String collection,
            int dim, String distance, int upsertBatch, Duration timeout) {
        this.base = "http://" + host + ":" + port;
        this.collection = collection;
        this.dim = dim;
        this.distance = distance;
        this.upsertBatch = Math.max(1, upsertBatch);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Call on startup to ensure collection exists with correct dim + distance.
     */
    public void ensureCollection() {
        try {
            // Check if exists
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/collections/" + collection))
                    .timeout(timeout)
                    .GET().build();

            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return; // OK
            }
            // Create
            ObjectNode vectorsConfig = mapper.createObjectNode();
            vectorsConfig.put("size", dim);
            vectorsConfig.put("distance", distance);

            ObjectNode vectorsCfg = mapper.createObjectNode();
            vectorsCfg.set("default", vectorsConfig);

            ObjectNode body = mapper.createObjectNode();
            body.set("vectors", vectorsCfg);

            var createReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/collections/" + collection))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
            if (createResp.statusCode() / 100 != 2) {
                throw new RuntimeException("Qdrant create collection HTTP " + createResp.statusCode() + ": " + createResp.body());
            }
        } catch (Exception e) {
            throw new RuntimeException("ensureCollection failed: " + e, e);
        }
    }

    @Override
    public void upsert(Doc doc) {
        upsertBatch(List.of(doc));
    }

    @Override
    public void upsertBatch(List<Doc> docs) {
        // Split into batches
        for (int i = 0; i < docs.size(); i += upsertBatch) {
            List<Doc> batch = docs.subList(i, Math.min(docs.size(), i + upsertBatch));
            doUpsert(batch);
        }
    }

    private void doUpsert(List<Doc> docs) {
        try {
            ArrayNode points = mapper.createArrayNode();
            for (Doc d : docs) {
                if (d.embedding() == null || d.embedding().length != dim) {
                    throw new IllegalArgumentException("Embedding dim mismatch for id=" + d.id()
                            + " got=" + (d.embedding() == null ? "null" : d.embedding().length)
                            + " expected=" + dim);
                }
                ObjectNode p = mapper.createObjectNode();

                // Use UUIDs (Qdrant requires UUID or unsigned int)
                p.put("id", java.util.UUID.nameUUIDFromBytes(
                        d.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ).toString());

                ArrayNode vec = mapper.createArrayNode();
                for (float v : d.embedding()) {
                    vec.add(v);
                }
                ObjectNode vecObj = mapper.createObjectNode();
                vecObj.set("default", vec);
                p.set("vector", vecObj);

                // Payload
                ObjectNode payload = mapper.createObjectNode();
                payload.put("docId", d.docId());
                payload.put("source", d.id());
                payload.put("text", d.text());
                p.set("payload", payload);

                points.add(p);
            }

            ObjectNode body = mapper.createObjectNode();
            body.set("points", points);

            var req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/collections/" + collection + "/points?wait=true"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Qdrant upsert HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (Exception e) {
            throw new RuntimeException("Qdrant upsert failed: " + e, e);
        }
    }

    @Override
    public List<SearchHit> topK(float[] queryEmbedding, int k) {
        try {
            if (queryEmbedding == null || queryEmbedding.length != dim) {
                throw new IllegalArgumentException("Query embedding dim mismatch (got "
                        + (queryEmbedding == null ? "null" : queryEmbedding.length) + ", expected " + dim + ")");
            }

            // vector array
            ArrayNode vec = mapper.createArrayNode();
            for (float v : queryEmbedding) {
                vec.add(v);
            }

            ObjectNode named = mapper.createObjectNode();
            named.put("name", "default");
            named.set("vector", vec);

            ObjectNode body = mapper.createObjectNode();
            body.set("vector", named);
            body.put("limit", k);
            body.put("with_payload", true);
            body.put("with_vector", false);

            var req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/collections/" + collection + "/points/search"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Qdrant search HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode result = root.get("result");
            if (result == null || !result.isArray()) {
                return List.of();
            }

            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode r : result) {
                String id = r.path("id").asText();
                double score = r.path("score").asDouble(0.0);
                JsonNode payload = r.path("payload");
                String docId = payload.path("docId").asText("");
                String text = payload.path("text").asText("");
                hits.add(new SearchHit(id, docId, text, (float) score));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant search failed: " + e, e);
        }
    }
}
