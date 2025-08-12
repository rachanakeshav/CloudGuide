/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.pricing;

/**
 *
 * @author rachanakeshav
 */

import java.util.concurrent.CompletableFuture;
import com.cloudguide.pricing.PricingModels.PricingQuery;
import com.cloudguide.pricing.PricingModels.PricingResult;

public interface PricingProvider {
  boolean supports(String providerKey);               
  CompletableFuture<PricingResult> fetch(PricingQuery query);
}
