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
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.layer-name :refer [layer-name*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.shape-icon :as usi]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc layer-item-inner
  {::mf/wrap-props false}
  [{:keys [item depth parent-size name-ref children ref
           ;; Flags
           read-only? highlighted? selected? component-tree?
           filtered? expanded? dnd-over? dnd-over-top? dnd-over-bot? hide-toggle?
           ;; Callbacks
           on-select-shape on-context-menu on-pointer-enter on-pointer-leave on-zoom-to-selected
           on-toggle-collapse on-enable-drag on-disable-drag on-toggle-visibility on-toggle-blocking]}]

  (let [id                    (:id item)
        name                  (:name item)
        blocked?              (:blocked item)
        hidden?               (:hidden item)
        has-shapes?           (-> item :shapes seq boolean)
        touched?              (-> item :touched seq boolean)
        parent-board?         (and (cfh/frame-shape? item)
                                   (= uuid/zero (:parent-id item)))
        absolute?             (ctl/item-absolute? item)
        is-variant?           (ctk/is-variant? item)
        is-variant-container? (ctk/is-variant-container? item)
        variant-id            (when is-variant? (:variant-id item))
        variant-name          (when is-variant? (:variant-name item))
        variant-error         (when is-variant? (:variant-error item))

        data                  (deref refs/workspace-data)
        component             (ctkl/get-component data (:component-id item))
        variant-properties    (:variant-properties component)
        icon-shape            (usi/get-shape-icon item)]

    [:*
     [:div {:id id
            :ref ref
            :on-click on-select-shape
            :on-context-menu on-context-menu
            :data-testid "layer-row"
            :class (stl/css-case
                    :layer-row true
                    :highlight highlighted?
                    :component (ctk/instance-head? item)
                    :masked (:masked-group item)
                    :selected selected?
                    :type-frame (cfh/frame-shape? item)
                    :type-bool (cfh/bool-shape? item)
                    :type-comp (or component-tree? is-variant-container?)
                    :hidden hidden?
                    :dnd-over dnd-over?
                    :dnd-over-top dnd-over-top?
                    :dnd-over-bot dnd-over-bot?
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
          (when (and (not hide-toggle?) (not filtered?))
            [:button {:class (stl/css-case
                              :toggle-content true
                              :inverse expanded?)
                      :on-click on-toggle-collapse}
             deprecated-icon/arrow])

          [:div {:class (stl/css :icon-shape)
                 :on-double-click on-zoom-to-selected}
           (when absolute?
             [:div {:class (stl/css :absolute)}])
           [:> icon* {:icon-id icon-shape :size "s" :data-testid (str "icon-" icon-shape)}]]]

         [:div {:class (stl/css :button-content)}
          (when (not ^boolean filtered?)
            [:span {:class (stl/css :toggle-content)}])
          [:div {:class (stl/css :icon-shape)
                 :on-double-click on-zoom-to-selected}
           (when ^boolean absolute?
             [:div {:class (stl/css :absolute)}])
           [:> icon* {:icon-id icon-shape :size "s" :data-testid (str "icon-" icon-shape)}]]])

       [:> layer-name* {:ref name-ref
                        :shape-id id
                        :shape-name name
                        :is-shape-touched touched?
                        :disabled-double-click read-only?
                        :on-start-edit on-disable-drag
                        :on-stop-edit on-enable-drag
                        :depth depth
                        :is-blocked blocked?
                        :parent-size parent-size
                        :is-selected selected?
                        :type-comp (or component-tree? is-variant-container?)
                        :type-frame (cfh/frame-shape? item)
                        :variant-id variant-id
                        :variant-name variant-name
                        :variant-properties variant-properties
                        :variant-error variant-error
                        :component-id (:id component)
                        :is-hidden hidden?}]

       (when (not read-only?)
         [:div {:class (stl/css-case
                        :element-actions true
                        :is-parent has-shapes?
                        :selected hidden?
                        :selected blocked?)}
          [:button {:class (stl/css-case
                            :toggle-element true
                            :selected hidden?)
                    :title (if hidden?
                             (tr "workspace.shape.menu.show")
                             (tr "workspace.shape.menu.hide"))
                    :on-click on-toggle-visibility}
           (if ^boolean hidden? deprecated-icon/hide deprecated-icon/shown)]
          [:button {:class (stl/css-case
                            :block-element true
                            :selected blocked?)
                    :title (if (:blocked item)
                             (tr "workspace.shape.menu.unlock")
                             (tr "workspace.shape.menu.lock"))
                    :on-click on-toggle-blocking}
           (if ^boolean blocked? deprecated-icon/lock deprecated-icon/unlock)]])]]

     children]))

