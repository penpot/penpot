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
   [app.main.data.workspace.modifiers :as dwmm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.util.object :as obj]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/deftest test-common-shape-properties
  (thw/with-wasm-mocks*
    (fn []
      (let [;; ==== Setup
            store   (ths/setup-store (cthf/sample-file :file1 :page-label :page1))

            ^js context (api/create-context "00000000-0000-0000-0000-000000000000")

            _       (set! st/state store)

            ^js file    (. context -currentFile)
            ^js page    (. context -currentPage)
            ^js shape   (.createRectangle context)

            get-shape-path
            #(vector :files (aget file "$id") :data :pages-index (aget page "$id") :objects (aget shape "$id") %)

            gradient
            (fn []
              #js {:type "linear"
                   :startX 0.5
                   :startY 0
                   :endX 0.5
                   :endY 1
                   :width 1
                   :stops #js [#js {:color "#b400ff" :opacity 1 :offset 0}
                               #js {:color "#0c3fd5" :opacity 1 :offset 1}]})

            parsed-gradient
            {:type :linear
             :start-x 0.5
             :start-y 0
             :end-x 0.5
             :end-y 1
             :width 1
             :stops [{:color "#b400ff" :opacity 1 :offset 0}
                     {:color "#0c3fd5" :opacity 1 :offset 1}]}]

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
            (t/is (= (-> (. ^js shape -strokes) (aget 0) (aget "strokeWidth")) 5)))

          (t/testing " - fills per-element property mutation (bug #8357)"
            (set! (.-fills shape) #js [#js {:fillColor "#fabada" :fillOpacity 1}])
            (obj/set! (aget (.-fills shape) 0) "fillColor" "#ff0000")
            (t/is (= (get-in @store (get-shape-path :fills)) [{:fill-color "#ff0000" :fill-opacity 1}]))
            (t/is (= (-> (. shape -fills) (aget 0) (aget "fillColor")) "#ff0000")))

          (t/testing " - fills element replacement (bug #8357)"
            (set! (.-fills shape) #js [#js {:fillColor "#fabada" :fillOpacity 1}])
            (aset (.-fills shape) 0 #js {:fillColor "#00ff00" :fillOpacity 0.5})
            (t/is (= (get-in @store (get-shape-path :fills)) [{:fill-color "#00ff00" :fill-opacity 0.5}])))

          (t/testing " - fills push/pop (bug #8357)"
            (set! (.-fills shape) #js [#js {:fillColor "#fabada" :fillOpacity 1}])
            (.push (.-fills shape) #js {:fillColor "#00ff00" :fillOpacity 1})
            (t/is (= (get-in @store (get-shape-path :fills))
                     [{:fill-color "#fabada" :fill-opacity 1}
                      {:fill-color "#00ff00" :fill-opacity 1}]))
            (.pop (.-fills shape))
            (t/is (= (get-in @store (get-shape-path :fills)) [{:fill-color "#fabada" :fill-opacity 1}])))

          (t/testing " - fills gradient assignment replaces solid color (bug #8357)"
            (set! (.-fills shape) #js [#js {:fillColor "#fabada" :fillOpacity 1}])
            (obj/set! (aget (.-fills shape) 0) "fillColorGradient" (gradient))
            (t/is (= (get-in @store (get-shape-path :fills))
                     [{:fill-opacity 1 :fill-color-gradient parsed-gradient}]))
            (t/is (nil? (-> (. shape -fills) (aget 0) (aget "fillColor")))))

          (t/testing " - fills nested gradient mutation (bug #8357)"
            (set! (.-fills shape) #js [#js {:fillColorGradient (gradient) :fillOpacity 1}])
            (let [fill-gradient (-> (. shape -fills) (aget 0) (aget "fillColorGradient"))
                  stop          (-> fill-gradient (aget "stops") (aget 0))]
              (obj/set! fill-gradient "startX" 0.25)
              (obj/set! stop "color" "#ffffff")
              (t/is (= (get-in @store (get-shape-path :fills))
                       [{:fill-opacity 1
                         :fill-color-gradient (-> parsed-gradient
                                                  (assoc :start-x 0.25)
                                                  (assoc-in [:stops 0 :color] "#ffffff"))}]))))

          (t/testing " - strokes per-element property mutation (bug #8357)"
            (set! (.-strokes shape) #js [#js {:strokeColor "#fabada" :strokeOpacity 1 :strokeWidth 5}])
            (obj/set! (aget (.-strokes shape) 0) "strokeColor" "#0000ff")
            (t/is (= (get-in @store (get-shape-path :strokes)) [{:stroke-color "#0000ff" :stroke-opacity 1 :stroke-width 5}])))

          (t/testing " - strokes element replacement (bug #8357)"
            (set! (.-strokes shape) #js [#js {:strokeColor "#fabada" :strokeOpacity 1 :strokeWidth 5}])
            (aset (.-strokes shape) 0 #js {:strokeColor "#00ff00" :strokeOpacity 0.5 :strokeWidth 2})
            (t/is (= (get-in @store (get-shape-path :strokes)) [{:stroke-color "#00ff00" :stroke-opacity 0.5 :stroke-width 2}])))

          (t/testing " - strokes gradient assignment replaces solid color (bug #8357)"
            (set! (.-strokes shape) #js [#js {:strokeColor "#fabada" :strokeOpacity 1 :strokeWidth 5}])
            (obj/set! (aget (.-strokes shape) 0) "strokeColorGradient" (gradient))
            (t/is (= (get-in @store (get-shape-path :strokes))
                     [{:stroke-opacity 1 :stroke-width 5 :stroke-color-gradient parsed-gradient}])))

          (t/testing " - strokes nested gradient mutation (bug #8357)"
            (set! (.-strokes shape) #js [#js {:strokeColorGradient (gradient) :strokeOpacity 1 :strokeWidth 5}])
            (let [stroke-gradient (-> (. shape -strokes) (aget 0) (aget "strokeColorGradient"))
                  stop            (-> stroke-gradient (aget "stops") (aget 1))]
              (obj/set! stroke-gradient "endY" 0.75)
              (obj/set! stop "opacity" 0.25)
              (t/is (= (get-in @store (get-shape-path :strokes))
                       [{:stroke-opacity 1
                         :stroke-width 5
                         :stroke-color-gradient (-> parsed-gradient
                                                    (assoc :end-y 0.75)
                                                    (assoc-in [:stops 1 :opacity] 0.25))}])))))

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
        (t/testing "Remove")

        (t/testing "WASM mocks were exercised"
          (t/is (pos? (thw/call-count :clean-modifiers)))
          (t/is (pos? (thw/call-count :set-structure-modifiers)))
          (t/is (pos? (thw/call-count :propagate-modifiers))))))))

;; ---- waitForLayoutUpdate tests ------------------------------------------
;;
;; Each test sets up a fresh potok store and replaces st/state / st/stream so
;; that st/emit! and waitForLayoutUpdate work against the same event bus.
;; This avoids any cross-test contamination from the deftest above.

(defn- make-test-store
  "Creates a minimal potok store with an empty state map and installs it as the
  global st/state and st/stream for the duration of the calling test."
  []
  (let [test-store (ptk/store {:state {} :on-error (fn [e] (js/console.error e))})]
    (set! st/state test-store)
    (set! st/stream (ptk/input-stream test-store))
    test-store))

(t/deftest test-wait-for-layout-update-no-pending
  ;; When all pending flags are clear the promise should resolve immediately
  ;; via the fast path — no store event is needed.
  (t/async done
    (make-test-store)
    (reset! dwsl/layout-pending false)
    (reset! dwwt/resize-pending false)
    (reset! dwt/font-pending nil)
    (let [^js ctx (api/create-context "00000000-0000-0000-0000-000000000000")]
      (-> (.waitForLayoutUpdate ctx)
          (.then (fn []
                   (t/is true "resolved with no pending work")
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected rejection: " err))
                    (done)))))))

(t/deftest test-wait-for-layout-update-layout-pending
  ;; layout-pending=true signals a flex/grid recalculation in progress.
  ;; The promise should stay pending until ::update-layout-positions fires.
  (t/async done
    (make-test-store)
    (reset! dwsl/layout-pending true)
    (reset! dwwt/resize-pending false)
    (reset! dwt/font-pending nil)
    (let [^js ctx (api/create-context "00000000-0000-0000-0000-000000000000")]
      (-> (.waitForLayoutUpdate ctx)
          (.then (fn []
                   (t/is true "resolved after update-layout-positions event")
                   (reset! dwsl/layout-pending false)
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected rejection: " err))
                    (reset! dwsl/layout-pending false)
                    (done))))
      ;; The Promise constructor ran synchronously so the subscription is
      ;; already live on st/stream. Emitting now triggers the rx/filter.
      (st/emit! (ptk/reify ::dwsl/update-layout-positions)))))

(t/deftest test-wait-for-layout-update-resize-pending
  ;; resize-pending=true signals wasm text geometry being recalculated.
  ;; The promise should stay pending until ::apply-wasm-modifiers fires.
  (t/async done
    (make-test-store)
    (reset! dwsl/layout-pending false)
    (reset! dwwt/resize-pending true)
    (reset! dwt/font-pending nil)
    (let [^js ctx (api/create-context "00000000-0000-0000-0000-000000000000")]
      (-> (.waitForLayoutUpdate ctx)
          (.then (fn []
                   (t/is true "resolved after apply-wasm-modifiers event")
                   (reset! dwwt/resize-pending false)
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected rejection: " err))
                    (reset! dwwt/resize-pending false)
                    (done))))
      (st/emit! (ptk/reify ::dwmm/apply-wasm-modifiers)))))

(t/deftest test-wait-for-layout-update-font-pending
  ;; font-pending=true signals a non-wasm CSS font measurement in progress.
  ;; The promise should stay pending until ::commit-position-data fires.
  (t/async done
    (make-test-store)
    (reset! dwsl/layout-pending false)
    (reset! dwwt/resize-pending false)
    (reset! dwt/font-pending true)
    (let [^js ctx (api/create-context "00000000-0000-0000-0000-000000000000")]
      (-> (.waitForLayoutUpdate ctx)
          (.then (fn []
                   (t/is true "resolved after commit-position-data event")
                   (reset! dwt/font-pending nil)
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected rejection: " err))
                    (reset! dwt/font-pending nil)
                    (done))))
      (st/emit! (ptk/reify ::dwt/commit-position-data)))))

(t/deftest test-wait-for-layout-update-timeout
  ;; When the optional timeout fires before any completion event the promise
  ;; should reject with an Error whose message mentions "timeout".
  (t/async done
    (make-test-store)
    (reset! dwsl/layout-pending true)
    (reset! dwwt/resize-pending false)
    (reset! dwt/font-pending nil)
    (let [^js ctx (api/create-context "00000000-0000-0000-0000-000000000000")]
      (-> (.waitForLayoutUpdate ctx 20)
          (.then (fn []
                   (t/is false "expected rejection but promise resolved")
                   (reset! dwsl/layout-pending false)
                   (done)))
          (.catch (fn [^js err]
                    (t/is (instance? js/Error err) "rejection value should be an Error")
                    (t/is (some? (re-find #"timeout" (.-message err))) "error message should mention timeout")
                    (reset! dwsl/layout-pending false)
                    (done)))))))
