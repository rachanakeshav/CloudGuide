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
import com.cloudguide.pricing.PricingProvider;
import com.cloudguide.pricing.PricingModels.*;

public class PricingActor {

    public interface Command extends CborSerializable {
    }

    public static final class QueryPricing implements Command {

        public final PricingQuery query;
        public final ActorRef<PricingResult> replyTo;

        public QueryPricing(PricingQuery q, ActorRef<PricingResult> r) {
            this.query = q;
            this.replyTo = r;
        }
    }

    private static final class PriceFetched implements Command {

        final PricingResult result;
        final ActorRef<PricingResult> replyTo;

        PriceFetched(PricingResult r, ActorRef<PricingResult> to) {
            this.result = r;
            this.replyTo = to;
        }
    }

    public static Behavior<Command> create(PricingProvider provider) {
        return Behaviors.setup(ctx -> new PricingActor(ctx, provider).behavior());
    }

    private final ActorContext<Command> ctx;
    private final PricingProvider provider;

    private PricingActor(ActorContext<Command> ctx, PricingProvider provider) {
        this.ctx = ctx;
        this.provider = provider;

    }

    private Behavior<Command> behavior() {
        return Behaviors.receive(Command.class
        )
                .onMessage(QueryPricing.class,
                         this::onQuery)
                .onMessage(PriceFetched.class,
                         msg -> {
                            msg.replyTo.tell(msg.result);

                            return Behaviors.same();
                        }
                )
                .build();
    }

    private Behavior<Command> onQuery(QueryPricing msg) {
        if (!provider.supports(msg.query.provider())) {
            msg.replyTo.tell(new PricingResult(null, 0, "unsupported provider"));
            return Behaviors.same();
        }
        ctx.getLog().info("PricingActor: fetching {}", msg.query);
        ctx.pipeToSelf(
                provider.fetch(msg.query),
                (res, err) -> err == null ? new PriceFetched(res, msg.replyTo)
                        : new PriceFetched(new PricingResult(null, 0, "error: " + err.getMessage()), msg.replyTo)
        );
        return Behaviors.same();
    }
}
