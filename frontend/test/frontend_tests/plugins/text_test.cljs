;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
;; see typography.cljs), but the plugin setter rejected any leading minus,
;; so negative values were refused. Pin the accept/reject contract of the
;; plugin setter here.

(defn- apply-range-property
  "Sets `property` on a text range proxy and returns the attributes sent to
  the update event, or nil when the plugin rejected the value."
  [property value]
  (let [captured (atom nil)
        range    (plugins.text/text-range-proxy
                  plugin-id (random-uuid) (random-uuid) (random-uuid) 0 4)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  u/not-valid (fn [_ _ _] nil)
                  dwt/update-text-range
                  (fn [_ _ _ attrs]
                    (reset! captured attrs)
                    :update-text-range)
                  st/emit! mock/noop]
      (unchecked-set range property value)
      @captured)))

(defn- apply-letter-spacing
  [value]
  (apply-range-property "letterSpacing" value))

(t/deftest letter-spacing-accepts-negative-values
  (t/is (= {:letter-spacing "-0.56"} (apply-letter-spacing "-0.56")))
  (t/is (= {:letter-spacing "-12"} (apply-letter-spacing "-12")))
  (t/is (= {:letter-spacing "-200"} (apply-letter-spacing "-200"))))

(t/deftest letter-spacing-accepts-non-negative-values
  (t/is (= {:letter-spacing "0"} (apply-letter-spacing "0")))
  (t/is (= {:letter-spacing "12"} (apply-letter-spacing "12")))
  (t/is (= {:letter-spacing "1.5"} (apply-letter-spacing "1.5"))))

(t/deftest letter-spacing-rejects-non-numeric
  (t/is (nil? (apply-letter-spacing "abc")))
  (t/is (nil? (apply-letter-spacing "1-2")))
  (t/is (nil? (apply-letter-spacing "--1"))))

(t/deftest japanese-range-properties-accept-only-supported-values
  (doseq [[property attr accepted rejected]
          [["fontFeatures" :font-features ["none" "palt" "vpal"] ["liga" "palt,vpal"]]
           ["annotationClearance" :annotation-clearance ["none" "auto"] ["always"]]
           ["rubySize" :ruby-size ["half" "third" "quarter"] ["full"]]
           ["rubyAlign" :ruby-align ["space-around" "center" "start" "space-between"] ["end"]]
           ["rubyOverhang" :ruby-overhang ["auto" "none"] ["always"]]
           ["rubySide" :ruby-side ["over" "under"] ["right"]]
           ["ruby" :ruby ["かんじ" nil] [42]]]]
    (doseq [value accepted]
      (t/is (= {attr value} (apply-range-property property value))
            (str property " accepts " (pr-str value))))
    (doseq [value rejected]
      (t/is (nil? (apply-range-property property value))
            (str property " rejects " (pr-str value))))))

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

(def ^:private two-span-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text "漢" :font-weight "700"}
                                      {:text "字" :font-weight "400"}]}]}]})

(defn- capture-japanese-update
  "Sets `property` to `value` on `target` over `content`; returns the attrs
  sent to the update and the rejection reason, if any."
  [target property value content]
  (let [captured (atom {})]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  u/proxy->shape (constantly {:content content})
                  u/not-valid (fn [_ _ reason] (swap! captured assoc :rejected reason))
                  dwt/update-text-range
                  (fn [_ _ _ attrs] (swap! captured assoc :attrs attrs) :update)
                  dwt/update-attrs
                  (fn [_ attrs] (swap! captured assoc :attrs attrs) :update)
                  st/emit! mock/noop]
      (unchecked-set target property value)
      @captured)))

(t/deftest text-range-ruby-across-spans-is-rejected
  (let [range  (plugins.text/text-range-proxy plugin-id (random-uuid) (random-uuid) (random-uuid) 0 2)
        result (capture-japanese-update range "ruby" "かんじ" two-span-content)]
    (t/is (nil? (:attrs result)))
    (t/is (string? (:rejected result)))))

(t/deftest text-range-warichu-across-spans-is-rejected
  (let [range  (plugins.text/text-range-proxy plugin-id (random-uuid) (random-uuid) (random-uuid) 0 2)
        result (capture-japanese-update range "warichu" "warichu" two-span-content)]
    (t/is (nil? (:attrs result)))))

