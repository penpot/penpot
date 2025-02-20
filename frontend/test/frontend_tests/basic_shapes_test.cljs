;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.basic-shapes-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.shapes :as dwsh]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/deftest test-update-shape
  (t/async
    done
    (let [;; ==== Setup
          store
          (ths/setup-store
           (-> (cthf/sample-file :file1 :page-label :page1)
               (cths/add-sample-shape :shape1)))

          ;; ==== Action
          events
          [(dwsh/update-shapes [(cthi/id :shape1)]
                               #(assoc % :fills
                                       (cths/sample-fills-color :fill-color
                                                                "#fabada")))]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               objects (dsh/lookup-page-objects new-state)
               shape1' (get objects (cthi/id :shape1))
               fills'  (:fills shape1')
               fill'   (first fills')]

           ;; ==== Check
           (t/is (some? shape1'))
           (t/is (= (count fills') 1))
           (t/is (= (:fill-color fill') "#fabada"))
           (t/is (= (:fill-opacity fill') 1))))))))

(t/deftest test-update-stroke-color
  ;; Old shapes without stroke-alignment are rendered as if it is centered
  (t/async
    done
    (let [;; ==== Setup
          store
          (ths/setup-store
           (-> (cthf/sample-file :file1 :page-label :page1)
               (cths/add-sample-shape :shape1 :strokes [{:stroke-color "#000000"
                                                         :stroke-opacity 1
                                                         :stroke-width 2}])))

          ;; ==== Action
          events
          [(dc/change-stroke-color #{(cthi/id :shape1)} {:color "#FABADA"} 0)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               objects (dsh/lookup-page-objects new-state)
               shape1' (get objects (cthi/id :shape1))
               stroke' (first (:strokes shape1'))]

           ;; ==== Check
           ;; (println stroke')
           (t/is (some? shape1'))
           (t/is (= (:stroke-alignment stroke') :inner))
           (t/is (= (:stroke-color stroke') "#FABADA"))
           (t/is (= (:stroke-width stroke') 2))))))))
