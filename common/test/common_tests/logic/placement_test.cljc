;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.placement-test
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.logic.placement :as clp]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.component :as ctk]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-frame :board)
                 (tho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                                     :component2 :main2-root :nested-head
                                                     :copy2-root
                                                     :copy2-root-params {:parent-label :board}))]
    (:objects (thf/current-page file))))

(defn- moved-ignore-tree
  "The ignore tree of moving the shapes `moved-ids` by (10, 20), each with
   the same matrix, as the WASM renderer gives it."
  [objects moved-ids]
  (let [transforms (zipmap moved-ids (repeat (gmt/translate-matrix (gpt/point 10 20))))]
    (clp/calculate-ignore-tree objects moved-ids (clp/matrices-transform transforms))))

(defn- copy-ids
  [objects]
  (->> (cfh/get-children-ids-with-self objects (thi/id :copy2-root))
       (filter #(ctk/in-component-copy? (get objects %)))))

(t/deftest moving-a-board-keeps-its-copies-untouched
  (let [objects (setup)
        tree    (moved-ignore-tree objects (cfh/get-children-ids-with-self objects (thi/id :board)))]
    (t/is (seq (copy-ids objects)))
    (t/is (every? #(true? (get tree %)) (copy-ids objects)))))

(t/deftest moving-a-copy-keeps-its-children-untouched
  (let [objects (setup)
        tree    (moved-ignore-tree objects (cfh/get-children-ids-with-self objects (thi/id :copy2-root)))]
    (t/is (every? #(true? (get tree %)) (copy-ids objects)))))

(t/deftest moving-a-shape-inside-a-copy-touches-it
  (let [objects (setup)
        rect-id (last (copy-ids objects))
        tree    (moved-ignore-tree objects [rect-id])]
    (t/is (false? (get tree rect-id)))))

(t/deftest rotating-a-copy-with-modifiers-keeps-its-children-untouched
  (let [file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                                           :main-root-params {:width 100 :height 100}
                                                           :main-child-params {:x 10 :y 10 :width 20 :height 20}))
        objects    (:objects (thf/current-page file))
        root       (get objects (thi/id :copy-root))
        child-id   (first (:shapes root))
        modif-tree (gm/set-objects-modifiers
                    {(:id root) {:modifiers (ctm/rotation-modifiers root (gpt/point 50 50) 30)}}
                    objects)
        tree       (clp/calculate-ignore-tree objects (keys modif-tree) (clp/modifiers-transform modif-tree))]
    (t/testing "the root's rotation is an override"
      (t/is (false? (get tree (:id root)))))
    (t/testing "its child keeps its geometry relative to it"
      (t/is (true? (get tree child-id))))))
