;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.grid-layout-test
  (:require
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each ftm/fixture)

(defn- get-grid-file
  []
  (let [cell (fn [row column label]
               (let [id (uuid/next)]
                 [id {:id id :row row :column column :row-span 1 :column-span 1
                      :position :auto :align-self :auto :justify-self :auto
                      :shapes [(thi/id label)]}]))]
    (-> (thf/sample-file :file)
        (tho/add-frame :main (merge cls/initial-grid-layout
                                    {:width 240 :height 160
                                     :layout-grid-rows [{:type :fixed :value 80} {:type :fixed :value 80}]
                                     :layout-grid-columns [{:type :fixed :value 120} {:type :fixed :value 120}]}))
        (tho/add-rect :a :parent-label :main :width 40 :height 20)
        (tho/add-rect :b :parent-label :main :width 40 :height 20)
        (tho/add-rect :c :parent-label :main :width 40 :height 20)
        (tho/add-rect :d :parent-label :main :width 40 :height 20)
        (ths/update-shape :main :layout-grid-cells
                          (into {} [(cell 1 1 :a) (cell 1 2 :b) (cell 2 1 :c) (cell 2 2 :d)]))
        (thc/make-component :component :main)
        (thc/instantiate-component :component :copy)
        (thc/instantiate-component :component :other))))

(defn- get-shape
  [label]
  (ths/get-shape (ftm/current-file) label))

(defn- get-cells
  "Compare placements through source identities, without the production grid helpers."
  [label]
  (let [file (ftm/current-file)]
    (->> (:layout-grid-cells (ths/get-shape file label))
         vals
         (sort-by (juxt :row :column))
         (mapv (fn [cell]
                 (-> cell
                     (dissoc :id)
                     (update :shapes #(mapv (fn [id]
                                              (or (:shape-ref (ths/get-shape-by-id file id)) id)) %))))))))

(defn- get-cell-ids
  [label]
  (->> (:layout-grid-cells (get-shape label)) vals (sort-by (juxt :row :column)) (mapv :id)))

(defn- check-clean-children
  [label]
  (let [file (ftm/current-file)]
    (doseq [id (:shapes (get-shape label))]
      (t/is (empty? (:touched (ths/get-shape-by-id file id)))))))

