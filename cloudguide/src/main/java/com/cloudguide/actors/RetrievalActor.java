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

import java.time.Duration;
import java.util.stream.Collectors;

import static akka.actor.typed.javadsl.AskPattern.ask;

public class RetrievalActor {

    public interface Command extends CborSerializable {
    }

    public static final class AskWithContext implements Command {

        public final String userId;
        public final String question;
        public final ActorRef<RoutingActor.FinalAnswer> replyTo;

        public AskWithContext(String userId, String question, ActorRef<RoutingActor.FinalAnswer> replyTo) {
            this.userId = userId;
            this.question = question;
            this.replyTo = replyTo;
        }
    }

    public static Behavior<Command> create(EmbeddingsProvider emb, VectorDB store, ActorRef<LLMGateway.Command> gateway) {
        return Behaviors.setup(ctx -> new RetrievalActor(ctx, emb, store, gateway).behavior());
    }

    private final ActorContext<Command> ctx;
    private final EmbeddingsProvider emb;
    private final VectorDB store;
    private final ActorRef<LLMGateway.Command> gateway;

    private RetrievalActor(ActorContext<Command> ctx, EmbeddingsProvider e, VectorDB s, ActorRef<LLMGateway.Command> g) {
        this.ctx = ctx;
        this.emb = e;
        this.store = s;
        this.gateway = g;
    }

    private Behavior<Command> behavior() {
        return Behaviors.receive(Command.class)
                .onMessage(AskWithContext.class, this::onAsk)
                .onMessage(Wrapped.class, this::onWrapped)
                .build();
    }

    private Behavior<Command> onAsk(AskWithContext msg) {
        ctx.pipeToSelf(emb.embed(msg.question), (vec, err) -> new Wrapped(msg, vec, err));
        return Behaviors.same();
    }

    private static final class Wrapped implements Command {

        final AskWithContext orig;
        final float[] vec;
        final Throwable err;

        Wrapped(AskWithContext o, float[] v, Throwable e) {
            orig = o;
            vec = v;
            err = e;
        }
    }

    private Behavior<Command> onWrapped(Wrapped w) {
        if (w.err != null) {
            w.orig.replyTo.tell(new RoutingActor.FinalAnswer(
                    w.orig.userId, "Embedding error", RoutingActor.Source.LLM));
            return Behaviors.same();
        }

        // knobs from config
        var conf = ctx.getSystem().settings().config().getConfig("cloudguide");
        int topK = conf.getConfig("rag").getInt("topK");                      
        int maxChars = conf.getConfig("rag").getInt("max-context-chars");     
        java.time.Duration llmTimeout;
        try {
            var d = conf.getConfig("timeouts").getDuration("router-to-llm");  
            llmTimeout = java.time.Duration.ofSeconds(d.getSeconds());
        } catch (Exception e) {
            llmTimeout = java.time.Duration.ofSeconds(25);
        }

        java.util.List<VectorDB.SearchHit> hits = store.topK(w.vec, topK);
        String context = hits.stream()
                .map(h -> "- " + h.text())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (context.length() > maxChars) {
            context = context.substring(0, Math.max(0, maxChars));
        }

        String prompt = "Use the context to answer concisely. If uncertain, say you are unsure.\n\n" + "Context:\n" + context + "\n\n"
                + "Question: " + w.orig.question;

        var fut = akka.actor.typed.javadsl.AskPattern.ask(
                gateway,
                (akka.actor.typed.ActorRef<LLMActor.LLMResponse> r) -> new LLMGateway.ForwardAskLLM(prompt, r),
                llmTimeout,
                ctx.getSystem().scheduler()
        );

        fut.whenComplete((res, err) -> {
            String out = (err != null || res == null) ? "LLM error" : res.text();
            w.orig.replyTo.tell(new RoutingActor.FinalAnswer(
                    w.orig.userId, out, RoutingActor.Source.LLM));
        });

        return Behaviors.same();
    }
}
