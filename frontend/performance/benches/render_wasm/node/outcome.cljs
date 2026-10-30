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
   [benches.render-wasm.errors :as errors :include-macros true :refer [attempt failure?]]))

(defn interruption-code
  "Returns the exit code for the interrupting signal name: 143 for SIGTERM,
  130 otherwise."
  [signal-name]
  (case signal-name "SIGTERM" 143 130))

(defn failed
  "Returns an outcome with exit code 1 for `cause`. The log path comes from
  the ex-data of `cause`. `data` adds more keys."
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
