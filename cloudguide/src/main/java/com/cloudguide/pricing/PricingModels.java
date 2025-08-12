/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide.pricing;

/**
 *
 * @author rachanakeshav
 */
import com.cloudguide.CborSerializable;

public final class PricingModels {
  // Input to PricingActor
  public record PricingQuery(
      String provider,       
      String serviceName,    
      String region,         
      String skuContains,    
      String priceType,      
      String currencyCode    
  ) implements CborSerializable {}

  public record PricingQuote(
      String provider,
      String serviceName,
      String region,
      String skuName,
      String meterName,
      String unitOfMeasure,
      double unitPrice,
      double retailPrice,
      String currencyCode,
      String effectiveStartDate,
      String productName
  ) implements CborSerializable {}

  // Actor reply 
  public record PricingResult(
      PricingQuote quote,        // null if not found
      int itemsReturned,         
      String note                
  ) implements CborSerializable {}
}
