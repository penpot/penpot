;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.logic.update-position-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace :as dw]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/deftest test-update-positions-multiple-ids
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (ctho/add-rect :rect1 :x 10 :y 20 :width 10 :height 10)
                     (ctho/add-rect :rect2 :x 30 :y 40 :width 10 :height 10))
          store  (ths/setup-store file)
          rect1  (cths/get-shape file :rect1)
          rect2  (cths/get-shape file :rect2)
          ids    [(:id rect1) (:id rect2)]
          events [(dw/update-positions ids {:x 123.45})]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file' (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)
               rect2' (cths/get-shape file' :rect2)
               x1     (-> rect1' :points grc/points->rect :x)
               x2     (-> rect2' :points grc/points->rect :x)]
           (t/is (= 123.45 x1))
           (t/is (= 123.45 x2))))))))
