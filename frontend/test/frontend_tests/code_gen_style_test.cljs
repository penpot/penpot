;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.main.ui.shapes.text.html-text :as html-text]
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

;; --- Text lists ----------------------------------------------------------

(defn- list-paragraph
  [text list-style-type]
  (cond-> {:type "paragraph"
           :children [{:text text
                       :font-size "20"
                       :fills [{:fill-color "#ff0000" :fill-opacity 1}]}]}
    (some? list-style-type)
    (assoc :list-style-type list-style-type)))

(defn- text-shape-with
  [paragraphs]
  {:id (uuid/next) :name "Text" :type :text
   :parent-id uuid/zero :frame-id uuid/zero
   :selrect (grc/make-rect 0 0 100 20)
   :points (pts 0 0 100 20)
   :grow-type :fixed
   :content {:type "root"
             :children [{:type "paragraph-set" :children paragraphs}]}})

(defn- block-tags
  "The p/ul/li tags of `markup`, in order."
  [markup]
  (->> (re-seq #"<(/?)(p|ul|li)[ >]" markup)
       (map (fn [[_ close tag]] (str close tag)))))

(deftest text-markup-groups-list-paragraphs-into-lists
  (let [text   (text-shape-with [(list-paragraph "a" nil)
                                 (list-paragraph "b" "disc")
                                 (list-paragraph "c" "disc")
                                 (list-paragraph "d" "none")
                                 (list-paragraph "e" "disc")])
        markup (html/generate-markup (objects text) [text])]
    (is (= ["p" "/p" "ul" "li" "/li" "li" "/li" "/ul" "p" "/p" "ul" "li" "/li" "/ul"]
           (block-tags markup)))
    (testing "list items keep the paragraph class and direction"
      (is (str/includes? markup "<li dir=\"auto\" class=\"root-0-paragraph-set-0-paragraph-1\">")))))

(deftest text-markup-without-lists-has-no-list-tags
  (let [text   (text-shape-with [(list-paragraph "a" nil) (list-paragraph "b" "none")])
        markup (html/generate-markup (objects text) [text])]
    (is (= ["p" "/p" "p" "/p"] (block-tags markup)))))

(deftest text-css-styles-list-items
  (let [text (text-shape-with [(list-paragraph "a" nil)
                               (list-paragraph "b" "disc")])
        css  (css/generate-text-css text)]
    (testing "the list item rule sets the list style"
      (is (re-find #"(?s)\.root-0-paragraph-set-0-paragraph-1 \{[^}]*list-style-type: disc;" css))
      (is (not (re-find #"(?s)\.root-0-paragraph-set-0-paragraph-0 \{[^}]*list-style-type" css))))
    (testing "the marker gets the first span's size and color"
      (is (re-find #"(?s)\.root-0-paragraph-set-0-paragraph-1::marker \{[^}]*font-size: 20px;[^}]*color: rgba\(255, 0, 0, 1\);" css)))
    (testing "the list drops the browser's spacing"
      (is (= 1 (count (re-seq #" ul \{" css))))
      (is (re-find #"(?s) ul \{\s*margin: 0;\s*padding: 0;\s*\}" css)))))

(deftest text-css-without-lists-has-no-list-rules
  (let [css (css/generate-text-css (text-shape-with [(list-paragraph "a" nil)]))]
    (is (not (str/includes? css "list-style")))
    (is (not (str/includes? css " ul {")))
    (is (not (str/includes? css "::marker")))))

(deftest rendered-text-keeps-paragraphs-for-lists
  (testing "outside code mode list paragraphs still render as <p>"
    (let [text   (text-shape-with [(list-paragraph "b" "disc")])
          markup (rds/renderToStaticMarkup
                  (mf/element html-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (= ["p" "/p"] (block-tags markup))))))

(defn- marker-rule
  "The ::marker rule generated for a single list item made of `span`."
  [span]
  (let [css (css/generate-text-css
             (text-shape-with [{:type "paragraph"
                                :list-style-type "disc"
                                :children [span]}]))]
    (re-find #"(?s)::marker \{[^}]*\}" css)))

(deftest text-css-colors-the-marker-like-its-text
  (testing "a half-opaque fill keeps its opacity"
    (is (str/includes? (marker-rule {:text "a" :font-size "20"
                                     :fills [{:fill-color "#ff0000" :fill-opacity 0.5}]})
                       "color: rgba(255, 0, 0, 0.5);")))
  (testing "a gradient fill uses its first stop"
    (is (str/includes? (marker-rule {:text "a" :font-size "20"
                                     :fills [{:fill-color-gradient
                                              {:type :linear
                                               :start-x 0 :start-y 0 :end-x 1 :end-y 1 :width 1
                                               :stops [{:color "#00ff00" :opacity 0.8 :offset 0}
                                                       {:color "#0000ff" :opacity 1 :offset 1}]}}]})
                       "color: rgba(0, 255, 0, 0.8);")))
  (testing "a legacy span keeps its color on the node"
    (is (str/includes? (marker-rule {:text "a" :font-size "20"
                                     :fill-color "#0000ff" :fill-opacity 1})
                       "color: rgba(0, 0, 255, 1);")))
  (testing "a span without a font size falls back to the default"
    (is (str/includes? (marker-rule {:text "a"
                                     :fills [{:fill-color "#ff0000" :fill-opacity 1}]})
                       "font-size: 14px;"))))

(deftest text-markup-keeps-the-direction-of-list-items
  (let [text   (text-shape-with [(assoc (list-paragraph "a" "disc") :text-direction "rtl")])
        markup (html/generate-markup (objects text) [text])]
    (is (str/includes? markup "<li dir=\"rtl\""))))

