;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-tasks-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dcmt]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private board-id (uuid/custom 1 1))
(def ^:private button-id (uuid/custom 1 2))

(defn- objects
  []
  (let [board  (cts/setup-shape {:id board-id :type :frame :name "Checkout"
                                 :x 0 :y 0 :width 400 :height 800
                                 :parent-id uuid/zero :frame-id uuid/zero})
        button (cts/setup-shape {:id button-id :type :rect :name "Pay button"
                                 :x 20 :y 600 :width 300 :height 48
                                 :parent-id board-id :frame-id board-id})
        root   (cts/setup-shape {:id uuid/zero :type :frame :name "Root"
                                 :x 0 :y 0 :width 1 :height 1
                                 :parent-id uuid/zero :frame-id uuid/zero})]
    {uuid/zero (assoc root :shapes [board-id])
     board-id  (assoc board :shapes [button-id])
     button-id button}))

(t/deftest shape-at-position-prefers-the-layer-over-its-board
  (t/is (= button-id (:id (dwtk/shape-at-position (objects) {:x 50 :y 610}))))
  (t/is (= board-id (:id (dwtk/shape-at-position (objects) {:x 50 :y 50}))))
  (t/is (nil? (dwtk/shape-at-position (objects) {:x 900 :y 900}))))

(t/deftest anchor-position-falls-back-to-the-board-of-a-lost-layer
  (let [objs (objects)]
    (t/is (= (gpt/point 320 600)
             (dwtk/anchor-position objs {:shape-id button-id :frame-id board-id})))
    (t/is (= (gpt/point 400 0)
             (dwtk/anchor-position (dissoc objs button-id)
                                   {:shape-id button-id :frame-id board-id})))
    (t/is (nil? (dwtk/anchor-position objs {:shape-id nil :frame-id uuid/zero})))))

(t/deftest task-predicates
  (t/is (dwtk/to-do? {:status "reopened"}))
  (t/is (dwtk/to-verify? {:status "done"}))
  (t/is (dwtk/closed? {:status "wont-do"}))
  (t/is (dwtk/history? {:status "verified" :merge-id (uuid/next)}))
  (t/is (not (dwtk/history? {:status "open" :merge-id (uuid/next)}))))

(t/deftest tasks-for-counts-the-open-work-of-a-person
  (let [me (uuid/next)]
    (t/is (= 1 (count (dwtk/tasks-for [{:status "open" :assignee-id me}
                                       {:status "done" :assignee-id me}
                                       {:status "open" :assignee-id (uuid/next)}]
                                      me))))))

(t/deftest sort-tasks-puts-open-work-first
  (t/is (= ["open" "done" "verified"]
           (mapv :status (dwtk/sort-tasks [{:status "verified" :created-at 1}
                                           {:status "done" :created-at 2}
                                           {:status "open" :created-at 3}])))))

;; --- Creating a task

(def ^:private file-id (uuid/custom 2 1))
(def ^:private page-id (uuid/custom 2 2))
(def ^:private thread-id (uuid/custom 2 3))

(defn- workspace-state
  []
  {:current-file-id file-id
   :current-page-id page-id
   :files {file-id {:data {:pages-index {page-id {:objects (objects)}}}}}})

(defn- fake-server
  "The backend of the tasks: the creation answers with the task in the
  thread `thread-id`, the read of a thread answers with it. Every answer
  arrives later, as from the network."
  [calls]
  (mock/stub
   (fn [id params]
     (swap! calls conj {:cmd id :params params})
     (->> (case id
            :create-task        (rx/of (assoc params :id (uuid/next) :thread-id thread-id))
            :get-comment-thread (rx/of {:id thread-id :page-id page-id :position (:position params)})
            :get-file-tasks     (rx/of [])
            (rx/of nil))
          (rx/observe-on :async)))))

(defn- ^:async created-events
  "Run the creation of a task with `params` and collect what it emits."
  [params]
  (let [calls (atom [])
        seen  (atom [])]
    (await (mock/with-mocks*
             {rp/cmd! (fake-server calls)}
             (await (hva/observe (ptk/watch (dwtk/create-task params) (workspace-state) (rx/empty))
                                 {:on-next #(swap! seen conj (ptk/type %))}))))
    {:calls @calls :seen @seen}))

(t/deftest ^:async task-on-a-selected-layer-gets-a-thread-of-its-own
  (let [{:keys [calls seen]} (await (created-events {:title "Use the secondary token"
                                                     :page-id page-id
                                                     :frame-id board-id
                                                     :shape-id button-id}))
        create (some #(when (= :create-task (:cmd %)) (:params %)) calls)]

    (t/testing "its thread sits where its mark goes: the top right of the layer"
      (t/is (= {:x 320 :y 600} (into {} (:position create))))
      (t/is (nil? (:thread-id create))))

    (t/testing "the new thread is kept before the task opens as it"
      (t/is (some #(= {:cmd :get-comment-thread
                       :params {:file-id file-id :id thread-id}} %)
                  calls))
      (let [kept   (.indexOf seen ::dcmt/created-thread-on-workspace)
            opened (.indexOf seen ::dwtk/open-task)]
        (t/is (<= 0 kept))
        (t/is (< kept opened))))))

(t/deftest ^:async task-made-from-a-message-keeps-its-thread
  (let [{:keys [calls seen]} (await (created-events {:title "fix the radius"
                                                     :page-id page-id
                                                     :frame-id board-id
                                                     :shape-id board-id
                                                     :position {:x 10 :y 10}
                                                     :thread-id thread-id
                                                     :comment-id (uuid/next)}))]
    (t/is (not-any? #(= :get-comment-thread (:cmd %)) calls))
    (t/is (not-any? #{::dcmt/created-thread-on-workspace} seen))
    (t/is (some #{::dwtk/open-task} seen))))
