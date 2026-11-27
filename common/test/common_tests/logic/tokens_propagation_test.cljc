;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.tokens-propagation-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.math :as mth]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- resolved
  "Resolved tokens map from a map of token name to resolved value."
  [values]
  (into {} (map (fn [[name value]] [name {:name name :resolved-value value}])) values))

(defn- apply-tokens
  [file label applied]
  (ths/update-shape file label :applied-tokens applied))

(defn- propagation-changes
  [file targets values & {:as opts}]
  (let [page (thf/current-page file)]
    (-> (pcb/empty-changes nil (:id page))
        (pcb/with-page page)
        (cltp/generate-token-propagation (:objects page) (resolved values) targets opts))))

(defn- propagate
  [file values & {:as opts}]
  (let [page    (thf/current-page file)
        targets (tht/applied-targets (:objects page))]
    (thf/apply-changes file (propagation-changes file targets values opts))))

(defn- text-nodes
  [shape]
  (txt/node-seq txt/is-text-node? (:content shape)))

(t/deftest radius-tokens
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (apply-tokens :rect1 {:r1 "radius" :r2 "radius" :r3 "negative" :r4 "missing"}))
        file' (propagate file {"radius" 12 "negative" -4})
        rect  (ths/get-shape file' :rect1)]
    (t/is (= 12 (:r1 rect)))
    (t/is (= 12 (:r2 rect)))
    (t/is (= 0 (:r3 rect)))
    (t/is (= 0 (:r4 rect)) "a token that doesn't resolve leaves the attribute")))

