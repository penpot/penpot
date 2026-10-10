;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-libraries-test
  "The library events that act on several shapes or components at once, each
   as one undo entry, and the branches of the single-shape events that the
   composable cases do not reach: creating or placing a component with an
   id ref, a blank rename and updating a main that lives in a linked
   library."
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(t/use-fixtures :each ftm/fixture)

(def ^:private main-color "#111111")
(def ^:private override "#222222")

(defn- two-components
  "Components :c1 and :c2, two copies of :c1 (:copy-a, :copy-b) and two plain
   rects."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-simple-component :c1 :main1 :main1-child
                                 :child-params {:fills (cths/sample-fills-color :fill-color main-color)})
      (ctho/add-simple-component :c2 :main2 :main2-child)
      (cthc/instantiate-component :c1 :copy-a :children-labels [:copy-a-child])
      (cthc/instantiate-component :c1 :copy-b :children-labels [:copy-b-child])
      (ctho/add-rect :rect-a)
      (ctho/add-rect :rect-b)))

(defn- install-file!
  [file]
  (ftm/install! (tm/make-situation file)))

(defn- shape
  [label]
  (cths/get-shape (ftm/current-file) label))

(defn- shape-by-id
  [id]
  (cths/get-shape-by-id (ftm/current-file) id))

(defn- data
  []
  (:data (ftm/current-file)))

(defn- undo-entries
  []
  (count (get-in @st/state [:workspace-undo :items])))

(defn- step
  "Run `events` and wait until their work drains; fails the test when it
   does not."
  [& events]
  (.then (ftm/await-step (vec events))
         (fn [result]
           (t/is (= :settled result) "the step settled")
           result)))

(defn- select
  [& labels]
  (dws/select-shapes (into (d/ordered-set) (map cthi/id) labels)))

(defn- linked?
  [label]
  (some? (:component-id (shape label))))

(defn- fill
  [label]
  (-> (shape label) :fills first :fill-color))

