/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.pricing;

/**
 *
 * @author rachanakeshav
 */

import com.cloudguide.pricing.PricingModels.PricingQuery;
import com.cloudguide.pricing.PricingModels.PricingResult;
import com.cloudguide.pricing.PricingModels.PricingQuote;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AzureRetailPricingProvider implements PricingProvider {

    private static final String BASE =
        "https://prices.azure.com/api/retail/prices?api-version=2023-01-01-beta";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String providerKey) {
        return "azure".equalsIgnoreCase(providerKey);
    }

    @Override
    public CompletableFuture<PricingResult> fetch(PricingQuery q) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filter = and(
                    eq("serviceName", q.serviceName()),
                    eq("armRegionName", q.region()),
                    eq("type", q.priceType()),
                    eq("currencyCode", q.currencyCode()),
                    contains("skuName", q.skuContains())
                );

                String url = BASE + "&$filter=" + encode(filter);

                int scanned = 0;
                String next = url;
                PricingQuote best = null;

                while (next != null && scanned < 10) {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(next))
                            .timeout(Duration.ofSeconds(8))
                            .GET()
                            .build();

                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() / 100 != 2) {
                        return new PricingResult(null, 0, "Azure HTTP " + resp.statusCode());
                    }

                    JsonNode root = mapper.readTree(resp.body());
                    JsonNode items = root.path("Items");
                    int itemsCount = 0;
                    if (items.isArray()) {
                        for (JsonNode it : items) {
                            itemsCount++;
                            String armRegion = it.path("armRegionName").asText("");
                            String serviceName = it.path("serviceName").asText("");
                            String skuName     = it.path("skuName").asText("");
                            String meterName   = it.path("meterName").asText("");
                            String unit        = it.path("unitOfMeasure").asText("");
                            double retailPrice = it.path("retailPrice").asDouble(Double.NaN);
                            double unitPrice   = it.path("unitPrice").asDouble(Double.NaN);
                            String currency    = it.path("currencyCode").asText("USD");
                            String effective   = it.path("effectiveStartDate").asText("");
                            String productName = it.path("productName").asText("");

                            if (!Double.isFinite(retailPrice) || retailPrice <= 0.0) continue;

                            PricingQuote candidate = new PricingQuote(
                                "azure", serviceName, armRegion, skuName, meterName,
                                unit, unitPrice, retailPrice, currency, effective, productName
                            );

                            // Prefer first valid match, you can add better selection logic
                            if (best == null) best = candidate;
                        }
                    }

                    String nextLink = root.path("NextPageLink").asText(null);
                    next = (nextLink == null || nextLink.isBlank()) ? null : nextLink;
                    scanned++;

                    if (best != null) {
                        return new PricingResult(best, itemsCount, null);
                    }
                }

                return new PricingResult(null, 0, "No matching Azure retail price found");

            } catch (Exception e) {
                return new PricingResult(null, 0, "Azure pricing error: " + e.getMessage());
            }
        });
    }

    // --------- helpers ----------
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
    private static String esc(String s) {
        return s.replace("'", "''");
    }
    private static String eq(String field, String value) {
        if (value == null || value.isBlank()) return null;
        return field + " eq '" + esc(value.trim()) + "'";
    }
    private static String contains(String field, String value) {
        if (value == null || value.isBlank()) return null;
        return "contains(" + field + ", '" + esc(value.trim()) + "')";
    }
    private static String and(String... parts) {
        StringBuilder f = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (f.length() > 0) f.append(" and ");
            f.append(p);
        }
        return f.length() == 0 ? "true" : f.toString();
    }
}
