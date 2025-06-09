;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.autosaved-milestone
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.utilities.date :refer [date* valid-date?]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:milestone
  [:map
   [:class {:optional true} :string]
   [:active {:optional true} :boolean]
   [:versionToggled {:optional true} :boolean]
   [:label :string]
   [:autosavedMessage :string]
   [:snapshots [:vector [:fn valid-date?]]]])

(mf/defc autosaved-milestone*
  {::mf/schema schema:milestone}
  [{:keys [class active versionToggled label autosavedMessage snapshots
           onClickSnapshotMenu onToggleExpandSnapshots] :rest props}]
  (let [class (d/append-class class (stl/css-case :milestone true :is-selected active))
        props (mf/spread-props props {:class class :data-testid "milestone"})

        handle-click-menu
        (mf/use-fn
         (mf/deps onClickSnapshotMenu)
         (fn [event]
           (let [index (-> (dom/get-current-target event)
                           (dom/get-data "index")
                           (d/parse-integer))]
             (when onClickSnapshotMenu
               (onClickSnapshotMenu event index)))))]
    [:> "div" props
     [:> text*  {:as "span" :typography t/body-small :class (stl/css :name)} label]

     [:div {:class (stl/css :snapshots)}
      [:button {:class (stl/css :toggle-snapshots)
                :aria-label (tr "workspace.versions.expand-snapshot")
                :on-click onToggleExpandSnapshots}
       [:> i/icon* {:icon-id i/clock :class (stl/css :icon-clock)}]
       [:> text* {:as "span" :typography t/body-medium :class (stl/css :toggle-message)} autosavedMessage]
       [:> i/icon* {:icon-id i/arrow :class (stl/css-case :icon-arrow true :icon-arrow-toggled versionToggled)}]]

      (when versionToggled
        (for [[idx d] (d/enumerate snapshots)]
          [:div {:key (dm/str "entry-" idx)
                 :class (stl/css :version-entry)}
           [:> date* {:date d :class (stl/css :date) :typography t/body-small}]
           [:> icon-button* {:class (stl/css :entry-button)
                             :variant "ghost"
                             :icon "menu"
                             :aria-label (tr "workspace.versions.version-menu")
                             :data-index idx
                             :on-click handle-click-menu}]]))]]))

