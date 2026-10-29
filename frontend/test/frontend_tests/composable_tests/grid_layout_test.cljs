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
