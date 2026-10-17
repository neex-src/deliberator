(ns ijdev.plugins
  "Unloading and loading a plugin inside the running IDE (DynamicPlugins), reading the descriptor afresh from its sandbox
  directory, so plugin.xml changes (extensions, actions, services, listeners) take effect without a restart.

  Internal platform API (DynamicPlugins, PluginDescriptorLoader): fine for a dev tool, re-check the names when the platform moves."
  (:require [ijdev.hotswap :as hs]
            [ijdev.ide :as ide])
  (:import (com.intellij.openapi.application ModalityState)
           (com.intellij.ide.plugins DynamicPlugins PluginDescriptorLoader PluginMainDescriptor PluginManagerCore)))

(defn plugin-ids "Ids of the loaded non-bundled plugins (the ones a sandbox adds)." []
  (->> (PluginManagerCore/getLoadedPlugins) (remove #(.isBundled ^com.intellij.ide.plugins.IdeaPluginDescriptor %)) (map #(str (.getPluginId ^com.intellij.ide.plugins.IdeaPluginDescriptor %))) sort vec))

(defn why-not-unload
  "nil when the plugin can be unloaded without a restart, else the platform's reason. Background thread only (not the EDT)."
  [id]
  (.validateCanUnloadWithoutRestart DynamicPlugins/INSTANCE (ide/plugin id)))

(defn copies
  "Every discovered descriptor of plugin `id`. A runIde sandbox discovers its plugins twice (the plugins directory and
  -Dplugin.path), so unloading only the loaded copy leaves the other one in the module graph and the unload fails with \"was
  expected not to be loaded but was included in the module graph\"."
  [id]
  (->> (.getPluginLists (.getDiscoveryResult (.getInput (PluginManagerCore/getPluginSet))))
       (mapcat #(.getPlugins ^com.intellij.ide.plugins.DiscoveredPluginsList %))
       (filter #(= (str id) (str (.getPluginId ^PluginMainDescriptor %))))
       distinct vec))

(defn- unload-all!
  "DynamicPlugins.unloadPlugins(descriptors) with every other parameter at its Kotlin default (the parameter list differs between
  platform versions, so through the synthetic $default method). EDT only, non-modal: under ModalityState.any() the platform's own
  model changes during unload log \"Write-unsafe context\"."
  [descriptors]
  (let [m (->> (.getDeclaredMethods DynamicPlugins) (filter #(= "unloadPlugins$default" (.getName ^java.lang.reflect.Method %))) first)
        n (- (.getParameterCount ^java.lang.reflect.Method m) 3)
        args (concat [DynamicPlugins/INSTANCE (java.util.ArrayList. ^java.util.Collection descriptors)] (repeat (dec n) nil)
                     [(int (bit-and (dec (bit-shift-left 1 n)) (bit-not 1))) nil])]
    (.invoke ^java.lang.reflect.Method m nil (object-array args))))

(defn- fresh-descriptor ^PluginMainDescriptor [^java.nio.file.Path path]
  (PluginDescriptorLoader/loadDescriptor path false com.intellij.ide.plugins.PluginXmlPathResolver/DEFAULT_PATH_RESOLVER))

(defn reload!
  "Unloads plugin `id` and loads it again from its directory in the sandbox (rebuilt by the prepareSandbox task first).
  Returns {:reloaded true :ms ..} or {:reloaded false :step :check|:unload|:descriptor|:load :reason ..}; the idea.log lines of
  DynamicPlugins are attached on failure."
  [id]
  (let [t0 (System/currentTimeMillis)
        old (ide/plugin id)
        _ (when-not old (throw (ex-info (str "plugin " id " is not loaded") {:id id})))
        path (.getPluginPath old)
        fail (fn [step reason] {:reloaded false :step step :reason reason
                                :log (ide/log-tail 30 "DynamicPlugins|Plugin .* is not unload-safe|Cannot unload|leak")})]
    (if-let [why (why-not-unload id)]
      (fail :check why)
      (if-not (let [all (copies id)] (ide/edt* (ModalityState/nonModal) #(unload-all! all)))
        (fail :unload "DynamicPlugins.unloadPlugins returned false (the IDE wants a restart; see :log)")
        (if-let [fresh (try (fresh-descriptor path) (catch Throwable e (println "descriptor:" e) nil))]
          (do (clojure.core/swap! hs/swapped dissoc id)
              (if (ide/edt* (ModalityState/nonModal) #(.loadPlugin DynamicPlugins/INSTANCE fresh))
                {:reloaded true :id id :path (str path) :ms (- (System/currentTimeMillis) t0)}
                (fail :load "DynamicPlugins.loadPlugin returned false")))
          (fail :descriptor (str "no plugin descriptor under " path)))))))