(mf/defc layer-item
  {::mf/props :obj
   ::mf/memo true}
  [{:keys [index item selected objects sortable? filtered? depth parent-size component-child? highlighted]}]
  (let [id                (:id item)
        blocked?          (:blocked item)
        hidden?           (:hidden item)

        drag-disabled*    (mf/use-state false)
        drag-disabled?    (deref drag-disabled*)

        scroll-to-middle? (mf/use-var true)
        expanded-iref     (mf/with-memo [id]
                            (-> (l/in [:expanded id])
                                (l/derived refs/workspace-local)))
        expanded?         (mf/deref expanded-iref)

        selected?         (contains? selected id)
        highlighted?      (contains? highlighted id)

        container?        (or (cfh/frame-shape? item)
                              (cfh/group-shape? item))

        read-only?        (mf/use-ctx ctx/workspace-read-only?)
        parent-board?     (and (cfh/frame-shape? item)
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
         (mf/deps id index objects expanded? selected)
         (fn [side _data]
           (let [single? (= (count selected) 1)
                 same?   (and single? (= (first selected) id))]
             (when-not same?
               (let [files (deref refs/files)
                     shape (get objects id)

                     parent-id
                     (cond
                       (= side :center)
                       id

                       (and expanded? (= side :bot) (d/not-empty? (:shapes shape)))
                       id

                       :else
                       (cfh/get-parent-id objects id))

                     [parent-id _] (ctn/find-valid-parent-and-frame-ids parent-id objects (map #(get objects %) selected) false files)

                     parent    (get objects parent-id)

                     to-index  (cond
                                 (= side :center) 0
                                 (and expanded? (= side :bot) (d/not-empty? (:shapes shape))) (count (:shapes parent))
                                 (= side :top) (inc index)
                                 :else index)]
                 (st/emit! (dw/relocate-selected-shapes parent-id to-index)))))))

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
         :draggable? (and
                      sortable?
                      (not read-only?)
                      (not (ctn/has-any-copy-parent? objects item)))) ;; We don't want to change the structure of component copies

        ref             (mf/use-ref)
        depth           (+ depth 1)
        component-tree? (or component-child? (ctk/instance-root? item) (ctk/instance-head? item))

        enable-drag      (mf/use-fn #(reset! drag-disabled* false))
        disable-drag     (mf/use-fn #(reset! drag-disabled* true))]

    (mf/with-effect [selected? selected]
      (let [single? (= (count selected) 1)
            node (mf/ref-val ref)
            ;; NOTE: Neither get-parent-at nor get-parent-with-selector
            ;; work if the component template changes, so we need to
            ;; seek for an alternate solution. Maybe use-context?
            scroll-node (dom/get-parent-with-data node "scroll-container")
            parent-node (dom/get-parent-at node 2)
            first-child-node (dom/get-first-child parent-node)

            subid
            (when (and single? selected? @scroll-to-middle?)
              (ts/schedule
               100
               #(when (and node scroll-node)
                  (let [scroll-distance-ratio (dom/get-scroll-distance-ratio node scroll-node)
                        scroll-behavior (if (> scroll-distance-ratio 1) "instant" "smooth")]
                    (dom/scroll-into-view-if-needed! first-child-node #js {:block "center" :behavior scroll-behavior :inline "start"})
                    (reset! scroll-to-middle? true)))))]

        #(when (some? subid)
           (rx/dispose! subid))))

    [:& layer-item-inner
     {:ref dref
      :item item
      :depth depth
      :parent-size parent-size
      :name-ref ref
      :read-only? read-only?
      :highlighted? highlighted?
      :selected? selected?
      :component-tree? component-tree?
      :filtered? filtered?
      :expanded? expanded?
      :dnd-over? (= (:over dprops) :center)
      :dnd-over-top? (= (:over dprops) :top)
      :dnd-over-bot? (= (:over dprops) :bot)
      :on-select-shape select-shape
      :on-context-menu on-context-menu
      :on-pointer-enter on-pointer-enter
      :on-pointer-leave on-pointer-leave
      :on-zoom-to-selected zoom-to-selected
      :on-toggle-collapse toggle-collapse
      :on-enable-drag enable-drag
      :on-disable-drag disable-drag
      :on-toggle-visibility toggle-visibility
      :on-toggle-blocking toggle-blocking}

     (when (and (:shapes item) expanded?)
       [:div {:class (stl/css-case
                      :element-children true
                      :parent-selected selected?
                      :sticky-children parent-board?)
              :data-testid (dm/str "children-" id)}
        (for [[index id] (reverse (d/enumerate (:shapes item)))]
          (when-let [item (get objects id)]
            [:& layer-item
             {:item item
              :highlighted highlighted
              :selected selected
              :index index
              :objects objects
              :key (dm/str id)
              :sortable? sortable?
              :depth depth
              :parent-size parent-size
              :component-child? component-tree?}]))])]))
