;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.options-dropdown
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.ds.controls.shared.option :refer [option* schema:option]]
   [app.main.ui.ds.controls.shared.token-option :refer [token-option* schema:token-option]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:on-click fn?]
   ;; [:options [:vector schema:token-option]]
   [:options [:vector [:or
                       schema:option
                       schema:token-option]]]

   [:token-option {:optional true} :boolean]
    ;; [:selected :any]
   [:focused {:optional true} :any]
   [:empty-to-end {:optional true} :boolean]])

(def ^:private
  xf:filter-blank-id
  (filter #(str/blank? (get % :id))))

(def ^:private
  xf:filter-non-blank-id
  (remove #(str/blank? (get % :id))))

(mf/defc options-dropdown*
  ;; {::mf/schema schema:options-dropdown}
  [{:keys [ref on-click options selected focused empty-to-end token-option] :rest props}]
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
             name       (get option :name)
             group      (get option :group)
             resolved-value (get option :resolved-value)
             separator (get option :separator)]
         (if token-option
           [:> token-option* {:selected (= id selected)
                              :key (or id (uuid/next))
                              :id id
                              :name name
                              :resolved resolved-value
                              :ref ref
                              :group group
                              :separator separator
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
                name       (get option :name)
                aria-label (get option :aria-label)
                icon       (get option :icon)
                group      (get option :group)
                resolved-value (get option :resolved-value)]
            (if token-option
              [:> token-option* {:selected (= name selected)
                                 :key id
                                 :id id
                                 :label label
                                 :name name
                                 :group group
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
