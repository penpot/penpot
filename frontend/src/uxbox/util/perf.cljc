;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.perf
  "Performance and debugging tools."
  #?(:cljs (:require-macros [uxbox.util.perf]))
  #?(:cljs (:require [uxbox.util.math :as math])))

#?(:clj
   (defmacro with-measure
     [name & body]
     `(let [start# (js/performance.now)
            res# (do ~@body)
            end# (js/performance.now)
            time# (.toFixed (- end# start#) 2)]
        (println (str "[perf|" ~name "] => " time#))
        res#)))


;; id, // the "id" prop of the Profiler tree that has just committed
;; phase, // either "mount" (if the tree just mounted) or "update" (if it re-rendered)
;; actualDuration, // time spent rendering the committed update
;; baseDuration, // estimated time to render the entire subtree without memoization
;; startTime, // when React began rendering this update
;; commitTime, // when React committed this update
;; interactions // the Set of interactions belonging to this update

#?(:cljs
   (defn react-on-profile
     []
     (let [sum (volatile! 0)
           ctr (volatile! 0)]
       (fn [id phase adur, bdur, st, ct, itx]
         (vswap! sum (fn [prev] (+ prev adur)))
         (vswap! ctr inc)
         (js/console.log (str "[profile:" id ":" phase "]")
                         ""
                         (str "time=" (math/precision adur 4))
                         (str "avg="  (math/precision (/ @sum @ctr) 4)))))))
