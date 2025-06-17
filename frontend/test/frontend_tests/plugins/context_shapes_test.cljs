;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.context-shapes-test
  (:require
   [app.common.math :as m]
   [app.common.test-helpers.files :as cthf]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/deftest test-common-shape-properties
  (let [;; ==== Setup
        store   (ths/setup-store (cthf/sample-file :file1 :page-label :page1))

        ^js context (api/create-context "TEST")

        _       (set! st/state store)

        ^js file    (. context -currentFile)
        ^js page    (. context -currentPage)
        ^js shape   (.createRectangle context)

        get-shape-path
        #(vector :files (aget file "$id") :data :pages-index (aget page "$id") :objects (aget shape "$id") %)]

    (t/testing "Basic shape properites"
      (t/testing " - name"
        (set! (.-name shape) "TEST")
        (t/is (= (.-name shape) "TEST"))
        (t/is (= (get-in @store (get-shape-path :name)) "TEST")))

      (t/testing " - x"
        (set! (.-x shape) 10)
        (t/is (= (.-x shape) 10))
        (t/is (= (get-in @store (get-shape-path :x)) 10))

        (set! (.-x shape) "fail")
        (t/is (= (.-x shape) 10))
        (t/is (= (get-in @store (get-shape-path :x)) 10)))

      (t/testing " - y"
        (set! (.-y shape) 50)
        (t/is (= (.-y shape) 50))
        (t/is (= (get-in @store (get-shape-path :y)) 50))

        (set! (.-y shape) "fail")
        (t/is (= (.-y shape) 50))
        (t/is (= (get-in @store (get-shape-path :y)) 50)))

      (t/testing " - resize"
        (.resize shape 250 300)
        (t/is (= (.-width shape) 250))
        (t/is (= (.-height shape) 300))
        (t/is (= (get-in @store (get-shape-path :width)) 250))
        (t/is (= (get-in @store (get-shape-path :height)) 300))

        (.resize shape 0 0)
        (t/is (= (.-width shape) 250))
        (t/is (= (.-height shape) 300))
        (t/is (= (get-in @store (get-shape-path :width)) 250))
        (t/is (= (get-in @store (get-shape-path :height)) 300)))

      (t/testing " - blocked"
        (set! (.-blocked shape) true)
        (t/is (= (.-blocked shape) true))
        (t/is (= (get-in @store (get-shape-path :blocked)) true))

        (set! (.-blocked shape) false)
        (t/is (= (.-blocked shape) false))
        (t/is (= (get-in @store (get-shape-path :blocked)) false)))

      (t/testing " - hidden"
        (set! (.-hidden shape) true)
        (t/is (= (.-hidden shape) true))
        (t/is (= (get-in @store (get-shape-path :hidden)) true))

        (set! (.-hidden shape) false)
        (t/is (= (.-hidden shape) false))
        (t/is (= (get-in @store (get-shape-path :hidden)) false)))

      (t/testing " - proportionLock"
        (set! (.-proportionLock shape) true)
        (t/is (= (.-proportionLock shape) true))
        (t/is (= (get-in @store (get-shape-path :proportion-lock)) true)))

      (t/testing " - constraintsHorizontal"
        (set! (.-constraintsHorizontal shape) "fail")
        (t/is (not= (.-constraintsHorizontal shape) "fail"))
        (t/is (not= (get-in @store (get-shape-path :constraints-h)) "fail"))

        (set! (.-constraintsHorizontal shape) "right")
        (t/is (= (.-constraintsHorizontal shape) "right"))
        (t/is (= (get-in @store (get-shape-path :constraints-h)) :right)))

      (t/testing " - constraintsVertical"
        (set! (.-constraintsVertical shape) "fail")
        (t/is (not= (.-constraintsVertical shape) "fail"))
        (t/is (not= (get-in @store (get-shape-path :constraints-v)) "fail"))

        (set! (.-constraintsVertical shape) "bottom")
        (t/is (= (.-constraintsVertical shape) "bottom"))
        (t/is (= (get-in @store (get-shape-path :constraints-v)) :bottom)))

      (t/testing " - borderRadius"
        (set! (.-borderRadius shape) 10)
        (t/is (= (.-borderRadius shape) 10))
        (t/is (= (get-in @store (get-shape-path :r1)) 10))

        (set! (.-borderRadiusTopLeft shape) 20)
        (t/is (= (.-borderRadiusTopLeft shape) 20))
        (t/is (= (get-in @store (get-shape-path :r1)) 20))
        (t/is (= (get-in @store (get-shape-path :r2)) 10))
        (t/is (= (get-in @store (get-shape-path :r3)) 10))
        (t/is (= (get-in @store (get-shape-path :r4)) 10))

        (set! (.-borderRadiusTopRight shape) 30)
        (set! (.-borderRadiusBottomRight shape) 40)
        (set! (.-borderRadiusBottomLeft shape) 50)
        (t/is (= (.-borderRadiusTopRight shape) 30))
        (t/is (= (.-borderRadiusBottomRight shape) 40))
        (t/is (= (.-borderRadiusBottomLeft shape) 50))

        (t/is (= (get-in @store (get-shape-path :r1)) 20))
        (t/is (= (get-in @store (get-shape-path :r2)) 30))
        (t/is (= (get-in @store (get-shape-path :r3)) 40))
        (t/is (= (get-in @store (get-shape-path :r4)) 50)))

      (t/testing " - opacity"
        (set! (.-opacity shape) 0.5)
        (t/is (= (.-opacity shape) 0.5))
        (t/is (= (get-in @store (get-shape-path :opacity)) 0.5)))

      (t/testing " - blendMode"
        (set! (.-blendMode shape) "multiply")
        (t/is (= (.-blendMode shape) "multiply"))
        (t/is (= (get-in @store (get-shape-path :blend-mode)) :multiply))

        (set! (.-blendMode shape) "fail")
        (t/is (= (.-blendMode shape) "multiply"))
        (t/is (= (get-in @store (get-shape-path :blend-mode)) :multiply)))

      (t/testing " - shadows"
        (let [shadow #js {:style "drop-shadow"
                          :color #js {:color "#FABADA" :opacity 1}}]
          (set! (.-shadows shape) #js [shadow])
          (let [shadow-id (uuid/uuid (aget (aget (aget shape "shadows") 0) "id"))]
            (t/is (= (-> (. shape -shadows) (aget 0) (aget "style")) "drop-shadow"))
            (t/is (= (get-in @store (get-shape-path :shadow)) [{:id shadow-id
                                                                :style :drop-shadow
                                                                :offset-x 4
                                                                :offset-y 4
                                                                :blur 4
                                                                :spread 0
                                                                :color {:color "#fabada" :opacity 1}
                                                                :hidden false}]))))
        (let [shadow #js {:style "fail"}]
          (set! (.-shadows shape) #js [shadow])
          (t/is (= (-> (. shape -shadows) (aget 0) (aget "style")) "drop-shadow"))))

      (t/testing " - blur"
        (set! (.-blur shape) #js {:value 10})
        (t/is (= (-> (. shape -blur) (aget "type")) "layer-blur"))
        (t/is (= (-> (. shape -blur) (aget "value")) 10))
        (t/is (= (-> (. shape -blur) (aget "hidden")) false))
        (let [id (-> (. shape -blur) (aget "id") uuid/uuid)]
          (t/is (= (get-in @store (get-shape-path :blur)) {:id id :type :layer-blur :value 10 :hidden false}))))

      (t/testing " - exports"
        (set! (.-exports shape) #js [#js {:type "pdf" :scale 2 :suffix "test"}])
        (t/is (= (-> (. shape -exports) (aget 0) (aget "type")) "pdf"))
        (t/is (= (-> (. shape -exports) (aget 0) (aget "scale")) 2))
        (t/is (= (-> (. shape -exports) (aget 0) (aget "suffix")) "test"))
        (t/is (= (get-in @store (get-shape-path :exports)) [{:type :pdf :scale 2 :suffix "test" :skip-children false}]))

        (set! (.-exports shape) #js [#js {:type 10 :scale 2 :suffix "test"}])
        (t/is (= (get-in @store (get-shape-path :exports)) [{:type :pdf :scale 2 :suffix "test" :skip-children false}])))

      (t/testing " - flipX"
        (set! (.-flipX shape) true)
        (t/is (= (.-flipX shape) true))
        (t/is (= (get-in @store (get-shape-path :flip-x)) true)))

      (t/testing " - flipY"
        (set! (.-flipY shape) true)
        (t/is (= (.-flipY shape) true))
        (t/is (= (get-in @store (get-shape-path :flip-y)) true)))

      (t/testing " - rotation"
        (set! (.-rotation shape) 45)
        (t/is (= (.-rotation shape) 45))
        (t/is (= (get-in @store (get-shape-path :rotation)) 45))

        (set! (.-rotation shape) 0)
        (t/is (= (.-rotation shape) 0))
        (t/is (= (get-in @store (get-shape-path :rotation)) 0)))

      (t/testing " - fills"
        (set! (.-fills shape) #js [#js {:fillColor 100}])
        (t/is (= (get-in @store (get-shape-path :fills)) [{:fill-color "#B1B2B5" :fill-opacity 1}]))
        (t/is (= (-> (. shape -fills) (aget 0) (aget "fillColor")) "#B1B2B5"))

        (set! (.-fills shape) #js [#js {:fillColor "#fabada" :fillOpacity 1}])
        (t/is (= (get-in @store (get-shape-path :fills)) [{:fill-color "#fabada" :fill-opacity 1}]))
        (t/is (= (-> (. shape -fills) (aget 0) (aget "fillColor")) "#fabada"))
        (t/is (= (-> (. shape -fills) (aget 0) (aget "fillOpacity")) 1)))

      (t/testing " - strokes"
        (set! (.-strokes shape) #js [#js {:strokeColor "#fabada" :strokeOpacity 1 :strokeWidth 5}])
        (t/is (= (get-in @store (get-shape-path :strokes)) [{:stroke-color "#fabada" :stroke-opacity 1 :stroke-width 5}]))
        (t/is (= (-> (. ^js shape -strokes) (aget 0) (aget "strokeColor")) "#fabada"))
        (t/is (= (-> (. ^js shape -strokes) (aget 0) (aget "strokeOpacity")) 1))
        (t/is (= (-> (. ^js shape -strokes) (aget 0) (aget "strokeWidth")) 5))))

    (t/testing "Relative properties"
      (let [board (.createBoard context)]
        (set! (.-x board) 100)
        (set! (.-y board) 200)
        (t/is (= (.-x board) 100))
        (t/is (= (.-y board) 200))
        (.appendChild board shape)

        (t/testing " - boardX"
          (set! (.-boardX ^js shape) 10)
          (t/is (m/close? (.-boardX ^js shape) 10))
          (t/is (m/close? (.-x shape) 110))
          (t/is (m/close? (get-in @store (get-shape-path :x)) 110)))

        (t/testing " - boardY"
          (set! (.-boardY ^js shape) 20)
          (t/is (m/close? (.-boardY  ^js shape) 20))
          (t/is (m/close? (.-y shape) 220))
          (t/is (m/close? (get-in @store (get-shape-path :y)) 220)))

        (t/testing " - parentX"
          (set! (.-parentX ^js shape) 30)
          (t/is (m/close? (.-parentX ^js shape) 30))
          (t/is (m/close? (.-x shape) 130))
          (t/is (m/close? (get-in @store (get-shape-path :x)) 130)))

        (t/testing " - parentY"
          (set! (.-parentY ^js shape) 40)
          (t/is (m/close? (.-parentY ^js shape) 40))
          (t/is (m/close? (.-y shape) 240))
          (t/is (m/close? (get-in @store (get-shape-path :y)) 240)))))

    (t/testing "Clone")
    (t/testing "Remove")))
