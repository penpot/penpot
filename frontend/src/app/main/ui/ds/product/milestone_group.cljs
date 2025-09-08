;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.milestone-group
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as cm]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.utilities.date :refer [date*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:milestone-group
  [:map
   [:class {:optional true} :string]
   [:active {:optional true} :boolean]
   [:label :string]
   [:snapshots [:vector ::cm/inst]]])

(mf/defc milestone-group*
  {::mf/schema (sm/schema schema:milestone-group)}
  [{:keys [class active label snapshots on-menu-click] :rest props}]
  (let [class'
        (stl/css-case :milestone true
                      :is-selected active)

        props
        (mf/spread-props props
                         {:class [class class']
                          :data-testid "milestone"})

        open*
        (mf/use-state false)

        open?
        (deref open*)

        on-toggle-visibility
        (mf/use-fn (fn [] (swap! open* not)))

        on-menu-click
        (mf/use-fn
         (mf/deps on-menu-click)
         (fn [event]
           (let [index (-> (dom/get-current-target event)
                           (dom/get-data "index")
                           (d/parse-integer))]
             (when (fn? on-menu-click)
               (on-menu-click index event)))))]

    [:> :div props
     [:> text*  {:as "span" :typography t/body-small :class (stl/css :name)} label]

     [:div {:class (stl/css :snapshots)}
      [:button {:class (stl/css :toggle-snapshots)
                :aria-label (tr "workspace.versions.expand-snapshot")
                :on-click on-toggle-visibility}
       [:> icon* {:icon-id i/clock :class (stl/css :icon-clock)}]
       [:> text* {:as "span"
                  :typography t/body-medium
                  :class (stl/css :toggle-message)}
        (tr "workspace.versions.autosaved.entry" (count snapshots))]
       [:> icon* {:icon-id i/arrow
                  :class (stl/css-case :icon-arrow true
                                       :icon-arrow-toggled open?)}]]

      (when ^boolean open?
        (for [[idx d] (d/enumerate snapshots)]
          [:div {:key (dm/str "entry-" idx)
                 :class (stl/css :version-entry)}
           [:> date* {:date d :class (stl/css :date) :typography t/body-small}]
           [:> icon-button* {:class (stl/css :entry-button)
                             :variant "ghost"
                             :icon i/menu
                             :aria-label (tr "workspace.versions.version-menu")
                             :data-index idx
                             :on-click on-menu-click}]]))]]))

