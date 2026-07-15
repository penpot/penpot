;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.text-test
  (:require
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.plugins.fonts :as fonts]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.text :as plugins.text]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

;; Regression coverage for issue #9780.
;;
;; `letterSpacing` accepts negative tracking in the product UI (-200..200,
;; see typography.cljs), but the plugin validator regex rejected any leading
;; minus, so negative values were refused. `letter-spacing-re` is the shared
;; predicate behind both the shape- and range-level setters; pin its
;; accept/reject contract here.

(def ^:private letter-spacing-re @#'plugins.text/letter-spacing-re)
(def ^:private font-features-re @#'plugins.text/font-features-re)
(def ^:private annotation-clearance-re @#'plugins.text/annotation-clearance-re)
(def ^:private ruby-size-re @#'plugins.text/ruby-size-re)
(def ^:private ruby-align-re @#'plugins.text/ruby-align-re)
(def ^:private ruby-overhang-re @#'plugins.text/ruby-overhang-re)
(def ^:private ruby-side-re @#'plugins.text/ruby-side-re)

(defn- valid? [s] (boolean (re-matches letter-spacing-re s)))
(defn- valid-font-features? [s] (boolean (re-matches font-features-re s)))
(defn- valid-annotation-clearance? [s]
  (boolean (re-matches annotation-clearance-re s)))

(t/deftest letter-spacing-re-accepts-negative-values
  (t/is (valid? "-0.56"))
  (t/is (valid? "-12"))
  (t/is (valid? "-200")))

(t/deftest letter-spacing-re-accepts-non-negative-values
  (t/is (valid? "0"))
  (t/is (valid? "12"))
  (t/is (valid? "1.5")))

(t/deftest letter-spacing-re-rejects-non-numeric
  (t/is (not (valid? "abc")))
  (t/is (not (valid? "1-2")))
  (t/is (not (valid? "--1"))))

(t/deftest font-features-re-accepts-supported-japanese-proportional-features
  (t/is (valid-font-features? "none"))
  (t/is (valid-font-features? "palt"))
  (t/is (valid-font-features? "vpal"))
  (t/is (not (valid-font-features? "liga")))
  (t/is (not (valid-font-features? "palt,vpal"))))

(t/deftest annotation-clearance-re-accepts-supported-policies
  (t/is (valid-annotation-clearance? "none"))
  (t/is (valid-annotation-clearance? "auto"))
  (t/is (not (valid-annotation-clearance? "always"))))

(t/deftest ruby-customization-validates-supported-values
  (t/is (every? #(re-matches ruby-size-re %) ["half" "third" "quarter"]))
  (t/is (not (re-matches ruby-size-re "full")))
  (t/is (every? #(re-matches ruby-align-re %)
                ["space-around" "center" "start" "space-between"]))
  (t/is (not (re-matches ruby-align-re "end")))
  (t/is (every? #(re-matches ruby-overhang-re %) ["auto" "none"]))
  (t/is (not (re-matches ruby-overhang-re "always")))
  (t/is (every? #(re-matches ruby-side-re %) ["over" "under"]))
  (t/is (not (re-matches ruby-side-re "right"))))

(t/deftest text-range-japanese-properties-read-span-values
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        content  {:type "root"
                  :children [{:type "paragraph-set"
                              :children [{:type "paragraph"
                                          :children [{:text "漢字"
                                                      :text-combine-upright "digits2"
                                                      :text-emphasis "filled-dot"
                                                      :warichu "warichu"
                                                      :font-features "vpal"
                                                      :annotation-clearance "auto"
                                                      :ruby "かんじ"
                                                      :ruby-size "third"
                                                      :ruby-align "center"
                                                      :ruby-overhang "none"
                                                      :ruby-side "under"}]}]}]}
        range    (plugins.text/text-range-proxy plugin-id file-id page-id shape-id 0 2)]
    (with-redefs [u/proxy->shape (constantly {:content content})]
      (t/is (= "digits2" (.-textCombineUpright range)))
      (t/is (= "filled-dot" (.-textEmphasis range)))
      (t/is (= "warichu" (.-warichu range)))
      (t/is (= "vpal" (.-fontFeatures range)))
      (t/is (= "auto" (.-annotationClearance range)))
      (t/is (= "かんじ" (.-ruby range)))
      (t/is (= "third" (.-rubySize range)))
      (t/is (= "center" (.-rubyAlign range)))
      (t/is (= "none" (.-rubyOverhang range)))
      (t/is (= "under" (.-rubySide range))))))

(t/deftest text-range-japanese-properties-report-mixed-values
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        content  {:type "root"
                  :children [{:type "paragraph-set"
                              :children [{:type "paragraph"
                                          :children [{:text "日"
                                                      :text-emphasis "filled-dot"
                                                      :ruby "にち"
                                                      :ruby-size "third"}
                                                     {:text "本"
                                                      :text-emphasis "none"
                                                      :ruby nil
                                                      :ruby-size "half"}]}]}]}
        range    (plugins.text/text-range-proxy plugin-id file-id page-id shape-id 0 2)]
    (with-redefs [u/proxy->shape (constantly {:content content})]
      (t/is (= "mixed" (.-textEmphasis range)))
      (t/is (= "mixed" (.-ruby range)))
      (t/is (= "mixed" (.-rubySize range))))))

(t/deftest text-range-japanese-properties-update-the-selected-range
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        range    (plugins.text/text-range-proxy plugin-id file-id page-id shape-id 1 4)
        captured (atom [])]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  dwt/update-text-range
                  (fn [id start end attrs]
                    (swap! captured conj {:id id
                                          :start start
                                          :end end
                                          :attrs attrs})
                    :update-text-range)
                  st/emit! mock/noop]
      (set! (.-textCombineUpright range) "digits2")
      (set! (.-textEmphasis range) "filled-dot")
      (set! (.-warichu range) "warichu")
      (set! (.-fontFeatures range) "vpal")
      (set! (.-annotationClearance range) "auto")
      (set! (.-ruby range) "かんじ")
      (set! (.-rubySize range) "third")
      (set! (.-rubyAlign range) "center")
      (set! (.-rubyOverhang range) "none")
      (set! (.-rubySide range) "under")
      (t/is (= [{:id shape-id :start 1 :end 4 :attrs {:text-combine-upright "digits2"}}
                {:id shape-id :start 1 :end 4 :attrs {:text-emphasis "filled-dot"}}
                {:id shape-id :start 1 :end 4 :attrs {:warichu "warichu"}}
                {:id shape-id :start 1 :end 4 :attrs {:font-features "vpal"}}
                {:id shape-id :start 1 :end 4 :attrs {:annotation-clearance "auto"}}
                {:id shape-id :start 1 :end 4 :attrs {:ruby "かんじ"}}
                {:id shape-id :start 1 :end 4 :attrs {:ruby-size "third"}}
                {:id shape-id :start 1 :end 4 :attrs {:ruby-align "center"}}
                {:id shape-id :start 1 :end 4 :attrs {:ruby-overhang "none"}}
                {:id shape-id :start 1 :end 4 :attrs {:ruby-side "under"}}]
               @captured)))))


(t/deftest font-apply-to-text-uses-font-id-not-shape-id
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        font     (fonts/font-proxy
                  plugin-id
                  {:id "font-id"
                   :family "Inter"
                   :name "Inter"
                   :variants [{:id "regular"
                               :name "Regular"
                               :weight "400"
                               :style "normal"}]})
        text     (shape/shape-proxy plugin-id file-id page-id shape-id)
        captured (atom nil)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  dwt/update-attrs
                  (fn [id attrs]
                    (reset! captured {:id id :attrs attrs})
                    :update-attrs)
                  st/emit! mock/noop]
      (.applyToText font text nil)
      (t/is (= shape-id (:id @captured)))
      (t/is (= "font-id" (get-in @captured [:attrs :font-id]))))))

(t/deftest font-apply-to-range-uses-hidden-range-bounds
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        font     (fonts/font-proxy
                  plugin-id
                  {:id "font-id"
                   :family "Inter"
                   :name "Inter"
                   :variants [{:id "regular"
                               :name "Regular"
                               :weight "400"
                               :style "normal"}]})
        range    (plugins.text/text-range-proxy plugin-id file-id page-id shape-id 1 4)
        captured (atom nil)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  dwt/update-text-range
                  (fn [id start end attrs]
                    (reset! captured {:id id
                                      :start start
                                      :end end
                                      :attrs attrs})
                    :update-text-range)
                  st/emit! mock/noop]
      (.applyToRange font range nil)
      (t/is (= shape-id (:id @captured)))
      (t/is (= 1 (:start @captured)))
      (t/is (= 4 (:end @captured)))
      (t/is (= "font-id" (get-in @captured [:attrs :font-id]))))))

(t/deftest text-range-shape-returns-a-shape-proxy
  (let [file-id  (random-uuid)
        page-id  (random-uuid)
        shape-id (random-uuid)
        range    (plugins.text/text-range-proxy plugin-id file-id page-id shape-id 0 3)]
    (with-redefs [format/shape-proxy shape/shape-proxy]
      (let [text-shape (.-shape range)]
        (t/is (shape/shape-proxy? text-shape))
        (t/is (= shape-id (aget text-shape "$id")))))))
