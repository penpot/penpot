;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-activity-test
  (:require
   [app.util.activity :as activity]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(defn- elapsed
  "Milliseconds until `idle` emits, to `cb`."
  [idle cb]
  (let [start (js/performance.now)]
    (rx/subs! (fn [_] (cb (- (js/performance.now) start))) idle)))

(t/deftest idle-waits-for-a-pause-after-the-last-input
  (t/async
    done
    (let [input (js/performance.now)]
      (elapsed (activity/idle 40 1000 (constantly input))
               (fn [ms]
                 (t/is (>= ms 35))
                 (t/is (< ms 500))
                 (done))))))

(t/deftest idle-ends-after-the-longest-wait
  (t/async
    done
    ;; The user never stops
    (elapsed (activity/idle 1000 60 #(js/performance.now))
             (fn [ms]
               (t/is (>= ms 55))
               (t/is (< ms 500))
               (done)))))

(t/deftest idle-is-at-once-without-input
  (t/async
    done
    (elapsed (activity/idle 1000 5000 (constantly ##-Inf))
             (fn [ms]
               (t/is (< ms 50))
               (done)))))
