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

import com.cloudguide.actors.*;
import com.cloudguide.actors.LoggingActor.LogEnvelope;
import com.cloudguide.pricing.AzurePricingProvider;
import com.cloudguide.rag.*;
import com.cloudguide.http.HttpServer;

import java.time.Duration;

public class Boot {

    public static void main(String[] args) {
        String host = System.getProperty("HOST", "127.0.0.1");
        String port = System.getProperty("PORT", "2551");
        String role = System.getProperty("ROLE", "api");
        int httpPort = Integer.parseInt(System.getProperty("HTTP_PORT", "8080"));

        Config overrides = ConfigFactory.parseString(
                "akka.remote.artery.canonical.hostname=\"" + host + "\"\n"
                + "akka.remote.artery.canonical.port=" + port + "\n"
                + "akka.cluster.roles=[\"" + role + "\"]"
        );
        Config config = overrides.withFallback(ConfigFactory.load());

        ActorSystem<Void> system = ActorSystem.create(Behaviors.setup((ActorContext<Void> ctx) -> {
            ctx.getLog().info("Node up | role={} | {}:{}", role, host, port);

            if ("llm".equals(role)) {
                ctx.spawn(com.cloudguide.actors.LLMActor.create(), "llm-actor");
            }

            if ("api".equals(role)) {

                Config cg = ctx.getSystem().settings().config();
                String ollamaBaseUrl = cg.getString("cloudguide.ollama.base-url");
                String embedModel = cg.getString("cloudguide.ollama.embed-model");
                String storeKind = cg.getConfig("cloudguide.rag").getString("store");

                EmbeddingsProvider emb = new OllamaEmbeddingsProvider(ollamaBaseUrl, embedModel);
                VectorDB db;

                if ("qdrant".equalsIgnoreCase(storeKind)) {
                    Config qc = cg.getConfig("cloudguide.qdrant");
                    String qHost = qc.getString("host");
                    int qPort = qc.getInt("port");
                    String coll = qc.getString("collection");
                    int dim = qc.getInt("dim");
                    String dist = qc.getString("distance"); // "Cosine"
                    int batch = qc.getInt("upsert-batch");
                    Duration qTimeout = Duration.ofSeconds(qc.getDuration("timeout").getSeconds());
                    boolean allowFallback = qc.getBoolean("allow-fallback-to-memory");

                    QdrantVectorDB qvs = new QdrantVectorDB(qHost, qPort, coll, dim, dist, batch, qTimeout);
                    try {
                        qvs.ensureCollection();
                        db = qvs;
                        ctx.getLog().info("VectorDB: Qdrant {}:{} collection='{}' dim={} distance={}",
                                qHost, qPort, coll, dim, dist);
                    } catch (Exception e) {
                        if (allowFallback) {
                            ctx.getLog().warn("Qdrant unavailable ({}). Falling back to InMemoryVectorDB.", e.toString());
                            db = new InMemoryVectorDB();
                        } else {
                            throw e;
                        }
                    }
                } else {
                    db = new InMemoryVectorDB();
                    ctx.getLog().info("VectorDB: InMemory");
                }

                ActorRef<LoggingActor.Command> logger = ctx.spawn(LoggingActor.create(), "logging-actor");

                // Pricing
                var provider = new AzurePricingProvider();
                ActorRef<PricingActor.Command> pricing = ctx.spawn(PricingActor.create(provider), "pricing-actor");

                // LLM gateway (cluster discovery)
                ActorRef<LLMGateway.Command> gateway = ctx.spawn(LLMGateway.create(), "llm-gateway");

                // Retrieval
                ActorRef<RetrievalActor.Command> retriever
                        = ctx.spawn(RetrievalActor.create(emb, db, gateway), "retrieval-actor");

                // Router
                ActorRef<RoutingActor.Command> router
                        = ctx.spawn(RoutingActor.create(pricing, logger, gateway, retriever), "routing-actor");

                ActorRef<DocumentIngestorActor.Command> ingestor = ctx.spawn(DocumentIngestorActor.create(emb, db), "ingestor");

                ActorRef<RoutingActor.FinalAnswer> sink
                        = ctx.spawnAnonymous(
                                Behaviors.<RoutingActor.FinalAnswer>setup(inner
                                        -> Behaviors.receive(RoutingActor.FinalAnswer.class)
                                        .onMessage(RoutingActor.FinalAnswer.class, fa -> {
                                            inner.getLog().info("FINAL ANSWER ({} | {}): {}", fa.userId, fa.source, fa.text);
                                            return Behaviors.same();
                                        })
                                        .build()
                                )
                        );

                // HTTP server
                new HttpServer()
                        .start(ctx.getSystem(), router, httpPort)
                        .whenComplete((b, e) -> {
                            if (e != null) {
                                ctx.getLog().error("HTTP bind failed", e);
                            } else {
                                ctx.getLog().info("HTTP server bound on 0.0.0.0:{}", httpPort);
                            }
                        });

//                        ctx.getSystem().scheduler().scheduleOnce(
//                                java.time.Duration.ofSeconds(1),
//                                () -> {
//                                    var text = "price azure storage archive grs in westus2";
//                                    router.tell(new com.cloudguide.actors.RoutingActor.UserQuery("u1", text, sink));
//                                },
//                                ctx.getExecutionContext()
//                        );
//
//                        ctx.getSystem().scheduler().scheduleOnce(
//                                java.time.Duration.ofSeconds(5),
//                                () -> {
//                                    var text = "hello from api to remote llm";
//                                    router.tell(new com.cloudguide.actors.RoutingActor.UserQuery("u1", text, sink));
//                                },
//                                ctx.getExecutionContext()
//                        );
//                ctx.getSystem().scheduler().scheduleOnce(
//                        java.time.Duration.ofSeconds(5),
//                        () -> ingestor.tell(
//                                new DocumentIngestorActor.IngestPdf(
//                                        "aws-pricing", new java.io.File(System.getProperty("user.home") + "/cloudguide_docs/aws_pricing.pdf")
//                                )
//                        ),
//                        ctx.getExecutionContext()
//                );
//                ctx.getSystem().scheduler().scheduleOnce(
//                        java.time.Duration.ofSeconds(2),
//                        () -> router.tell(new RoutingActor.UserQuery("u-forward-demo-1", "hello", sink)),
//                        ctx.getExecutionContext()
//                );
            }

            return Behaviors.empty();
        }),
                "CloudGuideSystem",
                config
        );

        Runtime.getRuntime().addShutdownHook(new Thread(system::terminate));
    }
}
