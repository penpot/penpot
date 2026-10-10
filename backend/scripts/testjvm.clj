;; testjvm.clj: the persistent test JVM behind the MCP server's
;; `clojure_test` and `clojure_eval` tools (mcp/packages/server, TestJvm).
;;
;; One JVM on the backend's `:dev:test` classpath stays up between test runs.
;; Each run first reloads what changed on disk, then runs kaocha with the
;; repository's own tests.edn and the same focus ids `--focus` takes on the
;; command line. A cold `clojure -M:dev:test --focus ...` pays JVM start,
;; classpath, and the load of the whole backend on every run; this pays them
;; once.
;;
;; The file is on no classpath, so the running backend never loads it. The
;; MCP server names it in the `:main-opts` of an alias passed with -Sdeps,
;; so `-M:dev:test:testjvm` keeps :dev's and :test's deps and jvm-opts and
;; replaces only kaocha.runner as the entry point (the last alias with
;; :main-opts wins). The same alias adds clj-reload, which deps.edn does not
;; carry.
;;
;; Reloading uses clj-reload, not clojure.tools.namespace. tools.namespace's
;; first refresh reloads every namespace in every classpath directory, which
;; is the whole backend and all of common; clj-reload reloads only the files
;; whose modification time changed since the last reload, plus the loaded
;; namespaces that depend on them, and keeps `defonce` values.
;;
;; The entry points (`cli-run`, `reload-cli`, `cancel!`, `status-map`) print
;; one-line verdicts prefixed `[testjvm]` and return an exit status: 0 ok, 1
;; a test failed or none matched, 2 reload failed, 4 deadline passed or
;; cancelled, 5 restart needed, 6 environment or harness error. The MCP
;; server maps the status and the line to the tool's verdict.

(ns testjvm
  (:require
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [kaocha.hierarchy :as hierarchy]
   [kaocha.repl :as krepl]
   [kaocha.testable :as testable]
   [nrepl.server :as nrepl])
  (:import
   (java.io File)
   (java.lang ProcessHandle)
   (java.lang.management ManagementFactory MemoryPoolMXBean)
   (java.nio.file Files)
   (java.security MessageDigest)
   (java.util HexFormat)
   (java.util.concurrent TimeUnit)
   (java.util.concurrent.locks ReentrantLock)))

;; JVM start, not this file's load: the :dev alias loads dev/user.clj, and
;; with it the whole backend, before -i reaches this file.
(def started-ms
  (.getStartTime (ManagementFactory/getRuntimeMXBean)))

(def port
  (parse-long (or (System/getenv "PENPOT_TESTJVM_PORT") "6065")))

;; The pid file, written once the nREPL server listens. The MCP server
;; waits for it at start; its log, jvm.log, goes in the same directory.
(def pid-file
  (io/file (or (System/getenv "PENPOT_TESTJVM_DIR") "/tmp/penpot-testjvm") "jvm.pid"))

(defn- now [] (System/currentTimeMillis))

(defn- age-s [ms] (quot (- (now) ms) 1000))

;; The directories a reload may touch, relative to backend/ (the JVM's
;; working directory): the backend's sources and tests, and common's
;; sources, a :local/root dependency whose .cljc files load from disk.
;; Jars are never reloaded.
(def dirs ["src" "test" "../common/src"])

