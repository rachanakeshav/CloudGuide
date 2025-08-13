/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.actors;

/**
 *
 * @author rachanakeshav
 */
import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import com.cloudguide.CborSerializable;
import com.cloudguide.rag.EmbeddingsProvider;
import com.cloudguide.rag.VectorDB;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletionStage;

public class DocumentIngestorActor {

  public interface Command extends CborSerializable {}

  public static final class IngestPdf implements Command {
    public final String docId;
    public final File file;
    public IngestPdf(String docId, File file) { this.docId = docId; this.file = file; }
  }

  public static Behavior<Command> create(EmbeddingsProvider embeddings, VectorDB store) {
    return Behaviors.setup(ctx -> new DocumentIngestorActor(ctx, embeddings, store).behavior());
  }

  private final ActorContext<Command> ctx;
  private final EmbeddingsProvider embeddings;
  private final VectorDB store;

  private DocumentIngestorActor(ActorContext<Command> ctx, EmbeddingsProvider e, VectorDB s) {
    this.ctx = ctx; this.embeddings = e; this.store = s;
  }

  private Behavior<Command> behavior() {
    return Behaviors.receive(Command.class)
      .onMessage(IngestPdf.class, this::onIngest)
      .onMessage(Wrapped.class, this::onWrapped)
      .build();
  }

  private Behavior<Command> onIngest(IngestPdf msg) {
    try (PDDocument pd = PDDocument.load(msg.file)) {
      var stripper = new PDFTextStripper();
      String text = stripper.getText(pd);
      List<String> chunks = chunk(text, 800, 150);
      CompletionStage<List<float[]>> fut = embeddings.embedBatch(chunks);
      ctx.pipeToSelf(fut, (vecs, err) -> new Wrapped(msg, chunks, vecs, err));
    } catch (Exception e) {
      ctx.getLog().error("Ingest failed {}", e.toString());
    }
    return Behaviors.same();
  }

  // internal
  private static final class Wrapped implements Command {
    final IngestPdf orig;
    final List<String> chunks;
    final List<float[]> vecs;
    final Throwable err;
    Wrapped(IngestPdf o, List<String> c, List<float[]> v, Throwable e) { orig=o; chunks=c; vecs=v; err=e; }
  }

  private Behavior<Command> onWrapped(Wrapped w) {
    if (w.err != null) {
      ctx.getLog().error("Embedding failed {}", w.err.toString());
      return Behaviors.same();
    }
    for (int i = 0; i < w.chunks.size(); i++) {
      String id = w.orig.docId + "#" + i;
      store.upsert(new VectorDB.Doc(id, w.orig.docId, w.chunks.get(i), w.vecs.get(i)));
    }
    ctx.getLog().info("Ingested {} chunks from {}", w.chunks.size(), w.orig.docId);
    return Behaviors.same();
  }

  private List<String> chunk(String text, int size, int overlap) {
    var out = new ArrayList<String>();
    text = text.replaceAll("\\s+"," ").trim();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + size);
      int dot = text.lastIndexOf('.', end);
      if (dot > start + size/2) end = dot + 1;
      out.add(text.substring(start, end).trim());
      if (end >= text.length()) break;
      start = Math.max(end - overlap, 0);
    }
    return out;
  }

  private DocumentIngestorActor behaviorWithWrapped() { return this; }
  {
  }
}
