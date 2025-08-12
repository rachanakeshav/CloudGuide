/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.pricing;

/**
 *
 * @author rachanakeshav
 */
import com.cloudguide.pricing.PricingModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;


public class AzurePricingProvider implements PricingProvider {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(8))
      .build();
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public boolean supports(String key) {
    return "azure".equalsIgnoreCase(key);
  }

  @Override
  public CompletableFuture<PricingResult> fetch(PricingQuery q) {
    // Build Azure Retail Prices filter
    StringBuilder f = new StringBuilder();
    f.append("serviceName eq '").append(esc(q.serviceName())).append("'")
     .append(" and armRegionName eq '").append(esc(q.region())).append("'")
     .append(" and type eq '").append(esc(q.priceType())).append("'");

    if (q.currencyCode() != null && !q.currencyCode().isBlank()) {
      f.append(" and currencyCode eq '").append(esc(q.currencyCode())).append("'");
    }
    if (q.skuContains() != null && !q.skuContains().isBlank()) {
      String s = esc(q.skuContains());
      f.append(" and (contains(skuName,'").append(s).append("')")
       .append(" or contains(armSkuName,'").append(s).append("')")
       .append(" or contains(meterName,'").append(s).append("'))");
    }

    String url = "https://prices.azure.com/api/retail/prices?$filter=" +
        URLEncoder.encode(f.toString(), StandardCharsets.UTF_8);

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("User-Agent", "CloudGuide/1.0")
        .GET()
        .build();

    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
      .orTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
      .thenApply(resp -> {
        if (resp.statusCode() != 200) {
          return new PricingResult(null, 0, "http " + resp.statusCode());
        }
        try {
          JsonNode root = mapper.readTree(resp.body());
          JsonNode items = root.has("Items") ? root.get("Items") : root.path("items");
          int count = (items != null && items.isArray()) ? items.size() : 0;
          if (count == 0) return new PricingResult(null, 0, "no items");
          JsonNode it = items.get(0); // v1: first match
          PricingQuote quote = new PricingQuote(
              "azure",
              txt(it, "serviceName"),
              txt(it, "armRegionName"),
              txt(it, "skuName"),
              txt(it, "meterName"),
              txt(it, "unitOfMeasure"),
              num(it, "unitPrice"),
              num(it, "retailPrice"),
              txt(it, "currencyCode"),
              txt(it, "effectiveStartDate"),
              txt(it, "productName")
          );
          return new PricingResult(quote, count, "ok");
        } catch (Exception e) {
          return new PricingResult(null, 0, "parse error");
        }
      })
      .exceptionally(e -> new PricingResult(null, 0, "error: " + e.getMessage()));
  }

  private static String esc(String s){ return s.replace("'", "''"); }
  private static String txt(JsonNode n,String f){ var v=n.get(f); return v!=null&&!v.isNull()?v.asText():""; }
  private static double num(JsonNode n,String f){ var v=n.get(f); return v!=null&&v.isNumber()?v.asDouble():Double.NaN; }
}