;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.options-dropdown
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-list
  [:and :string
   [:fn {:error/message "invalid data: invalid icon"} #(contains? i/icon-list %)]])

(def schema:option
  [:and
   [:map {:title "option"}
    [:id :string]
    [:icon {:optional true} schema:icon-list]
    [:label {:optional true} :string]
    [:aria-label {:optional true} :string]]
   [:fn {:error/message "invalid data: missing required props"}
    (fn [option]
      (or (and (contains? option :icon)
               (or (contains? option :label)
                   (contains? option :aria-label)))
          (contains? option :label)))]])

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:on-click fn?]
   [:options [:vector schema:option]]
   [:selected :any]
   [:focused {:optional true} :any]
   [:empty-to-end {:optional true} :boolean]])

(def ^:private
  xf:filter-blank-id
  (filter #(str/blank? (get % :id))))

(def ^:private
  xf:filter-non-blank-id
  (remove #(str/blank? (get % :id))))

(mf/defc option*
  {::mf/private true}
  [{:keys [id ref label icon aria-label on-click selected focused dimmed] :rest props}]
  (let [class (stl/css-case :option true
                            :option-with-icon (some? icon)
                            :option-selected selected
                            :option-current focused)]
    [:li {:value id
          :class class
          :aria-selected selected
          :ref ref
          :role "option"
          :id id
          :on-click on-click
          :data-id id
          :data-testid "dropdown-option"}

     (when (some? icon)
       [:> i/icon*
        {:icon-id icon
         :size "s"
         :class (stl/css :option-icon)
         :aria-hidden (when label true)
         :aria-label  (when (not label) aria-label)}])

     [:span {:class (stl/css-case :option-text true
                                  :option-text-dimmed dimmed)}
      label]

     (when selected
       [:> i/icon*
        {:icon-id i/tick
         :size "s"
         :class (stl/css :option-check)
         :aria-hidden (when label true)}])]))

(mf/defc options-dropdown*
  {::mf/schema schema:options-dropdown}
  [{:keys [ref on-click options selected focused empty-to-end] :rest props}]
  (let [props
        (mf/spread-props props
                         {:class (stl/css :option-list)
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
       (let [id         (get option :id)
             label      (get option :label)
             aria-label (get option :aria-label)
             icon       (get option :icon)]
         [:> option* {:selected (= id selected)
                      :key id
                      :id id
                      :label label
                      :icon icon
                      :aria-label aria-label
                      :ref ref
                      :focused (= id focused)
                      :dimmed false
                      :on-click on-click}]))

     (when (seq options-blank)
       [:*
        (when (seq options)
          [:hr {:class (stl/css :option-separator)}])

        (for [option options-blank]
          (let [id         (get option :id)
                label      (get option :label)
                aria-label (get option :aria-label)
                icon       (get option :icon)]
            [:> option* {:selected (= id selected)
                         :key id
                         :id id
                         :label label
                         :icon icon
                         :aria-label aria-label
                         :ref ref
                         :focused (= id focused)
                         :dimmed true
                         :on-click on-click}]))])]))
