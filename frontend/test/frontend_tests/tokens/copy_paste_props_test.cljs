;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.tokens.copy-paste-props-test
  (:require
   [app.common.types.shape :as cts]
   [cljs.test :as t :include-macros true]))

(defn- rect
  [attrs]
  (merge {:type :rect
          :fills [{:fill-color "#111111" :fill-opacity 1}]
          :r1 4 :r2 4 :r3 4 :r4 4
          :opacity 1}
         attrs))

(defn- content
  [node]
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [node]}]}]})

(defn- text
  [attrs]
  (merge {:type :text
          :opacity 1
          :content (content {:text "hello"
                             :font-size "14"
                             :fills [{:fill-color "#111111" :fill-opacity 1}]})}
         attrs))

(defn- paste-props
  [source target]
  (cts/patch-props target (cts/extract-props source) {}))

;; ---------------------------------------------------------------------------
;; Token references must survive a copy/paste of properties
;; ---------------------------------------------------------------------------

(t/deftest copy-props-keeps-token-references
  (let [source (rect {:fills [{:fill-color "#ff0000" :fill-opacity 1}]
                      :r1 12
                      :applied-tokens {:fill "brand.primary" :r1 "radius.sm"}})
        target (rect {})
        result (paste-props source target)]

    (t/testing "the fill token reference is pasted, not only its resolved value"
      (t/is (= "brand.primary" (get-in result [:applied-tokens :fill]))))

    (t/testing "the border radius token reference is pasted"
      (t/is (= "radius.sm" (get-in result [:applied-tokens :r1]))))

    (t/testing "the resolved values are still pasted"
      (t/is (= [{:fill-color "#ff0000" :fill-opacity 1}] (:fills result)))
      (t/is (= 12 (:r1 result))))))

(t/deftest paste-props-removes-stale-target-tokens
  (let [source (rect {:fills [{:fill-color "#ff0000" :fill-opacity 1}]})
        target (rect {:applied-tokens {:fill "brand.secondary"}})
        result (paste-props source target)]

    (t/testing "a token-less source clears the token of the overwritten property"
      (t/is (nil? (get-in result [:applied-tokens :fill]))))))

(t/deftest paste-props-keeps-target-tokens-out-of-the-copied-domain
  (let [source (rect {:applied-tokens {:fill "brand.primary"}})
        target (rect {:applied-tokens {:width "size.lg"}})
        result (paste-props source target)]

    (t/testing "tokens of properties that are not copied are left untouched"
      (t/is (= "size.lg" (get-in result [:applied-tokens :width]))))

    (t/testing "tokens of copied properties are replaced"
      (t/is (= "brand.primary" (get-in result [:applied-tokens :fill]))))))

(t/deftest copy-props-ignores-tokens-of-non-copied-properties
  (let [source (rect {:applied-tokens {:width "size.lg" :height "size.lg"}})
        props  (cts/extract-props source)
        result (cts/patch-props (rect {}) props {})]

    (t/testing "sizing tokens are not copied because sizing is not a copied property"
      (t/is (nil? (get-in result [:applied-tokens :width])))
      (t/is (nil? (get-in result [:applied-tokens :height]))))))

(t/deftest copy-props-without-tokens-adds-no-empty-payload
  (let [source (rect {})
        props  (cts/extract-props source)
        result (cts/patch-props (rect {}) props {})]

    (t/testing "no empty :applied-tokens is introduced in the copied props"
      (t/is (not (contains? props :applied-tokens))))

    (t/testing "no empty :applied-tokens is introduced in the pasted shape"
      (t/is (not (contains? result :applied-tokens))))))

(t/deftest copy-props-keeps-layout-tokens-between-layout-frames
  (let [source (rect {:type :frame
                      :layout :flex
                      :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}
                      :layout-gap {:row-gap 4 :column-gap 4}
                      :applied-tokens {:p1 "spacing.md" :row-gap "spacing.sm"}})
        target (rect {:type :frame :layout :flex})
        result (paste-props source target)]

    (t/testing "padding and gap tokens travel with the layout properties"
      (t/is (= "spacing.md" (get-in result [:applied-tokens :p1])))
      (t/is (= "spacing.sm" (get-in result [:applied-tokens :row-gap])))
      (t/is (= {:p1 8 :p2 8 :p3 8 :p4 8} (:layout-padding result))))))

