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

public class LoggingActor {
  public static final class LogEnvelope implements CborSerializable {
    public final String category; public final String payload;
    public LogEnvelope(String category, String payload){ this.category=category; this.payload=payload; }
  }

  public static Behavior<LogEnvelope> create() {
    return Behaviors.setup(ctx ->
      Behaviors.receive(LogEnvelope.class)
        .onMessage(LogEnvelope.class, m -> {
          ctx.getLog().info("[{}] {}", m.category, m.payload);
          return Behaviors.same();
        })
        .build()
    );
  }
}
