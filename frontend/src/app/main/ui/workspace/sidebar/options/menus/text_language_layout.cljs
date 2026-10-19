;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.text-language-layout
  (:require-macros [app.main.style :as stl])
  (:require
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
                                  {:id "zh-Hant" :label (translate "workspace.options.text-options.profile-chinese-traditional")}]
                        :korean [{:id "ko" :label (translate "workspace.options.text-options.profile-korean")}]))
        mixed? (= :multiple value)
        id (if mixed? "multiple" (or value ""))]
    (cond-> options
      (not (some #(= (:id %) id) options))
      (conj {:id id
             :label (if mixed? (translate "labels.mixed-values") value)
             :disabled true
             :dimmed true}))))

(defn profile-change
  [value]
  {:text-language (when (seq value) value)})

(mf/defc language-layout-options*
  [{:keys [language values ruby-values text-selection-active
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
     (when (and (= :korean language) matches?)
       [:> tjl/span-select-option*
        (mf/spread-props props
                         {:attr :korean-wrap
                          :default-value "syllable"
                          :label (tr "workspace.options.text-options.korean-wrap")
                          :options [{:id "syllable" :label (tr "workspace.options.text-options.korean-wrap-syllable")}
                                    {:id "spaces-only" :label (tr "workspace.options.text-options.korean-wrap-spaces-only")}]})])]))
