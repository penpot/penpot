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
   [app.common.types.container :as ctn]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file
  []
  (-> (thf/sample-file :file1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib #(-> %
                                  (ctob/add-set (ctob/make-token-set :id (thi/new-id! :test-token-set)
                                                                     :name "test-token-set"))
                                  (ctob/add-theme (ctob/make-token-theme :name "test-theme"
                                                                         :sets #{"test-token-set"}))
                                  (ctob/set-active-themes #{"/test-theme"})
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-radius)
                                                                   :name "token-radius"
                                                                   :type :border-radius
                                                                   :value 10))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-rotation)
                                                                   :name "token-rotation"
                                                                   :type :rotation
                                                                   :value 30))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-opacity)
                                                                   :name "token-opacity"
                                                                   :type :opacity
                                                                   :value 0.7))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-stroke-width)
                                                                   :name "token-stroke-width"
                                                                   :type :stroke-width
                                                                   :value 2))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-color)
                                                                   :name "token-color"
                                                                   :type :color
                                                                   :value "#00ff00"))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-dimensions)
                                                                   :name "token-dimensions"
                                                                   :type :dimensions
                                                                   :value 100))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-font-size)
                                                                   :name "token-font-size"
                                                                   :type :font-size
                                                                   :value 24))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-letter-spacing)
                                                                   :name "token-letter-spacing"
                                                                   :type :letter-spacing
                                                                   :value 2))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-font-family)
                                                                   :name "token-font-family"
                                                                   :type :font-family
                                                                   :value ["Helvetica" "Arial" "sans-serif"]))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-sizing)
                                                                   :name "token-sizing"
                                                                   :type :sizing
                                                                   :value 10))
                                  (ctob/add-token (thi/id :test-token-set)
                                                  (ctob/make-token :id (thi/new-id! :token-spacing)
                                                                   :name "token-spacing"
                                                                   :type :spacing
                                                                   :value 30))))
      (tho/add-frame :frame1
                     :layout                 :flex     ;; TODO: those values come from main.data.workspace.shape_layout/default-layout-params
                     :layout-flex-dir        :row      ;;       it should be good to use it directly, but first it should be moved to common.logic
                     :layout-gap-type        :multiple
                     :layout-gap             {:row-gap 0 :column-gap 0}
                     :layout-align-items     :start
                     :layout-justify-content :start
                     :layout-align-content   :stretch
                     :layout-wrap-type       :nowrap
                     :layout-padding-type    :simple
                     :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0})
      (ths/add-sample-shape :circle1 :parent-label :frame-1)
      (tho/add-text :text1 "Hello World!")))

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
      (tht/apply-token-to-shape :text1 "token-font-size" [:font-size] [:font-size] 24)
      (tht/apply-token-to-shape :text1 "token-letter-spacing" [:letter-spacing] [:letter-spacing] 2)
      (tht/apply-token-to-shape :text1 "token-font-family" [:font-family] [:font-family] ["Helvetica" "Arial" "sans-serif"])
      (tht/apply-token-to-shape :circle1
                                "token-sizing"
                                [:layout-item-max-h :layout-item-max-w :layout-item-min-h :layout-item-min-w]
                                [:layout-item-max-h :layout-item-max-w :layout-item-min-h :layout-item-min-w]
                                10)
      (tht/apply-token-to-shape :circle1
                                "token-spacing"
                                [:m1 :m2 :m3 :m4]
                                [:layout-item-margin]
                                {:m1 30 :m2 30 :m3 30 :m4 30})))

