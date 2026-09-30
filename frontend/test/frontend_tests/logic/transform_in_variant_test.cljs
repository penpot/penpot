;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.transform-in-variant-test
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.component :as ctk]
   [app.main.data.workspace.variants :as dwv]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(t/deftest transform-component-in-variant
  "Ctrl+K on a component main turns it into a variant. Each step of the event
  chain is validated, so a validation error here fails the test through the
  store error handler."
  (t/async
    done
    (let [;; ==== Setup
          file   (-> (cthf/sample-file :file1)
                     (ctho/add-simple-component :c01 :m01 :r01))
          store  (ths/setup-store file)
          main   (cths/get-shape file :m01)

          ;; ==== Action
          events [(dwv/transform-in-variant (:id main))]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'      (ths/get-file-from-state new-state)
               main'      (cths/get-shape file' :m01)
               container' (cths/get-shape-by-id file' (:parent-id main'))
               comp'      (cthc/get-component file' :c01)]

           ;; ==== Check
           (t/is (ctk/is-variant-container? container'))
           (t/is (= (:id container') (:variant-id main')))
           (t/is (= (:id container') (:variant-id comp')))
           (t/is (= 1 (count (:variant-properties comp'))))))))))
