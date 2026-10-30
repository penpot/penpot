;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.errors
  "Failure values for the benchmark harness.

  A step that can fail returns its value or an Error. `attempt` turns a thrown
  value into that Error and `failure?` tells the two apart:

    (let [page (attempt (await (open-page owner)))]
      (if (failure? page)
        (record-failure page)
        (use page)))

  A fatal failure marks a write to the caller's own record or log. It cannot
  be recorded in that record, so `attempt` throws it again and only a boundary
  that owns the record or reports to the user catches it with `:catch-fatal`.

  This namespace has no Node or browser dependencies."
  (:require-macros
   [app.common.exceptions :as ex]))

(def ^:private max-cause-depth 8)

(defn failure?
  "True when `x` is an Error."
  [x]
  (instance? js/Error x))

(defn message-of
  "Returns the message of an Error or the string form of another value."
  [cause]
  (or (ex-message cause) (str cause)))

(defn fatal-error
  "Returns an exception that `fatal?` recognizes. `data` is its ex-data map and
  `cause` the original failure."
  [message data cause]
  (ex-info message (assoc data :fatal true) cause))

(defn fatal?
  "True when `cause` or one of its causes has the ex-data `:fatal` marker."
  [cause]
  (loop [cause cause
         depth 0]
    (cond
      (nil? cause) false
      (:fatal (ex-data cause)) true
      (>= depth max-cause-depth) false
      :else (recur (ex-cause cause) (inc depth)))))

(defn as-failure
  "Returns `thrown` when it is an Error. Otherwise returns an Error whose
  message is the string `thrown` or the printed value, with the value in the
  `:thrown` ex-data."
  [thrown]
  (if (failure? thrown)
    thrown
    (ex-info (if (string? thrown)
               thrown
               (str "non-error value thrown: " (or (ex/ignoring (pr-str thrown)) "unprintable value")))
             {:thrown thrown})))

(defn with-data
  "Returns `x` when it is not a failure. For a failure returns an exception
  with the same message, `x` as its cause and `data` merged under the ex-data
  of `x`, so keys already present on `x` win."
  [x data]
  (if (failure? x)
    (ex-info (message-of x) (merge data (ex-data x)) x)
    x))
