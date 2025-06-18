;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.token-apply-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.text :as txt]
   [app.common.types.container :as ctn]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file
  []
  (-> (thf/sample-file :file1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                                  (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                         :sets #{"test-token-set"}))
                                  (ctob/set-active-themes #{"/test-theme"})
                                  (ctob/add-token-in-set "test-token-set"
                                                         (ctob/make-token :name "token-radius"
                                                                          :type :border-radius
                                                                          :value 10))
                                  (ctob/add-token-in-set "test-token-set"
                                                         (ctob/make-token :name "token-rotation"
                                                                          :type :rotation
                                                                          :value 30))
                                  (ctob/add-token-in-set "test-token-set"
                                                         (ctob/make-token :name "token-opacity"
                                                                          :type :opacity
                                                                          :value 0.7))
                                  (ctob/add-token-in-set "test-token-set"
                                                         (ctob/make-token :name "token-stroke-width"
                                                                          :type :stroke-width
                                                                          :value 2))
                                  (ctob/add-token-in-set "test-token-set"
                                                         (ctob/make-token :name "token-color"
                                                                          :type :color
                                                                          :value "#00ff00"))
                                  (ctob/add-token-in-set "test-token-set"
                                                         (ctob/make-token :name "token-dimensions"
                                                                          :type :dimensions
                                                                          :value 100))))
      (tho/add-frame :frame1)
      (tho/add-text :text1 "Hello World")))

