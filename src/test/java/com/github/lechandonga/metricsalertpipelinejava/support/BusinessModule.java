package com.github.lechandonga.metricsalertpipelinejava.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tags a test class with the business capability it covers, so the test log makes the
 * relationship "business module -> test case -> result" explicit.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BusinessModule {
    String value();
}
