/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.cloudguide.rag;

/**
 *
 * @author rachanakeshav
 */
import java.util.List;

public interface VectorDB {

    record Doc(String id, String docId, String text, float[] embedding) {

    }

    record SearchHit(String id, String docId, String text, float score) {

    }

    void upsert(Doc doc);

    void upsertBatch(List<Doc> docs);

    List<SearchHit> topK(float[] queryEmbedding, int k);
}
