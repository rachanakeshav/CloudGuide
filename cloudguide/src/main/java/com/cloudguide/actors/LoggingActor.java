/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.actors;

/**
 *
 * @author rachanakeshav
 */
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import com.cloudguide.CborSerializable;
import akka.actor.typed.ActorRef;

public class LoggingActor {

    public interface Command extends CborSerializable {
    }

    public static final class LogEnvelope implements Command {

        public final String category, payload;

        public LogEnvelope(String category, String payload) {
            this.category = category;
            this.payload = payload;
        }
    }

    public static final class ForwardDemo implements Command {

        public final String note, userId;
        public final ActorRef<com.cloudguide.actors.RoutingActor.FinalAnswer> originalReplyTo;

        public ForwardDemo(String note,
                ActorRef<com.cloudguide.actors.RoutingActor.FinalAnswer> originalReplyTo,
                String userId) {
            this.note = note;
            this.originalReplyTo = originalReplyTo;
            this.userId = userId;
        }
    }

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx
                -> Behaviors.receive(Command.class)
                        .onMessage(LogEnvelope.class, m -> {
                            ctx.getLog().info("[{}] {}", m.category, m.payload);
                            return Behaviors.same();
                        })
                        .onMessage(ForwardDemo.class, m -> {
                            ctx.getLog().info("[forward.demo] {}", m.note);
                            m.originalReplyTo.tell(new com.cloudguide.actors.RoutingActor.FinalAnswer(m.userId, "forward-ack from Logger",
                                    com.cloudguide.actors.RoutingActor.Source.LLM));
                            return Behaviors.same();
                        })
                        .build()
        );
    }
}
