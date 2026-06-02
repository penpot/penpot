;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.variant-switch-test
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.variants :as dwv]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-variant-file
  []
  (-> (cthf/sample-file :file1)
      (thv/add-variant :v01 :c01 :m01 :c02 :m02)
      (cthc/instantiate-component :c01 :copy01)))

(defn- copy-instances-of
  "Returns non-main-instance copies with the given component-id."
  [page-objects comp-id]
  (filter #(and (= (:component-id %) comp-id) (not (:main-instance %))) (vals page-objects)))

(t/deftest variant-switch-nonexistent-value-is-noop
  "Switching to a value that no sibling variant has is a safe no-op — no exception, shape unchanged."
  (t/async
    done
    (let [file   (setup-variant-file)
          store  (ths/setup-store file)
          copy01 (cths/get-shape file :copy01)
          c01-id (cthi/id :c01)
          c02-id (cthi/id :c02)

          events
          [(dwv/variants-switch {:shapes [copy01] :pos 0 :val "NonExistentValue"})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [page-objects (dsh/lookup-page-objects new-state)]
           (t/is (= 1 (count (copy-instances-of page-objects c01-id))) "copy01 still points to c01")
           (t/is (empty? (copy-instances-of page-objects c02-id)) "no c02 copy was created")))))))

(t/deftest variant-switch-out-of-range-pos-is-noop
  "Switching with an out-of-range pos is a safe no-op — no exception, shape unchanged."
  (t/async
    done
    (let [file   (setup-variant-file)
          store  (ths/setup-store file)
          copy01 (cths/get-shape file :copy01)
          c01-id (cthi/id :c01)
          c02-id (cthi/id :c02)

          events
          [(dwv/variants-switch {:shapes [copy01] :pos 99 :val "Value2"})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [page-objects (dsh/lookup-page-objects new-state)]
           (t/is (= 1 (count (copy-instances-of page-objects c01-id))) "copy01 still points to c01")
           (t/is (empty? (copy-instances-of page-objects c02-id)) "no c02 copy was created")))))))

(t/deftest variant-switch-valid-switches-to-nearest-component
  "A valid switch with an existing value swaps the copy to the matching component."
  (t/async
    done
    (let [file   (setup-variant-file)
          store  (ths/setup-store file)
          copy01 (cths/get-shape file :copy01)
          c01-id (cthi/id :c01)
          c02-id (cthi/id :c02)

          events
          [(dwv/variants-switch {:shapes [copy01] :pos 0 :val "Value2"})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [page-objects (dsh/lookup-page-objects new-state)]
           (t/is (empty? (copy-instances-of page-objects c01-id)) "original c01 copy was removed")
           (t/is (= 1 (count (copy-instances-of page-objects c02-id))) "a c02 copy now exists")))))))
