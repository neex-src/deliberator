(ns ijdev.ide
  "Helpers for evaluating against the live IDE from a REPL thread. A REPL thread is a plain background thread: not the EDT, not
  inside a read action. So:

    reading PSI / VFS / documents / indexes   (ide/read ...)          or (ide/smart project ...) to wait for indexing
    touching Swing, editors, carets, popups   (ide/edt ...)           invokeAndWait, ModalityState.any(), value or rethrow
    changing documents / PSI (undoable)       (ide/write project ...) WriteCommandAction on the EDT

  Never block the EDT on something that needs the EDT (an `edt` inside an `edt` runs inline, fine; a `read` inside `edt` is fine;
  waiting for indexing inside `edt` deadlocks). Results print as text; `shot!` and `ui-tree` show UI."
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.intellij.ide.plugins PluginManagerCore)
           (com.intellij.ide.script IdeScriptEngineManager)
           (com.intellij.openapi.actionSystem ActionManager AnAction)
           (com.intellij.openapi.application ApplicationManager ModalityState PathManager ReadAction)
           (com.intellij.openapi.command WriteCommandAction)
           (com.intellij.openapi.editor Editor)
           (com.intellij.openapi.extensions PluginId)
           (com.intellij.openapi.fileEditor FileDocumentManager FileEditorManager OpenFileDescriptor)
           (com.intellij.openapi.project DumbService Project ProjectManager)
           (com.intellij.openapi.util Computable ThrowableComputable)
           (com.intellij.openapi.vfs LocalFileSystem VirtualFile)
           (com.intellij.psi PsiDocumentManager PsiFile PsiManager)
           (java.awt Component Container Window)
           (java.awt.image BufferedImage)
           (javax.imageio ImageIO)
           (javax.swing AbstractButton JComponent JLabel SwingUtilities)
           (javax.swing.text JTextComponent)))

(defn app [] (ApplicationManager/getApplication))

;; ---------------------------------------------------------------- threads

