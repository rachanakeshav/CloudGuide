/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide;

/**
 *
 * @author rachanakeshav
 */
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.ActorContext;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import com.cloudguide.actors.LoggingActor;
import com.cloudguide.actors.LoggingActor.LogEnvelope;
import com.cloudguide.actors.PricingActor;
import com.cloudguide.actors.RoutingActor;
import com.cloudguide.pricing.AzurePricingProvider;

import java.time.Duration;

public class Boot {
  public static void main(String[] args) {
    String host = System.getProperty("HOST", "127.0.0.1");
    String port = System.getProperty("PORT", "2551");
    String role = System.getProperty("ROLE", "api");

    Config overrides = ConfigFactory.parseString(
        "akka.remote.artery.canonical.hostname=\"" + host + "\"\n" +
        "akka.remote.artery.canonical.port=" + port + "\n" +
        "akka.cluster.roles=[\"" + role + "\"]"
    );
    Config config = overrides.withFallback(ConfigFactory.load());

    ActorSystem<Void> system = ActorSystem.create(
        Behaviors.setup((ActorContext<Void> ctx) -> {
          ctx.getLog().info("Node up | role={} | {}:{}", role, host, port);

          if ("api".equals(role)) {
            // Logger
            ActorRef<LogEnvelope> logger = ctx.spawn(LoggingActor.create(), "logging-actor");

            // Pricing provider + actor
            var provider = new AzurePricingProvider();
            ActorRef<PricingActor.Command> pricing =
                ctx.spawn(PricingActor.create(provider), "pricing-actor");

            // Router
            ActorRef<RoutingActor.Command> router =
                ctx.spawn(RoutingActor.create(pricing, logger), "routing-actor");

            // Sink to display final answer (demo only)
            ActorRef<RoutingActor.FinalAnswer> sink =
                ctx.spawnAnonymous(
                    Behaviors.<RoutingActor.FinalAnswer>setup((ActorContext<RoutingActor.FinalAnswer> innerCtx) ->
                        Behaviors.receive(RoutingActor.FinalAnswer.class)
                            .onMessage(RoutingActor.FinalAnswer.class, fa -> {
                              innerCtx.getLog().info("FINAL ANSWER ({}): {}", fa.userId, fa.text);
                              return Behaviors.same();
                            })
                            .build()
                    )
                );

            // Fire one demo query that should hit pricing
            ctx.getSystem().scheduler().scheduleOnce(
                Duration.ofSeconds(1),
                () -> {
                  var text = "price azure storage premium lrs in southcentralus";
                  router.tell(new RoutingActor.UserQuery("u1", text, sink));
                },
                ctx.getExecutionContext()
            );
          }

          return Behaviors.empty();
        }),
        "CloudGuideSystem",
        config
    );

    Runtime.getRuntime().addShutdownHook(new Thread(system::terminate));
  }
}