(defn- ^:async apply-step
  "Every user event and its derived work must undo and redo in one exact step."
  [event]
  (let [before (ftm/snapshot)]
    (t/is (= :settled (await (ftm/await-step [event]))))
    (let [after (ftm/snapshot)]
      (t/is (not (uc/same-state? before after)) "The user action must change the file")
      (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
      (t/is (uc/same-state? before (ftm/snapshot)))
      (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
      (t/is (uc/same-state? after (ftm/snapshot))))))

(defn- ^:async install-grid
  []
  (ftm/install! (tm/make-situation (get-grid-file)))
  (t/is (= :settled (await (ftm/await-step
                            [(ptk/data-event :layout/update {:ids [(thi/id :main) (thi/id :copy) (thi/id :other)]})]))))
  (t/is (= (get-cells :main) (get-cells :copy) (get-cells :other))))

(t/deftest ^:async grid-growth-keeps-overrides-within-inherited-tracks
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (doseq [direction [:row :column]
             turn-copy? [false true]]
       (await (install-grid))
       (let [initial (tm/make-situation (ftm/current-file))
             _       (ftm/uninstall!)
             ops     (concat (when (= direction :column) [(n/edit-grid :main :direction :column)])
                             [(n/edit-grid :copy :cell {:row 1 :column 1 :props {:align-self :end}})]
                             (when turn-copy? [(n/edit-grid :copy :direction (if (= direction :row) :column :row))])
                             [(n/add-child :main :extra-a :width 20 :height 20)
                              (n/add-child :main :extra-b :width 20 :height 20)])
             grown   (loop [live (ftm/start-generated initial) ops ops]
                       (if-let [op (first ops)]
                         (let [next (await (ftm/run-generated-step live op))]
                           (t/is (some? next))
                           (t/is (empty? (uc/failures next)))
                           (recur next (rest ops)))
                         live))
             dims    (if (= direction :row) [2 3] [3 2])
             coords  (set (for [row (range 1 (inc (first dims)))
                                column (range 1 (inc (second dims)))] [row column]))
             members (set (:shapes (get-shape :main)))]
         (doseq [label [:main :copy :other]]
           (t/is (= dims (mapv #(count (get (get-shape label) %)) [:layout-grid-rows :layout-grid-columns])))
           (t/is (= coords (set (map (juxt :row :column) (get-cells label)))))
           (t/is (= members (set (mapcat :shapes (get-cells label)))))
           (t/is (= (frequencies members) (frequencies (mapcat :shapes (get-cells label))))))
         (t/is (= :end (:align-self (first (get-cells :copy)))))
         (t/is (= (cond-> #{:layout-grid-cells} turn-copy? (conj :layout-grid-dir))
                  (:touched (get-shape :copy))))
         (t/is (empty? (:touched (get-shape :other))))
         (let [before  (ftm/snapshot)
               file-id (:id (ftm/current-file))]
           (t/is (= :settled (await (ftm/await-step [(dwl/sync-file file-id file-id :components (thi/id :component))]))))
           (t/is (uc/same-state? before (ftm/snapshot))))
         (t/is (empty? (uc/failures (await (ftm/finish-generated (ftm/refresh-generated grown))))))
         (ftm/uninstall!))))))

(t/deftest ^:async grid-direction-overrides-survive-main-edits-update-main-and-reset
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (await (install-grid))
     (let [row-cells    (get-cells :main)
           column-cells (mapv #(assoc %1 :shapes [(thi/id %2)]) row-cells [:a :c :b :d])
           main-order   (:shapes (get-shape :main))
           copy-order   (:shapes (get-shape :copy))
           other-order  (:shapes (get-shape :other))]
       (t/is (= (mapv #(vector (thi/id %)) [:a :b :c :d]) (mapv :shapes row-cells)))
       (await (apply-step (dwsl/change-grid-direction [(thi/id :copy)] :column)))
       (t/is (= :column (:layout-grid-dir (get-shape :copy))))
       (t/is (= column-cells (get-cells :copy)))
       (t/is (= row-cells (get-cells :main) (get-cells :other)))
       (t/is (= #{:layout-grid-dir :layout-grid-cells} (:touched (get-shape :copy))))
       (check-clean-children :copy)

       (await (apply-step (dwsl/change-grid-direction [(thi/id :main)] :column)))
       (t/is (= column-cells (get-cells :main) (get-cells :copy) (get-cells :other)))
       (t/is (= :column (:layout-grid-dir (get-shape :other))))
       (t/is (= #{:layout-grid-dir :layout-grid-cells} (:touched (get-shape :copy))))
       (t/is (empty? (:touched (get-shape :other))))

       (await (apply-step (dwsl/change-grid-direction [(thi/id :main)] :row)))
       (t/is (= row-cells (get-cells :main) (get-cells :other)))
       (t/is (= column-cells (get-cells :copy)))
       (t/is (= :column (:layout-grid-dir (get-shape :copy))))

       (await (apply-step (dwl/update-component-sync (thi/id :copy) (:id (ftm/current-file)))))
       (t/is (= column-cells (get-cells :main) (get-cells :copy) (get-cells :other)))
       (t/is (= [:column :column :column] (mapv #(:layout-grid-dir (get-shape %)) [:main :copy :other])))
       (t/is (empty? (:touched (get-shape :copy))))

       (await (apply-step (dwsl/change-grid-direction [(thi/id :copy)] :row)))
       (t/is (= row-cells (get-cells :copy)))
       (await (apply-step (dwl/reset-component (thi/id :copy))))
       (t/is (= column-cells (get-cells :copy)))
       (t/is (= :column (:layout-grid-dir (get-shape :copy))))
       (t/is (empty? (:touched (get-shape :copy))))
       (t/is (= main-order (:shapes (get-shape :main))))
       (t/is (= copy-order (:shapes (get-shape :copy))))
       (t/is (= other-order (:shapes (get-shape :other))))
       (check-clean-children :main)
       (check-clean-children :copy)
       (check-clean-children :other)))))

(t/deftest ^:async grid-cell-overrides-retain-the-whole-group-through-main-edits-and-update-main
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (await (install-grid))
     (let [initial   (get-cells :main)
           authored  (-> initial (assoc-in [0 :align-self] :end) (assoc-in [0 :justify-self] :center))
           inherited (-> initial (assoc-in [0 :align-self] :start) (assoc-in [1 :justify-self] :end))]
       (await (apply-step (dwsl/update-grid-cells (thi/id :copy) [(first (get-cell-ids :copy))]
                                                  {:align-self :end :justify-self :center})))
       (t/is (= authored (get-cells :copy)))
       (t/is (= #{:layout-grid-cells} (:touched (get-shape :copy))))
       (await (apply-step (dwsl/update-grid-cells (thi/id :main) [(first (get-cell-ids :main))] {:align-self :start})))
       (await (apply-step (dwsl/update-grid-cells (thi/id :main) [(second (get-cell-ids :main))] {:justify-self :end})))
       (t/is (= authored (get-cells :copy)))
       (t/is (= inherited (get-cells :main) (get-cells :other)))
       (t/is (empty? (:touched (get-shape :other))))
       (check-clean-children :copy)
       (check-clean-children :other)

       (await (apply-step (dwl/reset-component (thi/id :copy))))
       (t/is (= inherited (get-cells :copy)))
       (t/is (empty? (:touched (get-shape :copy))))
       (await (apply-step (dwsl/update-grid-cells (thi/id :other) [(first (get-cell-ids :other))] {:align-self :center})))
       (await (apply-step (dwsl/update-grid-cells (thi/id :copy) [(second (get-cell-ids :copy))]
                                                  {:align-self :end :justify-self :center})))
       (let [pushed   (-> inherited (assoc-in [1 :align-self] :end) (assoc-in [1 :justify-self] :center))
             retained (assoc-in inherited [0 :align-self] :center)]
         (await (apply-step (dwl/update-component-sync (thi/id :copy) (:id (ftm/current-file)))))
         (t/is (= pushed (get-cells :main) (get-cells :copy)))
         (t/is (= retained (get-cells :other)))
         (t/is (empty? (:touched (get-shape :copy))))
         (t/is (= #{:layout-grid-cells} (:touched (get-shape :other))))
         (check-clean-children :main)
         (check-clean-children :copy)
         (check-clean-children :other))))))
