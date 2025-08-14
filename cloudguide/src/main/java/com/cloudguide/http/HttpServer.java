/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.http;

/**
 *
 * @author rachanakeshav
 */
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import com.cloudguide.actors.RoutingActor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import akka.http.javadsl.unmarshalling.Unmarshaller;

import static akka.actor.typed.javadsl.AskPattern.ask;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;

public class HttpServer extends AllDirectives {

    public CompletionStage<ServerBinding> start(ActorSystem<?> system,
            ActorRef<RoutingActor.Command> router,
            int port) {
        return Http.get(system).newServerAt("0.0.0.0", port).bind(routes(system, router));
    }

    private Route routes(ActorSystem<?> system, ActorRef<RoutingActor.Command> router) {
        var httpToRouterTimeout = Duration.ofSeconds(
                system.settings().config().getDuration("cloudguide.timeouts.http-to-router").getSeconds()
        );

        return concat(
                path("healthz", () -> get(() -> complete("ok"))),
                pathPrefix("api", ()
                        -> path("ask", () -> concat(
                // GET /api/ask?text=
                get(()
                        -> parameter("text", text -> {
                    // 1) ask router
                    CompletionStage<RoutingActor.FinalAnswer> fut
                            = ask(
                                    router,
                                    (ActorRef<RoutingActor.FinalAnswer> reply)
                                    -> new RoutingActor.UserQuery("http", text, reply),
                                    httpToRouterTimeout,
                                    system.scheduler()
                            );

                    // 2) LOG the final answer in API node console
                    fut.whenComplete((ans, err) -> {
                        if (err != null) {
                            system.log().error("HTTP FINAL error: {}", err.toString());
                        } else {
                            system.log().info("HTTP FINAL ({}): {}", ans.source, ans.text);
                        }
                    });

                    // 3) Map to JSON string for the HTTP response
                    CompletionStage<String> payload = fut.thenApply(ans -> json(ans.text, ans.source.name()));

                    return onSuccess(fut, ans -> {
                        java.util.List<HttpHeader> headers = java.util.List.of(
                                RawHeader.create("Access-Control-Allow-Origin", "*"),
                                RawHeader.create("Access-Control-Allow-Methods", "GET,POST,OPTIONS"),
                                RawHeader.create("Access-Control-Allow-Headers", "Content-Type"),
                                RawHeader.create("X-Source", ans.source.name()) // put source in header
                        );

                        return respondWithHeaders(
                                headers,
                                () -> complete(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, ans.text))
                        );
                    });
                })
                ),
                post(()
                        -> entity(Unmarshaller.entityToString(), body -> {
                    String text = extractTextField(body);
                    CompletionStage<RoutingActor.FinalAnswer> fut
                            = ask(
                                    router,
                                    (ActorRef<RoutingActor.FinalAnswer> reply)
                                    -> new RoutingActor.UserQuery("http", text, reply),
                                    httpToRouterTimeout,
                                    system.scheduler()
                            );

                    fut.whenComplete((ans, err) -> {
                        if (err != null) {
                            system.log().error("HTTP FINAL error: {}", err.toString());
                        } else {
                            system.log().info("HTTP FINAL ({}): {}", ans.source, ans.text);
                        }
                    });

                    CompletionStage<String> payload = fut.thenApply(ans -> json(ans.text, ans.source.name()));
                    return onSuccess(fut, ans -> {
                        java.util.List<HttpHeader> headers = java.util.List.of(
                                RawHeader.create("Access-Control-Allow-Origin", "*"),
                                RawHeader.create("Access-Control-Allow-Methods", "GET,POST,OPTIONS"),
                                RawHeader.create("Access-Control-Allow-Headers", "Content-Type"),
                                RawHeader.create("X-Source", ans.source.name()) // put source in header
                        );

                        return respondWithHeaders(
                                headers,
                                () -> complete(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, ans.text))
                        );
                    });
                })
                ),
                // Simple CORS preflight
                options(() -> respondWithHeaders(corsHeaders(), () -> complete("ok")))
        ))
                )
        );
    }

    private List<HttpHeader> corsHeaders() {
        return List.of(
                RawHeader.create("Access-Control-Allow-Origin", "*"),
                RawHeader.create("Access-Control-Allow-Methods", "GET,POST,OPTIONS"),
                RawHeader.create("Access-Control-Allow-Headers", "Content-Type")
        );
    }

    private static String json(String text, String source) {
        String safe = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"text\":\"" + safe + "\",\"source\":\"" + source + "\"}";
    }

    private static String extractTextField(String body) {
        int i = body.indexOf("\"text\"");
        if (i < 0) {
            return body;
        }
        int c = body.indexOf(':', i);
        int q1 = body.indexOf('"', c + 1);
        int q2 = body.indexOf('"', q1 + 1);
        if (c < 0 || q1 < 0 || q2 < 0) {
            return body;
        }
        return body.substring(q1 + 1, q2);
    }

}
