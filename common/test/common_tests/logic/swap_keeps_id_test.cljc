;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.swap-keeps-id-test
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))


(t/deftest test-swap-keeps-id
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)

                      (tho/add-frame :frame-rectangle)
                      (ths/add-sample-shape :rectangle-shape :parent-label :frame-rectangle :type :rect)
                      (thc/make-component :rectangle :frame-rectangle)

                      (tho/add-frame :frame-circle)
                      (ths/add-sample-shape :circle :parent-label :frame-circle :type :circle)
                      (thc/make-component :circle :frame-circle)

                      (thc/instantiate-component :rectangle :copy01))

        copy   (ths/get-shape file :copy01)

        ;; ==== Action
        file'     (tho/swap-component file copy :circle {:new-shape-label :copy02 :keep-touched? true})

        copy'     (ths/get-shape file' :copy02)]
    ;; Both copies have the same id
    (t/is (= (:id copy) (:id copy')))))
