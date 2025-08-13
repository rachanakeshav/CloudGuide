/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.actors;

/**
 *
 * @author rachanakeshav
 */
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.ActorContext;

import com.cloudguide.CborSerializable;
import com.cloudguide.actors.LoggingActor.LogEnvelope;
import com.cloudguide.pricing.PricingModels.PricingQuery;
import com.cloudguide.pricing.PricingModels.PricingResult;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import static akka.actor.typed.javadsl.AskPattern.ask;

import com.cloudguide.actors.LLMGateway;
import com.cloudguide.actors.LLMActor;

public class RoutingActor {

    public enum Source {
        PRICING, LLM
    }

    public interface Command extends CborSerializable {
    }

    public static final class UserQuery implements Command {

        public final String userId;
        public final String text;
        public final ActorRef<FinalAnswer> replyTo;

        public UserQuery(String userId, String text, ActorRef<FinalAnswer> replyTo) {
            this.userId = userId;
            this.text = text;
            this.replyTo = replyTo;
        }
    }

    public static final class FinalAnswer implements CborSerializable {

        public final String userId;
        public final String text;
        public final Source source;

        public FinalAnswer(String userId, String text, Source source) {
            this.userId = userId;
            this.text = text;
            this.source = source;
        }
    }

    private final ActorRef<com.cloudguide.actors.PricingActor.Command> pricing;
    private final ActorRef<LogEnvelope> logger;
    private final ActorContext<Command> ctx;
    private final ActorRef<LLMGateway.Command> llmGateway;
    private final ActorRef<RetrievalActor.Command> retriever;
    private final java.time.Duration routerToLlmTimeout;

    public static Behavior<Command> create(
            ActorRef<com.cloudguide.actors.PricingActor.Command> pricing,
            ActorRef<LogEnvelope> logger, ActorRef<LLMGateway.Command> llmGateway, ActorRef<RetrievalActor.Command> retriever) {
        return Behaviors.setup(ctx -> {
            Duration llmTimeout;
            try {
                var d = ctx.getSystem().settings().config()
                        .getDuration("cloudguide.timeouts.router-to-llm");
                llmTimeout = Duration.ofSeconds(d.getSeconds());
            } catch (Exception ignore) {
                llmTimeout = Duration.ofSeconds(25);
            }
            return new RoutingActor(ctx, pricing, logger, llmGateway, retriever, llmTimeout).behavior();
        });
    }

    private RoutingActor(ActorContext<Command> ctx,
            ActorRef<com.cloudguide.actors.PricingActor.Command> pricing,
            ActorRef<LogEnvelope> logger, ActorRef<LLMGateway.Command> llmGateway, ActorRef<RetrievalActor.Command> retriever, Duration routerToLlmTimeout) {
        this.ctx = ctx;
        this.pricing = pricing;
        this.logger = logger;
        this.llmGateway = llmGateway;
        this.routerToLlmTimeout = routerToLlmTimeout;
        this.retriever = retriever;
    }

    private Behavior<Command> behavior() {
        return Behaviors.receive(Command.class)
                .onMessage(UserQuery.class, this::onUserQuery)
                .build();
    }

    private Behavior<Command> onUserQuery(UserQuery msg) {
        logger.tell(new LogEnvelope("user.query", msg.text));

        String t = msg.text.toLowerCase();

        if (t.startsWith("ask:")) {
            String question = msg.text.substring(4).trim();
            retriever.tell(new RetrievalActor.AskWithContext(msg.userId, question, msg.replyTo));
            return Behaviors.same();
        }

        boolean looksPricing = t.contains("price") || t.contains("cost") || t.contains("pricing");

        if (looksPricing) {
            String region = t.contains("westus2") ? "westus2"
                    : t.contains("southcentralus") ? "southcentralus"
                    : t.contains("eastus2") ? "eastus2"
                    : t.contains("eastus") ? "eastus" : "westus2";
            String service = (t.contains("vm") || t.contains("virtual machine")) ? "Virtual Machines"
                    : t.contains("storage") ? "Storage" : "Storage";
            String sku = t.contains("archive grs") ? "Archive GRS"
                    : t.contains("premium lrs") ? "Premium LRS"
                    : t.contains("d2as") ? "D2as v5"
                    : t.contains("d2") ? "D2" : "D2as v5";

            PricingQuery pq = new PricingQuery("azure", service, region, sku, "Consumption", "USD");

            Duration timeout = Duration.ofSeconds(6);

            CompletionStage<PricingResult> fut
                    = ask(
                            pricing,
                            (ActorRef<PricingResult> replyTo)
                            -> new com.cloudguide.actors.PricingActor.QueryPricing(pq, replyTo),
                            timeout,
                            ctx.getSystem().scheduler()
                    );

            fut.whenComplete((res, err) -> {
                if (err != null || res == null || res.quote() == null) {
                    msg.replyTo.tell(new FinalAnswer(msg.userId, "No pricing found (or error).", Source.PRICING));
                    ctx.getLog().info("FINAL ANSWER ({} | {}): {}", msg.userId, Source.PRICING, "No pricing found (or error).");
                } else {
                    var q = res.quote();
                    String snippet = String.format(
                            "[%s] %s | region=%s | sku=%s | price=%.4f %s (%s)",
                            q.provider(), q.serviceName(), q.region(), q.skuName(),
                            q.retailPrice(), q.currencyCode(), q.unitOfMeasure()
                    );
                    msg.replyTo.tell(new FinalAnswer(msg.userId, snippet, Source.PRICING));
                }
            });

        } else {
            // Ask LLM for non-pricing queries
            CompletionStage<LLMActor.LLMResponse> fut
                    = ask(
                            llmGateway,
                            (ActorRef<LLMActor.LLMResponse> replyTo)
                            -> new LLMGateway.ForwardAskLLM(msg.text, replyTo),
                            routerToLlmTimeout,
                            ctx.getSystem().scheduler()
                    );

            fut.whenComplete((res, err) -> {
                String out = (err != null || res == null) ? "LLM error" : res.text();
                msg.replyTo.tell(new FinalAnswer(msg.userId, out, Source.LLM));
                ctx.getLog().info("FINAL ANSWER ({} | {}): {}", msg.userId, Source.LLM, out);
            });
        }

        return Behaviors.same();
    }
}
