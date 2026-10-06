;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.measurement
  "Measurement normalization runs after renderer timing ends.
  Explicit entries declare which values supply observations. Raw traces retain
  zero spans, non-finite values and diagnostic settings. Render-call totals sum
  existing final/cached call durations and supply one observation per attempt."
  (:require
   [benches.render-wasm.result :as result]))

(defn- duration
  "Declares one total duration with its timing semantics and zero policy."
  [value semantics zero-policy]
  (result/metric {:value value :unit :ms :semantics semantics
                  :basis {:kind :attempt-total} :zero-policy zero-policy}))

(defn- add-duration
  "Adds a known duration only when its raw field exists, preserving explicit nil."
  [metrics measurement field id semantics zero-policy]
  (if (contains? measurement field)
    (assoc metrics id (duration (get measurement field) semantics zero-policy))
    metrics))

(defn render-call-total
  "Sums existing call durations after timing, without interpreting GPU or CPU work.
  No calls or missing durations yield nil; recorded zero durations remain zero.
  NaN and infinities propagate as raw values."
  [measurement]
  (let [slices (concat (:slices measurement) (:cached-slices measurement))]
    (when (and (seq slices) (every? #(number? (:duration-ms %)) slices))
      (reduce + 0 (map :duration-ms slices)))))

(defn metrics
  "Normalizes standard durations and any authored explicit metric entries.
  Fresh Full completion has the sole metric ID :first-render-ms. Warm setup
  stays in preparation diagnostics. Requested waits and counts stay raw."
  [measurement fresh? setup]
  (let [entries (into {} (map (fn [[id entry]] [id (result/metric entry)])) (:metrics measurement))
        entries (if fresh?
                  (-> entries
                      (add-duration setup :module-init-ms :module-init-ms :module-instantiation :positive)
                      (add-duration setup :graphics-init-ms :graphics-init-ms :graphics-initialization :positive)
                      (add-duration setup :upload-ms :upload-ms :shared-encoding-and-upload :positive)
                      (add-duration measurement :full-ms :first-render-ms :prepared-scene-to-full :positive)
                      (add-duration measurement :viewport-ready-ms :viewport-ready-ms :prepared-scene-to-viewport-ready :positive))
                  (-> entries
                      (add-duration measurement :interact-ms :interact-ms :camera-session-to-full :positive)
                      (add-duration measurement :active-ms :active-ms :camera-active-span :positive)
                      (add-duration measurement :settling-actual-ms :settling-actual-ms :actual-settling-span :allow)
                      (add-duration measurement :set-view-end-ms :set-view-end-ms :synchronous-set-view-end-call :allow)
                      (add-duration measurement :time-to-viewport-ready-ms :time-to-viewport-ready-ms :finalization-to-viewport-ready :positive)
                      (add-duration measurement :time-to-full-ms :time-to-full-ms :finalization-to-full :positive)
                      (add-duration measurement :last-input-to-full-ms :last-input-to-full-ms :last-input-to-full :positive)))]
    (if (or (contains? measurement :slices) (contains? measurement :cached-slices))
      (assoc entries :renderer-call-total-ms
             (duration (render-call-total measurement) :synchronous-render-call-elapsed :allow))
      entries)))

(defn attempt
  "Projects bridge evidence into a raw attempt; the run assigns its ordinal.
  Failed evidence remains raw and supplies no statistical observations."
  [case-id preparation-id warmup? evidence]
  (cond-> {:case-id case-id :preparation-id preparation-id :warmup? warmup?
           :outcome (if (= "ok" (:status evidence)) :completed :failed)
           :metrics (or (:metrics evidence) {}) :trace (or (:trace evidence) {})}
    (not= "ok" (:status evidence)) (assoc :failure (select-keys evidence [:status :phase :message :cause]))
    (:partial evidence) (assoc :partial (:partial evidence))))