(t/deftest color-tokens
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (tho/add-text :text1 "Hello")
                  (apply-tokens :rect1 {:fill "red" :stroke-color "translucent"})
                  (apply-tokens :text1 {:fill "red"}))
        file' (propagate file {"red" "#FF0000" "translucent" "rgba(0, 0, 255, 0.5)"})
        rect  (ths/get-shape file' :rect1)
        text  (ths/get-shape file' :text1)]
    (t/is (= "#ff0000" (get-in rect [:fills 0 :fill-color])))
    (t/is (= 1 (get-in rect [:fills 0 :fill-opacity])))
    (t/is (= "#0000ff" (get-in rect [:strokes 0 :stroke-color])))
    (t/is (= 0.5 (get-in rect [:strokes 0 :stroke-opacity])))
    (t/is (nil? (:fills text)))
    (t/is (every? #(= "#ff0000" (get-in % [:fills 0 :fill-color])) (text-nodes text)))))

(t/deftest invalid-color-is-ignored
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (apply-tokens :rect1 {:fill "bad"}))
        rect  (ths/get-shape file :rect1)
        rect' (ths/get-shape (propagate file {"bad" "not-a-color"}) :rect1)]
    (t/is (= (:fills rect) (:fills rect')))))

(t/deftest stroke-width-tokens
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (apply-tokens :rect1 {:stroke-width-top "thick" :stroke-width-left "thin"}))
        rect  (ths/get-shape (propagate file {"thick" 4 "thin" 1}) :rect1)
        stroke (get-in rect [:strokes 0])]
    (t/is (= 4 (:stroke-width-top stroke)))
    (t/is (= 1 (:stroke-width-left stroke)))
    (t/is (= 0 (:stroke-width-right stroke)))
    (t/is (= 4 (:stroke-width stroke)))))

(t/deftest opacity-and-shadow-tokens
  (let [shadow [{:offset-x 1 :offset-y 2 :blur 3 :spread 0 :color "#000000" :inset false}]
        file   (-> (thf/sample-file :file1)
                   (tho/add-rect :rect1)
                   (apply-tokens :rect1 {:opacity "half" :shadow "drop"}))
        file'  (propagate file {"half" 0.5 "drop" shadow})
        rect   (ths/get-shape file' :rect1)]
    (t/is (= 0.5 (:opacity rect)))
    (t/is (= :drop-shadow (get-in rect [:shadow 0 :style])))
    (t/is (= "#000000" (get-in rect [:shadow 0 :color :color])))

    (t/testing "the same value twice gives the same shape"
      (let [shadow' (assoc-in shadow [0 :blur] 3)
            file''  (propagate (apply-tokens file' :rect1 {:opacity "half" :shadow "drop2"})
                               {"half" 0.5 "drop2" shadow'})]
        (t/is (= (ths/get-shape file' :rect1)
                 (-> (ths/get-shape file'' :rect1)
                     (assoc :applied-tokens (:applied-tokens rect)))))))))

(t/deftest nothing-to-do-without-changes
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-rect :rect1)
                    (apply-tokens :rect1 {:r1 "radius" :opacity "half" :width "size"}))
        values  {"radius" 4 "half" 0.5 "size" 50}
        file'   (propagate file values)
        page    (thf/current-page file')
        changes (propagation-changes file' (tht/applied-targets (:objects page)) values)]
    (t/is (empty? (:redo-changes changes)))))

(t/deftest text-tokens-in-one-walk
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-text :text1 "Hello")
                  (apply-tokens :text1 {:typography "heading" :text-decoration "strike"}))
        file' (propagate file {"heading" {:font-size 24
                                          :letter-spacing 2
                                          :line-height 1.5
                                          :text-case "uppercase"}
                               "strike" "strike-through"})
        text  (ths/get-shape file' :text1)
        page  (thf/current-page file)
        changes (propagation-changes file (tht/applied-targets (:objects page))
                                     {"heading" {:font-size 24}})]
    (t/is (every? #(and (= "24" (:font-size %))
                        (= "2" (:letter-spacing %))
                        (= 1.5 (:line-height %))
                        (= "uppercase" (:text-transform %))
                        (= "line-through" (:text-decoration %)))
                  (text-nodes text)))
    (t/is (= 1 (->> (:redo-changes changes)
                    (mapcat :operations)
                    (filter #(= :content (:attr %)))
                    (count)))
          "a single write of the content")))

(t/deftest text-position-data-reset
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-text :text1 "Hello" :text-params {:position-data [{:x 0 :y 0 :width 10 :height 10
                                                                              :fills [] :text "Hello"}]})
                  (apply-tokens :text1 {:font-size "big" :opacity "half"}))
        kept  (ths/get-shape (propagate file {"half" 0.5}
                                        :reset-position-data? true)
                             :text1)
        reset (ths/get-shape (propagate file {"big" 30}
                                        :reset-position-data? true)
                             :text1)]
    (t/is (some? (:position-data kept)))
    (t/is (nil? (:position-data reset)))))

(t/deftest text-position-data-takes-a-new-fill
  (let [fragment {:x 0 :y 0 :width 10 :height 10 :text "Hello"
                  :fills [{:fill-color "#000000" :fill-opacity 1}]}
        file     (-> (thf/sample-file :file1)
                     (tho/add-text :text1 "Hello" :text-params {:position-data [fragment fragment]})
                     (apply-tokens :text1 {:fill "main" :font-size "big"}))
        filled   (ths/get-shape (propagate file {"main" "#ff0000"}
                                           :reset-position-data? true)
                                :text1)
        resized  (ths/get-shape (propagate file {"main" "#ff0000" "big" 30}
                                           :reset-position-data? true)
                                :text1)]
    (t/is (= [[{:fill-color "#ff0000" :fill-opacity 1}]
              [{:fill-color "#ff0000" :fill-opacity 1}]]
             (mapv :fills (:position-data filled)))
          "the fragments keep their place with the new fill")
    (t/is (nil? (:position-data resized)) "a new size moves the text")))

(def ^:private fonts
  {"inter" {:id "inter"
            :family "Inter"
            :variants [{:id "regular" :weight "400" :style "normal"}
                       {:id "bold" :weight "700" :style "normal"}]}})

