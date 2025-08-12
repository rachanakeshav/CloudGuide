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

public class RoutingActor {

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

        public FinalAnswer(String userId, String text) {
            this.userId = userId;
            this.text = text;
        }
    }

    private final ActorRef<com.cloudguide.actors.PricingActor.Command> pricing;
    private final ActorRef<LogEnvelope> logger;
    private final ActorContext<Command> ctx;

    public static Behavior<Command> create(
            ActorRef<com.cloudguide.actors.PricingActor.Command> pricing,
            ActorRef<LogEnvelope> logger) {
        return Behaviors.setup(ctx -> new RoutingActor(ctx, pricing, logger).behavior());
    }

    private RoutingActor(ActorContext<Command> ctx,
            ActorRef<com.cloudguide.actors.PricingActor.Command> pricing,
            ActorRef<LogEnvelope> logger) {
        this.ctx = ctx;
        this.pricing = pricing;
        this.logger = logger;
    }

    private Behavior<Command> behavior() {
        return Behaviors.receive(Command.class)
                .onMessage(UserQuery.class, this::onUserQuery)
                .build();
    }

    private Behavior<Command> onUserQuery(UserQuery msg) {
        logger.tell(new LogEnvelope("user.query", msg.text));

        String t = msg.text.toLowerCase();
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
                    msg.replyTo.tell(new FinalAnswer(msg.userId, "No pricing found (or error)."));
                } else {
                    var q = res.quote();
                    String snippet = String.format(
                            "[%s] %s | region=%s | sku=%s | price=%.4f %s (%s)",
                            q.provider(), q.serviceName(), q.region(), q.skuName(),
                            q.retailPrice(), q.currencyCode(), q.unitOfMeasure()
                    );
                    msg.replyTo.tell(new FinalAnswer(msg.userId, snippet));
                }
            });

        } else {
            msg.replyTo.tell(new FinalAnswer(msg.userId, "Not a pricing query."));
        }

        return Behaviors.same();
    }
}
