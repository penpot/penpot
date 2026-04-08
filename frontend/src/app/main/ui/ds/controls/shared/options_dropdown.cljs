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
   [app.main.ui.ds.controls.shared.render-option :refer [render-option]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-list
  [:and :string
   [:fn {:error/message "invalid data: invalid icon"} #(contains? i/icon-list %)]])

(def ^:private
  xf:filter-blank-id
  (filter #(str/blank? (get % :id))))

(def ^:private
  xf:filter-non-blank-id
  (remove #(str/blank? (get % :id))))

(def schema:option
  "A schema for the option data structure expected to receive on props
  for the `options-dropdown*` component."
  [:map
   [:id {:optional true} :string]
   [:resolved-value {:optional true}
    [:or :int :string :float]]
   [:name {:optional true} :string]
   [:value {:optional true} :keyword]
   [:icon {:optional true} schema:icon-list]
   [:label {:optional true} :string]
   [:aria-label {:optional true} :string]])

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:class {:optional true} :string]
   [:wrapper-ref {:optional true} :any]
   [:on-click fn?]
   [:options [:vector schema:option]]
   [:selected {:optional true} :any]
   [:focused {:optional true} :any]
   [:empty-to-end {:optional true} [:maybe :boolean]]
   [:align {:optional true} [:maybe [:enum :left :right]]]])

(mf/defc options-dropdown*
  {::mf/schema schema:options-dropdown}
  [{:keys [ref on-click options selected focused empty-to-end align wrapper-ref class] :rest props}]
  (let [align
        (d/nilv align :left)

        props
        (mf/spread-props props
                         {:class [class (stl/css-case :option-list true
                                                      :left-align (= align :left)
                                                      :right-align (= align :right))]
                          :ref wrapper-ref
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