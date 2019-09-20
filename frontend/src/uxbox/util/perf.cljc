;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.perf
  "Performance and debugging tools."
  #?(:cljs (:require-macros [uxbox.util.perf])))

#?(:clj
   (defmacro with-measure
     [name & body]
     `(let [start# (js/performance.now)
            res# (do ~@body)
            end# (js/performance.now)
            time# (.toFixed (- end# start#) 2)]
        (println (str "[perf|" ~name "] => " time#))
        res#)))
