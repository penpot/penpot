;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.layer-item
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.components.shape-icon-refactor :as sic]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.layer-name :refer [layer-name]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.v2 :as mf]))


(mf/defc layer-item
  {::mf/wrap-props false}
  [{:keys [index item selected objects sortable? filtered? recieved-depth parent-size component-child?]}]
  (let [id                   (:id item)
        blocked?             (:blocked item)
        hidden?              (:hidden item)

        disable-drag         (mf/use-state false)
        scroll-to-middle?    (mf/use-var true)
        expanded-iref        (mf/with-memo [id]
                               (-> (l/in [:expanded id])
                                   (l/derived refs/workspace-local)))

        expanded?            (mf/deref expanded-iref)
        selected?            (contains? selected id)
        container?           (or (cph/frame-shape? item)
                                 (cph/group-shape? item))
        absolute?            (ctl/layout-absolute? item)

        components-v2        (mf/use-ctx ctx/components-v2)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        new-css-system       (mf/use-ctx ctx/new-css-system)
        main-instance?       (if components-v2
                               (:main-instance item)
                               true)
        parent-board? (and (= :frame (:type item))
                           (= uuid/zero (:parent-id item)))
        toggle-collapse
        (mf/use-fn
         (mf/deps expanded?)
         (fn [event]
           (dom/stop-propagation event)
           (if (and expanded? (kbd/shift? event))
             (st/emit! (dwc/collapse-all))
             (st/emit! (dwc/toggle-collapse id)))))

        toggle-blocking
        (mf/use-fn
         (mf/deps id blocked?)
         (fn [event]
           (dom/stop-propagation event)
           (if blocked?
             (st/emit! (dw/update-shape-flags [id] {:blocked false}))
             (st/emit! (dw/update-shape-flags [id] {:blocked true})
                       (dw/deselect-shape id)))))

        toggle-visibility
        (mf/use-fn
         (mf/deps hidden?)
         (fn [event]
           (dom/stop-propagation event)
           (if hidden?
             (st/emit! (dw/update-shape-flags [id] {:hidden false}))
             (st/emit! (dw/update-shape-flags [id] {:hidden true})))))

        select-shape
        (mf/use-fn
         (mf/deps id filtered? objects)
         (fn [event]
           (dom/prevent-default event)
           (reset! scroll-to-middle? false)
           (cond
             (kbd/shift? event)
             (if filtered?
               (st/emit! (dw/shift-select-shapes id objects))
               (st/emit! (dw/shift-select-shapes id)))

             (kbd/mod? event)
             (st/emit! (dw/select-shape id true))

             (> (count selected) 1)
             (st/emit! (dw/select-shape id))

             :else
             (st/emit! (dw/select-shape id)))))

        on-pointer-enter
        (mf/use-fn
         (mf/deps id)
         (fn [_event]
           (st/emit! (dw/highlight-shape id))))

        on-pointer-leave
        (mf/use-fn
         (mf/deps id)
         (fn [_event]
           (st/emit! (dw/dehighlight-shape id))))

        on-context-menu
        (mf/use-fn
         (mf/deps item workspace-read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not workspace-read-only?
             (let [pos (dom/get-client-position event)]
               (st/emit! (dw/show-shape-context-menu {:position pos :shape item}))))))

        on-drag
        (mf/use-fn
         (mf/deps id selected)
         (fn [{:keys [id]}]
           (when (not (contains? selected id))
             (st/emit! (dw/select-shape id)))))

        on-drop
        (mf/use-fn
         (mf/deps id index objects)
         (fn [side _data]
           (if (= side :center)
             (st/emit! (dw/relocate-selected-shapes id 0))
             (let [to-index  (if (= side :top) (inc index) index)
                   parent-id (cph/get-parent-id objects id)]
               (st/emit! (dw/relocate-selected-shapes parent-id to-index))))))

        on-hold
        (mf/use-fn
         (mf/deps id expanded?)
         (fn []
           (when-not expanded?
             (st/emit! (dwc/toggle-collapse id)))))

        [dprops dref]
        (hooks/use-sortable
         :data-type "penpot/layer"
         :on-drop on-drop
         :on-drag on-drag
         :on-hold on-hold
         :disabled @disable-drag
         :detect-center? container?
         :data {:id (:id item)
                :index index
                :name (:name item)}
         :draggable? (and sortable? (not workspace-read-only?)))

        ref         (mf/use-ref)
        depth (+ recieved-depth 1)
        component-tree? (or component-child? (:component-root item))]

    (mf/with-effect [selected? selected]
      (let [single? (= (count selected) 1)
            node (mf/ref-val ref)
            parent-node (dom/get-parent (dom/get-parent node))

            subid
            (when (and single? selected?)
              (let [scroll-to @scroll-to-middle?]
                (ts/schedule
                 100
                 #(if scroll-to
                    (dom/scroll-into-view! parent-node #js {:block "center" :behavior "smooth"  :inline "start"})
                    (do
                      (dom/scroll-into-view-if-needed! parent-node #js {:block "center" :behavior "smooth" :inline "start"})
                      (reset! scroll-to-middle? true))))))]

        #(when (some? subid)
           (rx/dispose! subid))))

    (if new-css-system
      [:*
       [:div {:on-context-menu on-context-menu
              :ref dref
              :on-click select-shape
              :id id
              :class (dom/classnames
                      (css :layer-row) true
                      (css :component) (not (nil? (:component-id item)))
                      (css :masked) (:masked-group item)
                      (css :selected) selected?
                      (css :type-frame) (= :frame (:type item))
                      (css :type-bool) (= :bool (:type item))
                      (css :type-comp) component-tree?
                      (css :hidden) (:hidden item)
                      :dnd-over (= (:over dprops) :center)
                      :dnd-over-top (= (:over dprops) :top)
                      :dnd-over-bot (= (:over dprops) :bot)
                      :root-board parent-board?)}
        [:span {:class (dom/classnames (css :tab-indentation) true
                                       (css :filtered) filtered?)
                :style #js {"--depth" depth}}]
        [:div {:class (dom/classnames (css :element-list-body) true
                                      (css :filtered) filtered?
                                      (css :selected) selected?
                                      (css :icon-layer) (= (:type item) :icon))
               :style #js {"--depth" depth}
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave
               :on-double-click #(dom/stop-propagation %)}

         (if (< 0 (count (:shapes item)))
           [:div {:class (dom/classnames (css :button-content) true)}
            (when (not filtered?)
              [:button {:class (dom/classnames (css :toggle-content) true
                                               (css :inverse) expanded?)
                        :on-click toggle-collapse}
               i/arrow-refactor])

            [:div {:class (dom/classnames (css :icon-shape) true)
                   :on-double-click #(do (dom/stop-propagation %)
                                         (dom/prevent-default %)
                                         (st/emit! dw/zoom-to-selected-shape))}
             (when absolute?
               [:div {:class (dom/classnames (css :absolute) true)} ])
             [:& sic/element-icon-refactor {:shape item
                                            :main-instance? main-instance?}]]]
           [:div {:class (dom/classnames (css :button-content) true)}
            (when (not filtered?)
              [:span {:class (dom/classnames (css :toggle-content) true)}])
            [:div {:class (dom/classnames (css :icon-shape) true)
                   :on-double-click #(do (dom/stop-propagation %)
                                         (dom/prevent-default %)
                                         (st/emit! dw/zoom-to-selected-shape))}
             (when absolute?
               [:div {:class (dom/classnames (css :absolute) true)} ])
             [:& sic/element-icon-refactor {:shape item
                                            :main-instance? main-instance?}]]])

         [:& layer-name {:ref ref
                         :shape-id (:id item)
                         :shape-name (:name item)
                         :shape-touched? (boolean (seq (:touched item)))
                         :disabled-double-click workspace-read-only?
                         :on-start-edit #(reset! disable-drag true)
                         :on-stop-edit #(reset! disable-drag false)
                         :depth depth
                         :parent-size parent-size
                         :selected? selected?
                         :type-comp component-tree?
                         :type-frame (= :frame (:type item))
                         :hidden? (:hidden item)}]
         [:div {:class (dom/classnames (css :element-actions) true
                                       (css :is-parent) (:shapes item)
                                       (css :selected) (:hidden item)
                                       (css :selected) (:blocked item))}
          [:button {:class (dom/classnames (css :toggle-element) true
                                           (css :selected) (:hidden item))
                    :on-click toggle-visibility}
           (if (:hidden item) i/hide-refactor i/shown-refactor)]
          [:button {:class (dom/classnames (css :block-element) true
                                           (css :selected) (:blocked item))
                    :on-click toggle-blocking}
           (if (:blocked item) i/lock-refactor i/unlock-refactor)]]]]
       (when (and (:shapes item) expanded?)
         [:div {:class (dom/classnames (css :element-children) true
                                       (css :parent-selected) selected?
                                       :sticky-children parent-board?)
                :data-id (when parent-board? (:id item))}
          (for [[index id] (reverse (d/enumerate (:shapes item)))]
            (when-let [item (get objects id)]
              [:& layer-item
               {:item item
                :selected selected
                :index index
                :objects objects
                :key (:id item)
                :sortable? sortable?
                :recieved-depth depth
                :parent-size parent-size
                :component-child? component-tree?}]))])]
      [:li {:on-context-menu on-context-menu
            :ref dref
            :class (dom/classnames
                    :component (not (nil? (:component-id item)))
                    :masked (:masked-group item)
                    :dnd-over (= (:over dprops) :center)
                    :dnd-over-top (= (:over dprops) :top)
                    :dnd-over-bot (= (:over dprops) :bot)
                    :selected selected?
                    :type-frame (= :frame (:type item)))}

       [:div.element-list-body {:class (dom/classnames :selected selected?
                                                       :icon-layer (= (:type item) :icon))
                                :on-click select-shape
                                :on-pointer-enter on-pointer-enter
                                :on-pointer-leave on-pointer-leave
                                :on-double-click #(dom/stop-propagation %)}

        [:div.icon {:on-double-click #(do (dom/stop-propagation %)
                                          (dom/prevent-default %)
                                          (st/emit! dw/zoom-to-selected-shape))}
         (when absolute?
           [:div.absolute i/position-absolute])
         [:& si/element-icon {:shape item
                              :main-instance? main-instance?}]]
        [:& layer-name {:ref ref
                        :shape-id (:id item)
                        :shape-name (:name item)
                        :shape-touched? (boolean (seq (:touched item)))
                        :on-start-edit #(reset! disable-drag true)
                        :on-stop-edit #(reset! disable-drag false)
                        :disabled-double-click workspace-read-only?
                        :selected? selected?
                        :type-comp component-tree?
                        :type-frame (= :frame (:type item))
                        :hidden? (:hidden item)}]

        [:div.element-actions {:class (when (:shapes item) "is-parent")}
         [:div.toggle-element {:class (when (:hidden item) "selected")
                               :on-click toggle-visibility}
          (if (:hidden item) i/eye-closed i/eye)]
         [:div.block-element {:class (when (:blocked item) "selected")
                              :on-click toggle-blocking}
          (if (:blocked item) i/lock i/unlock)]]

        (when (:shapes item)
          (when (not filtered?) [:span.toggle-content
                                 {:on-click toggle-collapse
                                  :class (when expanded? "inverse")}
                                 i/arrow-slide]))]
       (when (and (:shapes item) expanded?)
         [:ul.element-children
          (for [[index id] (reverse (d/enumerate (:shapes item)))]
            (when-let [item (get objects id)]
              [:& layer-item
               {:item item
                :selected selected
                :index index
                :objects objects
                :key (:id item)
                :sortable? sortable?}]))])])))
