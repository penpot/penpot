;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.process
  "Deadline-bound Node commands with owned POSIX process groups."
  (:require
   ["child_process" :as child-process]
   ["fs" :as fs]
   ["node:readline" :as readline]
   [clojure.string :as str]))

(def default-termination-grace-ms 5000)

(defn signal-name
  "Returns the signal name for an aborted AbortSignal, defaulting to SIGINT."
  [signal]
  (when (and signal (.-aborted ^js signal))
    (let [reason (.-reason ^js signal)]
      (cond
        (string? reason) reason
        (keyword? reason) (name reason)
        :else (or (:signal (ex-data reason)) "SIGINT")))))

(defn deadline
  "Returns an absolute Date.now() timestamp, in milliseconds. All commands
  in a preparation can share it, including commands waiting for a lock.
  This is a wall clock deadline, so clock changes affect the time left."
  [timeout-ms]
  (+ (js/Date.now) timeout-ms))

(defn remaining
  "Returns the nonnegative milliseconds left on a Date.now() deadline."
  [deadline]
  (max 0 (- deadline (js/Date.now))))

(defn- signal-group
  [pid signal]
  (try
    (.kill js/process (- pid) signal)
    true
    (catch :default cause
      (if (= "ESRCH" (.-code cause))
        false
        (throw cause)))))

