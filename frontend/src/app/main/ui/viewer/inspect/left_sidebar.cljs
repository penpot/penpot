;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.left-sidebar
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.layer-name :refer [layer-name]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-collapsed-iref
  [id]
  #(-> (l/in [:viewer-local :collapsed id])
       (l/derived st/state)))

(mf/defc layer-item
  [{:keys [item selected objects disable-collapse?] :as props}]
  (let [id        (:id item)
        name              (:name item)
        hidden?           (:hidden item)
        touched?          (-> item :touched seq boolean)
        selected? (contains? selected id)
        item-ref  (mf/use-ref nil)

        file              (mf/deref refs/viewer-file)
        components-v2     (dm/get-in file [:data :options :components-v2])
        main-instance?    (if components-v2
                            (ctk/main-instance? item)
                            true)
        collapsed-iref (mf/use-memo
                        (mf/deps id)
                        (make-collapsed-iref id))

        expanded? (not (mf/deref collapsed-iref))
        absolute? (ctl/layout-absolute? item)
        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (st/emit! (dv/toggle-collapse id)))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (let [id (:id item)]
            (cond
              (kbd/mod? event)
              (st/emit! (dv/toggle-selection id))

              (kbd/shift? event)
              (st/emit! (dv/shift-select-to id))

              :else
              (st/emit! (dv/select-shape id)))))]

    (mf/use-effect
     (mf/deps selected)
     (fn []
       (when (and (= (count selected) 1) selected?)
         (dom/scroll-into-view-if-needed! (mf/ref-val item-ref) true))))

    [:li {:ref item-ref
          :class (dom/classnames
                  :component (not (nil? (:component-id item)))
                  :masked (:masked-group item)
                  :selected selected?)}

     [:div.element-list-body {:class (dom/classnames :selected selected?
                                                     :icon-layer (= (:type item) :icon))
                              :on-click select-shape}
      [:div.icon
       (when absolute?
         [:div.absolute i/position-absolute])
       [:& si/element-icon {:shape item :main-instance? main-instance?}]]
      [:& layer-name {:shape-id id
                      :shape-name name
                      :shape-touched? touched?
                      :hidden? hidden?
                      :selected? selected?
                      :type-frame (cph/frame-shape? item)
                      :disabled-double-click true}]

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

(mf/defc left-sidebar
  [{:keys [frame page local]}]
  (let [selected (:selected local)
        objects  (:objects page)]

    [:aside.settings-bar.settings-bar-left
     [:div.settings-bar-inside
      [:ul.element-list
       [:& layer-item
        {:item frame
         :selected selected
         :index 0
         :objects objects
         :disable-collapse? true}]]]]))
