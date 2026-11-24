;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.text-selection-menu-test
  (:require
   ["react-dom/server" :as rds]
   [app.main.ui.workspace.shapes.text.selection-menu :as selection-menu]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- render
  "Static markup of the menu over a selection whose top edge is `top`
  canvas units below the viewport top, with `annotations`."
  [top annotations]
  (rds/renderToStaticMarkup
   (mf/element @#'selection-menu/menu*
               (mf/props {:shape-id (random-uuid)
                          :rect {:x 100 :y top :width 40 :height 20}
                          :vbox {:x 0 :y 0}
                          :zoom 1
                          :family :japanese
                          :annotations annotations
                          :ruby-values {}
                          :zhuyin false
                          :vertical true
                          :readable true
                          :ruby-enabled true
                          :warichu-enabled true}))))

(t/deftest annotations-tell-whether-the-selection-has-cjk-options
  (t/is (false? (selection-menu/annotated? {:ruby "" :emphasis "none"})))
  (t/is (false? (selection-menu/annotated? {})))
  (t/is (true? (selection-menu/annotated? {:ruby "ねこ"})))
  (t/is (true? (selection-menu/annotated? {:emphasis "filled-dot"})))
  (t/is (true? (selection-menu/annotated? {:warichu "warichu"})))
  (t/is (true? (selection-menu/annotated? {:combine "all"}))))

(t/deftest the-menu-starts-as-an-icon-at-the-selection-corner
  (let [markup (render 200 {:emphasis "none"})]
    (t/is (str/includes? markup "data-testid=\"selection-menu-trigger\""))
    (t/is (not (str/includes? markup "role=\"toolbar\""))
          "the toolbar opens on hover or click")
    (t/is (str/includes? markup "left:140px"))
    (t/is (str/includes? markup "top:200px"))
    (t/is (not (str/includes? markup "selection-menu-dot")))))

(t/deftest the-icon-shows-a-dot-when-the-selection-has-options
  (t/is (str/includes? (render 200 {:ruby "ねこ"}) "selection-menu-dot")))

(t/deftest the-icon-shows-below-without-room-above
  (let [markup (render 10 {})]
    (t/is (str/includes? markup "top:30px") "anchored at the bottom right corner")
    (t/is (str/includes? markup "selection-anchor-below"))))

(defn- render-toolbar
  "Static markup of the toolbar for a `family` text, its readings zhuyin
  when `zhuyin` is true."
  [family zhuyin]
  (rds/renderToStaticMarkup
   (mf/element @#'selection-menu/toolbar*
               (mf/props {:shape-id (random-uuid)
                          :family family
                          :zhuyin zhuyin
                          :annotations {}
                          :readable true
                          :ruby-enabled true
                          :warichu-enabled true
                          :emphasis-open false
                          :on-emphasis-open identity
                          :on-ruby identity}))))

(t/deftest reading-item-shows-the-glyph-of-the-language
  (t/is (str/includes? (render-toolbar :japanese false) "あ"))
  (let [pinyin (render-toolbar :chinese false)
        zhuyin (render-toolbar :chinese true)]
    (t/is (str/includes? pinyin "拼"))
    (t/is (not (str/includes? pinyin "あ")))
    (t/is (str/includes? zhuyin "ㄅ"))
    (t/is (not (str/includes? zhuyin "あ")))))

(defn- render-ruby-input
  "Static markup of the reading input, with a more button when `on-more`."
  [on-more]
  (rds/renderToStaticMarkup
   (mf/element @#'selection-menu/ruby-input*
               (mf/props {:label "reading"
                          :reading ""
                          :more-label "workspace.text-selection-menu.pinyin-options"
                          :more-open false
                          :on-more on-more
                          :on-apply identity
                          :on-back identity}))))

(t/deftest reading-input-shows-its-options-button-only-with-on-more
  (let [markup (render-ruby-input identity)]
    (t/is (str/includes? markup "data-testid=\"selection-menu-ruby-options\""))
    (t/is (str/includes? markup "aria-label=\"workspace.text-selection-menu.pinyin-options\"")))
  (t/is (not (str/includes? (render-ruby-input nil) "selection-menu-ruby-options"))
        "zhuyin has no presentation options"))
