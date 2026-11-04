;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.task-row
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.layer-chip :refer [layer-chip*]]
   [app.main.ui.ds.product.review-label :refer [review-label*]]
   [app.main.ui.ds.product.task-status :as ts :refer [task-status*]]
   [app.util.dom :as dom]
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
   [:review {:optional true} [:maybe :string]]
   [:is-review {:optional true} [:maybe :boolean]]
   [:assignee {:optional true} [:maybe :any]]
   [:actions {:optional true} [:maybe :any]]
   [:on-click {:optional true} [:maybe fn?]]])

(mf/defc task-row*
  "One task of a list: its title over its state and the layer it points
  at, with the assignee (`assignee`, an avatar) and the actions of the
  task (`actions`, a menu) at the end. A task whose layer was deleted
  shows the layer it lost in the warning tone. `review` names the design
  review it belongs to, and `is-review` outlines it selected in the review
  colour."
  {::mf/schema schema:task-row}
  [{:keys [class title status status-label layer is-layer-lost is-selected review is-review
           assignee actions on-click] :rest props}]
  (let [is-selected   (d/nilv is-selected false)
        is-layer-lost (d/nilv is-layer-lost false)
        on-key-down   (mf/use-fn
                       (mf/deps on-click)
                       (fn [event]
                         ;; the keys on the actions of the row are theirs
                         (when (and on-click
                                    (identical? (dom/get-target event) (dom/get-current-target event))
                                    (or (kbd/enter? event) (kbd/space? event)))
                           (dom/prevent-default event)
                           (on-click event))))
        props         (mf/spread-props props
                                       {:class [class (stl/css-case :task-row true
                                                                    :is-selected is-selected
                                                                    :is-review is-review)]
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
     (when review
       [:> review-label* {:label review}])
     [:div {:class (stl/css :footer)}
      [:> task-status* {:status status :label status-label}]
      (when layer
        [:> layer-chip* {:label layer :is-lost is-layer-lost}])
      (when assignee
        [:div {:class (stl/css :assignee)} assignee])]]))
