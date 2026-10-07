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

(deftest foreign-object-text-combines-each-digit-run
  (testing "browsers lack CSS `digits <n>`, so each digit run gets its own `all` span"
    (let [text   (text-shape tcy-digits2-text-content)
          markup (rds/renderToStaticMarkup
                  (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))]
      (is (re-find #"<span[^>]*style=\"text-combine-upright:all\">31</span>" markup))
      (is (not (str/includes? markup "digits"))))))

(deftest generated-code-combines-each-digit-run
  (testing "generated markup marks digit runs and the CSS combines them"
    (let [text   (text-shape tcy-digits2-text-content)
          markup (html/generate-markup (objects text) [text])
          css    (css/generate-text-css text)]
      (is (re-find #"<span class=\"tcy\"\s*>31</span>" markup))
      (is (re-find #"\.tcy \{\s*text-combine-upright: all" css))
      (is (not (str/includes? css "digits"))))))

(deftest generated-css-keeps-the-writing-mode
  (testing "code keeps vertical writing whatever renderer is active"
    (let [css (css/generate-text-css (text-shape ruby-text-content))]
      (is (str/includes? css "writing-mode: vertical-rl"))
      (is (str/includes? css "text-orientation: upright")))))

(deftest ruby-text-drops-base-only-styles
  (testing "the reading does not inherit emphasis marks or font features"
    (let [content (update-in ruby-text-content [:children 0 :children 0 :children 0]
                             assoc :text-emphasis "filled-dot" :font-features "palt")
          markup  (rds/renderToStaticMarkup
                   (mf/element fo-text/text-shape* #js {:shape (text-shape content) :grow-type :fixed}))
          rt      (re-find #"<rt[^>]*>" markup)]
      (is (not (str/includes? rt "text-emphasis")))
      (is (not (str/includes? rt "font-feature-settings"))))))

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

(deftest foreign-object-ruby-text-has-a-pixel-size
  (testing "ruby text takes a pixel size from its base, since paragraphs set font-size 0"
    (let [content (assoc-in ruby-text-content
                            [:children 0 :children 0 :children 0 :ruby-size]
                            "quarter")
          text    (text-shape content)
          markup  (rds/renderToStaticMarkup
                   (mf/element fo-text/text-shape* #js {:shape text :grow-type :fixed}))
          rt      (re-find #"<rt[^>]*>" markup)]
      (is (str/includes? rt "font-size:5px")))))

(deftest generated-css-styles-ruby-annotations
  (testing "generated CSS has rules for the ruby wrapper and its annotation"
    (let [text   (text-shape ruby-text-content)
          markup (html/generate-markup (objects text) [text])
          css    (css/generate-text-css text)
          ruby-class (second (re-find #"<ruby[^>]*class=\"[^\"]*?([^\" ]+-ruby)\"" markup))
          rt-class   (second (re-find #"<rt[^>]*class=\"([^\"]+)\"" markup))
          rule   (fn [class]
                   (second (re-find (re-pattern (str "\\." class " \\{([^}]*)\\}")) css)))]
      (is (some? ruby-class))
      (is (some? rt-class))
      (is (re-find #"ruby-position:\s*over" (or (rule ruby-class) "")))
      (is (re-find #"font-size:\s*10px" (or (rule rt-class) ""))))))

(deftest generated-css-styles-ruby-only-on-text-nodes
  (testing "a paragraph that stores the reading as a default gets no ruby rules"
    (let [content (assoc-in ruby-text-content
                            [:children 0 :children 0 :ruby]
                            "かんじ")
          css     (css/generate-text-css (text-shape content))]
      (is (not (str/includes? css "paragraph-0-ruby")))
      (is (not (str/includes? css "paragraph-0-rt"))))))

(deftest generated-css-defines-the-paragraph-line-height-for-annotation-room
  (testing "auto clearance builds on the paragraph line height, so code keeps the variable"
    (let [content (-> ruby-text-content
                      (assoc-in [:children 0 :children 0 :line-height] "2")
                      (assoc-in [:children 0 :children 0 :children 0 :annotation-clearance] "auto"))
          css     (css/generate-text-css (text-shape content))]
      (is (re-find #"--paragraph-line-height:\s*2" css))
      (is (re-find #"line-height:\s*calc\(var\(--paragraph-line-height" css))
      (is (not (str/includes? css "--fills")))
      (is (not (str/includes? css "--font-id"))))))
