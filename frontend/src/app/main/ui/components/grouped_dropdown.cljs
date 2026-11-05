;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.components.grouped-dropdown
  "Dropdown list of options split in groups by non-selectable header rows,
  with an optional search bar. The caller owns the trigger and the open
  state, and positions the list from the nearest positioned ancestor."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc option-row*
  {::mf/private true}
  [{:keys [option on-select]}]
  (let [{:keys [id label detail selected]} option

        handle-click
        (mf/use-fn
         (mf/deps id on-select)
         (fn [event]
           (on-select id event)))

        handle-key-down
        (mf/use-fn
         (mf/deps id on-select)
         (fn [event]
           (when (kbd/enter? event)
             (dom/prevent-default event)
             (on-select id event))))]
    [:li {:class (stl/css-case :option true
                               :selected selected)
          :role "option"
          :aria-selected (boolean selected)
          :tab-index 0
          :data-testid (str "grouped-dropdown-option-" id)
          :on-click handle-click
          :on-key-down handle-key-down}
     [:div {:class (stl/css :option-text)}
      [:span {:class (stl/css :option-label)} label]
      (when (some? detail)
        [:span {:class (stl/css :option-detail)} detail])]
     (when selected
       [:span {:class (stl/css :check-icon)} deprecated-icon/tick])]))

(mf/defc grouped-dropdown*
  "`options` holds the rows in display order: a header row is
  `{:id :label :header true}`, an option row `{:id :label}` with an optional
  `:detail` text and `:selected` flag. `on-select` receives the option id and
  the event. Passing `on-search-change` shows the search bar; `empty-label`
  is the row shown when `options` is empty."
  [{:keys [show on-close container options on-select class
           search-term search-placeholder on-search-change empty-label]}]
  [:& dropdown {:show show
                :on-close on-close
                :container container}
   [:div {:class [class (stl/css :dropdown)]
          :on-click dom/stop-propagation}
    (when (some? on-search-change)
      [:div {:class (stl/css :search)}
       [:> search-bar* {:on-change on-search-change
                        :value search-term
                        :auto-focus true
                        :placeholder search-placeholder}]])
    [:ul {:class (stl/css :option-list)
          :role "listbox"}
     (if (empty? options)
       [:li {:class (stl/css :empty)} empty-label]
       (for [option options]
         (if (:header option)
           [:li {:key (:id option)
                 :class (stl/css :header)
                 :role "presentation"}
            (:label option)]
           [:> option-row* {:key (:id option)
                            :option option
                            :on-select on-select}])))]]])
