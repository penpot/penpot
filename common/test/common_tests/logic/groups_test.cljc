;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.groups-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.bool :as clb]
   [app.common.logic.groups :as clg]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- two-rects
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-shape :rect-a)
      (ths/add-sample-shape :rect-b)))

(defn- objects
  [file]
  (:objects (thf/current-page file)))

(defn- group
  [file labels]
  (let [page    (thf/current-page file)
        shapes  (clg/shapes-for-group-or-mask (:objects page) (map thi/id labels))
        [group changes] (clg/prepare-create-group (pcb/empty-changes nil) (uuid/next) (:objects page)
                                                  (:id page) shapes "Group" false)]
    [(:id group) (thf/apply-changes file changes)]))

(t/deftest grouping-puts-the-shapes-in-a-new-group
  (let [[id file] (group (two-rects) [:rect-a :rect-b])
        group     (get (objects file) id)]
    (t/is (= :group (:type group)))
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (set (:shapes group))))
    (t/is (every? #(= id (:parent-id (get (objects file) %))) (:shapes group)))))

(t/deftest ungrouping-gives-the-children-back-to-the-parent
  (let [[id file] (group (two-rects) [:rect-a :rect-b])
        page      (thf/current-page file)
        ids       (clg/ungroup-ids (:objects page) [id])
        file      (thf/apply-changes file (clg/generate-ungroup-changes nil (:id page) (:objects page) ids))]
    (t/is (nil? (get (objects file) id)))
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (clg/ungrouped-children (:objects page) ids)))
    (t/is (= uuid/zero (:parent-id (get (objects file) (thi/id :rect-a)))))))

(t/deftest ungrouping-leaves-copy-heads-whole
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component :main-root :main-child :copy-root))]
    (t/is (empty? (clg/ungroup-ids (objects file) [(thi/id :copy-root)])))))

(t/deftest masking-a-lone-group-masks-it-and-unmasking-takes-the-mask-off
  (let [[id file] (group (two-rects) [:rect-a :rect-b])
        page      (thf/current-page file)
        shapes    (clg/shapes-for-group-or-mask (:objects page) [id])
        [mask changes] (clg/generate-mask-changes nil (uuid/next) (:id page) (:objects page) shapes)
        masked    (thf/apply-changes file changes)
        page      (thf/current-page masked)
        unmasked  (thf/apply-changes masked (clg/generate-unmask-changes nil (:id page) (:objects page) [id]))]
    (t/is (= id (:id mask)))
    (t/is (true? (:masked-group (get (objects masked) id))))
    (t/is (nil? (:masked-group (get (objects unmasked) id))))))

(t/deftest a-bool-combines-shapes-but-not-boards
  (let [file (-> (two-rects)
                 (ths/add-sample-shape :board :type :frame))
        page (thf/current-page file)
        ids  (map thi/id [:rect-a :rect-b :board])
        [shape changes] (clb/generate-create-bool nil (:id page) (:objects page) ids :union nil)
        file (thf/apply-changes file changes)]
    (t/is (= :bool (:type (get (objects file) (:id shape)))))
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (set (:shapes (get (objects file) (:id shape))))))
    (t/is (nil? (clb/generate-create-bool nil (:id page) (:objects page) [(thi/id :board)] :union nil)))))
