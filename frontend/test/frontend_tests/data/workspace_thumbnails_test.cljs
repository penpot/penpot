;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-thumbnails-test
  (:require
   [app.common.thumbnails :as thc]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.thumbnails :as thumbnails]
   [app.main.repo :as rp]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

;; The qualified keyword used internally by app.main.data.workspace.thumbnails
;; for tracking the pending deletion queue in application state.
(def ^:private deletion-queue-key
  :app.main.data.workspace.thumbnails/thumbnails-deletion-queue)

(t/deftest extract-frame-changes-handles-cyclic-frame-links
  (let [page-id    (uuid/next)
        root-id    (uuid/next)
        shape-a-id (uuid/next)
        shape-b-id (uuid/next)
        event      {:changes [{:type :mod-obj
                               :page-id page-id
                               :id shape-a-id}]}
        old-data   {:pages-index
                    {page-id
                     {:objects
                      {root-id    {:id root-id :type :frame :frame-id uuid/zero}
                       shape-a-id {:id shape-a-id :type :rect :frame-id shape-b-id}
                       shape-b-id {:id shape-b-id :type :group :frame-id shape-a-id}}}}}
        new-data   {:pages-index
                    {page-id
                     {:objects
                      {root-id    {:id root-id :type :frame :frame-id uuid/zero}
                       shape-a-id {:id shape-a-id :type :rect :frame-id root-id}
                       shape-b-id {:id shape-b-id :type :group :frame-id shape-a-id
                                   :component-root true}}}}}]
    (t/is (= #{["frame" root-id]
               ["component" shape-b-id]}
             (#'thumbnails/extract-frame-changes page-id [event [old-data new-data]])))))

;; --- Batch deletion queue state management ---

(t/deftest clear-thumbnail-adds-to-deletion-queue
  (let [file-id   (uuid/next)
        object-id (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        uri       "blob:http://localhost/test-thumb"
        event     (thumbnails/clear-thumbnail file-id object-id)
        state     {:thumbnails {object-id {:uri uri :rendered-at nil}}}
        result    (ptk/update event state)]
    ;; Thumbnail removed from the map
    (t/is (nil? (get-in result [:thumbnails object-id])))
    ;; Object-id added to the deletion queue with its URI
    (t/is (= uri (get-in result [deletion-queue-key object-id])))))

(t/deftest clear-thumbnail-keeps-other-thumbnails
  (let [file-id    (uuid/next)
        object-id1 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        object-id2 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        uri1       "blob:http://localhost/thumb-1"
        uri2       "blob:http://localhost/thumb-2"
        event      (thumbnails/clear-thumbnail file-id object-id1)
        state      {:thumbnails {object-id1 {:uri uri1 :rendered-at nil}
                                 object-id2 {:uri uri2 :rendered-at nil}}}
        result     (ptk/update event state)]
    ;; Only the cleared thumbnail is removed
    (t/is (nil? (get-in result [:thumbnails object-id1])))
    (t/is (= uri2 (get-in result [:thumbnails object-id2 :uri])))
    ;; Only the cleared thumbnail is queued
    (t/is (= uri1 (get-in result [deletion-queue-key object-id1])))
    (t/is (nil? (get-in result [deletion-queue-key object-id2])))))

(t/deftest clear-thumbnail-accumulates-in-queue
  (let [file-id    (uuid/next)
        object-id1 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        object-id2 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        uri1       "blob:http://localhost/thumb-1"
        uri2       "blob:http://localhost/thumb-2"
        event1     (thumbnails/clear-thumbnail file-id object-id1)
        event2     (thumbnails/clear-thumbnail file-id object-id2)
        state      {:thumbnails {object-id1 {:uri uri1 :rendered-at nil}
                                 object-id2 {:uri uri2 :rendered-at nil}}}
        state1     (ptk/update event1 state)
        state2     (ptk/update event2 state1)]
    ;; Both removed from thumbnails
    (t/is (nil? (get-in state2 [:thumbnails object-id1])))
    (t/is (nil? (get-in state2 [:thumbnails object-id2])))
    ;; Both accumulated in the queue
    (t/is (= uri1 (get-in state2 [deletion-queue-key object-id1])))
    (t/is (= uri2 (get-in state2 [deletion-queue-key object-id2])))
    (t/is (= 2 (count (get state2 deletion-queue-key))))))

(t/deftest remove-from-deletion-queue-removes-entry
  (let [file-id   (uuid/next)
        object-id (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        event     (thumbnails/remove-from-deletion-queue object-id)
        state     {deletion-queue-key {object-id "blob:http://localhost/thumb"}}
        result    (ptk/update event state)]
    (t/is (nil? (get-in result [deletion-queue-key object-id])))
    (t/is (empty? (get result deletion-queue-key)))))

(t/deftest remove-from-deletion-queue-keeps-other-entries
  (let [file-id    (uuid/next)
        object-id1 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        object-id2 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        uri1       "blob:http://localhost/thumb-1"
        uri2       "blob:http://localhost/thumb-2"
        event      (thumbnails/remove-from-deletion-queue object-id1)
        state      {deletion-queue-key {object-id1 uri1
                                        object-id2 uri2}}
        result     (ptk/update event state)]
    ;; Only the specified entry is removed
    (t/is (nil? (get-in result [deletion-queue-key object-id1])))
    (t/is (= uri2 (get-in result [deletion-queue-key object-id2])))
    (t/is (= 1 (count (get result deletion-queue-key))))))

(t/deftest remove-before-clear-cancels-pending-delete
  (let [file-id   (uuid/next)
        object-id (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        uri       "blob:http://localhost/thumb"
        ;; Step 1: clear-thumbnail queues the delete
        state1    (ptk/update (thumbnails/clear-thumbnail file-id object-id)
                              {:thumbnails {object-id {:uri uri :rendered-at nil}}})
        ;; Step 2: remove-from-deletion-queue cancels the pending delete
        state2    (ptk/update (thumbnails/remove-from-deletion-queue object-id)
                              state1)]
    ;; Thumbnail was removed from :thumbnails map by clear-thumbnail
    (t/is (nil? (get-in state2 [:thumbnails object-id])))
    ;; But the deletion queue entry was cancelled by remove-from-deletion-queue
    (t/is (nil? (get-in state2 [deletion-queue-key object-id])))
    (t/is (empty? (get state2 deletion-queue-key)))))

(t/deftest clear-thumbnail-batch-drains-queue
  (let [file-id    (uuid/next)
        object-id1 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        object-id2 (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
        uri1       "blob:http://localhost/thumb-1"
        uri2       "blob:http://localhost/thumb-2"
        ;; Build up the queue state manually (simulating accumulated clear-thumbnails)
        state      {deletion-queue-key {object-id1 uri1 object-id2 uri2}}
        event      (#'thumbnails/clear-thumbnail-batch)
        result     (ptk/update event state)]
    ;; The queue is drained from application state
    (t/is (empty? (get result deletion-queue-key)))))

(t/deftest clear-thumbnail-batch-empty-queue-noop
  (let [state  {deletion-queue-key {}}
        event  (#'thumbnails/clear-thumbnail-batch)
        result (ptk/update event state)]
    ;; Queue key removed from state; rest of state unchanged
    (t/is (empty? (get result deletion-queue-key)))
    (t/is (= (dissoc state deletion-queue-key) (dissoc result deletion-queue-key)))))

(t/deftest assoc-thumbnail-adds-to-map
  (let [object-id (thc/fmt-object-id (uuid/next) (uuid/next) (uuid/next) "frame")
        uri       "blob:http://localhost/new-thumb"
        event     (#'thumbnails/assoc-thumbnail object-id uri)
        state     {:thumbnails {}}
        result    (ptk/update event state)]
    (t/is (= uri (get-in result [:thumbnails object-id :uri])))
    (t/is (some? (get-in result [:thumbnails object-id :rendered-at])))))

(t/deftest duplicate-thumbnail-copies-entry
  (let [old-id  (thc/fmt-object-id (uuid/next) (uuid/next) (uuid/next) "frame")
        new-id  (thc/fmt-object-id (uuid/next) (uuid/next) (uuid/next) "frame")
        uri     "blob:http://localhost/dup-thumb"
        entry   {:uri uri :rendered-at nil}
        event   (thumbnails/duplicate-thumbnail old-id new-id)
        state   {:thumbnails {old-id entry}}
        result  (ptk/update event state)]
    (t/is (= entry (get-in result [:thumbnails old-id])))
    (t/is (= entry (get-in result [:thumbnails new-id])))))

;; --- Async WatchEvent tests ---

(defn- make-obj-ids
  "Helper to create n properly-formatted object-ids for a single file."
  [file-id n]
  (vec (repeatedly n #(thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame"))))

(t/deftest clear-thumbnail-batch-watch-calls-rpc-with-object-ids
  (t/async
    done
    (let [file-id (uuid/next)
          oids    (make-obj-ids file-id 3)
          state   {deletion-queue-key (zipmap oids (repeat "blob:http://test"))}
          event   (#'thumbnails/clear-thumbnail-batch)]
      (ptk/update event state)
      (mock/with-mocks
        {rp/cmd!             mock/rpc-cmd-mock
         tm/schedule-on-idle mock/schedule-on-idle-mock}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [_] nil)
                (fn [err] (t/is (nil? err)) (done'))
                (fn [_]
                  (t/is (= 1 (count @mock/rpc-calls)))
                  (let [[{:keys [cmd params]}] @mock/rpc-calls]
                    (t/is (= :delete-file-object-thumbnails cmd))
                    (t/is (= (vec oids) (:object-ids params))))
                  (done')))))
        done))))

(t/deftest clear-thumbnail-batch-watch-partitions-large-batch
  (t/async
    done
    (let [file-id (uuid/next)
          oids    (make-obj-ids file-id 250)
          state   {deletion-queue-key (zipmap oids (repeat "blob:http://test"))}
          event   (#'thumbnails/clear-thumbnail-batch)]
      (ptk/update event state)
      (mock/with-mocks
        {rp/cmd!             mock/rpc-cmd-mock
         tm/schedule-on-idle mock/schedule-on-idle-mock}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [_] nil)
                (fn [err] (t/is (nil? err)) (done'))
                (fn [_]
                  (t/is (= 2 (count @mock/rpc-calls)))
                  (let [[c1 c2] @mock/rpc-calls]
                    (t/is (= :delete-file-object-thumbnails (:cmd c1)))
                    (t/is (= :delete-file-object-thumbnails (:cmd c2)))
                    (t/is (= 200 (count (:object-ids (:params c1)))))
                    (t/is (= 50 (count (:object-ids (:params c2)))))
                    (t/is (= (set oids)
                             (set (concat (:object-ids (:params c1))
                                          (:object-ids (:params c2)))))))
                  (done')))))
        done))))

(t/deftest clear-thumbnail-batch-watch-revokes-blob-uris
  (t/async
    done
    (let [file-id (uuid/next)
          oids    (make-obj-ids file-id 2)
          uris    ["blob:http://localhost/thumb-1"
                   "blob:http://localhost/thumb-2"]
          state   {deletion-queue-key (zipmap oids uris)}
          event   (#'thumbnails/clear-thumbnail-batch)]
      (ptk/update event state)
      (mock/with-mocks
        {rp/cmd!             mock/rpc-cmd-mock
         wapi/revoke-uri     mock/revoke-uri-mock
         tm/schedule-on-idle mock/schedule-on-idle-mock}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [_] nil)
                (fn [err] (t/is (nil? err)) (done'))
                (fn [_]
                  (t/is (= (set uris) (set @mock/revoked-uris)))
                  (done')))))
        done))))

(t/deftest clear-thumbnail-batch-watch-empty-queue-no-rpc
  (t/async
    done
    (let [event (#'thumbnails/clear-thumbnail-batch)
          state {}]
      (ptk/update event state)
      (mock/with-mocks
        {rp/cmd!             mock/rpc-cmd-mock
         tm/schedule-on-idle mock/schedule-on-idle-mock}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [_] nil)
                (fn [err] (t/is (nil? err)) (done'))
                (fn [_]
                  (t/is (empty? @mock/rpc-calls))
                  (done')))))
        done))))

(t/deftest clear-thumbnail-watch-emits-batch-after-debounce
  (t/async
    done
    (let [file-id   (uuid/next)
          object-id (thc/fmt-object-id file-id (uuid/next) (uuid/next) "frame")
          uri       "blob:http://localhost/thumb"
          state     {:thumbnails {object-id {:uri uri :rendered-at nil}}}
          event     (thumbnails/clear-thumbnail file-id object-id)]
      (ptk/update event state)
      (mock/with-mocks
        {rx/timer mock/timer-mock}
        (fn [done']
          (let [stream (rx/subject)]
            (->> (ptk/watch event state stream)
                 (rx/reduce conj [])
                 (rx/subs!
                  (fn [events]
                    (t/is (= 1 (count events)))
                    (t/is (ptk/event? (first events)))
                    (done'))
                  (fn [err] (t/is (nil? err)) (done'))
                  nil))))
        done))))
