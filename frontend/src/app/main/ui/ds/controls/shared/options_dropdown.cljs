;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.options-dropdown
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.weak :refer [weak-key]]
   [app.main.ui.ds.controls.shared.option :refer [option*]]
   [app.main.ui.ds.controls.shared.token-option :refer [token-option*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-list
  [:and :string
   [:fn {:error/message "invalid data: invalid icon"} #(contains? i/icon-list %)]])

(def schema:option
  "A schema for the option data structure expected to receive on props
  for the `options-dropdown*` component."
  [:map
   [:id {:optional true} :string]
   [:resolved-value {:optional true}
    [:or :int :string]]
   [:name {:optional true} :string]
   [:icon {:optional true} schema:icon-list]
   [:label {:optional true} :string]
   [:aria-label {:optional true} :string]])

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:on-click fn?]
   [:options [:vector schema:option]]
   [:selected {:optional true} :any]
   [:focused {:optional true} :any]
   [:empty-to-end {:optional true} [:maybe :boolean]]
   [:align {:optional true} [:maybe [:enum :left :right]]]])

(def ^:private
  xf:filter-blank-id
  (filter #(str/blank? (get % :id))))

(def ^:private
  xf:filter-non-blank-id
  (remove #(str/blank? (get % :id))))

(defn- render-option
  [option ref on-click selected focused]
  (let [id   (get option :id)
        name (get option :name)
        type (get option :type)]

    (mf/html
     (case type
       :group
       [:li {:class (stl/css :group-option)
             :key (weak-key option)}
        [:> icon*
         {:icon-id i/arrow-down
          :size "m"
          :class (stl/css :option-check)
          :aria-hidden (when name true)}]
        (d/name name)]

       :separator
       [:hr {:key (weak-key option) :class (stl/css :option-separator)}]

       :empty
       [:li {:key (weak-key option) :class (stl/css :option-empty)}
        (get option :label)]

       ;; Token option
       :token
       [:> token-option* {:selected (= id selected)
                          :key (weak-key option)
                          :id id
                          :name name
                          :resolved (get option :resolved-value)
                          :ref ref
                          :focused (= id focused)
                          :on-click on-click}]

       ;; Normal option
       [:> option* {:selected (= id selected)
                    :key (weak-key option)
                    :id id
                    :label (get option :label)
                    :aria-label (get option :aria-label)
                    :icon (get option :icon)
                    :ref ref
                    :focused (= id focused)
                    :dimmed false
                    :on-click on-click}]))))


(mf/defc options-dropdown*
  {::mf/schema schema:options-dropdown}
  [{:keys [ref on-click options selected focused empty-to-end align] :rest props}]
  (let [align
        (d/nilv align :left)

        props
        (mf/spread-props props
                         {:class (stl/css-case :option-list true
                                               :left-align (= align :left)
                                               :right-align (= align :right))
                          :tab-index "-1"
                          :role "listbox"})

        options-blank
        (mf/with-memo [empty-to-end options]
          (when ^boolean empty-to-end
            (into [] xf:filter-blank-id options)))

        options
        (mf/with-memo [empty-to-end options]
          (if ^boolean empty-to-end
            (into [] xf:filter-non-blank-id options)
            options))]

    [:> :ul props
     (for [option options]
       (render-option option ref on-click selected focused))

     (when (seq options-blank)
       [:*
        (when (seq options)
          [:hr {:class (stl/css :option-separator)}])

        (for [option options-blank]
          (render-option option ref on-click selected focused))])]))
