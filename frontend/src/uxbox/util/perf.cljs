;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.perf
  "Performance profiling for react components."
  (:require [uxbox.util.math :as math]
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
;; automatically debouced for avod excesive spam to the console.

;; #?(:clj
;;    (defmacro with-measure
;;      [name & body]
;;      `(let [start# (js/performance.now)
;;             res# (do ~@body)
;;             end# (js/performance.now)
;;             time# (.toFixed (- end# start#) 2)]
;;         (println (str "[perf|" ~name "] => " time#))
;;         res#)))


(defn on-render-factory
  [label]
  (let [buf (td/TDigest.)
        log (f/debounce
             (fn [phase buf]
               (js/console.log (str "[profile: " label " (" phase ")] "
                                    "samples=" (unchecked-get buf "n") "\n"
                                    "Q50=" (.percentile buf 0.50) "\n"
                                    "Q75=" (.percentile buf 0.75) "\n"
                                    "Q95=" (.percentile buf 0.90) "\n"
                                    "MAX=" (.percentile buf 1))))
             300)]
    (fn [id phase adur, bdur, st, ct, itx]
      (.push buf adur)
      (log phase buf))))

(mf/defc profiler
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")
        label    (unchecked-get props "label")
        enabled? (unchecked-get props "enabled")
        on-render (mf/use-memo
                   (mf/deps label)
                   #(on-render-factory label))]
    (if enabled?
      [:> react/Profiler {:id label
                          :on-render on-render}
       children]
      children)))
