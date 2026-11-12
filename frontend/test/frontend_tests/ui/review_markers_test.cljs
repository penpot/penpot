;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.review-markers-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.ui.workspace.review.markers :as rvm]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private shape-a (uuid/next))
(def ^:private shape-b (uuid/next))

(def ^:private objects
  {shape-a {:id shape-a :selrect {:x 0 :y 0 :width 100 :height 100}}
   shape-b {:id shape-b :selrect {:x 0 :y 0 :width 120 :height 100}}})

(defn- task
  "A task on `shape-id`, in a thread of its own unless `attrs` names one:
  every task lives in a thread."
  [order shape-id & {:as attrs}]
  (merge {:id (uuid/next)
          :thread-id (uuid/next)
          :shape-id shape-id
          :created-at order}
         attrs))

(defn- thread
  [order position & {:as attrs}]
  (merge {:id (uuid/next)
          :anchor-type :canvas
          :position position
          :created-at order}
         attrs))

(defn- cluster-sizes
  "How many marks each cluster has and how many items each mark holds."
  [clusters]
  (->> clusters
       (map (fn [marks]
              (sort (map #(if (:thread %) 1 (count (:tasks %))) marks))))
       (sort-by count)))

(t/deftest tasks-of-a-layer-share-a-cluster
  (let [tasks [(task 1 shape-a) (task 2 shape-a) (task 3 shape-a)]]
    (t/is (= [[1 1 1]]
             (cluster-sizes (rvm/mark-clusters objects 1 [] tasks))))))

(t/deftest tasks-of-a-layer-stay-together-at-any-zoom
  (let [tasks [(task 1 shape-a) (task 2 shape-a)]]
    (t/is (= [[1 1]]
             (cluster-sizes (rvm/mark-clusters objects 64 [] tasks))))))

(t/deftest tasks-of-a-thread-make-one-mark
  (let [thread-id (uuid/next)
        tasks     [(task 1 shape-a :thread-id thread-id)
                   (task 2 shape-a :thread-id thread-id)]]
    (t/is (= [[2]]
             (cluster-sizes (rvm/mark-clusters objects 1 [] tasks))))))

(t/deftest tasks-of-a-thread-on-other-layers-make-a-mark-each
  (let [thread-id (uuid/next)
        tasks     [(task 1 shape-a :thread-id thread-id)
                   (task 2 shape-b :thread-id thread-id)]]
    (t/is (= [[1 1]]
             (cluster-sizes (rvm/mark-clusters objects 1 [] tasks))))))

(t/deftest marks-of-close-layers-group-by-zoom
  (let [tasks [(task 1 shape-a) (task 2 shape-b)]]
    (t/testing "their marks overlap on screen"
      (t/is (= [[1 1]]
               (cluster-sizes (rvm/mark-clusters objects 1 [] tasks)))))
    (t/testing "zooming in separates them"
      (t/is (= [[1] [1]]
               (cluster-sizes (rvm/mark-clusters objects 4 [] tasks)))))))

(t/deftest threads-of-a-layer-share-a-cluster
  (let [threads [(thread 1 {:x 0 :y 0} :anchor-type :layer :shape-id shape-a)
                 (thread 2 {:x 50 :y 50} :anchor-type :layer :shape-id shape-a)]]
    (t/is (= [[1 1]]
             (cluster-sizes (rvm/mark-clusters objects 64 threads []))))))

(t/deftest a-thread-and-a-task-of-a-layer-share-a-cluster
  (let [threads  [(thread 2 {:x 0 :y 0} :anchor-type :layer :shape-id shape-a)]
        clusters (rvm/mark-clusters objects 1 threads [(task 1 shape-a)])]
    (t/is (= [[1 1]] (cluster-sizes clusters)))
    (t/testing "oldest first, whatever their kind"
      (t/is (= [false true]
               (->> (first clusters)
                    (sort-by :seqn)
                    (mapv #(some? (:thread %)))))))))

(t/deftest threads-apart-keep-their-marks
  (let [threads [(thread 1 {:x 0 :y 0}) (thread 2 {:x 500 :y 500})]]
    (t/is (= [[1] [1]]
             (cluster-sizes (rvm/mark-clusters objects 1 threads []))))))

(t/deftest marks-keep-the-order-of-their-tasks
  (let [first-task  (task 1 shape-a)
        second-task (task 2 shape-a)
        clusters    (rvm/mark-clusters objects 1 [] [second-task first-task])
        marks       (sort-by :seqn (first clusters))]
    (t/is (= [(:id first-task) (:id second-task)]
             (mapv :id marks)))))

(t/deftest a-task-without-a-place-has-no-mark
  (t/is (empty? (rvm/mark-clusters objects 1 [] [(task 1 (uuid/next))]))))

(t/deftest marks-name-the-layer-to-outline
  (let [threads [(thread 1 {:x 900 :y 900})
                 (thread 2 {:x 0 :y 0} :anchor-type :layer :shape-id shape-b)]
        marks   (->> (rvm/mark-clusters objects 1 threads [(task 3 shape-a)])
                     (apply concat)
                     (sort-by :seqn))]
    (t/testing "a thread on the canvas names none"
      (t/is (nil? (:shape-ids (first marks)))))
    (t/testing "a thread on a layer names it"
      (t/is (= [shape-b] (:shape-ids (second marks)))))
    (t/testing "a task names its layer"
      (t/is (= [shape-a] (:shape-ids (last marks)))))))

(t/deftest a-task-of-a-deleted-layer-names-its-board
  (let [board-id (uuid/next)
        objects  (assoc objects board-id {:id board-id
                                          :selrect {:x 0 :y 0 :width 900 :height 900}})
        task     (task 1 (uuid/next) :frame-id board-id)
        mark     (ffirst (rvm/mark-clusters objects 1 [] [task]))]
    (t/is (= [board-id] (:shape-ids mark)))))

(t/deftest a-thread-on-a-layer-opens-by-the-layer-as-a-point
  ;; the open thread takes this position, and answering it checks that the
  ;; thread is valid: a plain map is not a point
  (let [position (rvm/thread-position objects (thread 1 (gpt/point 0 0)
                                                      :anchor-type :layer
                                                      :shape-id shape-a))]
    (t/is (gpt/point? position))
    (t/is (= (gpt/point 100 0) position))))

(t/deftest opening-a-task-collapses-the-fan
  (let [state  {:comments-local {:expanded #{(uuid/next)}}}
        result (ptk/update (dwtk/open-task (uuid/next)) state)]
    (t/is (nil? (get-in result [:comments-local :expanded])))))

(t/deftest changing-the-assignee-shows-right-away
  (let [task-id  (uuid/next)
        assignee (uuid/next)
        state    {:workspace-tasks {:data {task-id {:id task-id :assignee-id assignee}}}}]
    (t/testing "to somebody else"
      (let [other  (uuid/next)
            result (ptk/update (dwtk/update-task task-id {:assignee-id other}) state)]
        (t/is (= other (get-in result [:workspace-tasks :data task-id :assignee-id])))))
    (t/testing "to nobody"
      (let [result (ptk/update (dwtk/update-task task-id {:assignee-id nil}) state)]
        (t/is (nil? (get-in result [:workspace-tasks :data task-id :assignee-id])))))
    (t/testing "a rename keeps it"
      (let [result (ptk/update (dwtk/update-task task-id {:title "Other"}) state)]
        (t/is (= assignee (get-in result [:workspace-tasks :data task-id :assignee-id])))))))
