;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-thumbnails-wasm-test
  "The main thread side of the component thumbnails the worker renders
  and uploads: what it sends the worker, and how it shows the renders
  that come back."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.thumbnails-wasm :as dwt.wasm]
   [app.main.worker :as mw]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/deftest ^:async the-worker-gets-the-shapes-of-the-main
  (cthi/reset-idmap!)
  (let [file    (-> (cthf/sample-file :file)
                    (ctho/add-frame-with-child :root :child)
                    (ctho/add-frame :other))
        state   @(ths/setup-store file)
        page-id (cthf/current-page-id file)
        sent    (atom [])]
    (await
     (mock/with-mocks*
       {mw/emit! (mock/stub (fn [message] (swap! sent conj message) (rx/empty)))}
       (ptk/effect (dwt.wasm/render-thumbnail (:id file) page-id (cthi/id :root) :persist? true)
                   state nil)))
    (t/is (= [{:cmd :thumbnails/render-component
               :file-id (:id file)
               :page-id page-id
               :root-id (cthi/id :root)
               :persist? true}]
             (mapv #(dissoc % :objects) @sent)))
    (t/is (= #{(cthi/id :root) (cthi/id :child)}
             (set (keys (:objects (first @sent))))))))

(t/deftest ^:async a-missing-main-sends-nothing
  (cthi/reset-idmap!)
  (let [file  (cthf/sample-file :file)
        state @(ths/setup-store file)
        sent  (atom [])]
    (await
     (mock/with-mocks*
       {mw/emit! (mock/stub (fn [message] (swap! sent conj message) (rx/empty)))}
       (ptk/effect (dwt.wasm/render-thumbnail (:id file) (cthf/current-page-id file) nil)
                   state nil)))
    (t/is (empty? @sent))))

(t/deftest ^:async worker-renders-are-shown
  (let [renders (rx/subject)
        events  (atom [])]
    (await
     (mock/with-mocks*
       {mw/ask-many!    (mock/stub (fn [_] (rx/observe-on :async renders)))
        wapi/create-uri (mock/stub (fn [_] "blob:test"))}
       (let [done (async/observe (dwt.wasm/component-thumbnail-events)
                                 :on-next #(swap! events conj %))]
         (rx/push! renders {:object-id "a" :blob (wapi/create-blob "a" "image/png")})
         (rx/push! renders {:object-id "b" :blob (wapi/create-blob "b" "image/png")})
         (await (async/wait-for #(= 2 (count @events)) "both renders shown"))
         (t/is (every? (ptk/type? ::dwt/assoc-thumbnail) @events))
         (rx/end! renders)
         (await done))))))
