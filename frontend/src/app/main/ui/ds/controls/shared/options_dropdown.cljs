;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.options-dropdown
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.controls.shared.option :refer [option* schema:option]]
   [app.main.ui.ds.controls.shared.token-option :refer [token-option* schema:token-option]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:on-click fn?]
   [:options [:vector [:or
                       schema:option
                       schema:token-option]]]
   [:selected :any]
   [:focused {:optional true} :any]
   [:empty-to-end {:optional true} :boolean]])

(def ^:private
  xf:filter-blank-id
  (filter #(str/blank? (get % :id))))

(def ^:private
  xf:filter-non-blank-id
  (remove #(str/blank? (get % :id))))

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
             icon       (get option :icon)
             resolved-value (get option :resolved)]
         (if resolved-value
           [:> token-option* {:selected (= id selected)
                              :key id
                              :id id
                              :label label
                              :resolved resolved-value
                              :ref ref
                              :focused (= id focused)
                              :on-click on-click}]
           [:> option* {:selected (= id selected)
                        :key id
                        :id id
                        :label label
                        :icon icon
                        :aria-label aria-label
                        :ref ref
                        :focused (= id focused)
                        :dimmed false
                        :on-click on-click}])))

     (when (seq options-blank)
       [:*
        (when (seq options)
          [:hr {:class (stl/css :option-separator)}])

        (for [option options-blank]
          (let [id         (get option :id)
                label      (get option :label)
                aria-label (get option :aria-label)
                icon       (get option :icon)
                resolved-value (get option :resolved)]
            (if resolved-value
              [:> token-option* {:selected (= id selected)
                                 :key id
                                 :id id
                                 :label label
                                 :resolved resolved-value
                                 :aria-label aria-label
                                 :ref ref
                                 :focused (= id focused)
                                 :on-click on-click}]
              [:> option* {:selected (= id selected)
                           :key id
                           :id id
                           :label label
                           :icon icon
                           :aria-label aria-label
                           :ref ref
                           :focused (= id focused)
                           :dimmed true
                           :on-click on-click}])))])]))