(t/deftest apply-tokens-to-shape
  (let [;; ==== Setup
        file                 (setup-file)
        page                 (thf/current-page file)
        frame1               (ths/get-shape file :frame1)
        text1                (ths/get-shape file :text1)
        circle1              (ths/get-shape file :circle1)
        token-radius         (tht/get-token file (thi/id :test-token-set) (thi/id :token-radius))
        token-rotation       (tht/get-token file (thi/id :test-token-set) (thi/id :token-rotation))
        token-opacity        (tht/get-token file (thi/id :test-token-set) (thi/id :token-opacity))
        token-stroke-width   (tht/get-token file (thi/id :test-token-set) (thi/id :token-stroke-width))
        token-color          (tht/get-token file (thi/id :test-token-set) (thi/id :token-color))
        token-dimensions     (tht/get-token file (thi/id :test-token-set) (thi/id :token-dimensions))
        token-font-size      (tht/get-token file (thi/id :test-token-set) (thi/id :token-font-size))
        token-letter-spacing (tht/get-token file (thi/id :test-token-set) (thi/id :token-letter-spacing))
        token-font-family    (tht/get-token file (thi/id :test-token-set) (thi/id :token-font-family))
        token-sizing         (tht/get-token file (thi/id :test-token-set) (thi/id :token-sizing))
        token-spacing        (tht/get-token file (thi/id :test-token-set) (thi/id :token-spacing))

        ;; ==== Action
        changes (-> (-> (pcb/empty-changes nil)
                        (pcb/with-page page)
                        (pcb/with-objects (:objects page)))
                    (cls/generate-update-shapes [(:id frame1)]
                                                (fn [shape]
                                                  (as-> shape $
                                                    (cto/apply-token-to-shape {:token token-radius
                                                                               :shape $
                                                                               :attributes [:r1 :r2 :r3 :r4]})
                                                    (cto/apply-token-to-shape {:token token-rotation
                                                                               :shape $
                                                                               :attributes [:rotation]})
                                                    (cto/apply-token-to-shape {:token token-opacity
                                                                               :shape $
                                                                               :attributes [:opacity]})
                                                    (cto/apply-token-to-shape {:token token-stroke-width
                                                                               :shape $
                                                                               :attributes [:stroke-width]})
                                                    (cto/apply-token-to-shape {:token token-color
                                                                               :shape $
                                                                               :attributes [:stroke-color]})
                                                    (cto/apply-token-to-shape {:token token-color
                                                                               :shape $
                                                                               :attributes [:fill]})
                                                    (cto/apply-token-to-shape {:token token-dimensions
                                                                               :shape $
                                                                               :attributes [:width :height]})))
                                                (:objects page)
                                                {})
                    (cls/generate-update-shapes [(:id text1)]
                                                (fn [shape]
                                                  (as-> shape $
                                                    (cto/apply-token-to-shape {:token token-font-size
                                                                               :shape $
                                                                               :attributes [:font-size]})
                                                    (cto/apply-token-to-shape {:token token-letter-spacing
                                                                               :shape $
                                                                               :attributes [:letter-spacing]})
                                                    (cto/apply-token-to-shape {:token token-font-family
                                                                               :shape $
                                                                               :attributes [:font-family]})))
                                                (:objects page)
                                                {})
                    (cls/generate-update-shapes [(:id circle1)]
                                                (fn [shape]
                                                  (as-> shape $
                                                    (cto/apply-token-to-shape {:token token-sizing
                                                                               :shape $
                                                                               :attributes [:layout-item-max-h :layout-item-max-w :layout-item-min-h :layout-item-min-w]})
                                                    (cto/apply-token-to-shape {:token token-spacing
                                                                               :shape $
                                                                               :attributes [:m1 :m2 :m3 :m4]})))
                                                (:objects page)
                                                {}))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        frame1'                  (ths/get-shape file' :frame1)
        frame1'-applied-tokens   (:applied-tokens frame1')
        text1'                   (ths/get-shape file' :text1)
        text1'-applied-tokens    (:applied-tokens text1')
        circle1'                 (ths/get-shape file' :circle1)
        circle1'-applied-tokens  (:applied-tokens circle1')]

    ;; ==== Check
    (t/is (= (count frame1'-applied-tokens) 11))
    (t/is (= (:r1 frame1'-applied-tokens) "token-radius"))
    (t/is (= (:r2 frame1'-applied-tokens) "token-radius"))
    (t/is (= (:r3 frame1'-applied-tokens) "token-radius"))
    (t/is (= (:r4 frame1'-applied-tokens) "token-radius"))
    (t/is (= (:rotation frame1'-applied-tokens) "token-rotation"))
    (t/is (= (:opacity frame1'-applied-tokens) "token-opacity"))
    (t/is (= (:stroke-width frame1'-applied-tokens) "token-stroke-width"))
    (t/is (= (:stroke-color frame1'-applied-tokens) "token-color"))
    (t/is (= (:fill frame1'-applied-tokens) "token-color"))
    (t/is (= (:width frame1'-applied-tokens) "token-dimensions"))
    (t/is (= (:height frame1'-applied-tokens) "token-dimensions"))

    (t/is (= (count text1'-applied-tokens) 3))
    (t/is (= (:font-size text1'-applied-tokens) "token-font-size"))
    (t/is (= (:letter-spacing text1'-applied-tokens) "token-letter-spacing"))
    (t/is (= (:font-family text1'-applied-tokens) "token-font-family"))

    (t/is (= (count circle1'-applied-tokens) 8))
    (t/is (= (:layout-item-max-h circle1'-applied-tokens) "token-sizing"))
    (t/is (= (:layout-item-min-h circle1'-applied-tokens) "token-sizing"))
    (t/is (= (:layout-item-max-w circle1'-applied-tokens) "token-sizing"))
    (t/is (= (:layout-item-min-w circle1'-applied-tokens) "token-sizing"))
    (t/is (= (:m1 circle1'-applied-tokens) "token-spacing"))
    (t/is (= (:m2 circle1'-applied-tokens) "token-spacing"))
    (t/is (= (:m3 circle1'-applied-tokens) "token-spacing"))
    (t/is (= (:m4 circle1'-applied-tokens) "token-spacing"))))

(t/deftest unapply-tokens-from-shape
  (let [;; ==== Setup
        file    (-> (setup-file)
                    (apply-all-tokens))
        page    (thf/current-page file)
        frame1  (ths/get-shape file :frame1)
        text1   (ths/get-shape file :text1)
        circle1 (ths/get-shape file :circle1)

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
                                                {})
                    (cls/generate-update-shapes [(:id text1)]
                                                (fn [shape]
                                                  (-> shape
                                                      (cto/unapply-token-id [:font-size])
                                                      (cto/unapply-token-id [:letter-spacing])
                                                      (cto/unapply-token-id [:font-family])))
                                                (:objects page)
                                                {})
                    (cls/generate-update-shapes [(:id circle1)]
                                                (fn [shape]
                                                  (-> shape
                                                      (cto/unapply-token-id [:layout-item-max-h :layout-item-min-h :layout-item-max-w :layout-item-min-w])
                                                      (cto/unapply-token-id [:m1 :m2 :m3 :m4])))
                                                (:objects page)
                                                {}))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        frame1'                 (ths/get-shape file' :frame1)
        frame1'-applied-tokens  (:applied-tokens frame1')
        text1'                  (ths/get-shape file' :text1)
        text1'-applied-tokens   (:applied-tokens text1')
        circle1'                (ths/get-shape file' :circle1)
        circle1'-applied-tokens (:applied-tokens circle1')]

    ;; ==== Check
    (t/is (= (count frame1'-applied-tokens) 0))
    (t/is (= (count text1'-applied-tokens) 0))
    (t/is (= (count circle1'-applied-tokens) 0))))

(t/deftest unapply-tokens-automatic
  (let [;; ==== Setup
        file    (-> (setup-file)
                    (apply-all-tokens))
        page    (thf/current-page file)
        frame1  (ths/get-shape file :frame1)
        text1   (ths/get-shape file :text1)
        circle1 (ths/get-shape file :circle1)

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
                                                   {:fills (ths/sample-fills-color :fill-color "#fabada")
                                                    :font-size "1"
                                                    :letter-spacing "0"
                                                    :font-family "Arial"}))
                                                (:objects page)
                                                {})
                    (cls/generate-update-shapes [(:id circle1)]
                                                (fn [shape]
                                                  (-> shape
                                                      (ctn/set-shape-attr :layout-item-max-h 0)
                                                      (ctn/set-shape-attr :layout-item-min-h 0)
                                                      (ctn/set-shape-attr :layout-item-max-w 0)
                                                      (ctn/set-shape-attr :layout-item-min-w 0)
                                                      (ctn/set-shape-attr :layout-item-margin {})))
                                                (:objects page)
                                                {}))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        frame1'                 (ths/get-shape file' :frame1)
        text1'                  (ths/get-shape file' :text1)
        circle1'                (ths/get-shape file' :circle1)
        applied-tokens-frame'   (:applied-tokens frame1')
        applied-tokens-text'    (:applied-tokens text1')
        applied-tokens-circle'  (:applied-tokens circle1')]

    ;; ==== Check
    (t/is (= (count applied-tokens-frame') 0))
    (t/is (= (count applied-tokens-text') 0))
    (t/is (= (count applied-tokens-circle') 0))))
