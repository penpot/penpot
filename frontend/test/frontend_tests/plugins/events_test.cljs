;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.events-test
  "Unit tests for app.plugins.events, the plugin state-change listeners."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.persistence :as dps]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private plugin-id (str (uuid/next)))

;; A listener type that always fails. The dispatch value is namespaced so
;; product code never reaches it.
(defmethod events/handle-state-change "test/always-throws"
  [_ _ _ _ _]
  (throw (js/Error. "listener boom")))

(defn- touch-state!
  "Applies a throwaway state change to fire the store watches."
  []
  (swap! st/state assoc ::probe (uuid/next)))

(t/deftest shapechange-listener-with-blank-shape-id-does-not-raise
  ;; The listener watches the store, so it runs on every state change. A
  ;; raise there reaches the global error handler, whose error toast
  ;; changes the state again and re-enters the listener.
  (let [key (events/add-listener "shapechange" plugin-id (fn [_]) #js {"shapeId" ""})]
    (try
      (t/is (some? (touch-state!)))
      (finally
        (events/remove-listener key)
        (swap! st/state dissoc ::probe)))))

(t/deftest malformed-shape-id-is-rejected-at-registration
  (t/testing "the plugin sees the failure when it registers the listener"
    (t/is (thrown? js/Error
                   (events/add-listener "shapechange" plugin-id (fn [_]) #js {"shapeId" "not-a-uuid"}))))

  (t/testing "no watch survives a rejected registration"
    (let [seen (atom 0)]
      (add-watch st/state ::probe-watch (fn [_ _ _ _] (swap! seen inc)))
      (try
        (touch-state!)
        (t/is (= 1 @seen))
        (finally
          (remove-watch st/state ::probe-watch)
          (swap! st/state dissoc ::probe))))))

(t/deftest failing-listener-does-not-starve-other-watches
  ;; Watches are notified by a plain iteration: a listener that raises
  ;; aborts the loop, and the watches registered after it miss the change.
  (let [seen (atom 0)
        key  (events/add-listener "test/always-throws" plugin-id (fn [_]) nil)]
    (add-watch st/state ::probe-watch (fn [_ _ _ _] (swap! seen inc)))
    (try
      (t/is (some? (touch-state!)))
      (t/is (= 1 @seen))
      (finally
        (remove-watch st/state ::probe-watch)
        (events/remove-listener key)
        (swap! st/state dissoc ::probe)))))

(t/deftest contentrollback-listener-gets-every-rollback
  (t/async done
    (let [[file-id page-id shape-id] (repeatedly 3 uuid/next)
          seen (atom [])
          key  (events/add-listener "contentrollback" plugin-id #(swap! seen conj (js->clj %)) nil)]
      (st/emit! (ptk/data-event ::dps/rolled-back {:file-ids #{file-id} :page-ids #{page-id} :shape-ids #{shape-id}})
                (ptk/data-event ::dps/rolled-back {:file-ids #{file-id} :page-ids #{} :shape-ids #{}}))
      (js/setTimeout
       (fn []
         (events/remove-listener key)
         (t/is (= [{"fileIds" [(str file-id)] "pageIds" [(str page-id)] "shapeIds" [(str shape-id)]}
                   {"fileIds" [(str file-id)] "pageIds" [] "shapeIds" []}]
                  @seen))
         (st/emit! (ptk/data-event ::dps/rolled-back {:file-ids #{file-id}}))
         (js/setTimeout
          (fn []
            (t/is (= 2 (count @seen)) "a removed listener gets nothing")
            (done))
          20))
       20))))
