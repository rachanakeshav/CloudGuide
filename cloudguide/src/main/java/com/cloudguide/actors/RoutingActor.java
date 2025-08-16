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
import java.util.Locale;
import static akka.actor.typed.javadsl.AskPattern.ask;

import com.cloudguide.actors.LLMGateway;
import com.cloudguide.actors.LLMActor;

public class RoutingActor {

    public enum Source {
        PRICING, LLM
    }

    private enum Plan {
        PRICING, RETRIEVAL_THEN_LLM, LLM_ONLY
    }

    private static final class Scratchpad {

        final String userId, text;
        final Plan plan;
        final long t0Nanos;
        final StringBuilder notes = new StringBuilder();

        Scratchpad(String userId, String text, Plan plan) {
            this.userId = userId;
            this.text = text;
            this.plan = plan;
            this.t0Nanos = System.nanoTime();
        }

        long ms() {
            return (System.nanoTime() - t0Nanos) / 1_000_000;
        }
    }

    private static final String[] AZURE_REGIONS = {
        "eastus", "eastus2", "westus", "westus2", "westus3",
        "centralus", "northcentralus", "southcentralus",
        "westcentralus",
        "canadacentral", "canadaeast",
        "brazilsouth", "brazilsoutheast",
        "northeurope", "westeurope", "swedencentral",
        "uksouth", "ukwest", "francecentral", "germanywestcentral",
        "switzerlandnorth", "norwayeast",
        "eastasia", "southeastasia", "japaneast", "japanwest",
        "australiaeast", "australiasoutheast", "australiacentral",
        "koreacentral", "koreasouth",
        "southindia", "centralindia", "westindia",
        "uaenorth", "israelcentral", "qatarcentral",
        "southafricanorth"
    };

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
    private final ActorRef<LoggingActor.Command> logger;
    private final ActorContext<Command> ctx;
    private final ActorRef<LLMGateway.Command> llmGateway;
    private final ActorRef<RetrievalActor.Command> retriever;
    private final java.time.Duration routerToLlmTimeout;

    public static Behavior<Command> create(
            ActorRef<com.cloudguide.actors.PricingActor.Command> pricing,
            ActorRef<LoggingActor.Command> logger, ActorRef<LLMGateway.Command> llmGateway, ActorRef<RetrievalActor.Command> retriever) {
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
            ActorRef<LoggingActor.Command> logger, ActorRef<LLMGateway.Command> llmGateway, ActorRef<RetrievalActor.Command> retriever, Duration routerToLlmTimeout) {
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

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }

// Try to detect provider (simple heuristic)
    private static String detectProvider(String t) {
        String n = norm(t);
        if (n.contains("aws") || n.contains("amazon")) {
            return "aws";
        }
        if (n.contains("gcp") || n.contains("googlecloud")) {
            return "gcp";
        }
        return "azure"; // default
    }

    private static String pickAzureRegion(String t, String fallback) {
        String n = norm(t);
        for (String r : AZURE_REGIONS) {
            if (n.contains(norm(r))) {
                return r;
            }
        }
        return fallback;
    }

    private static String detectService(String t) {
        String n = t.toLowerCase(Locale.ROOT);
        if (n.contains("vm") || n.contains("virtual machine") || n.contains("virtual machines") || n.contains("compute")) {
            return "Virtual Machines";
        }
        if (n.contains("storage") || n.contains("blob")) {
            return "Storage";
        }
        if (n.contains("cache")) {
            return "Redis Cache";
        }
        if (n.contains("analytics")) {
            return "Azure Synapse Analytics";
        }
        if (n.contains("db") || n.contains("database")) {
            return "Azure Database for MySQL";
        }
        return "Virtual Machines";
    }

