;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.changes-builder-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-change-parent-undo-uses-parent-in-objects
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :parent1)
                    (tho/add-frame :parent2)
                    (tho/add-frame :parent3)
                    (tho/add-rect :rect0 :parent-label :parent1)
                    (tho/add-rect :rect1 :parent-label :parent1)
                    (tho/add-rect :rect2 :parent-label :parent1))
        page    (thf/current-page file)
        ;; The map of the shape as it is before both moves
        rect1   (ths/get-shape file :rect1)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil (:id page))
                    (pcb/with-objects (:objects page))
                    (pcb/change-parent (thi/id :parent2) [rect1])
                    (pcb/change-parent (thi/id :parent3) [rect1]))
        file'   (thf/apply-changes file changes)
        file''  (thf/apply-undo-changes file' changes)]

    (t/is (= (thi/id :parent3) (:parent-id (ths/get-shape file' :rect1))))
    ;; The undo of the second move, run first, takes the shape back to
    ;; :parent2, where the first move left it, not to the parent in the map
    (t/is (= (thi/id :parent2) (:parent-id (first (:undo-changes changes)))))
    ;; The whole undo puts the shape back in its place in :parent1
    (t/is (= (thi/id :parent1) (:parent-id (ths/get-shape file'' :rect1))))
    (t/is (= (:shapes (ths/get-shape file :parent1))
             (:shapes (ths/get-shape file'' :parent1))))))

(defn- make-shape
  [id type parent-id shapes x y size]
  (-> (cts/setup-shape {:id id :type type :x x :y y :width size :height size})
      (assoc :parent-id parent-id
             :frame-id uuid/zero
             :shapes shapes)))

(t/deftest resize-parents-resizes-inner-parents-first
  (let [outer-id (uuid/next)
        inner-id (uuid/next)
        moved-id (uuid/next)
        other-id (uuid/next)

        ;; :moved left both groups, which still hold their old size
        objects  {uuid/zero (make-shape uuid/zero :frame nil [outer-id] 0 0 100)
                  outer-id  (make-shape outer-id :group uuid/zero [inner-id other-id] 0 0 30)
                  inner-id  (make-shape inner-id :group outer-id [moved-id] 0 0 10)
                  moved-id  (make-shape moved-id :rect inner-id [] 100 100 10)
                  other-id  (make-shape other-id :rect outer-id [] 20 20 10)}

        changes  (-> (pcb/empty-changes nil uuid/zero)
                     (pcb/with-objects objects)
                     (pcb/resize-parents [other-id moved-id]))

        objects' (pcb/lookup-objects changes)]

    (t/is (= 110.0 (double (get-in objects' [inner-id :selrect :x2]))))
    ;; The outer group holds the inner group once resized
    (t/is (= 110.0 (double (get-in objects' [outer-id :selrect :x2]))))))

(t/deftest resize-parents-keeps-moved-booleans
  (let [bool-id  (uuid/next)
        child-id (uuid/next)

        ;; The child left the boolean, which still holds its old content
        objects  {uuid/zero (make-shape uuid/zero :frame nil [bool-id] 0 0 100)
                  bool-id   (-> (make-shape bool-id :bool uuid/zero [child-id] 0 0 10)
                                (assoc :bool-type :union))
                  child-id  (make-shape child-id :rect bool-id [] 50 50 10)}

        resized  (fn [moved-bool-ids]
                   (-> (pcb/empty-changes nil uuid/zero)
                       (pcb/with-objects objects)
                       (pcb/resize-parents [child-id] moved-bool-ids)
                       (pcb/lookup-objects)
                       (get-in [bool-id :selrect :x1])
                       (double)))]

    (t/is (= 50.0 (resized nil)))
    (t/is (= 0.0 (resized #{bool-id})))))

(t/deftest update-shapes-without-touched-keeps-the-same-local-objects
  (let [rect-id (uuid/next)
        objects {uuid/zero (make-shape uuid/zero :frame nil [rect-id] 0 0 100)
                 rect-id   (-> (make-shape rect-id :rect uuid/zero [] 10 10 10)
                               (assoc :blocked true))}

        update  #(-> %
                     (assoc :x 40 :name "moved")
                     (dissoc :blocked))

        updated (fn [ignore-touched]
                  (-> (pcb/empty-changes nil uuid/zero)
                      (pcb/with-objects objects)
                      (pcb/update-shapes [rect-id] update {:ignore-touched ignore-touched})
                      (pcb/lookup-objects)
                      (get rect-id)))]

    (t/is (= 40 (:x (updated true))))
    (t/is (= (updated false) (updated true)))))

(t/deftest update-shapes-without-attrs-writes-what-changed
  (let [id      (uuid/next)
        objects {uuid/zero (make-shape uuid/zero :frame nil [id] 0 0 100)
                 id        (assoc (make-shape id :rect uuid/zero [] 0 0 10)
                                  :opacity 0.5 :blocked true)}
        changes (-> (pcb/empty-changes nil (uuid/next))
                    (pcb/with-objects objects)
                    (pcb/update-shapes [id] #(-> %
                                                 (assoc :opacity 0.7 :name "same")
                                                 (assoc :name (:name %))
                                                 (dissoc :blocked))))
        ops     (-> changes :redo-changes first :operations)]
    (t/is (= #{[:opacity 0.7] [:blocked nil]}
             (into #{} (map (juxt :attr :val)) ops))
          "the changed and the removed attributes, not the unchanged ones")))

