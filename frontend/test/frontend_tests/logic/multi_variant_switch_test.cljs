;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.multi-variant-switch-test
  "Regression tests for #12829: multi-select variants-switch /
  component-multi-swap must not emit one select-shape per copy, and must
  coalesce skipped per-swap layout into one :layout/update."
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as cthv]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as-alias dws]
   [app.main.data.workspace.variants :as dwv]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(def ^:private fill-value1 "#2152e5")
(def ^:private fill-value2 "#e52152")

(defn- count-events
  "Atom counting how many events of `type` land on the store input-stream
  (nested WatchEvents only appear there — not on a bare ptk/watch of
  variants-switch)."
  [store type]
  (let [counter (atom 0)]
    (->> (ptk/input-stream store)
         (rx/filter (ptk/type? type))
         (rx/tap (fn [_] (swap! counter inc)))
         (rx/subs! (fn [_] nil)))
    counter))

(defn- collect-layout-updates
  "Atom accumulating deref'd `:layout/update` payloads from the store
  input-stream (same subscribe-before-emit pattern as `count-events`)."
  [store]
  (let [acc (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter (ptk/type? :layout/update))
         (rx/tap #(swap! acc conj (deref %)))
         (rx/subs! (fn [_] nil)))
    acc))

(defn- assert-coalesced-layout
  [layout-updates flex-board-id]
  (t/is (= 1 (count @layout-updates))
        (str "expected one coalesced :layout/update; got "
             (count @layout-updates)))
  (let [ids (set (:ids (first @layout-updates)))]
    (t/is (seq ids)
          "coalesced :layout/update must carry non-empty :ids")
    (t/is (contains? ids flex-board-id)
          (str "coalesced :ids must include flex board; got " ids))))

(defn- fill-color
  [shape]
  (-> shape :fills first :fill-color))

(defn- setup-flex-variant-file
  "Two variant members with distinct fills and sizes."
  []
  (-> (cthf/sample-file :file1)
      (cthv/add-variant :v01 :c01 :m01 :c02 :m02
                        :variant1-params {:fills (cths/sample-fills-color
                                                  :fill-color fill-value1)
                                          :width 40
                                          :height 40}
                        :variant2-params {:fills (cths/sample-fills-color
                                                  :fill-color fill-value2)
                                          :width 50
                                          :height 50})
      (ctho/add-frame :flex-board
                      :name "Toggle Group"
                      :layout :flex
                      :layout-flex-dir :row
                      :width 400
                      :height 80)))

(defn- setup-flex-multi-copies
  "Three Value1 copies as children of a flex board."
  []
  (-> (setup-flex-variant-file)
      (cthc/instantiate-component :c01 :copy1 :parent-label :flex-board)
      (cthc/instantiate-component :c01 :copy2 :parent-label :flex-board)
      (cthc/instantiate-component :c01 :copy3 :parent-label :flex-board)))

(defn- setup-flex-mixed-copies
  "Two Value1 copies and one already on Value2 (no-op on switch to Value2)."
  []
  (-> (setup-flex-variant-file)
      (cthc/instantiate-component :c01 :copy1 :parent-label :flex-board)
      (cthc/instantiate-component :c01 :copy2 :parent-label :flex-board)
      (cthc/instantiate-component :c02 :copy3 :parent-label :flex-board)))

(t/deftest multi-variants-switch-emits-no-intermediate-select-shape
  (t/async
    done
    (let [file            (setup-flex-multi-copies)
          store           (ths/setup-store file)
          copy1           (cths/get-shape file :copy1)
          copy2           (cths/get-shape file :copy2)
          copy3           (cths/get-shape file :copy3)
          flex-board-id   (:id (cths/get-shape file :flex-board))
          target-comp-id  (cthi/id :c02)
          copy-ids        (d/ordered-set (:id copy1) (:id copy2) (:id copy3))
          select-shape-n  (count-events store ::dws/select-shape)
          layout-updates  (collect-layout-updates store)
          events
          [(dwv/variants-switch {:shapes [copy1 copy2 copy3]
                                 :pos 0
                                 :val "Value2"})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'    (ths/get-file-from-state new-state)
               copy1'   (cths/get-shape file' :copy1)
               copy2'   (cths/get-shape file' :copy2)
               copy3'   (cths/get-shape file' :copy3)
               selected (get-in new-state [:workspace-local :selected])]

           (t/is (zero? @select-shape-n)
                 (str "multi variants-switch must not emit intermediate "
                      "select-shape; got " @select-shape-n))
           (assert-coalesced-layout layout-updates flex-board-id)

           (t/is (= target-comp-id (:component-id copy1')))
           (t/is (= target-comp-id (:component-id copy2')))
           (t/is (= target-comp-id (:component-id copy3')))
           (t/is (= fill-value2 (fill-color copy1')))
           (t/is (= fill-value2 (fill-color copy2')))
           (t/is (= fill-value2 (fill-color copy3')))
           (t/is (= (set copy-ids) selected))))))))

;; Done-marker collection must not hang when some selected copies already
;; have the target value (no companion from those no-ops).
(t/deftest multi-variants-switch-coalesces-layout-when-some-noop
  (t/async
    done
    (let [file            (setup-flex-mixed-copies)
          store           (ths/setup-store file)
          copy1           (cths/get-shape file :copy1)
          copy2           (cths/get-shape file :copy2)
          copy3           (cths/get-shape file :copy3)
          flex-board-id   (:id (cths/get-shape file :flex-board))
          target-comp-id  (cthi/id :c02)
          copy-ids        (d/ordered-set (:id copy1) (:id copy2) (:id copy3))
          select-shape-n  (count-events store ::dws/select-shape)
          layout-updates  (collect-layout-updates store)
          events
          [(dwv/variants-switch {:shapes [copy1 copy2 copy3]
                                 :pos 0
                                 :val "Value2"})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'    (ths/get-file-from-state new-state)
               copy1'   (cths/get-shape file' :copy1)
               copy2'   (cths/get-shape file' :copy2)
               copy3'   (cths/get-shape file' :copy3)
               selected (get-in new-state [:workspace-local :selected])]

           (t/is (zero? @select-shape-n))
           (assert-coalesced-layout layout-updates flex-board-id)

           (t/is (= target-comp-id (:component-id copy1')))
           (t/is (= target-comp-id (:component-id copy2')))
           (t/is (= target-comp-id (:component-id copy3')))
           (t/is (= fill-value2 (fill-color copy1')))
           (t/is (= fill-value2 (fill-color copy2')))
           (t/is (= fill-value2 (fill-color copy3')))
           (t/is (= (set copy-ids) selected))))))))

(t/deftest component-multi-swap-skips-select-and-coalesces-layout
  (t/async
    done
    (let [file            (setup-flex-multi-copies)
          store           (ths/setup-store file)
          copy1           (cths/get-shape file :copy1)
          copy2           (cths/get-shape file :copy2)
          copy3           (cths/get-shape file :copy3)
          flex-board-id   (:id (cths/get-shape file :flex-board))
          target-comp-id  (cthi/id :c02)
          select-shape-n  (count-events store ::dws/select-shape)
          layout-updates  (collect-layout-updates store)
          events
          [(dwl/component-multi-swap [copy1 copy2 copy3]
                                     (:id file)
                                     target-comp-id)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               copy1' (cths/get-shape file' :copy1)
               copy2' (cths/get-shape file' :copy2)
               copy3' (cths/get-shape file' :copy3)]

           (t/is (zero? @select-shape-n)
                 (str "component-multi-swap must not emit intermediate "
                      "select-shape; got " @select-shape-n))
           (assert-coalesced-layout layout-updates flex-board-id)

           (t/is (= target-comp-id (:component-id copy1')))
           (t/is (= target-comp-id (:component-id copy2')))
           (t/is (= target-comp-id (:component-id copy3')))
           (t/is (= fill-value2 (fill-color copy1')))
           (t/is (= fill-value2 (fill-color copy2')))
           (t/is (= fill-value2 (fill-color copy3')))))))))

(t/deftest component-swap-noop-when-shape-missing
  (t/async
    done
    (let [file   (setup-flex-multi-copies)
          store  (ths/setup-store file)
          copy1  (cths/get-shape file :copy1)
          ghost  (assoc copy1 :id (uuid/next))
          before (ths/get-file-from-state @store)]
      (ths/run-store
       store done
       [(dwl/component-swap ghost (:id file) (cthi/id :c02) true)]
       (fn [new-state]
         (t/is (= before (ths/get-file-from-state new-state))
               "component-swap must no-op when shape id is absent from objects"))))))

(t/deftest variant-switch-noop-when-shape-missing
  (t/async
    done
    (let [file   (setup-flex-multi-copies)
          store  (ths/setup-store file)
          copy1  (cths/get-shape file :copy1)
          ghost  (assoc copy1 :id (uuid/next))
          before (ths/get-file-from-state @store)]
      (ths/run-store
       store done
       [(#'dwv/variant-switch ghost {:pos 0 :val "Value2"})]
       (fn [new-state]
         (t/is (= before (ths/get-file-from-state new-state))
               "variant-switch must no-op when shape id is absent from objects"))))))
