;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.fonts-sync-test
  "The main thread keeps the worker's copy of the team's custom fonts
  current, for the render there to find them."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [okulary.core :as l]))

(defn- font-variant
  []
  (let [id (uuid/next)]
    {:id id
     :font-id (uuid/next)
     :font-family "Custom"
     :font-weight 400
     :font-style "normal"
     :ttf-file-id (uuid/next)}))

(t/deftest ^:async the-worker-gets-the-fonts-each-time-they-change
  (let [a     (font-variant)
        b     (font-variant)
        store (l/atom {})
        sent  (atom [])]
    (await
     (mock/with-mocks*
       {mw/emit! (mock/stub (fn [message] (swap! sent conj message) (rx/empty)))}
       (add-watch store ::sync @#'df/sync-worker-fonts)
       (swap! store assoc :fonts {(:id a) a})
       (swap! store assoc :selected #{})
       (swap! store update :fonts assoc (:id b) b)))
    (t/is (= [{:cmd :fonts/sync :fonts {(:id a) a}}
              {:cmd :fonts/sync :fonts {(:id a) a (:id b) b}}]
             @sent))))

(t/deftest ^:async the-worker-keeps-the-fonts-it-gets
  (let [a (font-variant)
        b (font-variant)]
    (await
     (mock/with-mocks*
       {st/state (l/atom {:fonts {(:id a) a}})}
       (impl/handler {:cmd :fonts/sync :fonts {(:id b) b}})
       (t/is (= {(:id b) b} (:fonts @st/state)))))))
