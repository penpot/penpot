;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.colorpicker-token-set-order-test
  (:require
   [app.main.ui.workspace.colorpicker :refer [group-sets]]
   [cljs.test :as t :include-macros true]))

;; https://github.com/penpot/penpot/issues/10552
;; The Color tokens picker shows sets in reverse of their definition order
;; (highest-precedence / last-defined set first). The fix reverses the raw
;; set seq once, before it reaches `group-sets`. These tests drive
;; `group-sets` with already-reversed input -- matching what it receives in
;; the real pipeline -- to prove the `group-by` call inside `group-sets`
;; doesn't scramble that reversal, for both an ungrouped list and a
;; subgrouped one.

(defn- flat-entries
  "Reduce group-sets' output (a mix of single-set and multi-set grouped
   entries) to a flat [group name] seq, in output order."
  [grouped]
  (mapcat (fn [{:keys [group sets]}]
            (map (fn [{:keys [name]}] [group name]) sets))
          grouped))

(t/deftest reversed-flat-set-list
  (let [;; definition order: global, alias, semantic (ascending precedence)
        definition-order [{:id 1 :set "global"   :tokens []}
                          {:id 2 :set "alias"    :tokens []}
                          {:id 3 :set "semantic" :tokens []}]
        result (group-sets (reverse definition-order))]
    (t/is (= [[nil "semantic"] [nil "alias"] [nil "global"]]
             (flat-entries result)))))

(t/deftest reversed-subgroup-members-are-not-scrambled
  (let [;; brand/subgroup/one is defined before brand/subgroup/two
        definition-order [{:id 1 :set "brand/subgroup/one"}
                          {:id 2 :set "brand/subgroup/two"}]
        definition-order (map #(assoc % :tokens []) definition-order)
        result (group-sets (reverse definition-order))]
    ;; "two" has higher precedence (defined later) so it must lead --
    ;; group-by must not silently restore definition order within the
    ;; subgroup's own member list.
    (t/is (= [["brand/subgroup" "two"] ["brand/subgroup" "one"]]
             (flat-entries result)))))

(t/deftest reversed-mixed-flat-and-subgroup-set-list
  (let [;; definition order: an ungrouped set, then a two-member subgroup,
        ;; then another ungrouped set (highest precedence).
        definition-order [{:id 1 :set "global"             :tokens []}
                          {:id 2 :set "brand/subgroup/one" :tokens []}
                          {:id 3 :set "brand/subgroup/two" :tokens []}
                          {:id 4 :set "primitives"         :tokens []}]
        result (group-sets (reverse definition-order))
        entries (flat-entries result)]
    ;; Every set is present exactly once, and nothing outside the
    ;; subgroup reordered its two members relative to each other.
    (t/is (= #{[nil "global"] [nil "primitives"]
               ["brand/subgroup" "one"] ["brand/subgroup" "two"]}
             (set entries)))
    (t/is (= [["brand/subgroup" "two"] ["brand/subgroup" "one"]]
             (filter #(= (first %) "brand/subgroup") entries)))))
