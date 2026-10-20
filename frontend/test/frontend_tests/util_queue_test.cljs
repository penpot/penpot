;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-queue-test
  (:require
   [app.util.queue :as q]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]))

(defn- same-key?
  [request item]
  (= (unchecked-get request "key")
     (unchecked-get item "key")))

(defn- new-queue
  []
  ;; A high threshold lets queued items run back to back.
  (q/create same-key? 10000))

(defn- request
  [key tag]
  #js {:key key :tag tag})

(defn- work
  "Returns a render fn that records `id` in `calls` and emits it."
  [calls id]
  (fn []
    (swap! calls conj id)
    (rx/of id)))

(defn- collect
  "Returns a promise resolving with the values `ob` emitted."
  [ob]
  (let [values (atom [])]
    (-> (async/observe ob :on-next #(swap! values conj %) :timeout-ms 5000)
        (.then (fn [_] @values)))))

(defn- pending
  [queue]
  (.-length (unchecked-get queue "items")))

(t/deftest ^:async matching-requests-share-one-run-of-the-latest-work
  ;; Scenario: two requests for the same key are queued before the queue
  ;; runs. Proves: one item is queued, only the latest work runs, and
  ;; both callers receive its result.
  (let [queue (new-queue)
        calls (atom [])
        req-1 (q/enqueue-unique queue (request "a" "component") (work calls :first))
        req-2 (q/enqueue-unique queue (request "a" "component") (work calls :second))]
    (t/is (= 1 (pending queue)))
    (let [results (await (js/Promise.all #js [(collect req-1) (collect req-2)]))]
      (t/is (= [[:second] [:second]] (js->clj results)))
      (t/is (= [:second] @calls)))
    (q/clear! queue)))

(t/deftest ^:async different-requests-run-separately
  ;; Scenario: two requests with different keys. Proves: both are queued
  ;; and each caller receives its own result.
  (let [queue (new-queue)
        calls (atom [])
        a     (q/enqueue-unique queue (request "a" "component") (work calls :a))
        b     (q/enqueue-unique queue (request "b" "component") (work calls :b))]
    (t/is (= 2 (pending queue)))
    (let [results (await (js/Promise.all #js [(collect a) (collect b)]))]
      (t/is (= [[:a] [:b]] (js->clj results)))
      (t/is (= [:a :b] @calls)))
    (q/clear! queue)))

(t/deftest ^:async frame-requests-run-first-and-merge
  ;; Scenario: a component request, then two frame requests for the same
  ;; key. Proves: the frames merge into one item that runs before the
  ;; component, using the latest frame work.
  (let [queue     (new-queue)
        calls     (atom [])
        component (q/enqueue-unique queue (request "a" "component") (work calls :component))
        frame-1   (q/enqueue-unique queue (request "b" "frame") (work calls :frame-1))
        frame-2   (q/enqueue-unique queue (request "b" "frame") (work calls :frame-2))]
    (t/is (= 2 (pending queue)))
    (let [results (await (js/Promise.all #js [(collect component)
                                              (collect frame-1)
                                              (collect frame-2)]))]
      (t/is (= [[:component] [:frame-2] [:frame-2]] (js->clj results)))
      (t/is (= [:frame-2 :component] @calls)))
    (q/clear! queue)))

(t/deftest ^:async request-after-run-starts-is-queued-again
  ;; Scenario: a second matching request arrives once the first one has
  ;; left the queue. Proves: it is queued as new work, not merged into
  ;; work that already ran.
  (let [queue (new-queue)
        calls (atom [])
        req-1 (q/enqueue-unique queue (request "a" "component") (work calls :first))]
    (t/is (= [:first] (await (collect req-1))))
    (let [req-2 (q/enqueue-unique queue (request "a" "component") (work calls :second))]
      (t/is (= 1 (pending queue)))
      (t/is (= [:second] (await (collect req-2))))
      (t/is (= [:first :second] @calls)))
    (q/clear! queue)))