(defn- set-fill
  [& labels]
  (dwsh/update-shapes (into #{} (map cthi/id) labels)
                      #(assoc % :fills (cths/sample-fills-color :fill-color override))))

(defn- component-count
  []
  (count (ctkl/components-seq (data))))

(t/deftest ^:async detach-components-is-one-undo-entry
  (install-file! (two-components))
  (let [before (undo-entries)]
    (t/is (= :settled (await (step (dwl/detach-components [(cthi/id :copy-a) (cthi/id :copy-b)])))))
    (t/is (not (linked? :copy-a)))
    (t/is (not (linked? :copy-b)))
    (t/is (= (inc before) (undo-entries)))
    (await (step dwu/undo))
    (t/is (linked? :copy-a))
    (t/is (linked? :copy-b))))

(t/deftest ^:async detach-selected-components
  (install-file! (two-components))
  (t/testing "nothing happens when a selected shape is inside a copy"
    (await (step (select :copy-a :copy-b-child) dwl/detach-selected-components))
    (t/is (linked? :copy-a))
    (t/is (some? (:shape-ref (shape :copy-b-child)))))
  (t/testing "the selected copies are detached"
    (await (step (select :copy-a :copy-b) dwl/detach-selected-components))
    (t/is (not (linked? :copy-a)))
    (t/is (not (linked? :copy-b)))))

(t/deftest ^:async reset-components-is-one-undo-entry
  (install-file! (two-components))
  (await (step (set-fill :copy-a-child :copy-b-child)))
  (t/is (= override (fill :copy-a-child)))
  (let [before (undo-entries)]
    (await (step (dwl/reset-components [(cthi/id :copy-a) (cthi/id :copy-b)])))
    (t/is (= main-color (fill :copy-a-child)))
    (t/is (= main-color (fill :copy-b-child)))
    (t/is (= (inc before) (undo-entries)))
    (await (step dwu/undo))
    (t/is (= override (fill :copy-a-child)))
    (t/is (= override (fill :copy-b-child)))))

(defn- copies-of
  [component-label]
  (->> (vals (:objects (cthf/current-page (ftm/current-file))))
       (filter #(and (ctk/instance-head? %)
                     (not (ctk/main-instance? %))
                     (= (cthi/id component-label) (:component-id %))))))

(t/deftest ^:async multi-swap-is-one-undo-entry
  (install-file! (two-components))
  (let [before (undo-entries)]
    (await (step (dwl/component-multi-swap [(shape :copy-a) (shape :copy-b)]
                                           (:id (ftm/current-file))
                                           (cthi/id :c2))))
    (t/is (= 0 (count (copies-of :c1))))
    (t/is (= 2 (count (copies-of :c2))))
    (t/is (= (inc before) (undo-entries)))
    (await (step dwu/undo))
    (t/is (= 2 (count (copies-of :c1))))
    (t/is (= 0 (count (copies-of :c2))))))

(t/deftest ^:async add-multiple-components-is-one-undo-entry
  (install-file! (two-components))
  (let [before (undo-entries)]
    (await (step (select :rect-a :rect-b) (dwl/add-multiple-components)))
    (t/is (= 4 (component-count)))
    (t/is (= (inc before) (undo-entries)))
    (await (step dwu/undo))
    (t/is (= 2 (component-count)))))

(t/deftest ^:async add-multiple-components-refuses-a-selection-with-a-copy-child
  (install-file! (two-components))
  (await (step (select :rect-a :copy-a-child) (dwl/add-multiple-components)))
  (t/is (= 2 (component-count))))

(t/deftest ^:async add-component-sets-the-id-ref
  (install-file! (two-components))
  (let [id-ref (atom nil)]
    (await (step (dwl/add-component id-ref [(cthi/id :rect-a)])))
    (t/is (some? (ctkl/get-component (data) @id-ref)))))

(t/deftest ^:async duplicate-component-makes-a-new-main
  (install-file! (two-components))
  (await (step (dwl/duplicate-component (:id (ftm/current-file)) (cthi/id :c1))))
  (let [new-component (->> (ctkl/components-seq (data))
                           (remove #(#{(cthi/id :c1) (cthi/id :c2)} (:id %)))
                           first)]
    (t/is (= 3 (component-count)))
    (t/is (some? (shape-by-id (:main-instance-id new-component))))))

(t/deftest ^:async restore-components-is-one-undo-entry
  (install-file! (two-components))
  (let [file-id (:id (ftm/current-file))]
    (await (step (dwl/delete-component {:id (cthi/id :c1)})
                 (dwl/delete-component {:id (cthi/id :c2)})))
    (t/is (= 0 (component-count)))
    (let [before (undo-entries)]
      (await (step (dwl/restore-components {(cthi/id :c1) file-id
                                            (cthi/id :c2) file-id})))
      (t/is (= 2 (component-count)))
      (t/is (= (inc before) (undo-entries))))))

(t/deftest ^:async update-component-of-a-library-main
  (let [library (-> (cthf/sample-file :library :is-shared true)
                    (ctho/add-simple-component :lib-c :lib-main :lib-main-child))
        file    (-> (cthf/sample-file :file1)
                    (cthc/instantiate-component :lib-c :copy
                                                :library library
                                                :children-labels [:copy-child]))]
    (ftm/install! (-> (tm/make-situation file)
                      (tm/with-aux-files {(:id library) library})))
    (await (step (set-fill :copy-child)))
    (await (step (dwl/update-component (cthi/id :copy))))
    (let [library' (get-in @st/state [:files (:id library)])]
      (t/is (= override (-> (cths/get-shape library' :lib-main-child) :fills first :fill-color))))
    (t/testing "a shape that is not a head pushes nothing"
      (await (step (dwsh/update-shapes #{(cthi/id :copy-child)}
                                       #(assoc % :fills (cths/sample-fills-color :fill-color "#333333")))))
      (await (step (dwl/update-component (cthi/id :copy-child))))
      (let [library' (get-in @st/state [:files (:id library)])]
        (t/is (= override (-> (cths/get-shape library' :lib-main-child) :fills first :fill-color))))
      (t/is (= "#333333" (fill :copy-child))))))

(t/deftest ^:async rename-component-to-a-blank-name
  (install-file! (two-components))
  (let [before (:name (ctkl/get-component (data) (cthi/id :c1)))]
    (await (step (dwl/rename-component (cthi/id :c1) "   ")))
    (t/is (= before (:name (ctkl/get-component (data) (cthi/id :c1)))))))

(t/deftest ^:async instantiate-component-sets-the-id-ref
  (install-file! (two-components))
  (let [id-ref (atom nil)]
    (await (step (dwl/instantiate-component (:id (ftm/current-file)) (cthi/id :c2)
                                            (gpt/point 0 0) {:id-ref id-ref :origin "plugin"})))
    (t/is (= (cthi/id :c2) (:component-id (shape-by-id @id-ref))))))
