/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.tools;

/**
 *
 * @author rachanakeshav
 */

import com.cloudguide.rag.*;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class IngestCli {

  public static void main(String[] args) throws Exception {
    // ---- args ----
    Map<String,String> cli = parseArgs(args);
    Path dir = Paths.get(cli.getOrDefault("--dir", System.getProperty("user.home") + "/cloudguide_docs"));
    String docIdPrefix = cli.getOrDefault("--doc-id-prefix", "manual");
    int batch = Integer.parseInt(cli.getOrDefault("--batch", "64"));
    int chunkSize = Integer.parseInt(cli.getOrDefault("--chunk", "800"));
    int overlap = Integer.parseInt(cli.getOrDefault("--overlap", "150"));

    // ---- config ----
    Config conf = ConfigFactory.load();
    String ollamaBase = conf.getString("cloudguide.ollama.base-url");
    String embedModel = conf.getString("cloudguide.ollama.embed-model");

    String storeKind = conf.getString("cloudguide.rag.store"); // "qdrant" or "memory"

    EmbeddingsProvider emb = new OllamaEmbeddingsProvider(ollamaBase, embedModel);
    VectorDB db;

    if ("qdrant".equalsIgnoreCase(storeKind)) {
      Config qc = conf.getConfig("cloudguide.qdrant");
      String host = qc.getString("host");
      int port = qc.getInt("port");
      String coll = qc.getString("collection");
      int dim = qc.getInt("dim");
      String dist = qc.getString("distance");
      int upsertBatch = qc.getInt("upsert-batch");
      Duration timeout = Duration.ofSeconds(qc.getDuration("timeout").getSeconds());

      QdrantVectorDB qvs = new QdrantVectorDB(host, port, coll, dim, dist, upsertBatch, timeout);
      qvs.ensureCollection(); // handles named "default" vector if configured that way
      db = qvs;

      System.out.printf("VectorDB: Qdrant %s:%d collection='%s' dim=%d distance=%s%n",
          host, port, coll, dim, dist);
    } else {
      db = new InMemoryVectorDB();
      System.out.println("VectorDB: InMemory");
    }

    // ---- scan + ingest ----
    System.out.printf("Ingesting from %s (chunk=%d overlap=%d batch=%d)%n",
        dir.toAbsolutePath(), chunkSize, overlap, batch);

    List<Path> files = Files.walk(dir)
        .filter(p -> Files.isRegularFile(p))
        .filter(p -> {
          String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
          return n.endsWith(".pdf") || n.endsWith(".txt") || n.endsWith(".json");
        })
        .sorted()
        .collect(Collectors.toList());

    int totalChunks = 0;
    for (Path p : files) {
      String docId = docIdPrefix + ":" + dir.relativize(p).toString().replace(File.separatorChar, '/');
      String text = extractText(p);
      if (text == null || text.isBlank()) {
        System.out.println("Skip (empty): " + p);
        continue;
      }
      text = normalize(text);
      List<String> chunks = chunk(text, chunkSize, overlap);

      // embed in batches
      for (int i = 0; i < chunks.size(); i += batch) {
        List<String> window = chunks.subList(i, Math.min(chunks.size(), i + batch));
        List<float[]> vecs = emb.embedBatch(window).toCompletableFuture().join();

        List<VectorDB.Doc> points = new ArrayList<>(window.size());
        for (int j = 0; j < window.size(); j++) {
          String id = docId + "#" + (i + j);
          points.add(new VectorDB.Doc(id, docId, window.get(j), vecs.get(j)));
        }
        db.upsertBatch(points);
      }

      totalChunks += chunks.size();
      System.out.printf("Ingested %d chunks from %s%n", chunks.size(), docId);
    }

    System.out.printf("DONE. Total chunks: %d%n", totalChunks);
  }

  // -------- helpers --------

  private static Map<String,String> parseArgs(String[] args) {
    Map<String,String> m = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.startsWith("--")) {
        String v = (i + 1 < args.length && !args[i+1].startsWith("--")) ? args[++i] : "true";
        m.put(a, v);
      }
    }
    return m;
  }

  private static String extractText(Path p) {
    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
    try {
      if (name.endsWith(".pdf")) {
        try (PDDocument pd = PDDocument.load(p.toFile())) {
          PDFTextStripper stripper = new PDFTextStripper();
          return stripper.getText(pd);
        }
      } else {
        // treat .txt or .json as text
        return Files.readString(p, StandardCharsets.UTF_8);
      }
    } catch (Exception e) {
      System.err.println("extractText failed for " + p + ": " + e);
      return null;
    }
  }

  private static String normalize(String s) {
    s = s.replace('\u0000', ' ');
    s = s.replaceAll("[\\r\\t]+", " ");
    s = s.replaceAll(" +", " ");
    s = s.replaceAll("\\s*\\n\\s*", "\n"); 
    s = s.trim();
    return s;
  }

  private static List<String> chunk(String text, int size, int overlap) {
    List<String> out = new ArrayList<>();
    String t = text.replaceAll("\\s+", " ").trim();
    int start = 0;
    while (start < t.length()) {
      int end = Math.min(t.length(), start + size);
      int dot = t.lastIndexOf('.', end);
      if (dot > start + size / 2) end = dot + 1; 
      out.add(t.substring(start, end).trim());
      if (end >= t.length()) break;
      start = Math.max(end - overlap, 0);
    }
    return out;
  }
}
