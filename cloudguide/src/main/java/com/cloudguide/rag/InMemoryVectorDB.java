/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.rag;

/**
 *
 * @author rachanakeshav
 */

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVectorDB implements VectorDB {

  // Store the interface's Doc record objects
  private final Map<String, VectorDB.Doc> byId = new ConcurrentHashMap<>();

  @Override
  public void upsert(VectorDB.Doc doc) {
    byId.put(doc.id(), doc);
  }

  @Override
  public void upsertBatch(List<VectorDB.Doc> docs) {
    for (var d : docs) byId.put(d.id(), d);
  }

  @Override
  public List<VectorDB.SearchHit> topK(float[] queryEmbedding, int k) {
    if (k <= 0 || byId.isEmpty()) return List.of();

    // Max-heap by negative similarity to keep top K
    PriorityQueue<Map.Entry<String, VectorDB.Doc>> pq =
        new PriorityQueue<>(Comparator.comparingDouble(e -> -cosine(queryEmbedding, e.getValue().embedding())));

    for (var e : byId.entrySet()) pq.offer(e);

    List<VectorDB.SearchHit> hits = new ArrayList<>(Math.min(k, pq.size()));
    for (int i = 0; i < k && !pq.isEmpty(); i++) {
      var e = pq.poll();
      var d = e.getValue();
      float score = (float) cosine(queryEmbedding, d.embedding());
      hits.add(new VectorDB.SearchHit(d.id(), d.docId(), d.text(), score));
    }
    return hits;
  }

  private static double cosine(float[] a, float[] b) {
    int n = Math.min(a.length, b.length);
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < n; i++) {
      dot += a[i] * b[i];
      na  += a[i] * a[i];
      nb  += b[i] * b[i];
    }
    return (na == 0 || nb == 0) ? -1.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }
}
