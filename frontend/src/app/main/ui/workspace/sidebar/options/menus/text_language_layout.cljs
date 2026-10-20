;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.text-language-layout
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.text.cjk-layout :as cjk]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.workspace.sidebar.options.menus.text-japanese-layout :as tjl]
   [app.util.i18n :refer [tr]]
   [app.util.text.layout :as tl]
   [rumext.v2 :as mf]))

(defn profile-options
  [language value translate]
  (let [options (into [{:id "" :label (translate "workspace.options.text-options.profile-plain")}]
                      (case language
                        :japanese [{:id "ja" :label (translate "workspace.options.text-options.profile-japanese")}]
                        :chinese [{:id "zh-Hans" :label (translate "workspace.options.text-options.profile-chinese-simplified")}
                                  {:id "zh-Hant" :label (translate "workspace.options.text-options.profile-chinese-traditional")}
                                  {:id "zh-Hans-CN" :label (translate "workspace.options.text-options.profile-chinese-mainland-simplified")}
                                  {:id "zh-Hant-CN" :label (translate "workspace.options.text-options.profile-chinese-mainland-traditional")}
                                  {:id "zh-Hant-TW" :label (translate "workspace.options.text-options.profile-chinese-taiwan")}
                                  {:id "zh-Hant-HK" :label (translate "workspace.options.text-options.profile-chinese-hong-kong")}]
                        :korean [{:id "ko" :label (translate "workspace.options.text-options.profile-korean")}]))
        mixed? (= :multiple value)
        id (if mixed? "multiple" (or value ""))]
    (cond-> options
      (not (some #(= (:id %) id) options))
      (conj {:id id
             :label (if mixed? (translate "labels.mixed-values") value)
             :disabled true
             :dimmed true}))))

(defn korean-wrap-options
  [value translate]
  (cond-> [{:id "syllable" :label (translate "workspace.options.text-options.korean-wrap-syllable")}
           {:id "keep-words" :label (translate "workspace.options.text-options.korean-wrap-keep-words")}]
    (= value "spaces-only")
    (conj {:id "spaces-only" :label (translate "workspace.options.text-options.korean-wrap-spaces-only")})))

(defn profile-change
  [value]
  {:text-language (when (seq value) value)})

(defn emphasis-position-options
  [translate]
  [{:id "auto" :label (translate "workspace.options.text-options.position-auto")}
   {:id "over-right" :label (translate "workspace.options.text-options.position-over-right")}
   {:id "over-left" :label (translate "workspace.options.text-options.position-over-left")}
   {:id "under-right" :label (translate "workspace.options.text-options.position-under-right")}
   {:id "under-left" :label (translate "workspace.options.text-options.position-under-left")}])

(defn underline-position-options
  [translate]
  [{:id "auto" :label (translate "workspace.options.text-options.position-auto")}
   {:id "under" :label (translate "workspace.options.text-options.position-under")}
   {:id "left" :label (translate "workspace.options.text-options.position-left")}
   {:id "right" :label (translate "workspace.options.text-options.position-right")}
   {:id "under-left" :label (translate "workspace.options.text-options.position-under-left")}
   {:id "under-right" :label (translate "workspace.options.text-options.position-under-right")}])

(mf/defc chinese-decoration-options*
  [{:keys [values on-change on-blur]}]
  (let [props (mf/props {:values values :on-change on-change :on-blur on-blur})]
    [:div {:class (stl/css :decoration-options)}
     [:> tjl/span-select-option*
      (mf/spread-props props {:attr :text-emphasis :default-value "none"
                              :label (tr "workspace.options.text-options.text-emphasis")
                              :options (tjl/text-emphasis-options tr)})]
     [:> tjl/span-select-option*
      (mf/spread-props props {:attr :text-emphasis-position :default-value "auto"
                              :label (tr "workspace.options.text-options.emphasis-position")
                              :options (emphasis-position-options tr)})]
     [:> tjl/span-select-option*
      (mf/spread-props props {:attr :text-underline-position :default-value "auto"
                              :label (tr "workspace.options.text-options.underline-position")
                              :options (underline-position-options tr)})]
     [:> tjl/span-select-option*
      (mf/spread-props props {:attr :annotation-clearance :default-value "none"
                              :label (tr "workspace.options.text-options.annotation-clearance")
                              :options (tjl/annotation-clearance-options)})]]))

(mf/defc chinese-reading-options*
  [{:keys [values ruby-values text-selection-active reading-selection-active zhuyin-selection-active
           on-change on-ruby-presentation-change on-blur]}]
  (let [zhuyin? (true? (:ruby-zhuyin values))
        labels {:input (if zhuyin? (tr "workspace.options.text-options.zhuyin") (tr "workspace.options.text-options.pinyin"))
                :placeholder (if zhuyin? (tr "workspace.options.text-options.zhuyin-placeholder") (tr "workspace.options.text-options.pinyin-placeholder"))
                :advanced (tr "workspace.options.text-options.pinyin-advanced-options")
                :hidden (if zhuyin? (tr "workspace.options.text-options.zhuyin-hidden") (tr "workspace.options.text-options.pinyin-hidden"))
                :size (tr "workspace.options.text-options.pinyin-size")
                :align (tr "workspace.options.text-options.pinyin-align")
                :overhang (tr "workspace.options.text-options.pinyin-overhang")
                :side (tr "workspace.options.text-options.pinyin-side")}
        handle-format-change (mf/use-fn
                              (mf/deps on-change)
                              (fn [value] (on-change {:ruby-zhuyin (= "zhuyin" value)})))
        props (mf/props {:values values :on-change on-change
                         :on-blur on-blur :labels (cond-> labels zhuyin?
                                                          (assoc :validate cjk/zhuyin-reading?
                                                                 :invalid (tr "workspace.options.text-options.zhuyin-invalid")))})]
    [:div {:class (stl/css :pinyin-options)}
     (when reading-selection-active
       [:div {:class (stl/css :profile-option)}
        [:span {:class (stl/css :option-label)} (tr "workspace.options.text-options.chinese-reading-format")]
        [:> select* {:default-selected (if zhuyin? "zhuyin" "pinyin")
                     :aria-label (tr "workspace.options.text-options.chinese-reading-format")
                     :options [{:id "pinyin" :label (tr "workspace.options.text-options.pinyin")}
                               {:id "zhuyin" :label (tr "workspace.options.text-options.zhuyin")
                                :disabled (not zhuyin-selection-active)}]
                     :on-change handle-format-change}]])
     (if (and reading-selection-active (or (not zhuyin?) zhuyin-selection-active))
       (if zhuyin?
         [:* [:p {:class (stl/css :selection-hint)} (tr "workspace.options.text-options.zhuyin-selection-hint")]
          [:> tjl/ruby-options* props]
          [:> tjl/ruby-hidden-option* props]]
         [:> tjl/ruby-advanced-options* props])
       [:*
        [:p {:class (stl/css :selection-hint)}
         (if zhuyin? (tr "workspace.options.text-options.zhuyin-selection-hint") (tr "workspace.options.text-options.pinyin-selection-hint"))]
        (when-not text-selection-active
          (if zhuyin?
            [:> tjl/ruby-hidden-option*
             (mf/spread-props props {:values ruby-values :on-change on-ruby-presentation-change})]
            [:> tjl/ruby-presentation-options*
             (mf/spread-props props {:values ruby-values
                                     :on-change on-ruby-presentation-change})]))])]))

(mf/defc language-layout-options*
  [{:keys [language values ruby-values text-selection-active reading-selection-active zhuyin-selection-active
           on-change on-ruby-presentation-change on-blur]}]
  (let [profile (:text-language values)
        selected (if (= :multiple profile) "multiple" (or profile ""))
        options (mf/with-memo [language profile] (profile-options language profile tr))
        matches? (tl/profile-matches? language profile)
        handle-profile-change (mf/use-fn
                               (mf/deps on-change)
                               #(on-change (profile-change %)))
        props (mf/props {:values values :on-change on-change :on-blur on-blur})]
    [:div {:class (stl/css :language-layout-options)}
     [:div {:class (stl/css :profile-option)}
      [:span {:class (stl/css :option-label)}
       (tr "workspace.options.text-options.language-profile")]
      [:> select* {:default-selected selected
                   :aria-label (tr "workspace.options.text-options.language-profile")
                   :options options
                   :on-change handle-profile-change}]]
     (if (and (= :japanese language) matches?)
       [:> tjl/japanese-layout-options*
        (mf/spread-props props {:ruby-values ruby-values
                                :text-selection-active text-selection-active
                                :on-ruby-presentation-change on-ruby-presentation-change})]
       [:> tjl/writing-mode-controls* props])
     (when (and (= :chinese language) matches?)
       [:> chinese-decoration-options* props])
     (when (and (= :chinese language) matches?)
       [:> chinese-reading-options*
        (mf/spread-props props {:ruby-values ruby-values
                                :text-selection-active text-selection-active
                                :reading-selection-active reading-selection-active
                                :zhuyin-selection-active zhuyin-selection-active
                                :on-ruby-presentation-change on-ruby-presentation-change})])
     (when (and (= :korean language) matches?)
       [:> tjl/span-select-option*
        (mf/spread-props props
                         {:attr :korean-wrap
                          :default-value "syllable"
                          :label (tr "workspace.options.text-options.korean-wrap")
                          :options (korean-wrap-options (:korean-wrap values) tr)})])]))
