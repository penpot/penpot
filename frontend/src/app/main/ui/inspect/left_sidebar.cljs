;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.left-sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctk]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.layer-item :refer [layer-item-inner]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-collapsed-iref
  [id]
  #(-> (l/in [:viewer-local :collapsed id])
       (l/derived st/state)))

(mf/defc layer-item
  [{:keys [item selected objects  depth component-child? hide-toggle?] :as props}]
  (let [id        (:id item)
        hidden?   (:hidden item)
        selected? (contains? selected id)
        item-ref  (mf/use-ref nil)
        depth     (+ depth 1)

        component-tree? (or component-child? (ctk/instance-root? item) (ctk/instance-head? item))

        collapsed-iref
        (mf/use-memo
         (mf/deps id)
         (make-collapsed-iref id))

        expanded? (not (mf/deref collapsed-iref))

        toggle-collapse
        (mf/use-callback
         (mf/deps id)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dv/toggle-collapse id))))

        select-shape
        (mf/use-callback
         (mf/deps id)
         (fn [event]
           (dom/prevent-default event)
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

    [:& layer-item-inner
     {:ref item-ref
      :item item
      :depth depth
      :read-only? true
      :highlighted? false
      :selected? selected?
      :component-tree? component-tree?
      :hidden? hidden?
      :filtered? false
      :expanded? expanded?
      :hide-toggle? hide-toggle?
      :on-select-shape select-shape
      :on-toggle-collapse toggle-collapse}

     (when (and (:shapes item) expanded?)
       [:div {:class (stl/css-case
                      :element-children true
                      :parent-selected selected?)
              :data-testid (dm/str "children-" id)}
        (for [[index id] (reverse (d/enumerate (:shapes item)))]
          (when-let [item (get objects id)]
            [:& layer-item
             {:item item
              :selected selected
              :index index
              :objects objects
              :key (dm/str id)
              :depth depth
              :component-child? component-tree?}]))])]))

(mf/defc left-sidebar
  [{:keys [frame page local]}]
  (let [selected (:selected local)
        objects  (:objects page)]

    [:aside {:class (stl/css :settings-bar-left)}
     [:div {:class (stl/css :settings-bar-inside)}
      [:div {:class (stl/css :element-list)}
       [:& layer-item
        {:item frame
         :selected selected
         :index 0
         :objects objects
         :sortable? false
         :filtered? false
         :depth -2
         :hide-toggle? true}]]]]))
