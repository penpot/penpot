;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.measurement
  "Projects browser phase evidence into saved attempts."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.result :as result]))

(defn partial-phases
  "Returns the valid prefix of delivered phase history.

  Args:
    phases: raw blocks from a browser response.
    preparation-id: expected owner of the blocks.
    order: allowed phase names in execution order.

  Returns:
    Valid blocks up to the first malformed block or failed phase."
  [phases preparation-id order]
  (loop [phases (when (sequential? phases) (seq phases)) order order out []]
    (if-let [phase (first phases)]
      (if (and (sm/validate result/schema:phase phase)
               (= preparation-id (:preparation-id phase))
               (= (first order) (:phase phase))
               (every? #(= 1 (count (get-in % [:metrics :command/elapsed]))) (:events phase)))
        (let [out (conj out phase)]
          (if (= :failed (:outcome phase)) out
              (recur (next phases) (next order) out)))
        out)
      out)))

(defn attempt
  "Returns an attempt with raw phase histories and available failure evidence.

  Args:
    case-id: saved case identifier.
    preparation-id: preparation identifier, or nil for an early failure.
    warmup?: whether this execution occupies a warmup slot.
    evidence: browser response with status, phases and optional failure details.

  Returns:
    An attempt ready for chronological recording."
  [case-id preparation-id warmup? evidence]
  (cond-> {:case-id case-id :preparation-id preparation-id :warmup? warmup?
           :outcome (if (= "ok" (:status evidence)) :completed :failed)
           :phases (or (:phases evidence) [])}
    (not= "ok" (:status evidence))
    (assoc :failure (select-keys evidence [:status :phase :message :cause]))
    (:partial evidence) (assoc :partial (:partial evidence))))
