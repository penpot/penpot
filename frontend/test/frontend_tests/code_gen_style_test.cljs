;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.code-gen-style-test
  "Regression tests for the inspect code-generation (HTML/CSS export).

  Each test guards against a concrete bug found in the CSS/HTML generation
  of layout children and text shapes."
  (:require
   ["react-dom/server" :as rds]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.uuid :as uuid]
   [app.main.ui.shapes.text.fo-text :as fo-text]
   [app.util.code-gen.markup-html :as html]
   [app.util.code-gen.style-css :as css]
   [cljs.test :refer [deftest is testing] :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Builders ------------------------------------------------------------

(defn- pts
  "Rectangular point ring matching a x/y/w/h box."
  [x y w h]
  [(gpt/point x y)
   (gpt/point (+ x w) y)
   (gpt/point (+ x w) (+ y h))
   (gpt/point x (+ y h))])

(defn- frame
  [id & {:as extra}]
  (merge {:id id :name "Board" :type :frame
          :parent-id uuid/zero :frame-id uuid/zero
          :selrect (grc/make-rect 0 0 200 200)
          :points (pts 0 0 200 200)}
         extra))

(defn- grid-frame
  "A grid board with a single auto cell that holds `child-id`."
  [id child-id & {:as extra}]
  (let [cell-id (uuid/next)]
    (merge (frame id)
           {:name "Grid"
            :layout :grid
            :layout-grid-rows [{:type :flex :value 1}]
            :layout-grid-columns [{:type :flex :value 1}]
            :layout-grid-cells {cell-id {:id cell-id :row 1 :column 1
                                         :row-span 1 :column-span 1
                                         :position :auto
                                         :align-self :auto :justify-self :auto
                                         :shapes [child-id]}}}
           extra)))

(defn- child
  [id parent-id & {:as extra}]
  (merge {:id id :name "Child" :type :rect :parent-id parent-id
          :selrect (grc/make-rect 0 0 50 50)
          :points (pts 0 0 50 50)
          :transform (gmt/matrix)}
         extra))

(defn- objects [& shapes]
  (into {} (map (juxt :id identity)) shapes))

(def ^:private sample-margin
  {:m1 10 :m2 20 :m3 30 :m4 40})

;; --- Margins on layout children -----------------------------------------

(deftest grid-child-margins-use-logical-longhand
  (testing "margins of grid children map to logical longhand properties"
    (let [pid (uuid/next)
          cid (uuid/next)
          c   (child cid pid
                     :layout-item-margin sample-margin
                     :layout-item-margin-type :multiple)
          objs (objects (grid-frame pid cid) c)]
      (is (= "10px" (css/get-css-value objs c :margin-block-start)))
      (is (= "20px" (css/get-css-value objs c :margin-inline-end)))
      (is (= "30px" (css/get-css-value objs c :margin-block-end)))
      (is (= "40px" (css/get-css-value objs c :margin-inline-start))))))

(deftest grid-child-css-has-no-redundant-margin-shorthand
  (testing "the generated rule emits logical longhand margins, not a shorthand"
    (let [pid (uuid/next)
          cid (uuid/next)
          c   (child cid pid
                     :layout-item-margin sample-margin
                     :layout-item-margin-type :multiple)
          out (css/get-shape-css-selector (objects (grid-frame pid cid) c) c)]
      (is (str/includes? out "margin-block-start: 10px;"))
      (is (not (re-find #"margin:" out))
          "must not emit the `margin` shorthand alongside the longhand props"))))

(deftest wrapped-layout-child-margin-only-on-wrapper
  (testing "a rotated (wrapped) child keeps margins on the wrapper, not the inner element"
    (let [pid (uuid/next)
          cid (uuid/next)
          c   (child cid pid
                     :layout-item-margin {:m1 10 :m2 0 :m3 0 :m4 0}
                     :layout-item-margin-type :multiple
                     :transform (gmt/rotate-matrix 30))
          out (css/get-shape-css-selector (objects (grid-frame pid cid) c) c)
          ;; the inner element is the only rule carrying the transform matrix
          inner (->> (str/split out "}")
                     (filter #(str/includes? % "transform:"))
                     (first))]
      (is (str/includes? out "-wrapper {"))
      (is (str/includes? out "margin-block-start: 10px;")
          "margin is emitted (on the wrapper)")
      (is (some? inner))
      (is (not (str/includes? inner "margin"))
          "the inner transformed element must not double the margin"))))

(deftest absolute-positioned-child-has-no-margin
  (testing "absolutely positioned layout children drop their margin"
    (let [pid (uuid/next)
          cid (uuid/next)
          c   (child cid pid
                     :layout-item-margin sample-margin
                     :layout-item-margin-type :multiple
                     :layout-item-absolute true)
          objs (objects (grid-frame pid cid) c)]
      (is (nil? (css/get-css-value objs c :margin)))
      (is (nil? (css/get-css-value objs c :margin-block-start))))))

;; --- Grid fill sizing ----------------------------------------------------

(deftest grid-fill-child-stretches-instead-of-fixed-size
  (testing "fill-sized grid children rely on stretch instead of width/height: 100%"
    (let [pid (uuid/next)
          cid (uuid/next)
          c   (child cid pid
                     :layout-item-h-sizing :fill
                     :layout-item-v-sizing :fill)
          objs (objects (grid-frame pid cid) c)]
      (is (nil? (css/get-css-value objs c :width))
          "no explicit width: 100% that would overflow the cell with a margin")
      (is (nil? (css/get-css-value objs c :height)))
      (is (= "stretch" (css/get-css-value objs c :justify-self)))
      (is (= "stretch" (css/get-css-value objs c :align-self))))))

;; --- Absolute positioning vs parent border -------------------------------

(deftest absolute-position-discounts-parent-border
  (testing "absolute coords are measured from the padding box, so the parent border is discounted"
    (let [pid    (uuid/next)
          cid    (uuid/next)
          parent (frame pid :strokes [{:stroke-width 80
                                       :stroke-style :solid
                                       :stroke-color "#000000"
                                       :stroke-alignment :inner}])
          c      (child cid pid
                        :selrect (grc/make-rect 100 100 50 50)
                        :points (pts 100 100 50 50))
          objs   (objects parent c)]
      ;; left/top = 100 (shape) - 0 (parent) - 80 (border) = 20
      (is (= "20px" (css/get-css-value objs c :left)))
      (is (= "20px" (css/get-css-value objs c :top))))))

(deftest absolute-position-without-border-is-unchanged
  (testing "without a parent border the absolute coords are the raw offset"
    (let [pid  (uuid/next)
          cid  (uuid/next)
          c    (child cid pid
                      :selrect (grc/make-rect 100 100 50 50)
                      :points (pts 100 100 50 50))
          objs (objects (frame pid) c)]
      (is (= "100px" (css/get-css-value objs c :left)))
      (is (= "100px" (css/get-css-value objs c :top))))))

;; --- Text node markup ----------------------------------------------------

(def ^:private text-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text "Hello"
                                       :fills [{:fill-color "#000000" :fill-opacity 1}]}]}]}]})