(t/deftest text-range-ruby-inside-one-span-is-applied
  (let [range  (plugins.text/text-range-proxy plugin-id (random-uuid) (random-uuid) (random-uuid) 1 2)
        result (capture-japanese-update range "ruby" "じ" two-span-content)]
    (t/is (= {:ruby "じ"} (:attrs result)))))

(t/deftest text-ruby-on-a-multi-span-text-is-rejected
  (let [text   (plugins.text/add-text-props #js {:$id (random-uuid) :$page (random-uuid)} plugin-id)
        result (capture-japanese-update text "ruby" "かんじ" two-span-content)]
    (t/is (nil? (:attrs result)))
    (t/is (string? (:rejected result)))))

(t/deftest text-ruby-can-be-cleared-on-a-multi-span-text
  (let [text   (plugins.text/add-text-props #js {:$id (random-uuid) :$page (random-uuid)} plugin-id)
        result (capture-japanese-update text "ruby" "" two-span-content)]
    (t/is (= {:ruby ""} (:attrs result)))))

(defn- text-proxy-over
  [content]
  [(plugins.text/add-text-props #js {:$id (random-uuid) :$page (random-uuid)} plugin-id)
   {:content content}])

(t/deftest text-japanese-getters-report-defaults-when-unset
  (let [[^js text shape] (text-proxy-over two-span-content)]
    (with-redefs [u/proxy->shape (constantly shape)]
      (t/is (= "horizontal-tb" (.-writingMode text)))
      (t/is (= "mixed" (.-textOrientation text)))
      (t/is (= "none" (.-textCombineUpright text)))
      (t/is (= "none" (.-textEmphasis text)))
      (t/is (= "none" (.-warichu text)))
      (t/is (= "none" (.-fontFeatures text)))
      (t/is (= "none" (.-annotationClearance text)))
      (t/is (= "half" (.-rubySize text)))
      (t/is (= "push-in-first" (.-lineAdjustment text)))
      (t/is (false? (.-rubyHidden text)))
      (t/is (nil? (.-ruby text))))))

(t/deftest text-line-adjustment-is-a-root-value
  (let [[^js text shape] (text-proxy-over (assoc two-span-content :line-adjustment "push-out-only"))]
    (with-redefs [u/proxy->shape (constantly shape)]
      (t/is (= "push-out-only" (.-lineAdjustment text)))))
  (let [text   (plugins.text/add-text-props #js {:$id (random-uuid) :$page (random-uuid)} plugin-id)
        result (capture-japanese-update text "lineAdjustment" "push-out-first" two-span-content)]
    (t/is (= {:line-adjustment "push-out-first"} (:attrs result))))
  (let [text   (plugins.text/add-text-props #js {:$id (random-uuid) :$page (random-uuid)} plugin-id)
        result (capture-japanese-update text "lineAdjustment" "squeeze" two-span-content)]
    (t/is (nil? (:attrs result)))
    (t/is (= "squeeze" (:rejected result)))))

(t/deftest text-orientation-is-a-whole-shape-value
  (let [content {:type "root"
                 :children [{:type "paragraph-set"
                             :children [{:type "paragraph"
                                         :text-orientation "upright"
                                         :children [{:text "縦"}]}
                                        {:type "paragraph"
                                         :children [{:text "書き"}]}]}]}
        [^js text shape] (text-proxy-over content)]
    (with-redefs [u/proxy->shape (constantly shape)]
      (t/is (= "upright" (.-textOrientation text))))))

(t/deftest ruby-hidden-is-exposed-on-text-and-ranges
  (let [range  (plugins.text/text-range-proxy plugin-id (random-uuid) (random-uuid) (random-uuid) 0 1)
        result (capture-japanese-update range "rubyHidden" true two-span-content)
        bad    (capture-japanese-update range "rubyHidden" "yes" two-span-content)
        [text] (text-proxy-over two-span-content)
        shape  (capture-japanese-update text "rubyHidden" false two-span-content)]
    (t/is (= {:ruby-hidden true} (:attrs result)))
    (t/is (nil? (:attrs bad)))
    (t/is (= {:ruby-hidden false} (:attrs shape)))))

(t/deftest text-japanese-getters-treat-unset-spans-as-default
  (let [content (assoc-in two-span-content
                          [:children 0 :children 0 :children 0 :text-emphasis]
                          "none")
        [^js text shape] (text-proxy-over content)]
    (with-redefs [u/proxy->shape (constantly shape)]
      (t/is (= "none" (.-textEmphasis text))))))
