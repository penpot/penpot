;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.page-active-validation-test
  "Tests for the guard that prevents plugins from modifying shapes/properties that
  live on a page which is not the currently active one (see
  `app.plugins.utils/page-active?`).

  Strategy: build a rich page2 via the public API *while page2 is active*, then
  enable `throwValidationErrors` so rejections surface as exceptions.

  - Negative test: with page1 active, every guarded property/method invoked on a
    page2-bound proxy must throw.
  - Positive test: with page2 active, every setter (with a valid value) must NOT
    throw. This proves the values used are valid, so the negative throws can only
    come from the page guard."
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.util.object :as obj]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- activate-page!
  [store page-id]
  (ptk/emit! store #(assoc % :current-page-id page-id)))

(defn- throws?
  [thunk]
  (try (thunk) false (catch :default _ true)))

(defn- setup
  "Creates a file with two pages and builds, *on the non-active page2*, a set of
  proxies covering every guarded proxy type. Leaves page1 active with the
  `throwValidationErrors` flag enabled. Returns the proxies + store + page ids."
  []
  (let [file        (-> (cthf/sample-file :file1 :page-label :page1)
                        (cthf/add-sample-page :page2))
        store       (ths/setup-store file)
        _           (set! st/state store)
        _           (set! st/stream (ptk/input-stream store))
        ^js context (api/create-context plugin-id)
        pages       (.. context -currentFile -pages)
        page1-id    (obj/get (aget pages 0) "$id")
        ^js page2   (aget pages 1)
        page2-id    (obj/get page2 "$id")

        ;; Build everything with page2 active so the constructive API succeeds.
        _           (activate-page! store page2-id)

        ^js rect         (.createRectangle context)
        ^js rect2        (.createRectangle context)
        ^js board        (.createBoard context)
        ^js child        (.createRectangle context)
        _                (.appendChild board child)

        ^js flex-board   (.createBoard context)
        ^js flex         (.addFlexLayout flex-board)
        ^js flex-child   (.createRectangle context)
        _                (.appendChild flex-board flex-child)
        ^js layout-child (.-layoutChild flex-child)

        ^js grid-board   (.createBoard context)
        ^js grid         (.addGridLayout grid-board)

        ^js text         (.createText context "hello")
        ^js range        (.getRange text 0 1)

        ^js guide        (.addRulerGuide page2 "vertical" 10 board)

        ;; Back to page1: page2 proxies must now be read-only.
        _           (activate-page! store page1-id)
        _           (ptk/emit! store #(assoc-in % [:plugins :flags plugin-id :throw-validation-errors] true))]

    {:store store :page1-id page1-id :page2-id page2-id :context context :page2 page2
     :rect rect :rect2 rect2 :board board :child child
     :flex flex :flex-board flex-board :layout-child layout-child :flex-child flex-child
     :grid grid :grid-board grid-board
     :text text :range range :guide guide}))

(defn- setter-specs
  "Property setters (value mutations). Used by both the positive and negative
  tests, so they must be idempotent / non-destructive."
  [{:keys [^js rect ^js child ^js flex ^js grid ^js layout-child
           ^js range ^js guide ^js board ^js text]}]
  [;; ---- ShapeProxy ----
   ["ShapeProxy.name"                    #(set! (.-name rect) "X")]
   ["ShapeProxy.x"                       #(set! (.-x rect) 10)]
   ["ShapeProxy.y"                       #(set! (.-y rect) 10)]
   ["ShapeProxy.blocked"                 #(set! (.-blocked rect) true)]
   ["ShapeProxy.hidden"                  #(set! (.-hidden rect) true)]
   ["ShapeProxy.visible"                 #(set! (.-visible rect) false)]
   ["ShapeProxy.proportionLock"          #(set! (.-proportionLock rect) true)]
   ["ShapeProxy.constraintsHorizontal"   #(set! (.-constraintsHorizontal rect) "right")]
   ["ShapeProxy.constraintsVertical"     #(set! (.-constraintsVertical rect) "bottom")]
   ["ShapeProxy.borderRadius"            #(set! (.-borderRadius rect) 5)]
   ["ShapeProxy.borderRadiusTopLeft"     #(set! (.-borderRadiusTopLeft rect) 5)]
   ["ShapeProxy.borderRadiusTopRight"    #(set! (.-borderRadiusTopRight rect) 5)]
   ["ShapeProxy.borderRadiusBottomRight" #(set! (.-borderRadiusBottomRight rect) 5)]
   ["ShapeProxy.borderRadiusBottomLeft"  #(set! (.-borderRadiusBottomLeft rect) 5)]
   ["ShapeProxy.opacity"                 #(set! (.-opacity rect) 0.5)]
   ["ShapeProxy.blendMode"               #(set! (.-blendMode rect) "multiply")]
   ["ShapeProxy.shadows"                 #(set! (.-shadows rect) #js [#js {:style "drop-shadow" :color #js {:color "#000000" :opacity 1}}])]
   ["ShapeProxy.blur"                    #(set! (.-blur rect) #js {:value 10})]
   ["ShapeProxy.exports"                 #(set! (.-exports rect) #js [#js {:type "png" :scale 1 :suffix ""}])]
   ["ShapeProxy.flipX"                   #(set! (.-flipX rect) true)]
   ["ShapeProxy.flipY"                   #(set! (.-flipY rect) true)]
   ["ShapeProxy.rotation"                #(set! (.-rotation rect) 45)]
   ["ShapeProxy.fills"                   #(set! (.-fills rect) #js [#js {:fillColor "#fabada" :fillOpacity 1}])]
   ["ShapeProxy.strokes"                 #(set! (.-strokes rect) #js [#js {:strokeColor "#fabada" :strokeOpacity 1 :strokeWidth 2}])]
   ;; relative geometry (shape inside a board)
   ["ShapeProxy.boardX"                  #(set! (.-boardX child) 10)]
   ["ShapeProxy.boardY"                  #(set! (.-boardY child) 10)]
   ["ShapeProxy.parentX"                 #(set! (.-parentX child) 10)]
   ["ShapeProxy.parentY"                 #(set! (.-parentY child) 10)]
   ;; layout-item sizing (frame is a layout item)
   ["ShapeProxy.horizontalSizing"        #(set! (.-horizontalSizing board) "fix")]
   ["ShapeProxy.verticalSizing"          #(set! (.-verticalSizing board) "fix")]
   ;; text shape props (added via add-text-props)
   ["ShapeProxy.growType"                #(set! (.-growType text) "fixed")]
   ["ShapeProxy.verticalAlign"           #(set! (.-verticalAlign text) "center")]

   ;; ---- FlexLayoutProxy ----
   ["FlexLayoutProxy.dir"               #(set! (.-dir flex) "row")]
   ["FlexLayoutProxy.wrap"              #(set! (.-wrap flex) "wrap")]
   ["FlexLayoutProxy.alignItems"        #(set! (.-alignItems flex) "center")]
   ["FlexLayoutProxy.alignContent"      #(set! (.-alignContent flex) "center")]
   ["FlexLayoutProxy.justifyItems"      #(set! (.-justifyItems flex) "start")]
   ["FlexLayoutProxy.justifyContent"    #(set! (.-justifyContent flex) "center")]
   ["FlexLayoutProxy.rowGap"            #(set! (.-rowGap flex) 5)]
   ["FlexLayoutProxy.columnGap"         #(set! (.-columnGap flex) 5)]
   ["FlexLayoutProxy.verticalPadding"   #(set! (.-verticalPadding flex) 5)]
   ["FlexLayoutProxy.horizontalPadding" #(set! (.-horizontalPadding flex) 5)]
   ["FlexLayoutProxy.topPadding"        #(set! (.-topPadding flex) 5)]
   ["FlexLayoutProxy.rightPadding"      #(set! (.-rightPadding flex) 5)]
   ["FlexLayoutProxy.bottomPadding"     #(set! (.-bottomPadding flex) 5)]
   ["FlexLayoutProxy.leftPadding"       #(set! (.-leftPadding flex) 5)]

   ;; ---- LayoutChildProxy ----
   ["LayoutChildProxy.absolute"         #(set! (.-absolute layout-child) true)]
   ["LayoutChildProxy.zIndex"           #(set! (.-zIndex layout-child) 1)]
   ["LayoutChildProxy.horizontalSizing" #(set! (.-horizontalSizing layout-child) "fix")]
   ["LayoutChildProxy.verticalSizing"   #(set! (.-verticalSizing layout-child) "fix")]
   ["LayoutChildProxy.alignSelf"        #(set! (.-alignSelf layout-child) "center")]
   ["LayoutChildProxy.horizontalMargin" #(set! (.-horizontalMargin layout-child) 5)]
   ["LayoutChildProxy.verticalMargin"   #(set! (.-verticalMargin layout-child) 5)]
   ["LayoutChildProxy.topMargin"        #(set! (.-topMargin layout-child) 5)]
   ["LayoutChildProxy.rightMargin"      #(set! (.-rightMargin layout-child) 5)]
   ["LayoutChildProxy.bottomMargin"     #(set! (.-bottomMargin layout-child) 5)]
   ["LayoutChildProxy.leftMargin"       #(set! (.-leftMargin layout-child) 5)]
   ["LayoutChildProxy.maxWidth"         #(set! (.-maxWidth layout-child) 100)]
   ["LayoutChildProxy.maxHeight"        #(set! (.-maxHeight layout-child) 100)]
   ["LayoutChildProxy.minWidth"         #(set! (.-minWidth layout-child) 0)]
   ["LayoutChildProxy.minHeight"        #(set! (.-minHeight layout-child) 0)]

   ;; ---- GridLayoutProxy ----
   ["GridLayoutProxy.dir"               #(set! (.-dir grid) "row")]
   ["GridLayoutProxy.alignItems"        #(set! (.-alignItems grid) "center")]
   ["GridLayoutProxy.alignContent"      #(set! (.-alignContent grid) "center")]
   ["GridLayoutProxy.justifyItems"      #(set! (.-justifyItems grid) "start")]
   ["GridLayoutProxy.justifyContent"    #(set! (.-justifyContent grid) "center")]
   ["GridLayoutProxy.rowGap"            #(set! (.-rowGap grid) 5)]
   ["GridLayoutProxy.columnGap"         #(set! (.-columnGap grid) 5)]
   ["GridLayoutProxy.verticalPadding"   #(set! (.-verticalPadding grid) 5)]
   ["GridLayoutProxy.horizontalPadding" #(set! (.-horizontalPadding grid) 5)]
   ["GridLayoutProxy.topPadding"        #(set! (.-topPadding grid) 5)]
   ["GridLayoutProxy.rightPadding"      #(set! (.-rightPadding grid) 5)]
   ["GridLayoutProxy.bottomPadding"     #(set! (.-bottomPadding grid) 5)]
   ["GridLayoutProxy.leftPadding"       #(set! (.-leftPadding grid) 5)]

   ;; NOTE: GridCellProxy setters (row/column/rowSpan/columnSpan/areaName/position/
   ;; justifySelf/alignSelf) share the identical guard but require a child placed in
   ;; a grid cell, which cannot be fixtured in this headless test env
   ;; (move-shapes-to-frame is WASM-bound). They are verified by source review.

   ;; ---- TextRangeProxy ----
   ["TextRangeProxy.align"          #(set! (.-align range) "center")]
   ["TextRangeProxy.direction"      #(set! (.-direction range) "ltr")]
   ["TextRangeProxy.textTransform"  #(set! (.-textTransform range) "uppercase")]
   ["TextRangeProxy.textDecoration" #(set! (.-textDecoration range) "underline")]
   ["TextRangeProxy.fontSize"       #(set! (.-fontSize range) "16")]
   ["TextRangeProxy.lineHeight"     #(set! (.-lineHeight range) "1.2")]
   ["TextRangeProxy.letterSpacing"  #(set! (.-letterSpacing range) "1")]
   ["TextRangeProxy.fills"          #(set! (.-fills range) #js [#js {:fillColor "#fabada" :fillOpacity 1}])]

   ;; ---- RulerGuideProxy ----
   ["RulerGuideProxy.board"    #(set! (.-board guide) board)]
   ["RulerGuideProxy.color"    #(set! (.-color guide) "#fabada")]
   ["RulerGuideProxy.position" #(set! (.-position guide) 20)]])

(defn- method-specs
  "Structural / destructive methods. Tested in the negative direction only (they
  are rejected before performing any mutation), with trivially valid arguments."
  [{:keys [^js context ^js page2 ^js rect ^js rect2 ^js board ^js child
           ^js flex ^js grid ^js guide]}]
  [;; ---- ShapeProxy ----
   ["ShapeProxy.resize"              #(.resize rect 10 10)]
   ["ShapeProxy.rotate"             #(.rotate rect 45)]
   ["ShapeProxy.clone"              #(.clone rect)]
   ["ShapeProxy.remove"             #(.remove rect)]
   ["ShapeProxy.setParentIndex"     #(.setParentIndex child 0)]
   ["ShapeProxy.setPluginData"      #(.setPluginData rect "k" "v")]
   ["ShapeProxy.setSharedPluginData" #(.setSharedPluginData rect "ns" "k" "v")]
   ["ShapeProxy.appendChild"        #(.appendChild board rect)]
   ["ShapeProxy.insertChild"        #(.insertChild board 0 rect)]
   ["ShapeProxy.addFlexLayout"      #(.addFlexLayout board)]
   ["ShapeProxy.addGridLayout"      #(.addGridLayout board)]

   ;; ---- FlexLayoutProxy ----
   ["FlexLayoutProxy.appendChild"   #(.appendChild flex rect)]

   ;; ---- GridLayoutProxy ----
   ["GridLayoutProxy.appendChild"   #(.appendChild grid rect 1 1)]
   ["GridLayoutProxy.addRow"        #(.addRow grid "flex" 1)]
   ["GridLayoutProxy.addColumn"     #(.addColumn grid "flex" 1)]
   ["GridLayoutProxy.addRowAtIndex" #(.addRowAtIndex grid 0 "flex" 1)]
   ["GridLayoutProxy.addColumnAtIndex" #(.addColumnAtIndex grid 0 "flex" 1)]
   ["GridLayoutProxy.setRow"        #(.setRow grid 1 "flex" 1)]
   ["GridLayoutProxy.setColumn"     #(.setColumn grid 1 "flex" 1)]
   ["GridLayoutProxy.removeRow"     #(.removeRow grid 0)]
   ["GridLayoutProxy.removeColumn"  #(.removeColumn grid 0)]
   ["GridLayoutProxy.remove"        #(.remove grid)]

   ;; ---- GridCellProxy methods ----
   ;; (none beyond setters)

   ;; ---- RulerGuideProxy ----
   ["RulerGuideProxy.remove"        #(.remove guide)]

   ;; ---- Context ----
   ["context.group"                 #(.group context #js [rect rect2])]
   ["context.ungroup"               #(.ungroup context rect)]
   ["context.createBoolean"         #(.createBoolean context "union" #js [rect rect2])]

   ;; ---- PageProxy ----
   ["page.addRulerGuide"            #(.addRulerGuide page2 "vertical" 20 board)]
   ["page.removeRulerGuide"         #(.removeRulerGuide page2 guide)]])

(t/deftest test-all-setters-rejected-on-non-active-page
  (thw/with-wasm-mocks*
    (fn []
      (let [m (setup)]
        (doseq [[label thunk] (setter-specs m)]
          (t/is (throws? thunk) (str label " must be rejected on a non-active page")))))))

(t/deftest test-all-methods-rejected-on-non-active-page
  (thw/with-wasm-mocks*
    (fn []
      (let [m (setup)]
        (doseq [[label thunk] (method-specs m)]
          (t/is (throws? thunk) (str label " must be rejected on a non-active page")))))))

(t/deftest test-all-setters-allowed-on-active-page
  ;; Sanity / value-validation anchor: every setter uses a valid value, so when
  ;; page2 is active none of them must throw. This guarantees the negative test's
  ;; throws come from the page guard and not from value validation.
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [store page2-id] :as m} (setup)]
        (activate-page! store page2-id)
        (doseq [[label thunk] (setter-specs m)]
          (t/is (not (throws? thunk)) (str label " must be allowed on the active page")))))))

(t/deftest test-layout-gap-padding-accepts-fractional-values
  ;; Regression: the flex/grid gap and padding setters validated with
  ;; `valid-safe-int?`, but the layout model types `:row-gap`/`:column-gap` and
  ;; `:p1`-`:p4` as `safe-number` (and the sidebar accepts decimals), so a
  ;; fractional value was wrongly rejected. With `throwValidationErrors` on (set
  ;; by `setup`) and page2 active, a fractional value must be accepted (no throw).
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [store page2-id ^js flex ^js grid]} (setup)]
        (activate-page! store page2-id)
        (doseq [[label thunk]
                [["flex.rowGap"            #(set! (.-rowGap flex) 10.5)]
                 ["flex.columnGap"         #(set! (.-columnGap flex) 3.25)]
                 ["flex.verticalPadding"   #(set! (.-verticalPadding flex) 4.5)]
                 ["flex.horizontalPadding" #(set! (.-horizontalPadding flex) 4.5)]
                 ["flex.topPadding"        #(set! (.-topPadding flex) 1.5)]
                 ["flex.rightPadding"      #(set! (.-rightPadding flex) 1.5)]
                 ["flex.bottomPadding"     #(set! (.-bottomPadding flex) 1.5)]
                 ["flex.leftPadding"       #(set! (.-leftPadding flex) 1.5)]
                 ["grid.rowGap"            #(set! (.-rowGap grid) 7.5)]
                 ["grid.columnGap"         #(set! (.-columnGap grid) 2.25)]
                 ["grid.verticalPadding"   #(set! (.-verticalPadding grid) 4.5)]
                 ["grid.horizontalPadding" #(set! (.-horizontalPadding grid) 4.5)]
                 ["grid.topPadding"        #(set! (.-topPadding grid) 1.5)]
                 ["grid.rightPadding"      #(set! (.-rightPadding grid) 1.5)]
                 ["grid.bottomPadding"     #(set! (.-bottomPadding grid) 1.5)]
                 ["grid.leftPadding"       #(set! (.-leftPadding grid) 1.5)]]]
          (t/is (not (throws? thunk)) (str label " must accept a fractional value")))))))

(t/deftest test-border-radius-accepts-fractional-values
  ;; Regression: the ShapeProxy borderRadius setters validated with
  ;; `valid-safe-int?`, but the model types `:r1`-`:r4` as `safe-number` (and the
  ;; radius sidebar input has min 0, not integer-only), so a fractional radius was
  ;; wrongly rejected. With `throwValidationErrors` on and page2 active, a
  ;; fractional radius must be accepted (no throw).
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [store page2-id ^js rect]} (setup)]
        (activate-page! store page2-id)
        (doseq [[label thunk]
                [["borderRadius"            #(set! (.-borderRadius rect) 7.5)]
                 ["borderRadiusTopLeft"     #(set! (.-borderRadiusTopLeft rect) 2.5)]
                 ["borderRadiusTopRight"    #(set! (.-borderRadiusTopRight rect) 2.5)]
                 ["borderRadiusBottomRight" #(set! (.-borderRadiusBottomRight rect) 2.5)]
                 ["borderRadiusBottomLeft"  #(set! (.-borderRadiusBottomLeft rect) 2.5)]]]
          (t/is (not (throws? thunk)) (str label " must accept a fractional value")))))))

