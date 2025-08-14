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
import akka.actor.typed.receptionist.Receptionist;
import com.cloudguide.CborSerializable;

import java.util.ArrayDeque;
import java.util.Set;

public class LLMGateway {

    public interface Command extends CborSerializable {
    }

    private static final class ListingUpdated implements Command {

        final Receptionist.Listing listing;

        ListingUpdated(Receptionist.Listing listing) {
            this.listing = listing;
        }
    }

    private static final class Pending {

        final String prompt;
        final ActorRef<LLMActor.LLMResponse> replyTo;

        Pending(String prompt, ActorRef<LLMActor.LLMResponse> replyTo) {
            this.prompt = prompt;
            this.replyTo = replyTo;
        }
    }

    private static final int MAX_BACKLOG = 50;

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx -> {
            ActorRef<Receptionist.Listing> adapter
                    = ctx.messageAdapter(Receptionist.Listing.class, ListingUpdated::new);
            ctx.getSystem().receptionist().tell(
                    Receptionist.subscribe(LLMActor.SERVICE_KEY, adapter));

            return active(ctx, null, new ArrayDeque<>());
        });
    }

    private final ActorContext<Command> ctx;

    private LLMGateway(ActorContext<Command> ctx) {
        this.ctx = ctx;
    }

    public static final class ForwardAskLLM implements Command {

        public final String prompt;
        public final ActorRef<LLMActor.LLMResponse> replyTo;

        public ForwardAskLLM(String p, ActorRef<LLMActor.LLMResponse> r) {
            this.prompt = p;
            this.replyTo = r;
        }
    }

    public static final class ForwardClassifyLLM implements Command {

        public final String text;
        public final ActorRef<LLMActor.LLMResponse> replyTo;

        public ForwardClassifyLLM(String t, ActorRef<LLMActor.LLMResponse> r) {
            this.text = t;
            this.replyTo = r;
        }
    }

    private static Behavior<Command> active(
            ActorContext<Command> ctx,
            ActorRef<LLMActor.Command> current,
            ArrayDeque<Pending> backlog
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(ListingUpdated.class, msg -> {
                    Set<ActorRef<LLMActor.Command>> set
                            = msg.listing.getServiceInstances(LLMActor.SERVICE_KEY);
                    ActorRef<LLMActor.Command> next = set.isEmpty() ? null : set.iterator().next();

                    if (next == null) {
                        ctx.getLog().info("LLMGateway: no LLM instances available");
                        return active(ctx, null, backlog);
                    } else {
                        ctx.getLog().info("LLMGateway: discovered LLM instance {}", next);
                        // flush backlog
                        while (!backlog.isEmpty()) {
                            Pending p = backlog.poll();
                            next.tell(new LLMActor.AskLLM(p.prompt, p.replyTo));
                        }
                        return active(ctx, next, backlog);
                    }
                })
                .onMessage(ForwardAskLLM.class, f -> {
                    if (current == null) {
                        if (backlog.size() < MAX_BACKLOG) {
                            backlog.add(new Pending(f.prompt, f.replyTo));
                            ctx.getLog().info("LLMGateway: buffering LLM ask ({} queued)", backlog.size());
                        } else {
                            f.replyTo.tell(new LLMActor.LLMResponse("LLM unavailable (queue full)"));
                        }
                    } else {
                        current.tell(new LLMActor.AskLLM(f.prompt, f.replyTo));
                    }
                    return Behaviors.same();
                })
                .onMessage(ForwardClassifyLLM.class, f -> {
                    if (current == null) {
                        if (backlog.size() < MAX_BACKLOG) {
                            backlog.add(new Pending("CLASSIFY:" + f.text, f.replyTo)); // simple reuse
                            ctx.getLog().info("LLMGateway: buffering classify ({} queued)", backlog.size());
                        } else {
                            f.replyTo.tell(new LLMActor.LLMResponse("ALLOW")); // fail-open
                        }
                    } else {
                        current.tell(new LLMActor.ClassifyTopic(f.text, f.replyTo));
                    }
                    return Behaviors.same();
                })
                .build();
    }
}
