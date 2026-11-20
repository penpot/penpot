;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.filter-menu
  "The menu that picks which comments or tasks a list shows."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item* menu-separator*]]
   [rumext.v2 :as mf]))

(def ^:private toggle-id
  "The id of the item that turns `toggle-label` on or off; the options
  use the names of their ids."
  "__toggle")

(mf/defc item-content*
  "The label of an item, with a tick when it is in use."
  {::mf/private true}
  [{:keys [label is-selected]}]
  [:span {:class (stl/css-case :item true :is-selected is-selected)}
   [:> text* {:as "span" :typography t/body-small :class (stl/css :item-label)} label]
   (when is-selected
     [:> icon* {:icon-id i/tick :size "s" :class (stl/css :item-tick)}])])

(mf/defc filter-menu*
  "A button naming the view of a list that opens the views to pick from
  (`options`, a vector of maps with `:id` and `:label`; `selected` is the
  id of the one in use), and below them what `toggle-label` turns on or off."
  [{:keys [options selected toggle-label is-toggled on-select on-toggle]}]
  (let [open*   (mf/use-state false)
        open?   (deref open*)
        current (d/seek #(= selected (:id %)) options)

        on-trigger (mf/use-fn #(swap! open* not))
        on-change  (mf/use-fn #(reset! open* %))

        on-action
        (mf/use-fn
         (mf/deps on-select on-toggle)
         (fn [action]
           (if (= toggle-id action)
             (on-toggle)
             (on-select (keyword action)))))]

    [:div {:class (stl/css :filter-menu)}
     [:> menu* {:is-open open?
                :on-open-change on-change
                :on-action on-action
                :placement "bottom start"
                ;; as wide as the button that opens it (`.menu`)
                :max-width "none"
                :class (stl/css :menu)
                :trigger
                (mf/html
                 [:button {:class (stl/css :trigger)
                           :type "button"
                           :aria-haspopup "menu"
                           :aria-expanded open?
                           :on-click on-trigger}
                  [:> text* {:as "span" :typography t/body-small :class (stl/css :trigger-label)}
                   (:label current)]
                  [:> icon* {:icon-id i/arrow-down :size "s" :class (stl/css :trigger-icon)}]])}
      (for [{:keys [id label]} options]
        [:> menu-item* {:key (name id) :id (name id) :text-value label}
         [:> item-content* {:label label :is-selected (= selected id)}]])
      [:> menu-separator* {}]
      [:> menu-item* {:id toggle-id :text-value toggle-label}
       [:> item-content* {:label toggle-label :is-selected (boolean is-toggled)}]]]]))