(t/deftest font-tokens
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-text :text1 "Hello")
                  (apply-tokens :text1 {:font-family "family" :font-weight "weight"}))
        file' (propagate file {"family" ["'Inter'"] "weight" "bold"} :fonts fonts)
        text  (ths/get-shape file' :text1)]
    (t/is (every? #(and (= "inter" (:font-id %))
                        (= "Inter" (:font-family %))
                        (= "700" (:font-weight %))
                        (= "bold" (:font-variant-id %)))
                  (text-nodes text)))))

(t/deftest size-tokens
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1 :width 10 :height 10)
                  (apply-tokens :rect1 {:width "w" :height "h"}))
        rect  (ths/get-shape (propagate file {"w" 120 "h" 40}) :rect1)]
    (t/is (mth/close? 120 (:width rect)))
    (t/is (mth/close? 40 (:height rect)))
    (t/is (mth/close? 120 (get-in rect [:selrect :width])))))

(t/deftest rotation-and-position-tokens
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-frame :frame1 :x 100 :y 100 :width 200 :height 200)
                  (ths/add-sample-shape :rect1 :parent-label :frame1 :x 110 :y 110 :width 10 :height 10)
                  (tho/add-rect :rect2 :x 0 :y 0 :width 10 :height 10)
                  (apply-tokens :rect1 {:x "pos" :y "pos"})
                  (apply-tokens :rect2 {:rotation "angle"}))
        file' (propagate file {"pos" 20 "angle" 45})
        rect1 (ths/get-shape file' :rect1)
        rect2 (ths/get-shape file' :rect2)]
    (t/is (mth/close? 120 (:x rect1)) "positions are relative to the parent board")
    (t/is (mth/close? 120 (:y rect1)))
    (t/is (mth/close? 45 (:rotation rect2)))))

(defn- add-flex
  [file]
  (-> file
      (tho/add-frame :layout :x 0 :y 0 :width 200 :height 100
                     :layout :flex
                     :layout-flex-dir :row
                     :layout-padding-type :multiple
                     :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                     :layout-gap {:row-gap 0 :column-gap 0})
      (ths/add-sample-shape :child1 :parent-label :layout :x 0 :y 0 :width 10 :height 10
                            :layout-item-margin-type :multiple)
      (ths/add-sample-shape :child2 :parent-label :layout :x 10 :y 0 :width 10 :height 10)))