    private static String parseVmSku(String t) {
        // Examples: normalize: d2as v5, D2as_v5, B2s, D4s v5, E2 v3, etc.
        var m1 = java.util.regex.Pattern
                .compile("\\b([bdefgilmnprstuvxz])(\\d{1,2})([a-z]{0,3})\\s*[_\\- ]?v\\s*(\\d)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m1.find()) {
            String family = m1.group(1).toUpperCase(Locale.ROOT);
            String size = m1.group(2);
            String suffix = m1.group(3) == null ? "" : m1.group(3).toLowerCase(Locale.ROOT);
            String ver = m1.group(4);
            String suf = suffix.isBlank() ? "" : suffix;
            // e.g., D2as v5
            return family + size + suf + " v" + ver;
        }
        // Simpler shape: B2s, D2s, E4a, F8 etc.
        var m2 = java.util.regex.Pattern
                .compile("\\b([bdefgilmnprstuvxz])(\\d{1,2})([a-z])\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m2.find()) {
            String family = m2.group(1).toUpperCase(Locale.ROOT);
            String size = m2.group(2);
            String suffix = m2.group(3).toLowerCase(Locale.ROOT);
            return family + size + suffix;
        }
        return null;
    }

    private static String parseStorageSku(String t) {
        String n = t.toLowerCase(Locale.ROOT);
        if (n.contains("archive") && n.contains("grs")) {
            return "Archive GRS";
        }
        if (n.contains("archive") && n.contains("lrs")) {
            return "Archive LRS";
        }
        if (n.contains("premium") && n.contains("lrs")) {
            return "Premium LRS";
        }
        if (n.contains("cool") && n.contains("lrs")) {
            return "Cool LRS";
        }
        if (n.contains("hot") && n.contains("lrs")) {
            return "Hot LRS";
        }
        if (n.contains("grs")) {
            return "GRS";
        }
        if (n.contains("lrs")) {
            return "LRS";
        }
        return "Standard LRS";
    }

