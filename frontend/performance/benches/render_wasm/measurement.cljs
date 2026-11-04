;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.measurement
  "Projects browser phase evidence into saved attempts.")

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
           :phases (filterv #(#{:main :reset} (:phase %)) (:phases evidence))}
    (not= "ok" (:status evidence))
    (assoc :failure (select-keys evidence [:status :phase :message :cause]))
    (:partial evidence) (assoc :partial (:partial evidence))))
