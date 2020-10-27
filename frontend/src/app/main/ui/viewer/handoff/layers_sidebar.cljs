;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.layers-sidebar
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.main.data.viewer :as dv]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.workspace.sidebar.layers :refer [element-icon layer-name frame-wrapper]]))

(def selected-shapes
  (l/derived (comp :selected :viewer-local) st/state))

(def page-ref
  (l/derived (comp :page :viewer-data) st/state))

(defn- make-collapsed-iref
  [id]
  #(-> (l/in [:viewer-local :collapsed id])
       (l/derived st/state) ))

(mf/defc layer-item
  [{:keys [index item selected objects disable-collapse?] :as props}]
  (let [id        (:id item)
        selected? (contains? selected id)
        item-ref (mf/use-ref nil)
        collapsed-iref (mf/use-memo
                        (mf/deps id)
                        (make-collapsed-iref id))

        expanded? (not (mf/deref collapsed-iref))

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (if (and expanded? (kbd/shift? event))
            (st/emit! (dv/collapse-all))
            (st/emit! (dv/toggle-collapse id))))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (let [id (:id item)]
            (st/emit! (dv/select-shape id))
            #_(cond
              (or (:blocked item)
                  (:hidden item))
              nil

              (.-shiftKey event)
              (st/emit! (dv/select-shape id true))

              (> (count selected) 1)
              (st/emit! (dv/deselect-all)
                        (dv/select-shape id))
              :else
              (st/emit! (dv/deselect-all)
                        (dv/select-shape id)))))
        ]

    (mf/use-effect
     (mf/deps selected)
     (fn []
       (when (and (= (count selected) 1) selected?)
         (.scrollIntoView (mf/ref-val item-ref) false))))

    [:li {:ref item-ref
          :class (dom/classnames
                   :component (not (nil? (:component-id item)))
                   :masked (:masked-group? item)
                   :selected selected?)}

     [:div.element-list-body {:class (dom/classnames :selected selected?
                                                     :icon-layer (= (:type item) :icon))
                              :on-click select-shape}
      [:& element-icon {:shape item}]
      [:& layer-name {:shape item}]

      (when (and (not disable-collapse?) (:shapes item))
        [:span.toggle-content
         {:on-click toggle-collapse
          :class (when expanded? "inverse")}
         i/arrow-slide])]

     (when (and (:shapes item) expanded?)
       [:ul.element-children
        (for [[index id] (reverse (d/enumerate (:shapes item)))]
          (when-let [item (get objects id)]
            [:& layer-item
             {:item item
              :selected selected
              :index index
              :objects objects
              :key (:id item)}]))])]))

(mf/defc layers-sidebar [{:keys [frame]}]
  (let [page (mf/deref page-ref)
        selected (mf/deref selected-shapes)
        objects (:objects page)]

    [:aside.settings-bar.settings-bar-left
     [:div.settings-bar-inside
      [:ul.element-list
       [:& layer-item
        {:item frame
         :selected selected
         :index 0
         :objects objects
         :disable-collapse? true}]]]]))