    private Behavior<Command> onUserQuery(UserQuery msg) {
        final String t = msg.text.trim().toLowerCase(Locale.ROOT);

        // Plan selection
        final boolean isAsk = t.startsWith("ask:");
        final boolean looksPricing = t.contains("price") || t.contains("cost");
        final Plan plan = looksPricing ? Plan.PRICING : (isAsk ? Plan.RETRIEVAL_THEN_LLM : Plan.LLM_ONLY);

        Scratchpad sp = new Scratchpad(msg.userId, msg.text, plan);
        logger.tell(new LoggingActor.LogEnvelope("plan", plan.name() + " ms=" + sp.ms()));
        logger.tell(new LoggingActor.LogEnvelope("user.query", msg.text));

//        if (msg.userId.startsWith("u-forward-demo")) {
//            logger.tell(new LoggingActor.ForwardDemo("preserving original replyTo", msg.replyTo, msg.userId));
//        }
        switch (plan) {
            case PRICING: {
                // 1) decide provider (default azure)
                String provider = detectProvider(t);

                // 2) choose service
                String service = detectService(t);

                // 3) region (per provider; we only wired Azure list here)
                String region;
                if ("azure".equals(provider)) {
                    region = pickAzureRegion(t, "eastus"); // eastus as safe default
                } else {
                    // TODO: add AWS/GCP region pickers if you enable those providers
                    region = "us-east-1"; // safe default for non-azure, if you wire later
                }

                // 4) SKU guess
                String skuVal;
                if ("Virtual Machines".equals(service)) {
                    skuVal = parseVmSku(t);
                    if (skuVal == null) {
                        skuVal = "D2as v5"; // fallback VM size
                    }
                } else if ("Storage".equals(service)) {
                    skuVal = parseStorageSku(t);
                } else {
                    skuVal = "general";
                }
                final String sku = skuVal;

                // 5) build pricing query (you can later route by provider to different providers)
                PricingQuery pq = new PricingQuery(
                        provider, // "azure" | "aws" | "gcp"
                        service, // e.g., "Virtual Machines"
                        region, // e.g., "eastasia"
                        sku, // e.g., "D2as v5"
                        "Consumption", // price type
                        "USD" // currency
                );

                Duration timeout = Duration.ofSeconds(6);

                CompletionStage<PricingResult> fut = ask(
                        pricing,
                        (ActorRef<PricingResult> replyTo) -> new com.cloudguide.actors.PricingActor.QueryPricing(pq, replyTo),
                        timeout,
                        ctx.getSystem().scheduler()
                );

                fut.whenComplete((res, err) -> {
                    if (err != null || res == null || res.quote() == null) {
                        msg.replyTo.tell(new FinalAnswer(msg.userId,
                                "No pricing found for " + provider + " " + service + " in " + region + " (sku=" + sku + ").",
                                Source.PRICING));
                        ctx.getLog().info("FINAL ANSWER ({} | {}): {}", msg.userId, Source.PRICING, "No pricing found.");
                    } else {
                        var q = res.quote();
                        String snippet = String.format(
                                "[%s] %s | region=%s | sku=%s | price=%.4f %s (%s)",
                                q.provider(), q.serviceName(), q.region(), q.skuName(),
                                q.retailPrice(), q.currencyCode(), q.unitOfMeasure()
                        );
                        msg.replyTo.tell(new FinalAnswer(msg.userId, snippet, Source.PRICING));
                    }

                    logger.tell(new LoggingActor.LogEnvelope("pricing.done", "parsed provider=" + provider
                            + " service=" + service + " region=" + region + " sku=" + sku + " ms=" + sp.ms()));
                });
                break;
            }

            case RETRIEVAL_THEN_LLM: {
                int idx = t.indexOf("ask:");
                String question = msg.text.substring(idx + 4).trim();
                logger.tell(new LoggingActor.LogEnvelope("retrieval.start", ""));
                retriever.tell(new RetrievalActor.AskWithContext(msg.userId, question, msg.replyTo));
                logger.tell(new LoggingActor.LogEnvelope("retrieval.enqueued", ""));
                break;
            }

            case LLM_ONLY: {
                // 1) Classify with a short timeout
                CompletionStage<LLMActor.LLMResponse> gate
                        = ask(
                                llmGateway,
                                (ActorRef<LLMActor.LLMResponse> r) -> new LLMGateway.ForwardClassifyLLM(msg.text, r),
                                Duration.ofSeconds(4),
                                ctx.getSystem().scheduler()
                        );

                gate.whenComplete((g, ge) -> {
                    boolean allow = (ge == null && g != null && "ALLOW".equalsIgnoreCase(g.text()));
                    if (!allow) {
                        String redirect
                                = "I focus on cloud topics (AWS, Azure, GCP, pricing, architecture, DevOps/SRE). "
                                + "Ask me about cloud and I’ll help right away!";
                        msg.replyTo.tell(new FinalAnswer(msg.userId, redirect, Source.LLM));
                        logger.tell(new LoggingActor.LogEnvelope("llm.gate", "DECLINE ms=" + sp.ms()));
                        return;
                    }

                    // 2) Allowed → do the normal LLM call
                    CompletionStage<LLMActor.LLMResponse> fut
                            = ask(
                                    llmGateway,
                                    (ActorRef<LLMActor.LLMResponse> r) -> new LLMGateway.ForwardAskLLM(msg.text, r),
                                    routerToLlmTimeout,
                                    ctx.getSystem().scheduler()
                            );

                    fut.whenComplete((res, err) -> {
                        String out = (err != null || res == null) ? "LLM error" : res.text();
                        msg.replyTo.tell(new FinalAnswer(msg.userId, out, Source.LLM));
                        logger.tell(new LoggingActor.LogEnvelope("llm.done", "ms=" + sp.ms()));
                        ctx.getLog().info("FINAL ANSWER ({} | {}): {}", msg.userId, Source.LLM, out);
                    });
                });

                break;
            }
        }

        return Behaviors.same();
    }

}
