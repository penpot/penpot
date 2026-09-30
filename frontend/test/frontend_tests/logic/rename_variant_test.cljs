;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.rename-variant-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as cthv]
   [app.main.data.workspace :as dw]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(t/deftest rename-variant-with-invalid-name
  "Renaming a variant in the layers panel to a name that is not a valid
  properties formula marks it with :variant-error. Each step of the event
  chain is validated, so a validation error here fails the test through the
  store error handler."
  (t/async
    done
    (let [;; ==== Setup
          file   (-> (cthf/sample-file :file1)
                     (cthv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
          store  (ths/setup-store file)
          main   (cths/get-shape file :m01)

          ;; ==== Action
          events [(dw/rename-shape-or-variant (:id main) "hola")]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'  (ths/get-file-from-state new-state)
               main'  (cths/get-shape file' :m01)]

           ;; ==== Check
           (t/is (= "hola" (:variant-error main')))))))))