(defn- linux-group-member?
  [pid entry]
  (when (re-matches #"\d+" entry)
    (try
      ;; comm may contain spaces or ')'; fields after its final ')' start
      ;; with state, ppid and pgrp. Zombies retain a group but no resources.
      (let [stat   (fs/readFileSync (str "/proc/" entry "/stat") "utf8")
            fields (str/split (str/trim (subs stat (inc (.lastIndexOf stat ")")))) #"\s+")]
        (and (= pid (js/Number (nth fields 2)))
             (not (contains? #{"Z" "X" "x"} (first fields)))))
      (catch :default cause
        (if (contains? #{"ENOENT" "ESRCH"} (.-code cause))
          false
          (throw cause))))))

(defn- group-running?
  [pid]
  (and (signal-group pid 0)
       (if (= "linux" (.-platform js/process))
         ;; Container init need not reap orphan zombies promptly. Waiting
         ;; for kill(-pgid, 0) to return ESRCH can therefore hang forever.
         (some #(linux-group-member? pid %) (array-seq (fs/readdirSync "/proc")))
         true)))

(defn- pause
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- ^:async terminate-group
  [pid grace-deadline]
  (try
    (signal-group pid "SIGTERM")
    (loop []
      (when (group-running? pid)
        (when (zero? (remaining grace-deadline))
          ;; Include members forked while earlier members handled termination.
          (signal-group pid "SIGKILL"))
        (await (pause 10))
        (recur)))
    (catch :default cause
      ;; A failed liveness probe must not leave a running compiler or lock owner.
      (signal-group pid "SIGKILL")
      (throw cause))))

(defn- process-error
  [{:keys [command args cwd]} result {:keys [type message cause data]}]
  (ex-info message
           (merge {:type type :command command :args args :cwd cwd} result data)
           cause))

(defn- cancelled
  [^js signal]
  {:type :process-cancelled
   :message "Command cancelled"
   :data {:reason (.-reason signal)}})

(defn- timed-out
  [deadline]
  {:type :process-timeout
   :message "Command deadline expired"
   :data {:deadline deadline}})

(defn- spawn-failed
  [^js cause]
  {:type :process-spawn
   :message "Could not spawn command"
   :cause cause
   :data {:code (.-code cause)}})

(defn- stream-done
  [^js stream]
  (js/Promise.
   (fn [resolve _]
     (.once stream "end" resolve)
     (.once stream "close" resolve)
     (.once stream "error" resolve))))

(defn- drain-output
  "Allow queued pipe data to drain, but never wait indefinitely for an EOF."
  [done grace-deadline]
  (js/Promise.
   (fn [resolve _]
     (let [timer (js/setTimeout resolve (max 10 (remaining grace-deadline)))]
       (.then done
              (fn [_]
                (js/clearTimeout timer)
                (resolve)))))))

(defn ^:async run
  "Spawns command with literal args, cwd and env merged over process.env.
  deadline is an absolute Date.now() timestamp; signal is an optional
  AbortSignal. An expired deadline or aborted signal starts no process.

  on-output receives [UTF-8 text :stdout/:stderr] as data arrives. Optional
  on-line receives [line :stdout/:stderr], including a final unterminated line.
  Output is also collected unless capture-output? is false; then both result
  strings are empty. Returns a promise of
  {:exit-code 0 :stdout string :stderr string}.

  Failures reject with ex-info carrying :command, :exit-code, :signal and
  output. Types are :process-exit, :process-spawn, :process-timeout and
  :process-cancelled; output/cleanup errors use :process-output/:process-cleanup.

  The detached POSIX group belongs to this call, including descendants left
  after a successful root exit. Cleanup sends SIGTERM then, after the grace,
  SIGKILL. Settlement waits for live group members to stop. It uses root exit,
  not child close, since descendants may inherit and retain the output pipes."
  [{:keys [command args cwd env deadline signal termination-grace-ms on-output on-line capture-output?]
    :or {termination-grace-ms default-termination-grace-ms capture-output? true}
    :as options}]
  (let [empty-result {:exit-code nil :signal nil :stdout "" :stderr ""}
        environment  (js/Object.assign #js {} (.-env js/process) (if (map? env) (clj->js env) env))]
    (when (and signal (.-aborted ^js signal))
      (throw (process-error options empty-result (cancelled signal))))
    (when (zero? (remaining deadline))
      (throw (process-error options empty-result (timed-out deadline))))
    (let [^js child (try
                      (child-process/spawn command (clj->js args)
                                           #js {:cwd cwd
                                                :env environment
                                                :detached true
                                                :stdio #js ["pipe" "pipe" "pipe"]})
                      (catch :default cause
                        (throw (process-error options empty-result (spawn-failed cause)))))
          exited    (js/Promise.
                     (fn [resolve _]
                       (.once child "exit"
                              (fn [code signal]
                                (resolve {:exit-code code :signal signal})))
                       (.once child "error"
                              (fn [cause]
                                (resolve (spawn-failed cause))))))]
      (try
        (if-not (.-pid child)
          ;; A failed spawn may not provide stdin/stdout/stderr at all.
          (throw (process-error options empty-result (await exited)))
          (let [stdout         (volatile! [])
                stderr         (volatile! [])
                output-failure (volatile! nil)
                line-readers   (volatile! [])
                complete       (volatile! nil)
                timeout        (volatile! nil)
                abort          (fn [_] (@complete (cancelled signal)))
                stop-waiting   (fn []
                                 (js/clearTimeout @timeout)
                                 (when signal
                                   (.removeEventListener ^js signal "abort" abort)))
                stream-error   (fn [stream cause message]
                                 (let [failure {:type :process-output
                                                :message message
                                                :cause cause
                                                :data {:stream stream}}]
                                   (vreset! output-failure failure)
                                   (@complete failure)))
                route          (fn [parts stream text]
                                 (when capture-output?
                                   (vswap! parts conj text))
                                 (when on-output
                                   (try
                                     (on-output text stream)
                                     (catch :default cause
                                       (stream-error stream cause "Command output callback failed")))))
                output-done    (js/Promise.all #js [(stream-done (.-stdout child))
                                                    (stream-done (.-stderr child))])
                completed      (js/Promise.
                                (fn [resolve _]
                                  (vreset! complete resolve)
                                  (.then exited resolve)
                                  (letfn [(check-deadline []
                                            (let [ms (remaining deadline)]
                                              (if (zero? ms)
                                                (resolve (timed-out deadline))
                                                ;; Node timers overflow above a signed 32-bit delay.
                                                (vreset! timeout
                                                         (js/setTimeout check-deadline (min ms 2147483647))))))]
                                    (check-deadline))
                                  (when signal
                                    (.addEventListener ^js signal "abort" abort #js {:once true})
                                    (when (.-aborted ^js signal)
                                      (abort nil)))))]
            (try
              (.setEncoding (.-stdout child) "utf8")
              (.setEncoding (.-stderr child) "utf8")
              (.on (.-stdout child) "data" #(route stdout :stdout %))
              (.on (.-stderr child) "data" #(route stderr :stderr %))
              (.on (.-stdout child) "error" #(stream-error :stdout % "Could not read command output"))
              (.on (.-stderr child) "error" #(stream-error :stderr % "Could not read command output"))
              (when on-line
                (doseq [[stream input] [[:stdout (.-stdout child)] [:stderr (.-stderr child)]]]
                  (let [reader (readline/createInterface
                                #js {:input input :terminal false :crlfDelay js/Infinity})]
                    (vswap! line-readers conj reader)
                    (.on reader "line"
                         (fn [line]
                           (try
                             (on-line line stream)
                             (catch :default cause
                               (stream-error stream cause "Command output callback failed")))))
                    (.on reader "error" #(stream-error stream % "Could not read command output")))))
              ;; EPIPE is normal when a command exits without reading stdin.
              (.on (.-stdin child) "error"
                   (fn [cause]
                     (when-not (= "EPIPE" (.-code cause))
                       (stream-error :stdin cause "Could not close command stdin"))))
              (.end (.-stdin child))
              (let [reason         (await completed)
                    grace-deadline (benches.render-wasm.node.process/deadline termination-grace-ms)]
                (stop-waiting)
                (let [cleanup-failure (try
                                        (await (terminate-group (.-pid child) grace-deadline))
                                        nil
                                        (catch :default cause
                                          {:type :process-cleanup
                                           :message "Could not terminate command group"
                                           :cause cause}))
                      exit            (await exited)]
                  (await (drain-output output-done grace-deadline))
                  (let [result  {:exit-code (:exit-code exit)
                                 :signal (:signal exit)
                                 :stdout (str/join "" @stdout)
                                 :stderr (str/join "" @stderr)}
                        failure (or (when (:type reason) reason)
                                    @output-failure
                                    cleanup-failure
                                    (when (not= 0 (:exit-code exit))
                                      {:type :process-exit :message "Command exited unsuccessfully"}))]
                    (if failure
                      (throw (process-error options result failure))
                      (dissoc result :signal)))))
              (finally
                (doseq [reader @line-readers]
                  (.close ^js reader))
                (stop-waiting)))))
        (finally
          (when-let [stream (.-stdin child)]
            (.destroy ^js stream))
          (when-let [stream (.-stdout child)]
            (.destroy ^js stream))
          (when-let [stream (.-stderr child)]
            (.destroy ^js stream)))))))

(defn on-signals
  "Installs SIGINT/SIGTERM callbacks receiving the signal name. Returns an
  idempotent remover. The caller can abort its work and let output drain."
  [callback]
  (let [interrupt (fn [] (callback "SIGINT"))
        terminate (fn [] (callback "SIGTERM"))]
    (.on js/process "SIGINT" interrupt)
    (.on js/process "SIGTERM" terminate)
    (fn []
      (.removeListener js/process "SIGINT" interrupt)
      (.removeListener js/process "SIGTERM" terminate))))
