;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.page-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.util.object :as obj]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(defn- setup
  "Creates a file with two pages (page1 as current) and a plugin context."
  []
  (let [file    (-> (cthf/sample-file :file1 :page-label :page1)
                    (cthf/add-sample-page :page2)
                    (cthf/switch-to-page :page1))
        store   (ths/setup-store file)
        _       (set! st/state store)
        _       (set! st/stream (ptk/input-stream store))
        context (api/create-context "00000000-0000-0000-0000-000000000000")]
    {:file file :store store :context context}))

(defn- mock-page-initialized
  "Simulates the two effects of initialize-page* without routing:
  updates current-page-id in state, then emits the public :page-initialized event."
  [store page-id]
  (ptk/emit! store #(assoc % :current-page-id page-id))
  (ptk/emit! store (ptk/data-event :page-initialized page-id)))

(t/deftest test-open-page-returns-promise
  (let [{:keys [context]} (setup)
        ^js pages         (.. context -currentFile -pages)
        ^js page2         (aget pages 1)]
    (t/is (instance? js/Promise (.openPage context page2)))))

(t/deftest test-open-page-new-window-returns-promise
  (let [{:keys [context]} (setup)
        ^js pages         (.. context -currentFile -pages)
        ^js page2         (aget pages 1)]
    (t/is (instance? js/Promise (.openPage context page2 true)))))

(t/deftest test-open-page-invalid-arg-returns-nil
  (let [{:keys [context]} (setup)]
    (t/is (nil? (.openPage context "not-a-page")))))

(t/deftest test-open-page-resolves-when-page-changes
  (t/async done
    (let [{:keys [store context]} (setup)
          ^js pages               (.. context -currentFile -pages)
          ^js page2               (aget pages 1)
          page2-id                (obj/get page2 "$id")]

      (-> (.openPage context page2)
          (.then (fn [_]
                   (t/is (= (:current-page-id @store) page2-id))
                   (done))))

      (mock-page-initialized store page2-id))))

(t/deftest test-open-page-does-not-resolve-for-wrong-page
  ;; Promise should not resolve when a different page is initialized
  (t/async done
    (let [{:keys [store context]} (setup)
          ^js pages               (.. context -currentFile -pages)
          ^js page1               (aget pages 0)
          ^js page2               (aget pages 1)
          page1-id                (obj/get page1 "$id")
          page2-id                (obj/get page2 "$id")
          resolved?               (atom false)]

      (-> (.openPage context page2)
          (.then (fn [_] (reset! resolved? true))))

      ;; Initialize page1 (wrong page) — promise should not resolve
      (mock-page-initialized store page1-id)

      ;; Give microtasks a chance to run, then verify promise is still pending
      (js/setTimeout
       (fn []
         (t/is (not @resolved?))
         ;; Now initialize the correct page and confirm it resolves
         (-> (.openPage context page2)
             (.then (fn [_]
                      (t/is (= (:current-page-id @store) page2-id))
                      (done))))
         (mock-page-initialized store page2-id))
       0))))