;; ---------------------------------------------------------------------------
;; A token may only leave the target when the value it resolves is overwritten
;; ---------------------------------------------------------------------------

(t/deftest paste-props-keeps-tokens-of-margin-edges-the-source-does-not-set
  (let [source (rect {:layout-item-margin {:m1 8 :m3 8}})
        target (rect {:layout-item-margin {:m4 16}
                      :applied-tokens {:m4 "spacing.md"}})
        result (paste-props source target)]

    (t/testing "the edges the source does not set keep their value"
      (t/is (= {:m1 8 :m3 8 :m4 16} (:layout-item-margin result))))

    (t/testing "and so do the tokens that resolve them"
      (t/is (= "spacing.md" (get-in result [:applied-tokens :m4]))))))

(t/deftest copy-props-ignores-tokens-of-margin-edges-the-source-does-not-set
  (let [source (rect {:layout-item-margin {:m1 8}
                      :applied-tokens {:m1 "spacing.sm" :m2 "spacing.lg"}})
        target (rect {:layout-item-margin {:m2 2}})
        result (paste-props source target)]

    (t/testing "the edge the source does not set keeps its value"
      (t/is (= {:m1 8 :m2 2} (:layout-item-margin result))))

    (t/testing "so its token does not travel either"
      (t/is (nil? (get-in result [:applied-tokens :m2]))))

    (t/testing "the token of the edge the source does set travels"
      (t/is (= "spacing.sm" (get-in result [:applied-tokens :m1]))))))

(t/deftest paste-props-keeps-gap-tokens-the-source-does-not-set
  (let [source (rect {:type :frame
                      :layout :flex
                      :layout-gap {:column-gap 4}})
        target (rect {:type :frame
                      :layout :flex
                      :layout-gap {:row-gap 2 :column-gap 2}
                      :applied-tokens {:row-gap "gap.row" :column-gap "gap.column"}})
        result (paste-props source target)]

    (t/testing "the gap axis the source does not set keeps its value"
      (t/is (= {:row-gap 2 :column-gap 4} (:layout-gap result))))

    (t/testing "and so does the token that resolves it"
      (t/is (= "gap.row" (get-in result [:applied-tokens :row-gap]))))

    (t/testing "the overwritten axis loses its stale token"
      (t/is (nil? (get-in result [:applied-tokens :column-gap]))))))

(t/deftest paste-props-keeps-padding-tokens-the-source-does-not-set
  (let [source (rect {:type :frame
                      :layout :flex
                      :layout-padding {:p1 8}})
        target (rect {:type :frame
                      :layout :flex
                      :layout-padding {:p1 1 :p3 3}
                      :applied-tokens {:p1 "padding.top" :p3 "padding.bottom"}})
        result (paste-props source target)]

    (t/testing "the padding edge the source does not set keeps its value"
      (t/is (= {:p1 8 :p3 3} (:layout-padding result))))

    (t/testing "and so does the token that resolves it"
      (t/is (= "padding.bottom" (get-in result [:applied-tokens :p3]))))

    (t/testing "the overwritten edge loses its stale token"
      (t/is (nil? (get-in result [:applied-tokens :p1]))))))

(t/deftest paste-props-keeps-tokens-of-properties-the-source-does-not-carry
  (let [source {:type :circle
                :fills [{:fill-color "#ff0000" :fill-opacity 1}]
                :opacity 1}
        target (rect {:shadow [{:color {:color "#000000"}}]
                      :layout-item-margin {:m1 5 :m2 5 :m3 5 :m4 5}
                      :applied-tokens {:r1 "radius.sm"
                                       :m1 "spacing.xs"
                                       :shadow "shadow.md"}})
        result (paste-props source target)]

    (t/testing "the values the source does not carry are untouched"
      (t/is (= 4 (:r1 result)))
      (t/is (= {:m1 5 :m2 5 :m3 5 :m4 5} (:layout-item-margin result))))

    (t/testing "and so are the tokens that resolve them"
      (t/is (= "radius.sm" (get-in result [:applied-tokens :r1])))
      (t/is (= "spacing.xs" (get-in result [:applied-tokens :m1])))
      (t/is (= "shadow.md" (get-in result [:applied-tokens :shadow]))))))

