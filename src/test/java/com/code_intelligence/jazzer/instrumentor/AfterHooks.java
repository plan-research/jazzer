/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * By downloading, you agree to the Code Intelligence Jazzer Terms and Conditions.
 *
 * The Code Intelligence Jazzer Terms and Conditions are provided in LICENSE-JAZZER.txt
 * located in the root directory of the project.
 */

package com.code_intelligence.jazzer.instrumentor;

import com.code_intelligence.jazzer.api.HookType;
import com.code_intelligence.jazzer.api.MethodHook;
import java.lang.invoke.MethodHandle;

public class AfterHooks {
  static AfterHooksTargetContract instance;

  @MethodHook(
      type = HookType.AFTER,
      targetClassName = "com.code_intelligence.jazzer.instrumentor.AfterHooksTarget",
      targetMethod = "func1")
  public static void patchFunc1(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId, Object returnValue) {
    instance = (AfterHooksTargetContract) thisObject;
    ((AfterHooksTargetContract) thisObject).registerHasFunc1BeenCalled();
  }

  @MethodHook(
      type = HookType.AFTER,
      targetClassName = "com.code_intelligence.jazzer.instrumentor.AfterHooksTarget",
      targetMethod = "registerTimesCalled",
      targetMethodDescriptor = "()V")
  public static void patchRegisterTimesCalled(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId, Object returnValue)
      throws Throwable {
    // Invoke registerTimesCalled() again to pass the test.
    method.invoke();
  }

  @MethodHook(
      type = HookType.AFTER,
      targetClassName = "com.code_intelligence.jazzer.instrumentor.AfterHooksTarget",
      targetMethod = "getFirstSecret",
      targetMethodDescriptor = "()Ljava/lang/String;")
  public static void patchGetFirstSecret(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId, String returnValue) {
    // Use the returned secret to pass the test.
    ((AfterHooksTargetContract) thisObject).verifyFirstSecret(returnValue);
  }

  @MethodHook(
      type = HookType.AFTER,
      targetClassName = "com.code_intelligence.jazzer.instrumentor.AfterHooksTarget",
      targetMethod = "getSecondSecret")
  public static void patchGetSecondSecret(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId, Object returnValue) {
    // Use the returned secret to pass the test.
    ((AfterHooksTargetContract) thisObject).verifySecondSecret((String) returnValue);
  }

  // Verify the interaction of a BEFORE and an AFTER hook. The BEFORE hook modifies the argument of
  // the StringBuilder constructor.
  @MethodHook(
      type = HookType.BEFORE,
      targetClassName = "java.lang.StringBuilder",
      targetMethod = "<init>")
  public static void patchStringBuilderBeforeInit(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId) {
    arguments[0] = "hunter3";
  }

  @MethodHook(
      type = HookType.AFTER,
      targetClassName = "java.lang.StringBuilder",
      targetMethod = "<init>")
  public static void patchStringBuilderInit(
      MethodHandle method, Object thisObject, Object[] arguments, int hookId, Object returnValue) {
    String secret = ((StringBuilder) thisObject).toString();
    // Verify that the argument passed to this AFTER hook agrees with the argument passed to the
    // StringBuilder constructor, which has been modified by the BEFORE hook.
    if (secret.equals(arguments[0])) {
      // Verify that the argument has been modified to the correct value "hunter3".
      instance.verifyThirdSecret(secret);
    }
  }
}