(def ^:private ruby-text-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :writing-mode "vertical-rl"
                           :text-orientation "upright"
                           :children [{:text "漢字"
                                       :ruby "かんじ"
                                       :font-size "20"
                                       :fill-color "#000000"
                                       :fill-opacity 1}]}]}]})

(defn- text-shape
  [content]
  (let [tid (uuid/next)]
    {:id tid :name "Text" :type :text
     :parent-id uuid/zero :frame-id uuid/zero
     :x 0 :y 0 :width 100 :height 40
     :selrect (grc/make-rect 0 0 100 40)
     :points (pts 0 0 100 40)
     :grow-type :fixed
     :content content}))

(deftest text-markup-emits-node-id-classes
  (testing "generated text markup carries the $id classes the CSS rules target"
    (let [tid  (uuid/next)
          text {:id tid :name "Text" :type :text
                :parent-id uuid/zero :frame-id uuid/zero
                :selrect (grc/make-rect 0 0 100 20)
                :points (pts 0 0 100 20)
                :grow-type :fixed
                :content text-content}
          markup (html/generate-markup (objects text) [text])]
      (is (string? markup))
      (is (str/includes? markup "root-0")
          "the text nodes must expose their $id as a class for the CSS to apply")
      (is (str/includes? markup "root-0-paragraph-set-0-paragraph-0")))))

(def ^:private warichu-text-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :writing-mode "vertical-rl"
                           :text-orientation "upright"
                           :children [{:text "割注入り"
                                       :warichu "warichu"
                                       :font-size "20"
                                       :fill-color "#000000"
                                       :fill-opacity 1}]}]}]})

(def ^:private palt-text-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text "かな"
                                       :font-features "palt"
                                       :font-size "20"
                                       :fill-color "#000000"
                                       :fill-opacity 1}]}]}]})

(deftest foreign-object-text-emits-warichu-styles
  (testing "browser/foreignObject render folds a warichu span into two half-size lines"
    (let [text   (text-shape warichu-text-content)
          markup (rds/renderToStaticMarkup
                  (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (str/includes? markup "割注入り"))
      (is (str/includes? markup "display:inline-block"))
      (is (str/includes? markup "font-size:10px"))
      (is (str/includes? markup "inline-size:2em")))))

(deftest foreign-object-warichu-sizes-by-unicode-code-points
  (testing "non-BMP characters count as one slot when sizing warichu lines"
    (let [content (assoc-in warichu-text-content
                            [:children 0 :children 0 :children 0 :text]
                            "割𠀀注😀")
          text    (text-shape content)
          markup  (rds/renderToStaticMarkup
                   (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (str/includes? markup "割𠀀注😀"))
      (is (str/includes? markup "inline-size:2em")))))

(def ^:private tcy-digits2-text-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :writing-mode "vertical-rl"
                           :children [{:text "平成31年"
                                       :text-combine-upright "digits2"
                                       :font-size "20"
                                       :fill-color "#000000"
                                       :fill-opacity 1}]}]}]})

(deftest foreign-object-text-emits-counted-digits-css
  (testing "the counted digits variants serialize as CSS `digits <n>`"
    (let [text   (text-shape tcy-digits2-text-content)
          markup (rds/renderToStaticMarkup
                  (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (str/includes? markup "text-combine-upright:digits 2")))))

(deftest foreign-object-text-emits-font-feature-settings
  (testing "browser/foreignObject render emits palt/vpal as OpenType features"
    (let [text   (text-shape palt-text-content)
          markup (rds/renderToStaticMarkup
                  (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (str/includes? markup "font-feature-settings:&quot;palt&quot;")))))

(deftest text-markup-emits-ruby-annotations
  (testing "generated text markup keeps ruby annotations beside the base text"
    (let [text   (text-shape ruby-text-content)
          markup (html/generate-markup (objects text) [text])]
      (is (str/includes? markup "<ruby"))
      (is (str/includes? markup "<rt"))
      (is (str/includes? markup "漢字"))
      (is (str/includes? markup "かんじ")))))

(deftest foreign-object-text-emits-ruby-annotations
  (testing "browser/foreignObject text render keeps ruby annotations"
    (let [text   (text-shape ruby-text-content)
          markup (rds/renderToStaticMarkup
                  (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (str/includes? markup "<ruby"))
      (is (str/includes? markup "<rt"))
      (is (str/includes? markup "漢字"))
      (is (str/includes? markup "かんじ")))))
