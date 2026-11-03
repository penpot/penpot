;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.ds.product.task-row
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.task-status :as ts :refer [task-status*]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(def ^:private schema:task-row
  [:map
   [:class {:optional true} [:maybe :string]]
   [:title :string]
   [:status (into [:enum] ts/statuses)]
   [:status-label :string]
   [:layer {:optional true} [:maybe :string]]
   [:is-layer-lost {:optional true} [:maybe :boolean]]
   [:is-selected {:optional true} [:maybe :boolean]]
   [:on-click {:optional true} [:maybe fn?]]])

(mf/defc task-row*
  "One task of a list: its title over its state and the layer it points
  at, with the assignee (`assignee`, an avatar) and the actions of the
  task (`actions`, a menu) at the end. A task whose layer was deleted
  shows the layer it lost in the warning tone."
  {::mf/schema schema:task-row}
  [{:keys [class title status status-label layer is-layer-lost is-selected
           assignee actions on-click] :rest props}]
  (let [is-selected   (d/nilv is-selected false)
        is-layer-lost (d/nilv is-layer-lost false)
        on-key-down   (mf/use-fn
                       (mf/deps on-click)
                       (fn [event]
                         (when (and on-click (or (kbd/enter? event) (kbd/space? event)))
                           (.preventDefault event)
                           (on-click event))))
        props         (mf/spread-props props
                                       {:class [class (stl/css-case :task-row true
                                                                    :is-selected is-selected)]
                                        :role "button"
                                        :tab-index 0
                                        :aria-pressed is-selected
                                        :on-click on-click
                                        :on-key-down on-key-down})]
    [:> :div props
     [:div {:class (stl/css :header)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :title)} title]
      (when actions
        [:div {:class (stl/css :actions)} actions])]
     [:div {:class (stl/css :footer)}
      [:> task-status* {:status status :label status-label}]
      (when layer
        [:span {:class (stl/css-case :layer true
                                     :is-lost is-layer-lost)}
         [:> icon* {:icon-id (if is-layer-lost i/triangle-alert i/board) :size "s"}]
         [:span {:class (stl/css :layer-name)} layer]])
      (when assignee
        [:div {:class (stl/css :assignee)} assignee])]]))
