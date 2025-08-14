/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.cloudguide;

/**
 *
 * @author rachanakeshav
 */
public final class Main {
  public static void main(String[] args) {
    // mvn exec:java -Dexec.mainClass=com.cloudguide.Main
    if (System.getProperty("ROLE") == null) {
      System.setProperty("ROLE", "api");          // default to API node
    }
    if (System.getProperty("HOST") == null) {
      System.setProperty("HOST", "127.0.0.1");
    }
    if (System.getProperty("PORT") == null) {
      // default port per role, matches cluster seeds
      System.setProperty("PORT", "api".equals(System.getProperty("ROLE")) ? "2551" : "2552");
    }
    if ("api".equals(System.getProperty("ROLE")) && System.getProperty("HTTP_PORT") == null) {
      System.setProperty("HTTP_PORT", "8080");
    }

    Boot.main(args);
  }
}