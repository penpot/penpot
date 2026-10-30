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
  [layout]
  (let [cell (fn [row column label]
               (let [id (uuid/next)]
                 [id {:id id :row row :column column :row-span 1 :column-span 1
                      :position :auto :align-self :auto :justify-self :auto
                      :shapes [(thi/id label)]}]))]
    (-> (thf/sample-file :file)
        (tho/add-frame :main (merge layout
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

(defn- ^:async install-grid
  ([]
   (await (install-grid cls/initial-grid-layout)))
  ([layout]
   (ftm/install! (tm/make-situation (get-grid-file layout)))
   (t/is (= :settled (await (ftm/await-step
                             [(ptk/data-event :layout/update {:ids [(thi/id :main) (thi/id :copy) (thi/id :other)]})]))))
   (t/is (= (get-cells :main) (get-cells :copy) (get-cells :other)))))

(t/deftest ^:async a-grid-main-without-direction-grows-and-turns-like-a-row-grid
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (await (install-grid (dissoc cls/initial-grid-layout :layout-grid-dir)))
     (t/is (not (contains? (get-shape :main) :layout-grid-dir)))
     (let [initial (tm/make-situation (ftm/current-file))
           _       (ftm/uninstall!)
           grown   (loop [live (ftm/start-generated initial)
                          ops  [(n/add-child :main :extra-a :width 20 :height 20)
                                (n/add-child :main :extra-b :width 20 :height 20)]]
                     (if-let [op (first ops)]
                       (let [next (await (ftm/run-generated-step live op))]
                         (t/is (some? next))
                         (t/is (empty? (uc/failures next)))
                         (recur next (rest ops)))
                       live))
           members (set (:shapes (get-shape :main)))]
       (doseq [label [:main :copy :other]]
         (t/is (= [2 3] (mapv #(count (get (get-shape label) %)) [:layout-grid-rows :layout-grid-columns])))
         (t/is (= #{[1 1] [1 2] [1 3] [2 1] [2 2] [2 3]} (set (map (juxt :row :column) (get-cells label)))))
         (t/is (= members (set (mapcat :shapes (get-cells label))))))
       (t/is (empty? (:touched (get-shape :copy))))
       (t/is (empty? (:touched (get-shape :other))))
       (t/is (empty? (uc/failures (await (ftm/finish-generated (ftm/refresh-generated grown)))))))
     (let [rows (get-cells :main)]
       (await (apply-step (dwsl/change-grid-direction [(thi/id :main)] :column)))
       (t/is (= [:column :column :column] (mapv #(:layout-grid-dir (get-shape %)) [:main :copy :other])))
       (t/is (= (get-cells :main) (get-cells :copy) (get-cells :other)))
       (t/is (not= rows (get-cells :main)))
       (t/is (empty? (:touched (get-shape :copy))))
       (t/is (empty? (:touched (get-shape :other)))))
     (ftm/uninstall!))))

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
