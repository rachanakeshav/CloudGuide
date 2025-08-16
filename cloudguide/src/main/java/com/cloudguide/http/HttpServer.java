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
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import com.cloudguide.actors.RoutingActor;
import com.cloudguide.actors.DocumentIngestorActor;

import java.io.File;
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
            ActorRef<DocumentIngestorActor.Command> ingestor,
            int port) {
        return Http.get(system).newServerAt("0.0.0.0", port).bind(routes(system, router, ingestor));
    }

    private Route routes(ActorSystem<?> system,
            ActorRef<RoutingActor.Command> router,
            ActorRef<DocumentIngestorActor.Command> ingestor) {

        var httpToRouterTimeout = Duration.ofSeconds(
                system.settings().config().getDuration("cloudguide.timeouts.http-to-router").getSeconds()
        );

        return concat(
                path("healthz", () -> get(() -> complete("ok"))),
                pathPrefix("api", () -> concat(
                // /api/ask
                path("ask", () -> concat(
                get(() -> parameter("text", text -> {
            CompletionStage<RoutingActor.FinalAnswer> fut
                    = ask(router,
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

            return onSuccess(fut, ans -> respondWithHeaders(
                    List.of(
                            RawHeader.create("Access-Control-Allow-Origin", "*"),
                            RawHeader.create("Access-Control-Allow-Methods", "GET,POST,OPTIONS"),
                            RawHeader.create("Access-Control-Allow-Headers", "Content-Type"),
                            RawHeader.create("X-Source", ans.source.name())
                    ),
                    () -> complete(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, ans.text))
            ));
        })),
                post(() -> entity(Unmarshaller.entityToString(), body -> {
            String text = extractTextField(body);
            CompletionStage<RoutingActor.FinalAnswer> fut
                    = ask(router,
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

            return onSuccess(fut, ans -> respondWithHeaders(
                    List.of(
                            RawHeader.create("Access-Control-Allow-Origin", "*"),
                            RawHeader.create("Access-Control-Allow-Methods", "GET,POST,OPTIONS"),
                            RawHeader.create("Access-Control-Allow-Headers", "Content-Type"),
                            RawHeader.create("X-Source", ans.source.name())
                    ),
                    () -> complete(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, ans.text))
            ));
        })),
                options(() -> respondWithHeaders(corsHeaders(), () -> complete("ok")))
        )),
                // /api/ingest
                path("ingest", () -> concat(
                get(() -> parameter("docId", docId
                -> parameter("path", path -> {
                    var f = fileFromPath(path);
                    if (f == null || !f.exists()) {
                        return respondWithHeaders(corsHeaders(),
                                () -> complete(StatusCodes.BAD_REQUEST, "invalid or missing file"));
                    }
                    ingestor.tell(new DocumentIngestorActor.IngestPdf(docId, f));
                    return respondWithHeaders(corsHeaders(),
                            () -> complete(StatusCodes.ACCEPTED, "queued"));
                })
        )),
                post(() -> entity(Unmarshaller.entityToString(), body -> {
            String docId = extractField(body, "docId");
            String path = extractField(body, "path");
            var f = fileFromPath(path);
            if (docId.isBlank() || f == null || !f.exists()) {
                return respondWithHeaders(corsHeaders(),
                        () -> complete(StatusCodes.BAD_REQUEST, "invalid docId or file path"));
            }
            ingestor.tell(new DocumentIngestorActor.IngestPdf(docId, f));
            return respondWithHeaders(corsHeaders(),
                    () -> complete(StatusCodes.ACCEPTED, "queued"));
        })),
                options(() -> respondWithHeaders(corsHeaders(), () -> complete("ok")))
        ))
        ))
        );
    }

    private static File fileFromPath(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        if (p.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return new File(home + p.substring(1));
        }
        return new File(p);
    }

    private List<HttpHeader> corsHeaders() {
        return List.of(
                akka.http.javadsl.model.headers.RawHeader.create("Access-Control-Allow-Origin", "*"),
                akka.http.javadsl.model.headers.RawHeader.create("Access-Control-Allow-Methods", "GET,POST,OPTIONS"),
                akka.http.javadsl.model.headers.RawHeader.create("Access-Control-Allow-Headers", "Content-Type")
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

    private static String extractField(String body, String key) {
        try {
            int i = body.indexOf("\"" + key + "\"");
            if (i < 0) {
                return "";
            }
            int c = body.indexOf(':', i);
            int q1 = body.indexOf('"', c + 1);
            int q2 = body.indexOf('"', q1 + 1);
            return (c < 0 || q1 < 0 || q2 < 0) ? "" : body.substring(q1 + 1, q2);
        } catch (Exception ignore) {
            return "";
        }
    }

}
