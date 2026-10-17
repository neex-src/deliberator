package ijdev.agent;

import java.lang.instrument.Instrumentation;

/**
 * Keeps the JVM's Instrumentation for the ijdev REPL. Loaded by the system class loader: the REPL reads the field through
 * {@code Class.forName("ijdev.agent.Agent", false, ClassLoader.getSystemClassLoader())}. Not a system property: Clojure's compiler
 * casts every property value to String at class init.
 */
public final class Agent {
  public static volatile Instrumentation instrumentation;

  private Agent() { }

  public static void premain(String args, Instrumentation inst) { keep(inst); }

  public static void agentmain(String args, Instrumentation inst) { keep(inst); }

  private static void keep(Instrumentation inst) {
    instrumentation = inst;
  }
}