;; The flex layout only runs on the JS runtime.
#?(:cljs
   (t/deftest layout-tokens
     (let [file   (-> (thf/sample-file :file1)
                      (add-flex)
                      (apply-tokens :layout {:p4 "pad" :column-gap "gap"})
                      (apply-tokens :child1 {:m2 "margin" :layout-item-min-w "min"}))
           file'  (propagate file {"pad" 16 "gap" 8 "margin" 4 "min" -3})
           layout (ths/get-shape file' :layout)
           child1 (ths/get-shape file' :child1)
           child2 (ths/get-shape file' :child2)]
       (t/is (= 16 (get-in layout [:layout-padding :p4])))
       (t/is (= 8 (get-in layout [:layout-gap :column-gap])))
       (t/is (= 4 (get-in child1 [:layout-item-margin :m2])))
       (t/is (= 0 (:layout-item-min-w child1)))
       (t/testing "the layout places its children again, the last one first"
         (t/is (mth/close? 16 (:x child2)))
         (t/is (mth/close? (+ 16 10 8) (:x child1)))))))

#?(:cljs
   (t/deftest a-size-inside-a-group-lays-out-the-board
     (let [file   (-> (thf/sample-file :file1)
                      (tho/add-frame :layout :x 0 :y 0 :width 20 :height 10
                                     :layout :flex
                                     :layout-flex-dir :row
                                     :layout-item-h-sizing :auto
                                     :layout-padding-type :multiple
                                     :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                     :layout-gap {:row-gap 0 :column-gap 0})
                      ;; The last child goes first
                      (ths/add-sample-shape :sibling :parent-label :layout :x 10 :y 0 :width 10 :height 10)
                      (tho/add-group :group :parent-label :layout :x 0 :y 0 :width 10 :height 10)
                      (ths/add-sample-shape :rect :parent-label :group :x 0 :y 0 :width 10 :height 10)
                      (apply-tokens :rect {:width "w"}))
           file'  (propagate file {"w" 50})]
       (t/is (mth/close? 50 (:width (ths/get-shape file' :rect))))
       (t/is (mth/close? 50 (:width (ths/get-shape file' :group))))
       (t/is (mth/close? 50 (:x (ths/get-shape file' :sibling))) "the sibling is pushed")
       (t/is (mth/close? 60 (:width (ths/get-shape file' :layout))) "the board grows"))))

(t/deftest margin-only-on-layout-children
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (apply-tokens :rect1 {:m1 "margin" :p1 "margin"}))
        rect  (ths/get-shape (propagate file {"margin" 4}) :rect1)]
    (t/is (nil? (:layout-item-margin rect)))
    (t/is (nil? (:layout-padding rect)))))

(t/deftest copies-keep-touched-flags
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component-with-copy :component1 :main1-root :main1-child
                                                      :copy1-root)
                  (apply-tokens :copy1-root {:opacity "half"}))
        copy  (ths/get-shape file :copy1-root)
        copy' (ths/get-shape (propagate file {"half" 0.5}) :copy1-root)]
    (t/is (= 0.5 (:opacity copy')))
    (t/is (= (:touched copy) (:touched copy')))))

;; --- Readers

(t/deftest saved-resolved-values
  (let [file    (thf/sample-file :file1)
        changes (-> (pcb/empty-changes nil)
                    (pcb/set-tokens-resolved nil {"radius" 4 "red" "#ff0000"}))
        file'   (thf/apply-changes file changes)
        changes2 (-> (pcb/empty-changes nil)
                     (pcb/set-tokens-resolved (get-in file' [:data :tokens-resolved])
                                              {"radius" 8 "red" "#ff0000"}))]
    (t/is (= {"radius" 4 "red" "#ff0000"} (get-in file' [:data :tokens-resolved])))
    (t/is (= [{:type :set-tokens-resolved :values {"radius" 8}}] (:redo-changes changes2))
          "only what differs")
    (t/is (= {"radius" 8 "red" "#ff0000"}
             (get-in (thf/apply-changes file' changes2) [:data :tokens-resolved])))
    (t/testing "undo"
      (t/is (nil? (get-in (thf/apply-undo-changes file' changes) [:data :tokens-resolved]))))))

(t/deftest values-to-save-keep-the-ones-not-resolved
  (let [resolved {"radius" {:name "radius" :resolved-value 8}
                  "size"   {:name "size" :resolved-value nil}
                  "new"    {:name "new" :resolved-value nil}}
        saved    {"radius" 4 "size" 24 "removed" 1}]
    (t/is (= {"radius" 8 "size" 24} (cltp/values-to-save resolved saved))
          "a token without a resolved value keeps the saved one; a removed one goes")
    (t/is (= {"radius" 8} (cltp/values-to-save resolved nil)))))

(t/deftest resolved-values-of-some-pages
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-rect :rect1)
                 (apply-tokens :rect1 {:r1 "radius"})
                 (assoc-in [:data :tokens-resolved] {"radius" 4 "unused" 1}))
        page (thf/current-page file)]
    (t/is (= {"radius" 4} (cfo/get-pages-resolved (:data file) [page])))))

(t/deftest readers-correct-out-of-date-shapes
  (let [file     (-> (thf/sample-file :file1)
                     (tho/add-rect :stale)
                     (apply-tokens :stale {:r1 "radius"})
                     (ths/update-shape :stale :token-values {:r1 4})
                     (tho/add-rect :legacy)
                     (apply-tokens :legacy {:r1 "radius"}))
        page     (thf/current-page file)
        resolved (cltp/values->resolved-tokens {"radius" 12})
        objects  (cltp/correct-objects page resolved {})]
    (t/is (cltp/page-out-of-date? page resolved))
    (t/is (= 12 (get-in objects [(thi/id :stale) :r1])))
    (t/is (not= 12 (get-in objects [(thi/id :legacy) :r1])) "readers trust unassigned values")
    (t/is (not (cltp/page-out-of-date? (assoc page :objects objects) resolved)))))

(t/deftest readers-set-only-the-boards-of-the-shapes-they-show
  (let [stale    (fn [file frame rect]
                   (-> file
                       (tho/add-frame-with-child frame rect)
                       (apply-tokens rect {:r1 "radius"})
                       (ths/update-shape rect :token-values {:r1 4})))
        file     (-> (thf/sample-file :file1)
                     (stale :frame1 :rect1)
                     (stale :frame2 :rect2))
        page     (thf/current-page file)
        objects  (:objects page)
        resolved (cltp/values->resolved-tokens {"radius" 12})
        ids      [(thi/id :rect1)]
        objects' (cltp/correct-objects page resolved {} :ids ids)]
    (t/is (= #{(thi/id :frame1) (thi/id :rect1)} (cltp/layout-scope objects ids))
          "the shape right under the root that holds it, and its children")
    (t/is (nil? (cltp/layout-scope objects [uuid/zero])) "the root takes the whole page")
    (t/is (= #{(thi/id :rect1)}
             (cltp/layout-scope (select-keys objects [(thi/id :rect1)]) ids))
          "the shapes of a pruned page stop at the ones it holds")
    (t/is (= 12 (get-in objects' [(thi/id :rect1) :r1])))
    (t/is (= (get objects (thi/id :rect2)) (get objects' (thi/id :rect2)))
          "the other boards are left as they are")
    (t/is (cltp/page-out-of-date? (assoc page :objects objects') resolved))
    (t/is (not (cltp/page-out-of-date? (assoc page :objects objects') resolved :ids ids)))))

(t/deftest geometry-left-to-the-renderer
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-rect :rect1 :width 10 :height 10)
                    (apply-tokens :rect1 {:width "w" :opacity "half"}))
        page    (thf/current-page file)
        changes (-> (pcb/empty-changes nil (:id page))
                    (pcb/with-page page)
                    (cltp/generate-token-propagation (:objects page)
                                                     (resolved {"w" 120 "half" 0.5})
                                                     (tht/applied-targets (:objects page))
                                                     {:geometry :wasm}))
        rect    (ths/get-shape (thf/apply-changes file changes) :rect1)]
    (t/is (= 0.5 (:opacity rect)) "the rest is set")
    (t/is (= 10 (:width rect)) "the geometry is not")
    (t/is (= {:width 120 :opacity 0.5} (:token-values rect)))
    (t/is (= 120 (get-in (cltp/pending-geometry changes) [:updates (thi/id :rect1) :width])))))

(t/deftest geometry-already-held-is-not-left
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-rect :rect1 :width 20.000019 :height 10)
                    (apply-tokens :rect1 {:width "w"})
                    (tho/add-frame :hug :width 100 :height 30)
                    (ths/update-shape :hug :layout :flex)
                    (ths/update-shape :hug :layout-item-v-sizing :auto)
                    (apply-tokens :hug {:height "h"}))
        page    (thf/current-page file)
        changes (-> (pcb/empty-changes nil (:id page))
                    (pcb/with-page page)
                    (cltp/generate-token-propagation (:objects page)
                                                     (resolved {"w" 20 "h" 32})
                                                     (tht/applied-targets (:objects page))
                                                     {:geometry :wasm}))
        file'   (thf/apply-changes file changes)]
    (t/is (nil? (cltp/pending-geometry changes))
          "a size within the float precision, or set by the layout, is held")
    (t/is (= {:width 20} (:token-values (ths/get-shape file' :rect1))) "the values are recorded")
    (t/is (= {:height 32} (:token-values (ths/get-shape file' :hug))))
    (t/is (= 30 (:height (ths/get-shape file' :hug))) "the layout keeps the size")))
