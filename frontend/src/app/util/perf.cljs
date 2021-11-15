;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.perf
  "Performance profiling for react components."
  (:require-macros [app.util.perf])
  (:require
   [app.common.math :as math]
   [rumext.alpha :as mf]
   [goog.functions :as f]
   ["react" :as react]
   ["tdigest" :as td]))

;; For use it, just wrap the component you want to profile with
;; `perf/profiler` component and pass a label for debug purpose.
;;
;; Example:
;;
;; [:& perf/profiler {:label "viewport"}
;;  [:section
;;   [:& some-component]]]
;;
;; This will catch all renders and print to the console the
;; percentiles of render time measures. The log function is
;; automatically debounced to avoid excessive spam to the console.

(defn tdigest
  []
  (specify! (td/TDigest.)
    ITransientCollection
    (-conj! [this n]
      (.push this n)
      this)

    (-persistent! [this]
      this)))

(defn tdigest-summary
  [^js td]
  (str "samples=" (unchecked-get td "n") "\n"
       "Q50=" (.percentile td 0.50) "\n"
       "Q75=" (.percentile td 0.75) "\n"
       "Q95=" (.percentile td 0.90) "\n"
       "MAX=" (.percentile td 1)))

(defn timestamp
  []
  (js/performance.now))

(def registry (js/Map.))

(def register-measure
  (let [insert!
        (fn [name measure]
          (let [td (.get registry name)]
            (if td
              (conj! td measure)
              (.set registry name (conj! (tdigest) measure)))))

        print-single-summary!
        (fn [name td]
          (js/console.log (str "[measure: " name "] " (tdigest-summary td))))

        print-summary!
        (f/debounce
         #(.forEach registry (fn [td name] (print-single-summary! name td)))
         500)]
    (fn [name measure]
      (insert! name measure)
      (print-summary!))))

(defn measurable
  [name f]
  (fn [& args]
    (app.util.perf/with-measure name
      (apply f args))))

(defn on-render-factory
  [label]
  (let [td  (tdigest)
        log (f/debounce
             (fn [phase td]
               (js/console.log (str "[profile: " label " (" phase ")] "
                                    (tdigest-summary td))))
             300)]
    (fn [id phase adur, bdur, st, ct, itx]
      (conj! td adur)
      (log phase td))))

(mf/defc profiler
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")
        label    (unchecked-get props "label")
        enabled? (unchecked-get props "enabled")
        enabled? (if (nil? enabled?) true enabled?)
        on-render (mf/use-memo
                   (mf/deps label)
                   #(on-render-factory label))]
    (if enabled?
      [:> react/Profiler #js {:id label
                              :onRender on-render}
       children]
      children)))
