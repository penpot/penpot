;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.process-test
  "Real Node/POSIX processes, files and Linux flock ownership."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [benches.render-wasm.node.process :as process]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private temp-root (atom nil))
(def ^:private test-grace-ms 100)

(defn- file
  [name]
  (path/join @temp-root name))

(defn- pause
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- within
  "A broken lifecycle must fail the test rather than stall the test runner."
  [promise]
  (let [timer (volatile! nil)]
    (-> (js/Promise.race
         #js [promise
              (js/Promise.
               (fn [_ reject]
                 (vreset! timer
                          (js/setTimeout
                           #(reject (ex-info "Process test stalled" {:type :test-watchdog}))
                           5000))))])
        (.finally #(js/clearTimeout @timer)))))

(defn- ^:async wait-for
  [pred]
  (let [limit (process/deadline 3000)]
    (loop []
      (when-not (pred)
        (when (zero? (process/remaining limit))
          (throw (ex-info "Process test did not become ready" {:type :test-watchdog})))
        (await (pause 10))
        (recur)))))

(defn- process-status
  [pid]
  (try
    (let [stat   (fs/readFileSync (str "/proc/" pid "/stat") "utf8")
          fields (str/split (str/trim (subs stat (inc (.lastIndexOf stat ")")))) #"\s+")]
      {:state (first fields)
       :group (js/Number (nth fields 2))})
    (catch :default cause
      (if (contains? #{"ENOENT" "ESRCH"} (.-code cause))
        nil
        (throw cause)))))

(defn- running?
  [pid]
  (when-let [status (process-status pid)]
    ;; An orphan can remain as a zombie until init reaps it. It owns no pipes
    ;; or locks and cannot run; kill(pid, 0) alone does not distinguish it.
    (not (contains? #{"Z" "X" "x"} (:state status)))))

(defn- read-pid
  [name]
  (js/Number (fs/readFileSync (file name) "utf8")))

(t/use-fixtures
  :each
  {:before
   (fn []
     (reset! temp-root
             (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-wasm-process-test-"))))
   :after
   (fn []
     (t/async done
       (let [cleanup (^:async fn []
                       (try
                         (let [pids (->> (array-seq (fs/readdirSync @temp-root))
                                         (filter #(str/ends-with? % ".pid"))
                                         (mapv read-pid))]
                           ;; Also clean up when an assertion or watchdog exposes a regression.
                           ;; Signal individual recorded PIDs so a missing detached flag cannot
                           ;; make test teardown kill the test runner's own process group.
                           (doseq [pid pids]
                             (when (running? pid)
                               (try
                                 (.kill js/process pid "SIGKILL")
                                 (catch :default cause
                                   (when-not (= "ESRCH" (.-code cause))
                                     (throw cause))))))
                           (await (wait-for #(not-any? running? pids))))
                         (finally
                           (try
                             (fs/rmSync @temp-root #js {:recursive true :force true})
                             (finally
                               (reset! temp-root nil))))))]
         (.then (cleanup)
                (fn [_] (done))
                (fn [cause]
                  (try
                    (t/is false (str "Process test cleanup failed: " cause))
                    (finally
                      (done))))))))})

(defn- node-source
  [pid-file source]
  (str "const fs = require('node:fs');\n"
       "fs.writeFileSync(" (pr-str pid-file) ", String(process.pid));\n"
       source))

(defn- node-options
  [source overrides]
  (merge {:command (.-execPath js/process)
          :args ["-e" (node-source "root.pid" source)]
          :cwd @temp-root
          :deadline (process/deadline 3000)
          :termination-grace-ms test-grace-ms}
         overrides))

(defn- ^:async rejected
  [promise]
  (try
    (let [result (await (within promise))]
      (t/is false (str "Expected a process failure, got " (pr-str result)))
      nil)
    (catch :default cause
      (t/is (some? (ex-data cause)) "failures carry ex-info data")
      (ex-data cause))))

(defn- stubborn-descendant
  []
  (let [grandchild (node-source
                    "grandchild.pid"
                    (str "process.on('SIGTERM', () => fs.writeFileSync('grandchild.term', 'seen'));\n"
                         "fs.writeFileSync('grandchild.ready', 'ready');\n"
                         "setInterval(() => {}, 1000);\n"))]
    (node-source
     "descendant.pid"
     (str "process.on('SIGTERM', () => fs.writeFileSync('descendant.term', 'seen'));\n"
          "require('node:child_process').spawn(process.execPath,\n"
          "  ['-e', " (pr-str grandchild) "], {stdio: ['ignore', 'inherit', 'inherit']});\n"
          "const ready = setInterval(() => {\n"
          "  if (fs.existsSync('grandchild.ready')) {\n"
          "    clearInterval(ready); fs.writeFileSync('descendant.ready', 'ready');\n"
          "    process.stdout.write('descendant-ready\\n');\n"
          "  }\n"
          "}, 10);\n"
          "setInterval(() => fs.appendFileSync('heartbeat', '.'), 10);\n"))))

(defn- spawn-descendant
  []
  (str "const child = require('node:child_process').spawn(process.execPath,\n"
       "  ['-e', " (pr-str (stubborn-descendant)) "],\n"
       "  {stdio: ['ignore', 'inherit', 'inherit']});\n"))

(defn- ^:async assert-tree-stopped
  []
  (t/is (not (running? (read-pid "root.pid"))) "the owner has stopped")
  (t/is (not (running? (read-pid "descendant.pid"))) "the descendant has stopped at settlement")
  (t/is (not (running? (read-pid "grandchild.pid"))) "the grandchild has stopped at settlement")
  (t/is (= "seen" (fs/readFileSync (file "descendant.term") "utf8"))
        "the owned group receives SIGTERM before SIGKILL")
  (t/is (= "seen" (fs/readFileSync (file "grandchild.term") "utf8"))
        "SIGTERM also reaches the grandchild")
  (let [heartbeat (when (fs/existsSync (file "heartbeat"))
                    (fs/readFileSync (file "heartbeat") "utf8"))]
    (await (pause 30))
    (t/is (= heartbeat (when (fs/existsSync (file "heartbeat"))
                         (fs/readFileSync (file "heartbeat") "utf8")))
          "no descendant writes after the promise settles")))

(t/deftest ^:async deadlines-use-date-now-and-clamp-at-zero
  (let [before (js/Date.now)
        limit  (process/deadline 20)]
    (t/is (<= (+ before 20) limit (+ (js/Date.now) 20)))
    (t/is (<= 0 (process/remaining limit) 20))
    (await (pause 30))
    (t/is (zero? (process/remaining limit)))
    (t/is (zero? (process/remaining (- before 1000))))))

(t/deftest ^:async uses-real-cwd-literal-args-env-and-closed-stdin
  (let [source (str "process.stdin.resume();\n"
                    "process.stdin.on('end', () => {\n"
                    "  const fact = {cwd: process.cwd(), args: process.argv.slice(1),\n"
                    "    value: process.env.RENDERER_PROCESS_TEST, path: process.env.PATH || null};\n"
                    "  fs.writeFileSync('fact.json', JSON.stringify(fact));\n"
                    "  process.stdout.write(JSON.stringify(fact));\n"
                    "  process.stderr.write('stdin-ended\\n');\n"
                    "});\n")
        args   ["space ; $(touch unexpected)" "--literal"]]
    (doseq [env [{:RENDERER_PROCESS_TEST "map value"}
                 #js {:RENDERER_PROCESS_TEST "map value"}]]
      (let [options (node-options source {:env env})
            result  (await (within (process/run (update options :args into args))))
            fact    (js->clj (js/JSON.parse (:stdout result)) :keywordize-keys true)]
        (t/is (= 0 (:exit-code result)))
        (t/is (= "stdin-ended\n" (:stderr result)))
        (t/is (= {:cwd @temp-root
                  :args args
                  :value "map value"
                  :path (or (.-PATH (.-env js/process)) nil)}
                 fact))
        (t/is (= (:stdout result) (fs/readFileSync (file "fact.json") "utf8")))
        (t/is (not (fs/existsSync (file "unexpected"))) "arguments do not pass through a shell")))))

(t/deftest ^:async routes-both-streams-before-the-child-can-finish
  (let [seen   (atom {:stdout "" :stderr ""})
        source (str "process.stdout.write('ready\\n');\n"
                    "process.stderr.write('warning\\n');\n"
                    "const timer = setInterval(() => {\n"
                    "  if (fs.existsSync('ack')) {\n"
                    "    clearInterval(timer); fs.writeFileSync('done', 'yes');\n"
                    "    process.stdout.write('complete\\n');\n"
                    "  }\n"
                    "}, 10);\n")
        result (await
                (within
                 (process/run
                  (node-options
                   source
                   {:on-output
                    (fn [text stream]
                      (swap! seen update stream str text)
                      (when (str/includes? (:stdout @seen) "ready\n")
                        (fs/writeFileSync (file "ack") "yes")))}))))]
    (t/is (= {:exit-code 0 :stdout "ready\ncomplete\n" :stderr "warning\n"} result))
    (t/is (= (select-keys result [:stdout :stderr]) @seen))
    (t/is (= "yes" (fs/readFileSync (file "done") "utf8")))))

(t/deftest ^:async lines-keep-streams-separate-and-flush-final-fragments
  (let [seen (atom {:stdout [] :stderr []})
        source (str "process.stdout.write('out-'); process.stderr.write('err-');"
                    "setTimeout(() => { process.stdout.write('done\\r');"
                    "process.stderr.write('done\\nlast-err');"
                    "setTimeout(() => process.stdout.write('\\nlast-out'), 20); }, 20);")
        result (await (process/run
                       (node-options source
                                     {:capture-output? false
                                      :on-line (fn [line stream]
                                                 (swap! seen update stream conj line))})))]
    (t/is (= {:stdout ["out-done" "last-out"] :stderr ["err-done" "last-err"]} @seen))
    (t/is (= {:exit-code 0 :stdout "" :stderr ""} result))))

(t/deftest ^:async decodes-utf8-across-real-pipe-chunks
  (let [source (str "const bytes = Buffer.from('Ω😀');\n"
                    "process.stdout.write(bytes.subarray(0, 1));\n"
                    "setTimeout(() => process.stdout.write(bytes.subarray(1)), 20);\n")
        result (await (within (process/run (node-options source {}))))]
    (t/is (= "Ω😀" (:stdout result)))))

(t/deftest ^:async streams-large-output-without-retaining-it
  (let [sizes  (atom {:stdout 0 :stderr 0})
        source (str "process.stdout.write('x'.repeat(256 * 1024));\n"
                    "process.stderr.write('y'.repeat(128 * 1024));\n")
        result (await
                (within
                 (process/run
                  (node-options source
                                {:capture-output? false
                                 :on-output (fn [text stream]
                                              (swap! sizes update stream + (count text)))}))))]
    (t/is (= {:exit-code 0 :stdout "" :stderr ""} result))
    (t/is (= {:stdout (* 256 1024) :stderr (* 128 1024)} @sizes))))

(t/deftest ^:async nonzero-exit-keeps-code-command-and-output
  (let [options (node-options
                 "process.stdout.write('fact\\n'); process.stderr.write('failed\\n'); process.exitCode = 17;"
                 {})
        data    (await (rejected (process/run options)))]
    (t/is (= :process-exit (:type data)))
    (t/is (= (:command options) (:command data)))
    (t/is (= 17 (:exit-code data)))
    (t/is (nil? (:signal data)))
    (t/is (= "fact\n" (:stdout data)))
    (t/is (= "failed\n" (:stderr data)))))

(t/deftest ^:async spawn-failure-keeps-os-evidence
  (let [command (file "no-such-command")
        data    (await (rejected (process/run (node-options "" {:command command}))))]
    (t/is (= :process-spawn (:type data)))
    (t/is (= command (:command data)))
    (t/is (= "ENOENT" (:code data)))
    (t/is (nil? (:exit-code data)))
    (t/is (nil? (:signal data)))))

(t/deftest ^:async expired-deadline-does-not-start-work
  (let [data (await (rejected
                     (process/run
                      (node-options "fs.writeFileSync('started', 'yes');"
                                    {:deadline (- (js/Date.now) 1)}))))]
    (t/is (= :process-timeout (:type data)))
    (t/is (= (.-execPath js/process) (:command data)))
    (t/is (not (fs/existsSync (file "started"))))
    (t/is (not (fs/existsSync (file "root.pid"))))))

(t/deftest ^:async already-aborted-signal-does-not-start-work
  (let [controller (js/AbortController.)]
    (.abort controller "already-cancelled")
    (let [data (await (rejected
                       (process/run
                        (node-options "fs.writeFileSync('started', 'yes');"
                                      {:signal (.-signal controller)}))))]
      (t/is (= :process-cancelled (:type data)))
      (t/is (= "already-cancelled" (:reason data)))
      (t/is (not (fs/existsSync (file "started"))))
      (t/is (not (fs/existsSync (file "root.pid")))))))

(t/deftest ^:async timeout-kills-the-owner-and-a-term-resistant-descendant
  (let [limit (process/deadline 1000)
        data  (await
               (rejected
                (process/run
                 (node-options (str (spawn-descendant) "setInterval(() => {}, 1000);")
                               {:deadline limit}))))]
    (t/is (= :process-timeout (:type data)))
    (t/is (= limit (:deadline data)))
    (t/is (= "SIGTERM" (:signal data)))
    (t/is (fs/existsSync (file "descendant.ready")))
    (await (assert-tree-stopped))))

(t/deftest ^:async cancellation-kills-the-whole-owned-group
  (let [controller (js/AbortController.)
        output     (atom "")
        groups     (atom nil)
        data       (await
                    (rejected
                     (process/run
                      (node-options
                       (str (spawn-descendant) "setInterval(() => {}, 1000);")
                       {:signal (.-signal controller)
                        :on-output
                        (fn [text stream]
                          (when (= :stdout stream)
                            (swap! output str text)
                            (when (str/includes? @output "descendant-ready\n")
                              (reset! groups
                                      {:root-pid (read-pid "root.pid")
                                       :root (:group (process-status (read-pid "root.pid")))
                                       :descendant (:group (process-status (read-pid "descendant.pid")))
                                       :grandchild (:group (process-status (read-pid "grandchild.pid")))
                                       :runner (:group (process-status (.-pid js/process)))})
                              (.abort controller "cancel-tree"))))}))))]
    (t/is (= :process-cancelled (:type data)))
    (t/is (= "cancel-tree" (:reason data)))
    (t/is (= "SIGTERM" (:signal data)))
    (t/is (= (:root-pid @groups) (:root @groups) (:descendant @groups) (:grandchild @groups))
          "the root leads the group containing all generations")
    (t/is (not= (:runner @groups) (:root @groups)) "the group is separate from the runner")
    (await (assert-tree-stopped))))

(t/deftest ^:async output-callback-failure-cleans-the-owned-group
  (doseq [callback [:on-output :on-line]]
    (let [seen (atom "")
          data (await
                (rejected
                 (process/run
                  (node-options
                   (str (spawn-descendant) "setInterval(() => {}, 1000);")
                   {callback
                    (fn [text stream]
                      (when (= :stdout stream)
                        (swap! seen str text)
                        (when (str/includes? @seen "descendant-ready")
                          (throw (js/Error. "Output consumer failed")))))}))))]
      (t/is (= :process-output (:type data)))
      (t/is (= :stdout (:stream data)))
      (t/is (= "SIGTERM" (:signal data)))
      (t/is (str/includes? (:stdout data) "descendant-ready\n"))
      (await (assert-tree-stopped)))))

(t/deftest ^:async successful-owner-exit-cleans-descendants-that-hold-the-pipes
  (let [source (str (spawn-descendant)
                    "child.unref();\n"
                    "const timer = setInterval(() => {\n"
                    "  if (fs.existsSync('descendant.ready')) {\n"
                    "    clearInterval(timer); process.stdout.write('root-complete\\n');\n"
                    "  }\n"
                    "}, 10);\n")
        result (await (within (process/run (node-options source {}))))]
    (t/is (= 0 (:exit-code result)))
    (t/is (str/includes? (:stdout result) "root-complete\n"))
    (t/is (str/includes? (:stdout result) "descendant-ready\n"))
    (await (assert-tree-stopped))))

(t/deftest ^:async nonzero-owner-exit-also-cleans-descendants-that-hold-the-pipes
  (let [source (str (spawn-descendant)
                    "child.unref();\n"
                    "const timer = setInterval(() => {\n"
                    "  if (fs.existsSync('descendant.ready')) {\n"
                    "    clearInterval(timer); process.stderr.write('root-failed\\n');\n"
                    "    process.exitCode = 17;\n"
                    "  }\n"
                    "}, 10);\n")
        data   (await (rejected (process/run (node-options source {}))))]
    (t/is (= :process-exit (:type data)))
    (t/is (= 17 (:exit-code data)))
    (t/is (= "root-failed\n" (:stderr data)))
    (await (assert-tree-stopped))))

(t/deftest ^:async lock-wait-times-out-and-cancelled-holder-releases-inherited-flock
  (let [lock       (file "build.lock")
        controller (js/AbortController.)
        holder     (node-source
                    "holder.pid"
                    (str "process.on('SIGTERM', () => fs.writeFileSync('holder.term', 'seen'));\n"
                         "fs.writeFileSync('holder.ready', 'yes');\n"
                         "setInterval(() => {}, 1000);\n"))
        held       (rejected
                    (process/run
                     (node-options ""
                                   {:command "flock"
                                    :args ["--exclusive" lock (.-execPath js/process) "-e" holder]
                                    :signal (.-signal controller)})))]
    (try
      (await (wait-for #(fs/existsSync (file "holder.ready"))))
      (let [limit (process/deadline 150)
            data  (await
                   (rejected
                    (process/run
                     (node-options ""
                                   {:command "flock"
                                    :args ["--exclusive" lock (.-execPath js/process) "-e"
                                           (node-source "waiter.pid" "fs.writeFileSync('waiter-acquired', 'yes');")]
                                    :deadline limit}))))]
        (t/is (= :process-timeout (:type data)))
        (t/is (= limit (:deadline data)))
        (t/is (not (fs/existsSync (file "waiter-acquired"))))
        (t/is (running? (read-pid "holder.pid")) "the first owner still holds the lock"))
      (.abort controller "release-lock")
      (let [data (await held)]
        (t/is (= :process-cancelled (:type data)))
        (t/is (not (running? (read-pid "holder.pid")))))
      (let [result (await
                    (within
                     (process/run
                      (node-options ""
                                    {:command "flock"
                                     :args ["--exclusive" "--nonblock" lock (.-execPath js/process) "-e"
                                            (node-source "probe.pid" "fs.writeFileSync('probe-acquired', 'yes');")]}))))]
        (t/is (= 0 (:exit-code result)))
        (t/is (= "yes" (fs/readFileSync (file "probe-acquired") "utf8")))
        (t/is (not (fs/existsSync (file "waiter-acquired"))))
        (t/is (= "seen" (fs/readFileSync (file "holder.term") "utf8"))))
      (finally
        (.abort controller "test-cleanup")
        (await held)))))

(t/deftest ^:async real-signals-reach-the-callback-and-remover-restores-listeners
  (let [before-int  (.listenerCount js/process "SIGINT")
        before-term (.listenerCount js/process "SIGTERM")
        received    (atom [])
        next-signal (volatile! nil)
        remove      (process/on-signals
                     (fn [signal]
                       (swap! received conj signal)
                       (@next-signal signal)))
        receive     (fn [signal]
                      (within
                       (js/Promise.
                        (fn [resolve _]
                          (vreset! next-signal resolve)
                          (.kill js/process (.-pid js/process) signal)))))]
    (try
      (t/is (= "SIGINT" (await (receive "SIGINT"))))
      (t/is (= "SIGTERM" (await (receive "SIGTERM"))))
      (t/is (= ["SIGINT" "SIGTERM"] @received))
      (finally
        (remove)))
    (remove)
    (t/is (= before-int (.listenerCount js/process "SIGINT")))
    (t/is (= before-term (.listenerCount js/process "SIGTERM")))))
