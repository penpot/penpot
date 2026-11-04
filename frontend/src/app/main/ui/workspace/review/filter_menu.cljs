;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.filter-menu
  "The dropdown that picks which comments or tasks a list shows."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc menu-item*
  {::mf/private true}
  [{:keys [label id is-selected on-click]}]
  [:li
   [:button {:class (stl/css-case :item true
                                  :is-selected is-selected)
             :type "button"
             :data-id id
             :on-click on-click}
    [:> text* {:as "span" :typography t/body-small :class (stl/css :item-label)} label]
    [:> icon* {:icon-id i/tick :size "s" :class (stl/css :item-tick)}]]])

(mf/defc filter-menu*
  "A button naming the view of a list that opens the views to pick from
  (`options`, a vector of maps with `:id` and `:label`; `selected` is the
  id of the one in use), and below them what `toggle-label` turns on or off."
  [{:keys [options selected toggle-label is-toggled on-select on-toggle]}]
  (let [open*   (mf/use-state false)
        open?   (deref open*)
        current (d/seek #(= selected (:id %)) options)

        on-trigger
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! open* not)))

        on-close (mf/use-fn #(reset! open* false))

        on-item
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (on-select (-> (dom/get-current-target event)
                          (dom/get-data "id")
                          (keyword)))))]

    [:div {:class (stl/css :filter-menu)}
     [:button {:class (stl/css :trigger)
               :type "button"
               :aria-haspopup "menu"
               :aria-expanded open?
               :on-click on-trigger}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :trigger-label)}
       (:label current)]
      [:> icon* {:icon-id i/arrow-down :size "s" :class (stl/css :trigger-icon)}]]

     [:& dropdown {:show open? :on-close on-close}
      [:ul {:class (stl/css :menu)}
       (for [{:keys [id label]} options]
         [:> menu-item* {:key (name id)
                         :id (name id)
                         :label label
                         :is-selected (= selected id)
                         :on-click on-item}])
       [:li {:class (stl/css :separator) :role "separator"}]
       [:> menu-item* {:label toggle-label
                       :is-selected is-toggled
                       :on-click on-toggle}]]]]))
