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
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [clojure.string :as str]))

(def default-termination-grace-ms 5000)
(def ^:private final-termination-wait-ms 5000)

(def ^:private max-timer-delay-ms
  "Largest delay that Node timers accept; longer delays overflow a signed 32-bit integer."
  2147483647)

(def ^:private default-options
  {:termination-grace-ms default-termination-grace-ms
   :capture-output?      true})

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


(defn- unresolved-cause?
  [cause seen]
  (cond
    (nil? cause) false
    (:unresolved-process (ex-data cause)) true
    (contains? seen cause) false
    :else (recur (ex-cause cause) (conj seen cause))))

(defn unresolved?
  "True when a command's process group may still be running.

  Args:
    cause: An Error, including collected errors and wrapped causes, or nil.

  Returns:
    True if any error reports :unresolved-process true after termination."
  [cause]
  (boolean
   (some (fn [{:keys [error] :as entry}]
           (or (:unresolved-process entry) (unresolved-cause? error #{})))
         (errors/get-errors cause))))

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


(defn- get-group-observation
  "Checks whether a process group has a live member.

  Args:
    pid: The group's leader PID.

  Returns:
    A map with :status set to :running, :stopped or :unknown, and :error
    when the operating system rejects the probe."
  [pid]
  (let [result (attempt :catch-fatal (group-running? pid))]
    (if (failure? result)
      {:status :unknown :error result}
      {:status (if result :running :stopped)})))

(defn- wait-again?
  "Decides whether to take another process-group observation.

  Args:
    observation: A map with :status :running, :stopped or :unknown.
    context: The absolute :deadline and :phase, either :grace before SIGKILL
      or :confirmation after SIGKILL.
    now: Current time in milliseconds since the Unix epoch.

  Returns:
    True while time remains and the group may be running. Grace ends on a
    failed probe; confirmation continues through probe failures."
  [observation {:keys [phase deadline]} now]
  (and (not= :stopped (:status observation))
       (< now deadline)
       (or (= :confirmation phase) (= :running (:status observation)))))

(defn- group-failed
  "Returns the failure of an operation on a command group.

  Args:
    kind: The failure type.
    action: Text that describes the failed operation. The message appends the
      bounded text of the cause.
    cause: The Error that the operation raised.
    data: Diagnostic data of the failure."
  [kind action cause data]
  {:type    kind
   :message (str action ": " (errors/bounded-text (errors/message-of cause) 500))
   :cause   cause
   :data    data})

(defn- ^:async wait-for-group
  [{:keys [pid deadline signals] :as context} record]
  (let [observation (get-group-observation pid)
        now         (js/Date.now)]
    (when-let [cause (:error observation)]
      (record :probe-group (group-failed :process-probe "Could not inspect command group" cause {:signals signals})))
    (if (wait-again? observation context now)
      (do
        (await (pause (min 10 (- deadline now))))
        (await (wait-for-group context record)))
      observation)))

(defn- send-group-signal
  [pid signal record]
  (let [result (attempt :catch-fatal (signal-group pid signal))]
    (when (failure? result)
      (record :signal-group
              (group-failed :process-signal (str "Could not send " signal " to command group") result {:signal signal})))
    result))

(defn- ^:async terminate-group
  [pid grace-deadline record]
  (let [signals     ["SIGTERM"]
        term        (send-group-signal pid "SIGTERM" record)
        grace       {:pid pid :phase :grace :signals signals
                     :deadline (if (failure? term) (js/Date.now) grace-deadline)}
        observation (await (wait-for-group grace record))]
    (if (= :stopped (:status observation))
      {:signals signals :unresolved-process false}
      (let [signals (conj signals "SIGKILL")]
        (send-group-signal pid "SIGKILL" record)
        (let [confirmation (assoc grace :phase :confirmation :signals signals
                                  :deadline (deadline final-termination-wait-ms))
              observation  (await (wait-for-group confirmation record))
              unresolved   (not= :stopped (:status observation))]
          (when unresolved
            (record :terminate-group
                    {:type :process-unresolved
                     :message "Command group ownership remains unresolved after SIGKILL"
                     :data {:fatal true :unresolved-process true :signals signals}}))
          {:signals signals :unresolved-process unresolved})))))

(defn- process-error
  [{:keys [command args cwd]} result {:keys [type message cause data]}]
  (ex-info message
           (merge {:type type :command command :args args :cwd cwd} result data)
           cause))

(defn- cancelled
  [^js signal]
  (let [reason (.-reason signal)]
    {:type :process-cancelled
     :message "Command cancelled"
     :cause (when (failure? reason) reason)
     :data {:reason (signal-name signal)}}))

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

(def ^:private empty-result {:exit-code nil :signal nil :stdout "" :stderr ""})
(def ^:private read-failed-message "Could not read command output")
(def ^:private callback-failed-message "Command output callback failed")

(defn- group-ids
  "Returns {:pid pid :pgid pid} for the leader of a detached process group."
  [pid]
  {:pid pid :pgid pid})

(defn- launch-error
  [options failure]
  (process-error options empty-result failure))

(defn- launch-failure
  "Returns the failure that forbids starting a command, or nil.

  Args:
    options: Command settings with the absolute :deadline and the optional
      AbortSignal :signal."
  [{:keys [signal deadline]}]
  (cond
    (and signal (.-aborted ^js signal)) (cancelled signal)
    (zero? (remaining deadline))        (timed-out deadline)))

(defn- output-failed
  [stream cause message]
  {:type :process-output :message message :cause cause :data {:stream stream}})

(defn- completion-failure
  "Returns the failure to record for the reason a command completes.

  Args:
    reason: The exit status {:exit-code :signal} or a failure map with :type.

  Returns:
    The failure, or nil for a zero exit code. Output failures are recorded
    when they occur."
  [reason]
  (let [kind (:type reason)]
    (cond
      (= :process-output kind)     nil
      kind                         reason
      (not= 0 (:exit-code reason)) {:type :process-exit :message "Command exited unsuccessfully"})))

(defn- spawn-child
  "Spawns the command in a new process group.

  Args:
    options: Command settings with :command and literal :args, and optional
      :cwd and :env. A map or JS object under :env merges over process.env.

  Returns:
    The ChildProcess.

  Throws:
    A launch error when the operating system rejects the spawn."
  [{:keys [command args cwd env] :as options}]
  (let [environment (js/Object.assign #js {} (.-env js/process) (if (map? env) (clj->js env) env))]
    (try
      (child-process/spawn command (clj->js args)
                           #js {:cwd cwd :env environment :detached true
                                :stdio #js ["pipe" "pipe" "pipe"]})
      (catch :default cause
        (throw (launch-error options (spawn-failed cause)))))))

(defn- watch-exit
  "Observes the exit and the process errors of a child.

  Returns:
    A map with :promise, which resolves with the first outcome, and :state,
    a volatile holding the latest outcome. An outcome is {:exit-code :signal}
    or the failure of a process error."
  [^js child]
  (let [state   (volatile! nil)
        observe (fn [resolve outcome]
                  (vreset! state outcome)
                  (resolve outcome))]
    {:state   state
     :promise (js/Promise.
               (fn [resolve _]
                 (.once child "exit" (fn [code signal] (observe resolve {:exit-code code :signal signal})))
                 (.once child "error" #(observe resolve (spawn-failed %)))))}))

(defn- destroy-stream
  [^js stream]
  (when stream (.destroy stream)))

(defn- failure-recorder
  "Returns (record operation failure), which appends a failure occurrence.

  Args:
    options: Command settings. Occurrences carry its :command, :args and :cwd.
    child: The ChildProcess.
    exit-state: Volatile with the latest exit outcome. Occurrences carry the
      exit status known when they occur.
    failures: Volatile vector that receives the occurrences in order."
  [options ^js child exit-state failures]
  (fn [operation failure]
    (let [context (merge empty-result
                         (group-ids (.-pid child))
                         (select-keys @exit-state [:exit-code :signal]))]
      (vswap! failures conj {:phase "process" :operation operation
                             :error (process-error options context failure)}))))

(defn- new-completion
  "Returns the settlement of the completion of a command.

  Args:
    record: Function from `failure-recorder`.

  Returns:
    A map with :promise and :settle. `settle` takes a completion reason. The
    first reason records its failure, if any, and resolves :promise. Later
    reasons do nothing."
  [record]
  (let [^js completion (js/Promise.withResolvers)
        settled        (volatile! false)]
    {:promise (.-promise completion)
     :settle  (fn [reason]
                (when-not @settled
                  (vreset! settled true)
                  (when-let [failure (completion-failure reason)]
                    (record :run-command failure))
                  ((.-resolve completion) reason)))}))

(defn- watch-deadline
  "Calls `settle` with a timeout failure once `deadline` passes.

  Args:
    deadline: Absolute Date.now() timestamp in milliseconds.
    settle: Function of a completion reason.

  Returns:
    A function that cancels the pending check."
  [deadline settle]
  (let [timer (volatile! nil)
        check (fn check []
                (let [ms (remaining deadline)]
                  (if (zero? ms)
                    (settle (timed-out deadline))
                    (vreset! timer (js/setTimeout check (min ms max-timer-delay-ms))))))]
    (check)
    #(js/clearTimeout @timer)))

(defn- watch-abort
  "Calls `settle` with a cancellation failure when `signal` aborts, or at once
  when it already aborted.

  Args:
    signal: An AbortSignal, or nil.
    settle: Function of a completion reason.

  Returns:
    A function that removes the listener."
  [^js signal settle]
  (if signal
    (let [abort (fn [_] (settle (cancelled signal)))]
      (.addEventListener signal "abort" abort #js {:once true})
      (when (.-aborted signal) (abort nil))
      #(.removeEventListener signal "abort" abort))
    (constantly nil)))

(defn- watch-completion
  "Calls `settle` with the first completion reason among the exit outcome, the
  deadline and the abort.

  Args:
    options: Command settings with :deadline and the optional :signal.
    exited: Promise of the exit outcome.
    settle: Function of a completion reason.

  Returns:
    A function that stops watching the deadline and the signal."
  [{:keys [deadline signal]} ^js exited settle]
  (.then exited settle)
  (let [stop-deadline (watch-deadline deadline settle)
        stop-abort    (watch-abort signal settle)]
    (fn []
      (stop-deadline)
      (stop-abort))))

(defn- open-line-reader
  "Returns a readline interface that passes the complete lines of `input` to
  `on-line` with `stream`. Callback failures and stream errors go to `fail`.
  Stream errors keep going to `fail` after the reader closes."
  [^js input stream on-line fail]
  (let [reader     (readline/createInterface #js {:input input :terminal false :crlfDelay js/Infinity})
        unreadable #(fail stream % read-failed-message)]
    (.on reader "line"
         (fn [line]
           (try
             (on-line line stream)
             (catch :default cause
               (fail stream cause callback-failed-message)))))
    (.on reader "error" unreadable)
    (.once reader "close" #(.on input "error" unreadable))
    reader))

(defn- observe-output
  "Decodes stdout and stderr of a child as UTF-8 and routes their text.

  Appends each chunk to `captured` when :capture-output? is set, and calls
  :on-output with each chunk and :on-line with each complete line.

  Args:
    options: Command settings with :capture-output?, :on-output and :on-line.
    child: The ChildProcess.
    captured: Volatile map from :stdout and :stderr to vectors of text.
    fail: Function of a stream, a cause and a message that receives the
      failures of streams and callbacks.

  Returns:
    A vector with the line readers it opened."
  [{:keys [on-output on-line capture-output?]} ^js child captured fail]
  (let [route  (fn [stream text]
                 (when capture-output? (vswap! captured update stream conj text))
                 (when on-output
                   (try
                     (on-output text stream)
                     (catch :default cause
                       (fail stream cause callback-failed-message)))))
        listen (fn [^js input stream]
                 (.setEncoding input "utf8")
                 (.on input "data" #(route stream %))
                 (when-not on-line
                   (.on input "error" #(fail stream % read-failed-message))))]
    (listen (.-stdout child) :stdout)
    (listen (.-stderr child) :stderr)
    (if on-line
      [(open-line-reader (.-stdout child) :stdout on-line fail)
       (open-line-reader (.-stderr child) :stderr on-line fail)]
      [])))

(defn- close-stdin
  "Closes the stdin of a child. Write errors go to `fail`, except EPIPE, which
  a command that exits without reading stdin causes."
  [^js child fail]
  (.on (.-stdin child) "error"
       (fn [cause]
         (when-not (= "EPIPE" (.-code cause))
           (fail :stdin cause "Could not close command stdin"))))
  (.end (.-stdin child)))

(defn- command-result
  "Returns the exit status and the captured text of a finished command."
  [status captured]
  {:exit-code (:exit-code status)
   :signal    (:signal status)
   :stdout    (str/join "" (:stdout captured))
   :stderr    (str/join "" (:stderr captured))})

(defn- ^:async terminate-and-drain
  "Terminates the process group of a child and drains its queued output.

  Args:
    options: Command settings with :termination-grace-ms.
    child: The ChildProcess.
    exit: Map from `watch-exit`.
    output-done: Promise that resolves when stdout and stderr end.
    record: Function from `failure-recorder`.

  Returns:
    A promise of {:status exit-status :termination termination}. The status
    comes from the exit of the child. When the group stays unresolved, the
    status is the one already known, and the child no longer keeps this
    process alive."
  [{:keys [termination-grace-ms]} ^js child {exit-state :state exited :promise} output-done record]
  (let [grace-deadline (deadline termination-grace-ms)
        termination    (await (terminate-group (.-pid child) grace-deadline record))
        unresolved?    (:unresolved-process termination)]
    (when unresolved? (.unref child))
    (let [status (if unresolved? @exit-state (await exited))]
      (await (drain-output output-done grace-deadline))
      {:status status :termination termination})))

(defn- ^:async supervise
  "Watches a spawned command until it completes, then terminates its group.

  Args:
    options: The options of `run` with defaults applied.
    child: The spawned ChildProcess.
    exit: Map from `watch-exit`.

  Returns:
    A promise of {:exit-code 0 :stdout string :stderr string}.

  Throws:
    The Error that combines the recorded failures with the result, the
    termination and the process identity."
  [options ^js child exit]
  (let [pid                                 (.-pid child)
        failures                            (volatile! [])
        captured                            (volatile! {:stdout [] :stderr []})
        record                              (failure-recorder options child (:state exit) failures)
        {completed :promise settle :settle} (new-completion record)
        fail                                (fn [stream cause message]
                                              (let [failure (output-failed stream cause message)]
                                                (record :read-output failure)
                                                (settle failure)))
        output-done                         (js/Promise.all #js [(stream-done (.-stdout child))
                                                                 (stream-done (.-stderr child))])
        readers                             (observe-output options child captured fail)
        stop-waiting                        (watch-completion options (:promise exit) settle)]
    (try
      (close-stdin child fail)
      (await completed)
      (stop-waiting)
      (let [{:keys [status termination]} (await (terminate-and-drain options child exit output-done record))
            result                       (command-result status @captured)
            failure                      (errors/combine @failures)]
        (if failure
          (throw (errors/with-context failure (errors/message-of failure)
                   (merge result termination (group-ids pid))))
          (dissoc result :signal)))
      (finally
        (run! #(.close ^js %) readers)
        (stop-waiting)))))

(defn ^:async run
  "Runs a command and owns its detached POSIX process group.

  An expired deadline or aborted signal starts no process. Output callbacks
  receive UTF-8 chunks or complete lines, including a final unterminated
  line. Completion follows the exit of the root process, not the close of its
  output pipes, because descendants can keep the pipes open. Cleanup sends
  SIGTERM and waits the termination grace. If the group still runs, it sends
  SIGKILL once and allows five more seconds for the group to stop. Unresolved
  ownership produces a fatal failure.

  Args:
    options: Command settings with :command, literal :args, optional :cwd and
      :env, absolute :deadline and optional AbortSignal under :signal.
      :termination-grace-ms defaults to 5000. :on-output receives text and
      :stdout/:stderr; :on-line receives a line and its stream. Output capture
      defaults to true and accepts :capture-output? false for streamed output.

  Returns:
    A promise resolving to {:exit-code 0 :stdout string :stderr string}.

  Throws:
    Failures reject with command, output and process identity. Collected
    occurrences retain execution, output, signalling and probe failures in
    order. :process-unresolved marks unresolved group ownership as fatal.
    Cancellation records the signal name as :reason and retains an Error
    supplied as the abort reason as a cause."
  [options]
  (let [options (merge default-options options)]
    (when-let [failure (launch-failure options)]
      (throw (launch-error options failure)))
    (let [^js child (spawn-child options)
          exit      (watch-exit child)]
      (try
        (if (.-pid child)
          (await (supervise options child exit))
          (throw (launch-error options (await (:promise exit)))))
        (finally
          (destroy-stream (.-stdin child))
          (destroy-stream (.-stdout child))
          (destroy-stream (.-stderr child)))))))