(defn edt*
  "Runs f on the EDT and returns its value (rethrows its exception). Inline when already on the EDT. The default modality is
  `any` (runs even while a modal dialog is open): right for looking and for Swing, WRONG for model changes (documents, PSI, VFS,
  project model: the platform logs \"Write-unsafe context\"); `write` uses the non-modal state instead."
  ([f] (edt* (ModalityState/any) f))
  ([^ModalityState modality f]
   (if (.isDispatchThread (app))
     (f)
     (let [p (promise)]
       (.invokeAndWait (app) (reify Runnable (run [_] (deliver p (try {:v (f)} (catch Throwable t {:e t}))))) modality)
       (let [{:keys [v e]} @p] (if e (throw e) v))))))

(defmacro edt [& body] `(edt* (fn [] ~@body)))

(defn read* [f] (ReadAction/computeBlocking (reify ThrowableComputable (compute [_] (f)))))

(defmacro read "Body in a read action (any thread)." [& body] `(read* (fn [] ~@body)))

(defn smart*
  "f in a read action once the project is out of dumb mode (waits for indexing). Not from the EDT."
  [^Project project f]
  (.runReadActionInSmartMode (DumbService/getInstance project) (reify Computable (compute [_] (f)))))

(defmacro smart [project & body] `(smart* ~project (fn [] ~@body)))

(defn write*
  "f in an undoable write command on the EDT, in a write-safe (non-modal) context; returns its value. Waits while a modal
  dialog is open."
  [^Project project f]
  (edt* (ModalityState/nonModal)
        #(WriteCommandAction/runWriteCommandAction project ^Computable (reify Computable (compute [_] (f))))))

(defmacro write [project & body] `(write* ~project (fn [] ~@body)))

;; ---------------------------------------------------------------- projects, files, editors

(defn projects [] (vec (.getOpenProjects (ProjectManager/getInstance))))

(defn project
  "The open project named `name` (or whose base path contains it); without a name the only/first open project."
  (^Project [] (first (projects)))
  (^Project [name] (first (filter #(or (= name (.getName ^Project %)) (str/includes? (str (.getBasePath ^Project %)) name)) (projects)))))

(defn vfile
  "VirtualFile for an absolute path (refreshing it into the VFS when needed)."
  ^VirtualFile [path]
  (.refreshAndFindFileByPath (LocalFileSystem/getInstance) (str (.getAbsolutePath (io/file path)))))

(defn psi-file ^PsiFile [^Project project path]
  (let [vf (vfile path)] (read (.findFile (PsiManager/getInstance project) vf))))

(defn document [path] (read (.getDocument (FileDocumentManager/getInstance) (vfile path))))

(defn open!
  "Opens path in the editor of project (at a 0-based line when given); returns the Editor."
  ([project path] (open! project path 0))
  ([^Project project path line]
   (edt (.openTextEditor (FileEditorManager/getInstance project) (OpenFileDescriptor. project (vfile path) (int line) 0) true))))

(defn editor "The selected text editor of project." ^Editor [^Project project]
  (edt (.getSelectedTextEditor (FileEditorManager/getInstance project))))

(defn caret
  "Where the caret of the selected editor is: file, offset, 0-based line/column, selected text."
  [^Project project]
  (edt (when-let [^Editor e (editor project)]
         (let [c (.getPrimaryCaret (.getCaretModel e))
               pos (.getLogicalPosition c)]
           {:file (some-> (FileDocumentManager/getInstance) (.getFile (.getDocument e)) .getPath)
            :offset (.getOffset c) :line (.line pos) :column (.column pos)
            :selection (.getSelectedText c)}))))

(defn psi-at-caret
  "The PSI leaf at the caret and its parents (class names and text heads), innermost first."
  [^Project project]
  (let [{:keys [file offset]} (caret project)]
    (when file
      (read (let [pf (.findFile (PsiManager/getInstance project) (vfile file))
                  doc (.getDocument (PsiDocumentManager/getInstance project) pf)]
              (.commitDocument (PsiDocumentManager/getInstance project) doc)
              (->> (iterate #(.getParent ^com.intellij.psi.PsiElement %) (.findElementAt pf (int offset)))
                   (take-while some?)
                   (take-while #(not (instance? com.intellij.psi.PsiDirectory %)))
                   (mapv (fn [^com.intellij.psi.PsiElement el]
                           [(.getSimpleName (class el)) (let [t (.getText el)] (subs t 0 (min 60 (count t))))]))))))))

;; ---------------------------------------------------------------- plugins, services, actions

(defn plugin "The descriptor of a loaded plugin." [id] (PluginManagerCore/getPlugin (PluginId/getId (str id))))

(defn plugin-class
  "Class `cname` as the plugin `id` sees it (its own class loader), for when the union loader would find another copy."
  [id cname]
  (Class/forName (str cname) true (.getPluginClassLoader (plugin id))))

(defn service
  "An application service (one arg) or a project service (project, class)."
  ([^Class cls] (.getService (app) cls))
  ([^Project project ^Class cls] (.getService project cls)))

(defn action!
  "Performs the action with this id now (on the EDT), as if invoked from the place \"ijdev\"."
  [id]
  (edt (let [am (ActionManager/getInstance)
             ^AnAction a (or (.getAction am (str id)) (throw (ex-info (str "no action " id) {:id id})))]
         (.tryToExecute am a nil nil "ijdev" true)
         :performed)))

(defn actions-matching "Action ids containing s (case-insensitive)." [s]
  (let [s (str/lower-case s)]
    (->> (.getActionIdList (ActionManager/getInstance) "") (filter #(str/includes? (str/lower-case %) s)) sort vec)))

;; ---------------------------------------------------------------- the running API as the oracle

(defn members
  "Public methods and fields of class c (a Class or a name the union loader finds) whose name matches regex re, as signature
  lines. The platform version that is RUNNING answers, not the docs and not master's sources."
  ([c] (members c "."))
  ([c re]
   (let [^Class c (if (class? c) c (Class/forName (str c) false (.getContextClassLoader (Thread/currentThread))))
         short (fn [^Class t] (.getSimpleName t))]
     (->> (concat
           (for [^java.lang.reflect.Method m (.getMethods c) :when (re-find (re-pattern re) (.getName m))]
             (str (when (java.lang.reflect.Modifier/isStatic (.getModifiers m)) "static ")
                  (short (.getReturnType m)) " " (.getName m) "(" (str/join ", " (map short (.getParameterTypes m))) ")"))
           (for [^java.lang.reflect.Field f (.getFields c) :when (re-find (re-pattern re) (.getName f))]
             (str (when (java.lang.reflect.Modifier/isStatic (.getModifiers f)) "static ") "field " (short (.getType f)) " " (.getName f))))
          distinct sort (str/join "\n")))))

;; ---------------------------------------------------------------- Kotlin, through the IDE's own script engine

(defonce ^:private kotlin-engine (atom nil))

(defn kotlin
  "Evaluates Kotlin script source with the IDE's Kotlin JSR-223 engine (the one the IDE Scripting Console uses); `project` is
  bound when given (bindings[\"project\"]). The first call takes several seconds. Returns the script's value.
  EXPERIMENTAL. On 262 the only engine is Jsr223KotlincScriptEngineFactory (kotlinc, out of process over RMI): self-contained
  expressions work, scripts that touch IDE classes fail to compile and the report comes back as
  NotSerializableException: com.intellij.openapi.diagnostic.Attachment. Prefer Clojure interop for anything that touches the IDE."
  ([code] (kotlin nil code))
  ([project code]
   (let [e (or @kotlin-engine
               (reset! kotlin-engine (or (.getEngineByFileExtension (IdeScriptEngineManager/getInstance) "kts" nil)
                                         (throw (ex-info "no Kotlin script engine (is the Kotlin plugin enabled?)" {})))))]
     (when project (.setBinding ^com.intellij.ide.script.IdeScriptEngine e "project" project))
     (.eval ^com.intellij.ide.script.IdeScriptEngine e (str code)))))

;; ---------------------------------------------------------------- the UI as text and as pictures

(defn windows "Visible windows, frames first." []
  (->> (Window/getWindows) (filter #(.isShowing ^Window %)) (sort-by #(if (instance? java.awt.Frame %) 0 1)) vec))

(defn- describe [^Component c]
  (let [cls (.getName (class c))
        short (subs cls (inc (.lastIndexOf cls ".")))
        text (cond (instance? AbstractButton c) (.getText ^AbstractButton c)
                   (instance? JLabel c) (.getText ^JLabel c)
                   (instance? JTextComponent c) (let [t (str (.getText ^JTextComponent c))] (subs t 0 (min 80 (count t))))
                   :else nil)
        tip (when (instance? JComponent c) (.getToolTipText ^JComponent c))
        b (.getBounds c)]
    (str short
         (when-let [n (.getName c)] (str " name=" (pr-str n)))
         (when (seq text) (str " text=" (pr-str text)))
         (when (seq tip) (str " tip=" (pr-str tip)))
         (format " [%d,%d %dx%d]" (.x b) (.y b) (.width b) (.height b)))))

(defn ui-tree
  "The visible component tree of window w (default: the first visible frame) as indented text, at most `depth` levels."
  ([] (ui-tree (first (windows)) 12))
  ([w depth]
   (edt (let [sb (StringBuilder.)]
          (letfn [(walk [^Component c d]
                    (when (and (.isShowing c) (<= d depth))
                      (.append sb (str (apply str (repeat d "  ")) (describe c) "\n"))
                      (when (instance? Container c)
                        (doseq [k (.getComponents ^Container c)] (walk k (inc d))))))]
            (walk w 0))
          (str sb)))))

(defn shot!
  "Paints each visible window (or just `w`) into a PNG under <ijdev dir>/shots/<name>[-i].png; returns the paths. Painting the
  component tree, not the screen: works when the window is covered, shows no OS decorations."
  ([name] (shot! name nil))
  ([name w]
   (let [dir (doto (io/file (or (System/getProperty "ijdev.dir") (str (PathManager/getSystemPath) "/ijdev")) "shots") .mkdirs)
         ws (if w [w] (windows))]
     (edt (vec (map-indexed
                (fn [i ^Window win]
                  (let [img (BufferedImage. (max 1 (.getWidth win)) (max 1 (.getHeight win)) BufferedImage/TYPE_INT_RGB)
                        g (.createGraphics img)
                        f (io/file dir (str name (when (pos? i) (str "-" i)) ".png"))]
                    (try (.paint win g) (finally (.dispose g)))
                    (ImageIO/write img "png" f)
                    (str f)))
                ws))))))

;; ---------------------------------------------------------------- the log and the IDE's error reports

(defn errors
  "The IDE's error reports (the red balloon \"IDE error occurred\") as text. Identical messages are grouped (count, then the first
  `frames` lines of one trace); `frames` nil prints every trace in full."
  ([] (errors 8))
  ([frames]
   (->> (.getFatalErrors (com.intellij.diagnostic.MessagePool/getInstance) true true)
        (map (fn [^com.intellij.diagnostic.LogMessage m] [(str (.getMessage m)) (str (.getThrowableText m))]))
        (group-by (fn [[msg t]] (str msg (first (str/split-lines t)))))
        vals
        (map (fn [[[msg t] :as same]]
               (str (count same) "x " msg "\n"
                    (str/join "\n" (cond->> (str/split-lines t) frames (take frames))))))
        (str/join "\n----\n"))))

(defn clear-errors! "Marks every error report read and clears the list." []
  (.clearErrors (com.intellij.diagnostic.MessagePool/getInstance)))

(defn log-file [] (io/file (PathManager/getLogPath) "idea.log"))

(defn log-tail
  "The last n lines of idea.log, optionally only those matching regex re."
  ([n] (log-tail n nil))
  ([n re]
   (let [lines (with-open [r (io/reader (log-file))] (vec (line-seq r)))
         lines (if re (filterv #(re-find (re-pattern re) %) lines) lines)]
     (str/join "\n" (take-last n lines)))))
