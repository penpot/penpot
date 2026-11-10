;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.outcome
  "Result of a benchmark command.

  An outcome is a map with `:exit-code`. A command that failed adds `:errors`
  (a vector, primary failure first) and `:log-path`. Exit code 130 and 143
  mark an interruption and no failure replaces them."
  (:require
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]))

(defn interruption-code
  "Returns the exit code for the interrupting signal name: 143 for SIGTERM,
  130 otherwise."
  [signal-name]
  (case signal-name "SIGTERM" 143 130))

(defn succeeded
  "Returns a successful outcome with command result data."
  [data]
  (merge {:exit-code 0 :errors [] :log-path nil} data))

(defn failed
  "Returns an outcome for `cause` with defaults for errors, exit code and log.

  Args:
    cause: Failure supplying the default error and log path.
    data: Outcome fields that override defaults, including :errors,
      :exit-code and :log-path.

  Returns:
    An outcome with exit code 1 and the failure's log path as defaults."
  [cause data]
  (merge {:errors [cause]
          :exit-code 1
          :log-path (:log-path (ex-data cause))}
         data))

(defn add-error
  "Appends `cause` to the errors of `outcome`. An exit code that is not an
  interruption becomes 1."
  [outcome cause]
  (-> outcome
      (update :errors (fnil conj []) cause)
      (update :exit-code #(if (contains? #{130 143} %) % 1))))

(defn publish
  "Calls `f` to print a result of `outcome`. A failure of `f` is added to the
  errors of the returned outcome."
  [outcome f]
  (let [published (attempt :catch-fatal (f))]
    (cond-> outcome
      (failure? published) (add-error published))))
