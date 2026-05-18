;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns  app.main.ui.ds.controls.shared.token-option
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:token-option
  [:map
   [:id {:optiona true} :string]
   [:ref some?]
   [:resolved {:optional true} [:maybe [:or :int :string :float :map]]]
   [:value {:optional true} [:maybe [:or :int :string :float :map]]]
   [:name {:optional true} :string]
   [:on-click {:optional true} fn?]
   [:selected {:optional true} :boolean]
   [:focused {:optional true} :boolean]])

(mf/defc resolved-value-tooltip*
  ;; Generates the tooltip content for tokens whose resolved value is a map
  ;; (e.g. typography tokens). Each key/value pair is rendered as a list item:
  ;;   - font-family: "AR One Sans"
  ;;   - font-size: 23
  ;;   - ...
  ;;
  ;; Sequential values are formatted as comma-separated strings with quotes
  ;; around each item. This is mainly used for font-family values when multiple
  ;; fonts are present, to preserve the expected CSS-like representation:
  ;;  - font-family: "Font A", "Font B"
  [{:keys [token-name resolved-value]}]
  [:*
   [:span (dm/str (tr "workspace.tokens.token-name") ": ")]
   [:span {:class (stl/css :token-name-tooltip)} token-name]
   [:div
    [:span (tr "inspect.tabs.styles.token-resolved-value")]
    [:ul
     (for [[sub-prop prop-value] resolved-value]
       [:li {:key (d/name sub-prop)}
        [:span {:class (stl/css :resolved-key)} (str "- " (d/name sub-prop) ": ")]
        [:span {:class (stl/css :resolved-value)}
         (if (sequential? prop-value)
           (str/join ", " (map #(dm/str "\"" % "\"") prop-value))
           (dm/str prop-value))]])]]])

(mf/defc token-option*
  {::mf/schema schema:token-option}
  [{:keys [id name on-click selected ref focused resolved value] :rest props}]
  (let [internal-id (mf/use-id)
        id          (d/nilv id internal-id)
        element-ref (mf/use-ref nil)
        tooltip-content (if (map? resolved)
                          (mf/html [:> resolved-value-tooltip* {:token-name name
                                                                :resolved-value resolved}])
                          name)]
    [:li {:value id
          :class (stl/css-case :token-option true
                               :option-with-pill true
                               :option-selected-token selected
                               :option-current focused)
          :aria-selected selected
          :ref ref
          :role "option"
          :id id
          :on-click on-click
          :data-id id
          :aria-label name
          :data-testid "dropdown-option"}

     (if selected
       [:> icon*
        {:icon-id i/tick
         :size "s"
         :class (stl/css :option-check)
         :aria-hidden (when name true)}]
       [:span {:class (stl/css :icon-placeholder)}])
     [:> tooltip* {:content tooltip-content
                   :trigger-ref element-ref
                   :id (dm/str id "-name")
                   :class (stl/css :option-text)}

      [:span {:aria-labelledby (dm/str id "-name")
              :class (stl/css :option-name)
              :ref element-ref}
       name]]
     (when (and resolved (not (map? resolved)))
       [:span {:class (stl/css :option-pill)}
        resolved])
     (when (and (nil? resolved) value)
       [:span {:class (stl/css :option-pill)}
        "--"])]))
