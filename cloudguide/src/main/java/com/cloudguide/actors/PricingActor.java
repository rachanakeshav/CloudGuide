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

import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

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

        final PricingQuery query;
        final PricingResult result;
        final ActorRef<PricingResult> replyTo;

        PriceFetched(PricingQuery query, PricingResult result, ActorRef<PricingResult> replyTo) {
            this.query = query;
            this.result = result;
            this.replyTo = replyTo;
        }
    }

    public static Behavior<Command> create(PricingProvider provider) {
        return Behaviors.setup(ctx -> new PricingActor(ctx, provider).behavior());
    }

    private final ActorContext<Command> ctx;
    private final PricingProvider provider;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long ttlSeconds = 600;

    private PricingActor(ActorContext<Command> ctx, PricingProvider provider) {
        this.ctx = ctx;
        this.provider = provider;
    }

    private static final class CacheEntry {

        final PricingResult result;
        final Instant at;

        CacheEntry(PricingResult result) {
            this.result = result;
            this.at = Instant.now();
        }
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
        String key = msg.query.toString();
        CacheEntry cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.at.plusSeconds(ttlSeconds))) {
            msg.replyTo.tell(cached.result);
            return Behaviors.same();
        }
        ctx.getLog().info("PricingActor: fetching {}", msg.query);
        ctx.pipeToSelf(
                provider.fetch(msg.query),
                (res, err) -> {
                    if (err != null) {
                        String e = "error: " + err.getMessage();
                        return new PriceFetched(msg.query, new PricingResult(null, 0, e), msg.replyTo);
                    } else {
                        return new PriceFetched(msg.query, res, msg.replyTo);
                    }
                }
        );
        return Behaviors.same();
    }

    private Behavior<Command> onPriceFetched(PriceFetched msg) {
        if (msg.result != null && msg.result.quote() != null) {
            cache.put(msg.query.toString(), new CacheEntry(msg.result));
        }
        msg.replyTo.tell(msg.result);
        return Behaviors.same();
    }
}
