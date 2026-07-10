;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-modifiers-test
  "Tests for the bool recomputation ordering after applying modifiers
   (issue #10647 follow-up).

   The wasm boolean calculation uses the STORED content of nested bool
   children (the CLJS fallback recomputes them recursively), so when a
   drag triggers the recalculation of several nested bools, the inner
   ones must be recomputed before the outer ones read their content.
   Two facts make that ordering sufficient:

   - update-shapes processes ids one at a time, and each step is folded
     into the changes' file-data (apply-changes-local), so later steps
     observe earlier results (pinned here).
   - the bool ids produced for the update are ordered children before
     parents (pinned here)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.modifiers :as dwm]
   [cljs.test :as t :include-macros true]))

(defn- make-shape
  [id type parent-id children-ids]
  (cts/setup-shape
   (cond-> {:id id
            :type type
            :parent-id parent-id
            :frame-id uuid/zero
            :x 0 :y 0 :width 10 :height 10}
     (= type :bool) (assoc :bool-type :union)
     (some? children-ids) (assoc :shapes children-ids))))

(defn- nested-bool-objects
  "root frame ⊃ outer bool ⊃ inner bool ⊃ rect, plus a sibling rect
   directly inside the outer bool."
  []
  (let [outer-id (uuid/next)
        inner-id (uuid/next)
        rect-a   (uuid/next)
        rect-b   (uuid/next)]
    {:ids     {:outer outer-id :inner inner-id :rect-a rect-a :rect-b rect-b}
     :objects {uuid/zero (make-shape uuid/zero :frame nil [outer-id])
               outer-id  (make-shape outer-id :bool uuid/zero [inner-id rect-b])
               inner-id  (make-shape inner-id :bool outer-id [rect-a])
               rect-a    (make-shape rect-a :rect inner-id nil)
               rect-b    (make-shape rect-b :rect outer-id nil)}}))

(t/deftest bool-ids-ordered-children-before-parents
  "The bool ancestors of the moved shapes must come out children-first,
   so an outer bool is recomputed after its nested bool."
  (let [{:keys [ids objects]} (nested-bool-objects)
        {:keys [outer inner rect-a]} ids
        result (dwm/bool-ids-children-first objects [rect-a])]
    (t/is (= #{outer inner} (set result)) "both bool ancestors present, deduped")
    (t/is (< (.indexOf result inner) (.indexOf result outer))
          "the nested bool comes before the outer bool")))

(t/deftest update-shapes-steps-observe-earlier-results
  "generate-update-shapes must fold each shape's update into the
   objects that the next shape's update-fn receives: the children-first
   ordering only fixes nested bools because of this."
  (let [{:keys [ids objects]} (nested-bool-objects)
        {:keys [outer inner]} ids
        seen (atom {})
        update-fn (fn [shape objects']
                    (swap! seen assoc (:id shape) (get-in objects' [inner :marker]))
                    (assoc shape :marker :updated))]
    (-> (pcb/empty-changes nil uuid/zero)
        (cls/generate-update-shapes [inner outer] update-fn objects {:with-objects? true}))
    (t/is (nil? (get @seen inner)) "the first step sees the original objects")
    (t/is (= :updated (get @seen outer))
          "the second step sees the first step's result")))