(t/deftest paste-props-keeps-layout-tokens-of-a-frame-when-the-source-has-no-layout
  (let [source (rect {:applied-tokens {:fill "brand.primary"}})
        target (rect {:type :frame
                      :layout :flex
                      :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}
                      :layout-gap {:row-gap 4 :column-gap 4}
                      :applied-tokens {:p1 "spacing.md" :row-gap "spacing.sm"}})
        result (paste-props source target)]

    (t/testing "the layout values are untouched because the source has none"
      (t/is (= {:p1 8 :p2 8 :p3 8 :p4 8} (:layout-padding result)))
      (t/is (= {:row-gap 4 :column-gap 4} (:layout-gap result))))

    (t/testing "and so are the padding and gap tokens"
      (t/is (= "spacing.md" (get-in result [:applied-tokens :p1])))
      (t/is (= "spacing.sm" (get-in result [:applied-tokens :row-gap]))))

    (t/testing "the copied fill token is still applied"
      (t/is (= "brand.primary" (get-in result [:applied-tokens :fill]))))))

;; ---------------------------------------------------------------------------
;; Text shapes carry their fill inside the content nodes
;; ---------------------------------------------------------------------------

(t/deftest copy-props-of-a-text-shape-carries-the-fill-token
  (let [source (text {:content (content {:text "hello"
                                         :font-size "14"
                                         :fills [{:fill-color "#ff0000" :fill-opacity 1}]})
                      :applied-tokens {:fill "brand.primary"
                                       :font-size "font.lg"}})
        props  (cts/extract-props source)
        result (cts/patch-props (rect {:applied-tokens {:fill "brand.old"}}) props {})]

    (t/testing "the fill of the text content is copied"
      (t/is (= [{:fill-color "#ff0000" :fill-opacity 1}] (:fills props))))

    (t/testing "so is the fill token that resolves it"
      (t/is (= "brand.primary" (get-in result [:applied-tokens :fill]))))

    (t/testing "typography tokens are not applied to a shape without text content"
      (t/is (nil? (get-in result [:applied-tokens :font-size]))))))

(t/deftest paste-props-on-a-text-target-replaces-the-fill-token
  (let [source (rect {:fills [{:fill-color "#ff0000" :fill-opacity 1}]
                      :applied-tokens {:fill "brand.primary"}})
        target (text {:applied-tokens {:fill "brand.old"}})
        result (paste-props source target)]

    (t/testing "the fill of the text content is overwritten"
      (t/is (= [{:fill-color "#ff0000" :fill-opacity 1}]
               (get-in result [:content :children 0 :children 0 :children 0 :fills]))))

    (t/testing "so the stale fill token cannot survive"
      (t/is (= "brand.primary" (get-in result [:applied-tokens :fill]))))))

(t/deftest paste-props-keeps-typography-tokens-of-a-text-target-when-the-source-is-not-text
  (let [source (rect {:applied-tokens {:fill "brand.primary"}})
        target (text {:applied-tokens {:font-size "font.lg" :fill "brand.old"}})
        result (paste-props source target)]

    (t/testing "the font size of the text content is untouched"
      (t/is (= "14" (get-in result [:content :children 0 :children 0 :children 0 :font-size]))))

    (t/testing "and so is the typography token that resolves it"
      (t/is (= "font.lg" (get-in result [:applied-tokens :font-size]))))))

(t/deftest copy-props-of-a-text-shape-does-not-apply-typography-to-a-non-text-target
  (let [source (text {:applied-tokens {:font-size "font.lg" :opacity "opacity.50"}})
        target (rect {})
        result (paste-props source target)]

    (t/testing "typography tokens of a text source are not applied to a non text target"
      (t/is (nil? (get-in result [:applied-tokens :font-size]))))

    (t/testing "tokens shared by both shapes are applied"
      (t/is (= "opacity.50" (get-in result [:applied-tokens :opacity]))))))