;; The namespaces app.main/-main exempts from tools.namespace reloading
;; (`repl/disable-reload!`), plus user, exempted here too so both reload
;; paths agree on what is never reloaded in place. `stale-reason` reports
;; an edit to one of them.
(def no-reload '#{user integrant.core app.system app.common.debug})

(reload/init {:dirs dirs :no-reload no-reload})

;; clj-reload 1.0.0's init sets :since to now, about 10 s after JVM start,
;; so a file edited in between would keep its start-time code until edited
;; again. Back to JVM start: the first reload picks such files up. *state is
;; private in clj-reload 1.0.0.
(swap! @#'reload/*state assoc :since started-ms)

;;; Activity and the idle exit

;; An abandoned JVM holds about 2.5 GB, mostly swapped out after a day; the
;; next call after an idle exit pays a start instead of a swap-in.
(def idle-limit-ms (* 2 60 60 1000))

(def last-activity (atom (now)))

(defn touch!
  "Records activity for the idle exit. Returns 0, so a guard form can end
  with it."
  []
  (reset! last-activity (now))
  0)

;;; The run lock

;; Runs and reloads take this lock. kaocha in this JVM outlives any client:
;; two runs at once would truncate the same test database under each other
;; and interleave their reporters, and a reload would unload namespaces under
;; a running suite. Fair, so waiters go in arrival order; every taker waits
;; with a deadline, so a run whose client gave up never runs later.
(def ^ReentrantLock run-lock (ReentrantLock. true))

;; {:what :run|:reload, :ids [...], :token str, :started-ms ms, :cancelled? bool}
(def holder (atom nil))

;; Tokens of calls cancelled while they waited for the lock: each stops as
;; soon as it takes the lock, before it runs anything.
(def cancelled-tokens (atom #{}))

(def reloads
  "Reloads that loaded at least one namespace; each leaves classes in
  metaspace."
  (atom 0))

(defn holder-line
  "What holds the run lock, e.g. \"run of backend-tests.x-test started 3 s
  ago\", or nil when it is free."
  []
  (when-let [{:keys [what ids started-ms]} @holder]
    (str (case what
           :run    (if (seq ids) (str "run of " (str/join " " ids)) "whole-suite run")
           :reload "reload")
         " started " (age-s started-ms) " s ago")))

(defn- with-run-lock
  "Calls `f` holding the run lock, waiting at most until `deadline-ms`.
  Prints one line when it has to wait; returns 4 with the gave-up line when
  the deadline passes first, or with the cancelled line when `token` was
  cancelled while it waited. The untimed `tryLock` would barge past the
  queue, so even the first try is timed."
  [deadline-ms {:keys [what ids token]} f]
  (let [try-lock (fn [ms] (.tryLock run-lock (max 0 ms) TimeUnit/MILLISECONDS))]
    (try
      (if-not (or (try-lock 0)
                  (do (println (str "[testjvm] waiting for " (or (holder-line) "another run")))
                      (flush)
                      (try-lock (- deadline-ms (now)))))
        (do (println (str "[testjvm] gave up waiting for " (or (holder-line) "another run")))
            4)
        (try
          (reset! holder {:what what :ids ids :token token :started-ms (now)})
          (touch!)
          (if (contains? @cancelled-tokens token)
            (do (println "[testjvm] cancelled; 0 tests not run, before it started") 4)
            (f))
          (finally
            (reset! holder nil)
            (touch!)
            (.unlock run-lock))))
      (finally
        (swap! cancelled-tokens disj token)))))

(defn cancel!
  "Asks a run to stop: kaocha skips every test it has not started, and the
  test running now finishes first. Without a token, the run that holds the
  lock; with one, the call that passed that token to `cli-run`, also while
  it still waits for the lock."
  ([]
   (if-let [line (holder-line)]
     (do (swap! holder #(some-> % (assoc :cancelled? true)))
         (println (str "[testjvm] cancelling the " line "; it stops before its next test")))
     (println "[testjvm] nothing to cancel"))
   0)
  ([token]
   (if (= token (:token @holder))
     (cancel!)
     (do (swap! cancelled-tokens conj token)
         (println "[testjvm] that call does not hold the lock; it stops when it takes it")
         0))))

;;; Staleness: what a reload cannot pick up

(defn- sha256 [^File f]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (Files/readAllBytes (.toPath f)))))

(defn- shown
  "`f` relative to the worktree root, e.g. backend/deps.edn."
  [^File f]
  (str (.relativize (.toPath (.getCanonicalFile (io/file "..")))
                    (.toPath (.getCanonicalFile f)))))

;; The classpath is fixed at start. Hashed, so a touch or a checkout that
;; leaves the content alone does not ask for a restart.
(def ^:private deps-at-load
  (into {}
        (for [f [(io/file "deps.edn") (io/file "../common/deps.edn")]]
          [f {:mtime (.lastModified f) :sha (sha256 f)}])))

;; This file, as loaded. An edit to it changes the harness itself, which no
;; reload replaces.
(def ^:private script-at-load
  (let [f (io/file *file*)]
    {:file f :sha (sha256 f)}))

;; The source files of the no-reload namespaces that live on disk
;; (integrant.core is in a jar).
(def ^:private no-reload-files
  (vec (for [ns  no-reload
             ext [".clj" ".cljc"]
             :let [url (io/resource (str (-> (str ns) (str/replace "-" "_") (str/replace "." "/")) ext))]
             :when (and url (= "file" (.getProtocol url)))]
         (io/file (.toURI url)))))

(defn stale-reason
  "Why this JVM must restart to run the code on disk, or nil: a deps.edn
  changed (the classpath is fixed at start), this file changed, or a file
  clj-reload never reloads changed since the JVM started (backend/dev,
  outside the reload dirs, and the no-reload namespaces)."
  []
  (or (some (fn [[^File f {:keys [mtime sha]}]]
              ;; Edited between JVM start and this file's load: the classpath
              ;; may predate the edit.
              (when (or (> mtime started-ms)
                        (and (not= mtime (.lastModified f))
                             (or (not (.exists f)) (not= sha (sha256 f)))))
                (str (shown f) " changed since the JVM started; the classpath is fixed at start")))
            deps-at-load)
      (let [{:keys [^File file sha]} script-at-load]
        (when (or (not (.exists file)) (not= sha (sha256 file)))
          (str (shown file) " changed since the JVM started; it is the test JVM's own code")))
      (some (fn [^File f]
              (when (> (.lastModified f) started-ms)
                (str (shown f) " changed since the JVM started, and it is never reloaded")))
            (distinct (concat (filter #(str/ends-with? (.getName ^File %) ".clj")
                                      (.listFiles (io/file "dev")))
                              no-reload-files)))))

(defn- stale-exit
  "Prints the restart line and returns 5 when the JVM is stale, else nil."
  []
  (when-let [reason (stale-reason)]
    (println (str "[testjvm] restart needed: " reason))
    5))

;;; Storage preflight

;; Tests store objects with the fs backend under backend/assets (app.config's
;; default :objects-storage-fs-directory) and temp files under /tmp/penpot.
;; A `docker exec` without `-u penpot` runs as root and leaves directories
;; the penpot user cannot write; every test whose object hashes into one
;; then fails with AccessDeniedException (1,370 such directories in one
;; devenv, 2026-10-09). Objects live at depth 3 (assets/xx/yy/object), so
;; the check covers directories to depth 2.
(def ^:private storage-roots [(io/file "/tmp/penpot") (io/file "assets")])

;; depth-1 directory -> its mtime when all its subdirectories were writable.
;; A new subdirectory changes the mtime, so only changed ones are listed
;; again: about 2 ms per run instead of about 100 ms.
(def ^:private verified (atom {}))

(defn- writable? [^File f] (Files/isWritable (.toPath f)))

(defn- subdirs [^File d] (filter #(.isDirectory ^File %) (.listFiles d)))

(defn unwritable-storage
  "The storage directories, to depth 2, that this JVM's user cannot write."
  []
  (vec
   (for [^File root storage-roots
         :when (.isDirectory root)
         ^File f (cons root
                       (mapcat (fn [^File d]
                                 (let [mtime (.lastModified d)
                                       bad   (if (= mtime (@verified d))
                                               []
                                               (remove writable? (subdirs d)))]
                                   (when (empty? bad) (swap! verified assoc d mtime))
                                   (cons d bad)))
                               (subdirs root)))
         :when (not (writable? f))]
     f)))

(defn- storage-exit
  "Prints the environment line and returns 6 when storage is not writable,
  else nil. The line carries the repair: this JVM's user cannot chown, so it
  runs as root from the host. Docker names the container's hostname after
  its id, which `docker exec` accepts."
  []
  (when-let [bad (seq (unwritable-storage))]
    (let [^File f (first bad)
          user    (System/getProperty "user.name")]
      (println (format "[testjvm] environment: %d storage directories are not writable by %s, e.g. %s; repair from the host: docker exec -u root %s chown -R %s:%s %s"
                       (count bad) user
                       (if (.isAbsolute f) (.getPath f) (str "backend/" (.getPath f)))
                       (or (System/getenv "HOSTNAME") "<container>")
                       user user
                       (str/join " " (map #(.getCanonicalPath ^File %) storage-roots))))
      6)))

;;; Reload

(defn reload!
  "Reload the namespaces whose files changed since the last reload, and
  the loaded namespaces that depend on them. Returns clj-reload's report;
  a namespace that fails to compile is reported as `:failed` with its
  `:exception` instead of throwing, so the JVM stays usable: fix the file
  and run again.

  clj-reload 1.0.0 cannot report a file that does not read (an unclosed
  delimiter): its scan logs \"Failed to read <file> <message>\" and then
  throws a NullPointerException of its own, outside `:throw false`
  (observed 2026-10-06 and 2026-10-09). That case is reported as `:failed
  :unreadable-file`, with the `:file` and `:message` of the log line.

  clj-reload prints a failed load as a full `#error` map, about 85 lines
  of stack trace at any `:output` level. The caller prints the message and
  the cause in one line, so the trace is cut here. A reload with nothing
  to do prints nothing, because every `clojure_eval` in this JVM reloads
  first."
  []
  (let [sw  (java.io.StringWriter.)
        rep (binding [*out* sw]
              (try
                (reload/reload {:throw false})
                (catch Throwable e
                  {:failed :unreadable-file :exception e})))
        out (str sw)
        rep (if-let [[_ path msg] (and (= :unreadable-file (:failed rep))
                                       (re-find #"Failed to read (\S+) (.*)" out))]
              (assoc rep :file (io/file path) :message (str/replace msg #"^[\w.$]+: " ""))
              rep)
        cut (str/index-of out "#error {")]
    (when (or (:exception rep) (seq (:loaded rep)))
      (print (if cut (str (subs out 0 cut) "(stack trace omitted)\n") out))
      (flush))
    (when (seq (:loaded rep))
      (swap! reloads inc))
    rep))

(defn- failure-line
  "`[testjvm] reload failed in <ns> (<file>:<line>): <message>` for a
  report of `reload!`. The file comes from the report or from clj-reload's
  index of the namespace; the message is the innermost one, because a
  compiler exception's own message only repeats the location."
  [{:keys [failed exception file message]}]
  (let [state @@#'reload/*state
        chain (take-while some? (iterate ex-cause exception))
        ns    (if (symbol? failed) failed (first (get-in state [:files file :namespaces])))
        file  (or file (first (get-in state [:namespaces failed :ns-files])))
        msg   (or message (some ex-message (reverse chain)))
        line  (or (some #(:clojure.error/line (ex-data %)) chain)
                  (some->> msg (re-find #"line (\d+)") second))]
    (str "[testjvm] reload failed in " (or ns (some-> file shown) "a file that does not read")
         " (" (if file (shown file) "file unknown") (some->> line (str ":")) "): "
         msg)))

(defn reload-cli
  "`reload!` for the reload before each `clojure_eval` in this JVM (with
  `:quiet?`) and for an explicit reload. Takes the run lock until
  `:deadline-ms`. Prints one line naming the reloaded namespaces (unless
  `:quiet?`) or the failure; returns 0, 2 reload failed, 4 deadline, 5
  restart needed, 6 harness error."
  [{:keys [deadline-ms quiet?]}]
  (try
    (touch!)
    (or (stale-exit)
        (with-run-lock (or deadline-ms (+ (now) 300000)) {:what :reload}
          (fn []
            (let [{:keys [exception loaded] :as rep} (reload!)]
              (cond
                exception (do (println (failure-line rep)) 2)
                quiet?    0
                :else     (do (println (str "[testjvm] reloaded " (count loaded) " namespaces"
                                            (when (seq loaded) (str ": " (str/join " " loaded)))))
                              0))))))
    (catch Throwable e
      (println (str "[testjvm] harness error: " (.getName (class e)) ": " (ex-message e)))
      6)))

;;; Run

(defn- runnable-ids
  "The ids of the tests under `t` that would run: the focus marks a skipped
  namespace or suite with ::skip and leaves its tests unmarked, so a
  skipped node hides everything below it."
  [t]
  (cond
    (::testable/skip t)  #{}
    (hierarchy/leaf? t)  #{(::testable/id t)}
    :else                (into #{} (mapcat runnable-ids) (:kaocha.test-plan/tests t))))

(defn- stop-hook
  "A kaocha pre-test hook (kaocha.plugin/hooks): once the run is cancelled
  or its deadline has passed, marks each testable skipped, so kaocha starts
  nothing new, and counts the tests it skips into `stopped`."
  [deadline-ms stopped]
  (fn [t _test-plan]
    (if-let [why (cond (:cancelled? @holder)  :cancelled
                       (> (now) deadline-ms) :deadline)]
      ;; kaocha may offer a testable more than once: count ids, not calls
      (do (swap! stopped #(-> % (update :why (fnil identity why)) (update :ids into (runnable-ids t))))
          (assoc t ::testable/skip true))
      t)))

(defn run
  "Reload, then run kaocha on `ids`: namespace or var symbols, or suite
  keywords, as `--focus` takes them. No ids runs every suite in tests.edn.
  `opts`: `:deadline-ms` after which no new test starts, `:seed`, and
  `:randomize?` (default true; false keeps kaocha's sorted order). Returns
  kaocha's totals, plus `:reloaded`, `:elapsed-ms`, `:seed`, and
  `:stopped` ({:why :deadline|:cancelled, :skipped n} or nil), or
  `{:reload-failed <reload! report>}`. Takes no lock; `cli-run` does."
  [{:keys [deadline-ms seed randomize?] :or {randomize? true}} & ids]
  (let [t0  (System/nanoTime)
        rep (reload!)]
    (if (:exception rep)
      {:reload-failed rep}
      (let [seed    (when randomize? (or seed (rand-int Integer/MAX_VALUE)))
            stopped (atom {:why nil :ids #{}})
            cfg     (cond-> {:kaocha/plugins        [:kaocha.plugin/hooks]
                             :kaocha.hooks/pre-test [(stop-hook (or deadline-ms Long/MAX_VALUE) stopped)]}
                      seed             (assoc :kaocha.plugin.randomize/seed seed)
                      (not randomize?) (assoc :kaocha.plugin.randomize/randomize? false))
            totals  (if (seq ids)
                      (apply krepl/run (concat ids [cfg]))
                      ;; kaocha.repl's zero-arity run focuses *ns*, which
                      ;; matches no test; the whole suite is run-all.
                      (krepl/run-all cfg))
            base    {:reloaded   (:loaded rep)
                     :elapsed-ms (quot (- (System/nanoTime) t0) 1000000)
                     :seed       seed
                     :stopped    (when-let [why (:why @stopped)] {:why why :skipped (count (:ids @stopped))})}]
        ;; On an early exit kaocha returns its exit code, not its totals:
        ;; 0 when the focus matches no test ("All N tests were skipped",
        ;; observed with kaocha 1.91.1392), 25x for a config, plugin, or
        ;; reporter error.
        (cond
          (map? totals)  (merge totals base)
          (= 0 totals)   (assoc base :no-test-matched ids)
          :else          (assoc base :early-exit totals))))))

(defn cli-run
  "`run` under the run lock until `:deadline-ms`. Prints a one-line verdict
  ending in the seed and returns the exit status: 0 every test passed, 1 a
  failure, an error, or no test matched, 2 the reload failed and no test
  ran, 4 the deadline passed or the run was cancelled, 5 restart needed, 6
  storage not writable or harness error. `:token` names the call for
  `cancel!`."
  [{:keys [deadline-ms token] :as opts} & ids]
  (try
    (touch!)
    (let [deadline-ms (or deadline-ms (+ (now) 1800000))]
      (or (stale-exit)
          (storage-exit)
          (with-run-lock deadline-ms {:what :run :ids (vec ids) :token token}
            (fn []
              (let [r (apply run (assoc opts :deadline-ms deadline-ms) ids)]
                (cond
                  (:reload-failed r)
                  (do (println (failure-line (:reload-failed r))) 2)

                  (:early-exit r)
                  (do (println (str "[testjvm] harness error: kaocha exited early with code " (:early-exit r)))
                      6)

                  (contains? r :no-test-matched)
                  (do (println (str "[testjvm] no test matched " (pr-str (vec ids))
                                    "; an id is a test namespace, ns/test-var, or a suite keyword from tests.edn"))
                      1)

                  :else
                  (let [fails              (+ (:kaocha.result/fail r 0) (:kaocha.result/error r 0))
                        {:keys [why skipped]} (:stopped r)]
                    (println (format "[testjvm] %d tests, %d assertions passed, %d failed, %d errors; reloaded %d namespaces; %.1f s; %s"
                                     (:kaocha.result/count r 0) (:kaocha.result/pass r 0)
                                     (:kaocha.result/fail r 0) (:kaocha.result/error r 0)
                                     (count (:reloaded r)) (/ (:elapsed-ms r) 1000.0)
                                     (if-let [s (:seed r)] (str "seed " s) "order fixed")))
                    (case why
                      :deadline  (do (println (str "[testjvm] deadline passed; " skipped " tests not run")) 4)
                      :cancelled (do (println (str "[testjvm] cancelled; " skipped " tests not run")) 4)
                      (if (zero? fails) 0 1)))))))))
    (catch Throwable e
      (println (str "[testjvm] harness error: " (.getName (class e)) ": " (ex-message e)))
      6)))

;;; Status

(defn- proc-status-mb
  "VmRSS, VmSwap and the like from /proc/self/status, in MB."
  []
  (into {}
        (keep (fn [line]
                (when-let [[_ k kb] (re-matches #"(VmRSS|VmSwap):\s+(\d+) kB" line)]
                  [k (quot (parse-long kb) 1024)])))
        ;; Not slurp: FileInputStream.available fails on /proc files (EINVAL).
        (Files/readAllLines (.toPath (io/file "/proc/self/status")))))

(defn status-map
  "One map describing this JVM: memory, the run that holds the lock, staleness."
  []
  (let [rt   (Runtime/getRuntime)
        mb   #(quot % (* 1024 1024))
        proc (proc-status-mb)
        msp  (some (fn [^MemoryPoolMXBean p] (when (= "Metaspace" (.getName p)) (.getUsage p)))
                   (ManagementFactory/getMemoryPoolMXBeans))]
    {:pid          (.pid (ProcessHandle/current))
     :port         port
     :uptime-s     (age-s started-ms)
     :idle-s       (age-s @last-activity)
     :rss-mb       (get proc "VmRSS")
     :swap-mb      (get proc "VmSwap")
     :heap-used-mb (mb (- (.totalMemory rt) (.freeMemory rt)))
     :heap-max-mb  (mb (.maxMemory rt))
     :metaspace-mb (some-> msp .getUsed mb)
     :classes      (.getLoadedClassCount (ManagementFactory/getClassLoadingMXBean))
     ;; true only when PENPOT_FLAGS carried enable-backend-asserts at start:
     ;; app.config sets *assert* from the flags when it loads. The root
     ;; value, which new nREPL sessions copy and reloads compile under.
     :assert       (.getRawRoot #'clojure.core/*assert*)
     :busy         (holder-line)
     :reloads      @reloads
     :stale        (stale-reason)}))

;;; Start

(defn exit-when-idle!
  "Exits the JVM when nothing ran, reloaded, or evaluated through a guard
  for `idle-limit-ms` and no run holds the lock."
  []
  (when (and (> (- (now) @last-activity) idle-limit-ms)
             (not (.isLocked run-lock)))
    (println (str "[testjvm] idle for " (quot (age-s @last-activity) 60) " min; exiting"))
    (flush)
    (System/exit 0)))

(defonce server
  (nrepl/start-server :bind "127.0.0.1" :port port))

(let [pid (.pid (ProcessHandle/current))]
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(try
                                (when (= (str pid) (str/trim (slurp pid-file)))
                                  (io/delete-file pid-file true))
                                (catch Exception _))))
  (doto (Thread. #(loop []
                    (Thread/sleep 60000)
                    (exit-when-idle!)
                    (recur))
                 "testjvm-idle-exit")
    (.setDaemon true)
    (.start))
  (println (format "[testjvm] ready: nREPL on 127.0.0.1:%d after %.1f s (pid %d)"
                   port (/ (- (now) started-ms) 1000.0) pid))
  (flush)
  ;; Written after the ready line: the MCP server waits for this file at
  ;; start, and reads it to stop the JVM.
  (spit pid-file (str pid "\n")))

;; clojure.main would open a REPL on stdin after the -i file, and a detached
;; process has no stdin, so it would exit at once. Park the main thread.
@(promise)
