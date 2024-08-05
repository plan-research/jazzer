/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * By downloading, you agree to the Code Intelligence Jazzer Terms and Conditions.
 *
 * The Code Intelligence Jazzer Terms and Conditions are provided in LICENSE-JAZZER.txt
 * located in the root directory of the project.
 */

package com.code_intelligence.jazzer.api;

@FunctionalInterface
public interface Function4<T1, T2, T3, T4, R> {
  R apply(T1 t1, T2 t2, T3 t3, T4 t4);
}
