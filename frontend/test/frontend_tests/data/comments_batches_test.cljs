;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns frontend-tests.data.comments-batches-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dcmt]
   [app.main.data.notifications :as ntf]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- comment-threads-state
  [n]
  {:comment-threads (into {} (map (fn [_] [(uuid/next) {:id (uuid/next)}]) (range n)))})

(t/deftest mark-all-threads-batches-splits-the-ids
  (t/is (= [] (dcmt/mark-all-threads-batches [])))
  (t/is (= [[1 2 3]] (dcmt/mark-all-threads-batches [1 2 3])))
  (t/is (= [500 1] (mapv count (dcmt/mark-all-threads-batches (range 501))))))

(t/deftest ^:async mark-all-threads-as-read-sends-the-ids-in-batches
  (let [calls (atom [])
        state (comment-threads-state 1200)
        event (dcmt/mark-all-threads-as-read (uuid/next))]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [cmd params]
                   (swap! calls conj [cmd params])
                   (rx/of nil)))}
       (await (async/observe (ptk/watch event state nil)))))

    (t/is (= 3 (count @calls)))
    (t/is (every? #(= :mark-all-threads-as-read (first %)) @calls))
    (t/is (= [500 500 200] (mapv (comp count :threads second) @calls)))))

(t/deftest ^:async mark-all-threads-as-read-sends-a-single-batch
  (let [calls (atom [])
        state (comment-threads-state 10)
        event (dcmt/mark-all-threads-as-read (uuid/next))]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [cmd params]
                   (swap! calls conj [cmd params])
                   (rx/of nil)))}
       (await (async/observe (ptk/watch event state nil)))))

    (t/is (= 1 (count @calls)))
    (t/is (= 10 (count (:threads (second (first @calls))))))))

(t/deftest ^:async mark-all-threads-as-read-refreshes-the-unread-list-after-a-batch-fails
  (let [calls  (atom [])
        state  (comment-threads-state 1200)
        event  (dcmt/mark-all-threads-as-read (uuid/next))
        seen   (atom [])
        errors (atom [])]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [cmd params]
                   (swap! calls conj [cmd params])
                   (if (= 2 (count @calls))
                     (rx/throw (ex-info "boom" {}))
                     (rx/of nil))))}
       (await (async/observe (ptk/watch event state nil)
                             :on-next #(swap! seen conj (ptk/type %))
                             :on-error #(swap! errors conj %)))))

    (t/is (= 2 (count @calls))
          "the pipeline stops at the batch that fails")
    (t/is (= [::dcmt/retrieve-unread-comment-threads] @seen)
          "the unread list is re-read and no success toast is emitted")
    (t/is (= [{:type :comment-error}] @errors))))

(t/deftest ^:async mark-all-threads-as-read-refreshes-and-shows-the-success-toast
  (let [state (comment-threads-state 600)
        event (dcmt/mark-all-threads-as-read (uuid/next))
        seen  (atom [])]

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _] (rx/of nil)))}
       (await (async/observe (ptk/watch event state nil)
                             :on-next #(swap! seen conj (ptk/type %))))))

    (t/is (= [::dcmt/retrieve-unread-comment-threads ::ntf/show] @seen))))
