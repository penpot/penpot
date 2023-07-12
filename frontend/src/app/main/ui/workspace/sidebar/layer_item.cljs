;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.layer-item
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
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
  [{:keys [index item selected objects sortable? filtered? depth parent-size component-child?]}]
  (let [id                (:id item)
        name              (:name item)
        blocked?          (:blocked item)
        hidden?           (:hidden item)
        touched?          (-> item :touched seq boolean)
        has-shapes?       (-> item :shapes seq boolean)

        drag-disabled*    (mf/use-state false)
        drag-disabled?    (deref drag-disabled*)

        scroll-to-middle? (mf/use-var true)
        expanded-iref     (mf/with-memo [id]
                            (-> (l/in [:expanded id])
                                (l/derived refs/workspace-local)))
        expanded?         (mf/deref expanded-iref)

        selected?         (contains? selected id)
        container?        (or (cph/frame-shape? item)
                              (cph/group-shape? item))
        absolute?         (ctl/layout-absolute? item)

        components-v2     (mf/use-ctx ctx/components-v2)
        read-only?        (mf/use-ctx ctx/workspace-read-only?)
        new-css-system    (mf/use-ctx ctx/new-css-system)
        main-instance?    (if components-v2
                            (:main-instance item)
                            true)
        parent-board?     (and (cph/frame-shape? item)
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
         (fn [_]
           (st/emit! (dw/highlight-shape id))))

        on-pointer-leave
        (mf/use-fn
         (mf/deps id)
         (fn [_]
           (st/emit! (dw/dehighlight-shape id))))

        on-context-menu
        (mf/use-fn
         (mf/deps item read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not read-only?
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

        zoom-to-selected
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (st/emit! dw/zoom-to-selected-shape)))

        [dprops dref]
        (hooks/use-sortable
         :data-type "penpot/layer"
         :on-drop on-drop
         :on-drag on-drag
         :on-hold on-hold
         :disabled drag-disabled?
         :detect-center? container?
         :data {:id (:id item)
                :index index
                :name (:name item)}
         :draggable? (and sortable? (not read-only?)))

        ref             (mf/use-ref)
        depth           (+ depth 1)
        component-tree? (or component-child? (:component-root item))

        enable-drag      (mf/use-fn #(reset! drag-disabled* true))
        disable-drag     (mf/use-fn #(reset! drag-disabled* false))]

    (mf/with-effect [selected? selected]
      (let [single? (= (count selected) 1)
            node    (mf/ref-val ref)
            parent  (dom/get-parent (dom/get-parent node))

            subid
            (when (and single? selected?)
              (let [scroll-to @scroll-to-middle?]
                (ts/schedule
                 100
                 #(if scroll-to
                    (dom/scroll-into-view! parent {:block "center" :behavior "smooth"  :inline "start"})
                    (do
                      (dom/scroll-into-view-if-needed! parent {:block "center" :behavior "smooth" :inline "start"})
                      (reset! scroll-to-middle? true))))))]

        #(when (some? subid)
           (rx/dispose! subid))))

    (if new-css-system
      [:*
       [:div {:on-context-menu on-context-menu
              :ref dref
              :on-click select-shape
              :id id
              :class (stl/css-case
                      :layer-row true
                      :component (some? (:component-id item))
                      :masked (:masked-group item)
                      :selected selected?
                      :type-frame (cph/frame-shape? item)
                      :type-bool (cph/bool-shape? item)
                      :type-comp component-tree?
                      :hidden hidden?
                      :dnd-over (= (:over dprops) :center)
                      :dnd-over-top (= (:over dprops) :top)
                      :dnd-over-bot (= (:over dprops) :bot)
                      :root-board parent-board?)}
        [:span {:class (stl/css-case
                        :tab-indentation true
                        :filtered filtered?)
                :style {"--depth" depth}}]
        [:div {:class (stl/css-case
                       :element-list-body true
                       :filtered filtered?
                       :selected selected?
                       :icon-layer (= (:type item) :icon))
               :style {"--depth" depth}
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave
               :on-double-click dom/stop-propagation}

         (if (< 0 (count (:shapes item)))
           [:div {:class (stl/css :button-content)}
            (when (not filtered?)
              [:button {:class (stl/css-case
                                :toggle-content true
                                :inverse expanded?)
                        :on-click toggle-collapse}
               i/arrow-refactor])

            [:div {:class (stl/css :icon-shape)
                   :on-double-click zoom-to-selected}
             (when absolute?
               [:div {:class (stl/css :absolute)}])

             [:& sic/element-icon-refactor
              {:shape item
               :main-instance? main-instance?}]]]

           [:div {:class (stl/css :button-content)}
            (when (not ^boolean filtered?)
              [:span {:class (stl/css :toggle-content)}])
            [:div {:class (stl/css :icon-shape)
                   :on-double-click zoom-to-selected}
             (when ^boolean absolute?
               [:div {:class (stl/css :absolute)}])
             [:& sic/element-icon-refactor
              {:shape item
               :main-instance? main-instance?}]]])

         [:& layer-name {:ref ref
                         :shape-id id
                         :shape-name name
                         :shape-touched? touched?
                         :disabled-double-click read-only?
                         :on-start-edit disable-drag
                         :on-stop-edit enable-drag
                         :depth depth
                         :parent-size parent-size
                         :selected? selected?
                         :type-comp component-tree?
                         :type-frame (cph/frame-shape? item)
                         :hidden? hidden?}]
         [:div {:class (stl/css-case
                        :element-actions true
                        :is-parent has-shapes?
                        :selected hidden?
                        :selected blocked?)}
          [:button {:class (stl/css-case
                            :toggle-element true
                            :selected hidden?)
                    :on-click toggle-visibility}
           (if ^boolean hidden? i/hide-refactor i/shown-refactor)]
          [:button {:class (stl/css-case
                            :block-element true
                            :selected blocked?)
                    :on-click toggle-blocking}
           (if ^boolean blocked? i/lock-refactor i/unlock-refactor)]]]]

       (when (and (:shapes item) expanded?)
         [:div {:class (stl/css-case
                        :element-children true
                        :parent-selected selected?
                        :sticky-children parent-board?)
                :data-id (when ^boolean parent-board? id)}

          (for [[index id] (reverse (d/enumerate (:shapes item)))]
            (when-let [item (get objects id)]
              [:& layer-item
               {:item item
                :selected selected
                :index index
                :objects objects
                :key (dm/str id)
                :sortable? sortable?
                :depth depth
                :parent-size parent-size
                :component-child? component-tree?}]))])]

      ;; ---- OLD CSS
      [:li {:on-context-menu on-context-menu
            :ref dref
            :class (stl/css-case*
                    :component    (some? (:component-id item))
                    :masked       (:masked-group item)
                    :dnd-over     (= (:over dprops) :center)
                    :dnd-over-top (= (:over dprops) :top)
                    :dnd-over-bot (= (:over dprops) :bot)
                    :selected     selected?
                    :type-frame   (cph/frame-shape? item))}

       [:div.element-list-body {:class (stl/css-case*
                                        :selected selected?
                                        :icon-layer (= (:type item) :icon))
                                :on-click select-shape
                                :on-pointer-enter on-pointer-enter
                                :on-pointer-leave on-pointer-leave
                                :on-double-click dom/stop-propagation}

        [:div.icon {:on-double-click zoom-to-selected}
         (when ^boolean absolute?
           [:div.absolute i/position-absolute])
         [:& si/element-icon
          {:shape item
           :main-instance? main-instance?}]]
        [:& layer-name {:ref ref
                        :parent-size parent-size
                        :shape-id id
                        :shape-name name
                        :shape-touched? touched?
                        :on-start-edit disable-drag
                        :on-stop-edit enable-drag
                        :disabled-double-click read-only?
                        :selected? selected?
                        :type-comp component-tree?
                        :type-frame (cph/frame-shape? item)
                        :hidden? hidden?}]

        [:div.element-actions {:class (when ^boolean has-shapes? "is-parent")}
         [:div.toggle-element {:class (when ^boolean hidden? "selected")
                               :on-click toggle-visibility}
          (if ^boolean hidden? i/eye-closed i/eye)]
         [:div.block-element {:class (when ^boolean blocked? "selected")
                              :on-click toggle-blocking}
          (if ^boolean blocked? i/lock i/unlock)]]

        (when ^boolean has-shapes?
          (when (not ^boolean filtered?)
            [:span.toggle-content
             {:on-click toggle-collapse
              :class (when ^boolean expanded? "inverse")}
             i/arrow-slide]))]

       (when (and ^boolean has-shapes?
                  ^boolean expanded?)
         [:ul.element-children
          (for [[index id] (reverse (d/enumerate (:shapes item)))]
            (when-let [item (get objects id)]
              [:& layer-item
               {:item item
                :selected selected
                :index index
                :objects objects
                :key (dm/str id)
                :sortable? sortable?}]))])])))
