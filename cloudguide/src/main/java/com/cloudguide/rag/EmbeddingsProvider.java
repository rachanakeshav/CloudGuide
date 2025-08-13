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
import java.util.concurrent.CompletionStage;

public interface EmbeddingsProvider {
  CompletionStage<float[]> embed(String text);
  CompletionStage<List<float[]>> embedBatch(List<String> texts);

  String name();
}