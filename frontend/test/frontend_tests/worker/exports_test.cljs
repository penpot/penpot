;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.exports-test
  (:require
   [app.common.uuid :as uuid]
   [app.worker.wasm :as wwasm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]))

;; Record order inside `work`: `submit` starts before any subscriber, so
;; tapping the returned bus can miss values. Plain Promise (not promesa):
;; `await` + promesa crashed the full suite in Node.
(t/deftest ^:async submit-runs-jobs-serially
  (let [order (atom [])]
    (await
     (js/Promise.
      (fn [resolve reject]
        (let [timer (js/setTimeout
                     #(reject (js/Error. "submit queue timed out"))
                     2000)]
          (wwasm/submit
           (fn []
             (swap! order conj :a-start)
             (rx/concat
              (rx/of :a-start)
              (->> (rx/of :a-end)
                   (rx/delay 20)
                   (rx/tap #(swap! order conj :a-end))))))
          (wwasm/submit
           (fn []
             (swap! order conj
                    {:b-sees-a-start (boolean (some #{:a-start} @order))
                     :b-sees-a-end (boolean (some #{:a-end} @order))})
             (swap! order conj :b-end)
             (->> (rx/of :b-end)
                  (rx/finalize
                   (fn []
                     (js/clearTimeout timer)
                     (resolve nil))))))))))
    (t/is (= [:a-start :a-end
              {:b-sees-a-start true :b-sees-a-end true}
              :b-end]
             @order)
          "job B starts only after job A has finished")))

(t/deftest cancel-job-is-visible-to-cancelled
  (let [job-id (uuid/next)]
    (t/is (false? (wwasm/cancelled? job-id)))
    (wwasm/cancel-job job-id)
    (t/is (true? (wwasm/cancelled? job-id)))
    (wwasm/clear-cancel job-id)
    (t/is (false? (wwasm/cancelled? job-id)))))

;; After a macrotask yield (as between PDF pages), `cancelled?` still holds.
(t/deftest ^:async cancel-is-honored-after-async-yield
  (let [job-id (uuid/next)
        steps  (atom [])]
    (swap! steps conj :page-1)
    (wwasm/cancel-job job-id)
    (await (async/settle))
    (if (wwasm/cancelled? job-id)
      (swap! steps conj :stopped)
      (swap! steps conj :page-2))
    (t/is (= [:page-1 :stopped] @steps))
    (wwasm/clear-cancel job-id)))