(defn- apply-all-tokens
  [file]
  (-> file
      (tht/apply-token-to-shape :frame1 "token-radius" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
      (tht/apply-token-to-shape :frame1 "token-rotation" [:rotation] [:rotation] 30)
      (tht/apply-token-to-shape :frame1 "token-opacity" [:opacity] [:opacity] 0.7)
      (tht/apply-token-to-shape :frame1 "token-stroke-width" [:stroke-width] [:stroke-width] 2)
      (tht/apply-token-to-shape :frame1 "token-color" [:stroke-color] [:stroke-color] "#00ff00")
      (tht/apply-token-to-shape :frame1 "token-color" [:fill] [:fill] "#00ff00")
      (tht/apply-token-to-shape :frame1 "token-dimensions" [:width :height] [:width :height] 100)
      (tht/apply-token-to-shape :text1 "token-color" [:fill] [:fill] "#00ff00")))

(t/deftest apply-tokens-to-shape
  (let [;; ==== Setup
        file               (setup-file)
        page               (thf/current-page file)
        frame1             (ths/get-shape file :frame1)
        token-radius       (tht/get-token file "test-token-set" "token-radius")
        token-rotation     (tht/get-token file "test-token-set" "token-rotation")
        token-opacity      (tht/get-token file "test-token-set" "token-opacity")
        token-stroke-width (tht/get-token file "test-token-set" "token-stroke-width")
        token-color        (tht/get-token file "test-token-set" "token-color")
        token-dimensions   (tht/get-token file "test-token-set" "token-dimensions")

        ;; ==== Action
        changes (-> (-> (pcb/empty-changes nil)
                        (pcb/with-page page)
                        (pcb/with-objects (:objects page)))
                    (cls/generate-update-shapes [(:id frame1)]
                                                (fn [shape]
                                                  (as-> shape $
                                                    (cto/maybe-apply-token-to-shape {:token nil ; test nil case
                                                                                     :shape $
                                                                                     :attributes []})
                                                    (cto/maybe-apply-token-to-shape {:token token-radius
                                                                                     :shape $
                                                                                     :attributes [:r1 :r2 :r3 :r4]})
                                                    (cto/maybe-apply-token-to-shape {:token token-rotation
                                                                                     :shape $
                                                                                     :attributes [:rotation]})
                                                    (cto/maybe-apply-token-to-shape {:token token-opacity
                                                                                     :shape $
                                                                                     :attributes [:opacity]})
                                                    (cto/maybe-apply-token-to-shape {:token token-stroke-width
                                                                                     :shape $
                                                                                     :attributes [:stroke-width]})
                                                    (cto/maybe-apply-token-to-shape {:token token-color
                                                                                     :shape $
                                                                                     :attributes [:stroke-color]})
                                                    (cto/maybe-apply-token-to-shape {:token token-color
                                                                                     :shape $
                                                                                     :attributes [:fill]})
                                                    (cto/maybe-apply-token-to-shape {:token token-dimensions
                                                                                     :shape $
                                                                                     :attributes [:width :height]})))
                                                (:objects page)
                                                {}))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        frame1'         (ths/get-shape file' :frame1)
        applied-tokens' (:applied-tokens frame1')]

    ;; ==== Check
    (t/is (= (count applied-tokens') 11))
    (t/is (= (:r1 applied-tokens') "token-radius"))
    (t/is (= (:r2 applied-tokens') "token-radius"))
    (t/is (= (:r3 applied-tokens') "token-radius"))
    (t/is (= (:r4 applied-tokens') "token-radius"))
    (t/is (= (:rotation applied-tokens') "token-rotation"))
    (t/is (= (:opacity applied-tokens') "token-opacity"))
    (t/is (= (:stroke-width applied-tokens') "token-stroke-width"))
    (t/is (= (:stroke-color applied-tokens') "token-color"))
    (t/is (= (:fill applied-tokens') "token-color"))
    (t/is (= (:width applied-tokens') "token-dimensions"))
    (t/is (= (:height applied-tokens') "token-dimensions"))))

(t/deftest unapply-tokens-from-shape
  (let [;; ==== Setup
        file    (-> (setup-file)
                    (apply-all-tokens))
        page    (thf/current-page file)
        frame1  (ths/get-shape file :frame1)

        ;; ==== Action
        changes (-> (-> (pcb/empty-changes nil)
                        (pcb/with-page page)
                        (pcb/with-objects (:objects page)))
                    (cls/generate-update-shapes [(:id frame1)]
                                                (fn [shape]
                                                  (-> shape
                                                      (cto/unapply-token-id [:r1 :r2 :r3 :r4])
                                                      (cto/unapply-token-id [:rotation])
                                                      (cto/unapply-token-id [:opacity])
                                                      (cto/unapply-token-id [:stroke-width])
                                                      (cto/unapply-token-id [:stroke-color])
                                                      (cto/unapply-token-id [:fill])
                                                      (cto/unapply-token-id [:width :height])))
                                                (:objects page)
                                                {}))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        frame1'         (ths/get-shape file' :frame1)
        applied-tokens' (:applied-tokens frame1')]

    ;; ==== Check
    (t/is (= (count applied-tokens') 0))))

(t/deftest unapply-tokens-automatic
  (let [;; ==== Setup
        file    (-> (setup-file)
                    (apply-all-tokens))
        page    (thf/current-page file)
        frame1  (ths/get-shape file :frame1)
        text1   (ths/get-shape file :text1)

        ;; ==== Action
        changes (-> (-> (pcb/empty-changes nil)
                        (pcb/with-page page)
                        (pcb/with-objects (:objects page)))
                    (cls/generate-update-shapes [(:id frame1)]
                                                (fn [shape]
                                                  (-> shape
                                                      (ctn/set-shape-attr :r1 0)
                                                      (ctn/set-shape-attr :r2 0)
                                                      (ctn/set-shape-attr :r3 0)
                                                      (ctn/set-shape-attr :r4 0)
                                                      (ctn/set-shape-attr :rotation 0)
                                                      (ctn/set-shape-attr :opacity 0)
                                                      (ctn/set-shape-attr :strokes [])
                                                      (ctn/set-shape-attr :fills [])
                                                      (ctn/set-shape-attr :width 0)
                                                      (ctn/set-shape-attr :height 0)))
                                                (:objects page)
                                                {})
                    (cls/generate-update-shapes [(:id text1)]
                                                (fn [shape]
                                                  (txt/update-text-content
                                                   shape
                                                   txt/is-content-node?
                                                   d/txt-merge
                                                   {:fills (ths/sample-fills-color :fill-color "#fabada")}))
                                                (:objects page)
                                                {}))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        frame1'               (ths/get-shape file' :frame1)
        text1'                (ths/get-shape file' :text1)
        applied-tokens-frame' (:applied-tokens frame1')
        applied-tokens-text'  (:applied-tokens text1')]

    ;; ==== Check
    (t/is (= (count applied-tokens-frame') 0))
    (t/is (= (count applied-tokens-text') 0))))