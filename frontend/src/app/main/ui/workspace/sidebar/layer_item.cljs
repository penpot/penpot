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

;; Coalesce sidebar hover highlights to 1 frame to avoid long tasks
(defonce ^:private sidebar-hover-queue (atom {:enter #{} :leave #{}}))
(defonce ^:private sidebar-hover-pending? (atom false))

(def ^:const default-chunk-size 50)

(defn- schedule-sidebar-hover-flush []
  (when (compare-and-set! sidebar-hover-pending? false true)
    (ts/raf
     (fn []
       (let [{:keys [enter leave]} (swap! sidebar-hover-queue (constantly {:enter #{} :leave #{}}))]
         (reset! sidebar-hover-pending? false)
         (when (seq leave)
           (apply st/emit! (map dw/dehighlight-shape leave)))
         (when (seq enter)
           (apply st/emit! (map dw/highlight-shape enter))))))))

(mf/defc layer-item-inner
  {::mf/wrap-props false}
  [{:keys [item depth parent-size name-ref children ref style
           ;; Flags
           is-read-only is-highlighted is-selected is-component-tree
           is-filtered is-expanded dnd-over dnd-over-top dnd-over-bot hide-toggle
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
                    :highlight is-highlighted
                    :component (ctk/instance-head? item)
                    :masked (:masked-group item)
                    :selected is-selected
                    :type-frame (cfh/frame-shape? item)
                    :type-bool (cfh/bool-shape? item)
                    :type-comp (or is-component-tree is-variant-container?)
                    :hidden hidden?
                    :dnd-over dnd-over
                    :dnd-over-top dnd-over-top
                    :dnd-over-bot dnd-over-bot
                    :root-board parent-board?)
            :style style}
      [:span {:class (stl/css-case
                      :tab-indentation true
                      :filtered is-filtered)
              :style {"--depth" depth}}]
      [:div {:class (stl/css-case
                     :element-list-body true
                     :filtered is-filtered
                     :selected is-selected
                     :icon-layer (= (:type item) :icon))
             :style {"--depth" depth}
             :on-pointer-enter on-pointer-enter
             :on-pointer-leave on-pointer-leave
             :on-double-click dom/stop-propagation}

       (if (< 0 (count (:shapes item)))
         [:div {:class (stl/css :button-content)}
          (when (and (not hide-toggle) (not is-filtered))
            [:button {:class (stl/css-case
                              :toggle-content true
                              :inverse is-expanded)
                      :data-testid "toggle-content"
                      :aria-expanded is-expanded
                      :on-click on-toggle-collapse}
             deprecated-icon/arrow])

          [:div {:class (stl/css :icon-shape)
                 :on-double-click on-zoom-to-selected}
           (when absolute?
             [:div {:class (stl/css :absolute)}])
           [:> icon* {:icon-id icon-shape :size "s" :data-testid (str "icon-" icon-shape)}]]]

         [:div {:class (stl/css :button-content)}
          (when (not ^boolean is-filtered)
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
                        :disabled-double-click is-read-only
                        :on-start-edit on-disable-drag
                        :on-stop-edit on-enable-drag
                        :depth depth
                        :is-blocked blocked?
                        :parent-size parent-size
                        :is-selected is-selected
                        :type-comp (or is-component-tree is-variant-container?)
                        :type-frame (cfh/frame-shape? item)
                        :variant-id variant-id
                        :variant-name variant-name
                        :variant-properties variant-properties
                        :variant-error variant-error
                        :component-id (:id component)
                        :is-hidden hidden?}]]
      (when (not is-read-only)
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
          (if ^boolean blocked? deprecated-icon/lock deprecated-icon/unlock)]])]

     children]))

;; Memoized for performance
(mf/defc layer-item
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [index item selected objects is-sortable is-filtered depth parent-size is-component-child highlighted style render-children]
    :or {render-children true}}]
  (let [id                (:id item)
        blocked?          (:blocked item)
        hidden?           (:hidden item)

        drag-disabled*    (mf/use-state false)
        drag-disabled?    (deref drag-disabled*)

        scroll-middle-ref (mf/use-ref true)
        expanded-iref     (mf/with-memo [id]
                            (-> (l/in [:expanded id])
                                (l/derived refs/workspace-local)))
        is-expanded       (mf/deref expanded-iref)

        is-selected         (contains? selected id)
        is-highlighted      (contains? highlighted id)

        container?        (or (cfh/frame-shape? item)
                              (cfh/group-shape? item))

        is-read-only      (mf/use-ctx ctx/workspace-read-only?)
        parent-board?     (and (cfh/frame-shape? item)
                               (= uuid/zero (:parent-id item)))

        name-node-ref     (mf/use-ref)

        depth             (+ depth 1)

        is-component-tree (or ^boolean is-component-child
                              ^boolean (ctk/instance-root? item)
                              ^boolean (ctk/instance-head? item))

        enable-drag       (mf/use-fn #(reset! drag-disabled* false))
        disable-drag      (mf/use-fn #(reset! drag-disabled* true))

        ;; Lazy loading of child elements via IntersectionObserver
        children-count*   (mf/use-state 0)
        children-count    (deref children-count*)

        lazy-ref          (mf/use-ref nil)
        observer-ref      (mf/use-ref nil)

        toggle-collapse
        (mf/use-fn
         (mf/deps is-expanded)
         (fn [event]
           (dom/stop-propagation event)
           (if (and is-expanded (kbd/shift? event))
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
         (mf/deps id is-filtered objects)
         (fn [event]
           (dom/prevent-default event)
           (mf/set-ref-val! scroll-middle-ref false)
           (cond
             (kbd/shift? event)
             (if is-filtered
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
           (swap! sidebar-hover-queue (fn [{:keys [enter leave] :as q}]
                                        (-> q
                                            (assoc :enter (conj enter id))
                                            (assoc :leave (disj leave id)))))
           (schedule-sidebar-hover-flush)))

        on-pointer-leave
        (mf/use-fn
         (mf/deps id)
         (fn [_]
           (swap! sidebar-hover-queue (fn [{:keys [enter leave] :as q}]
                                        (-> q
                                            (assoc :enter (disj enter id))
                                            (assoc :leave (conj leave id)))))
           (schedule-sidebar-hover-flush)))

        on-context-menu
        (mf/use-fn
         (mf/deps item is-read-only)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not is-read-only
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
         (mf/deps id objects is-expanded selected)
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

                       (and is-expanded (= side :bot) (d/not-empty? (:shapes shape)))
                       id

                       :else
                       (cfh/get-parent-id objects id))

                     [parent-id _] (ctn/find-valid-parent-and-frame-ids parent-id objects (map #(get objects %) selected) false files)

                     parent        (get objects parent-id)
                     current-index (d/index-of (:shapes parent) id)

                     to-index  (cond
                                 (= side :center) 0
                                 (and is-expanded (= side :bot) (d/not-empty? (:shapes shape))) (count (:shapes parent))
                                 ;; target not found in parent (while lazy loading)
                                 (neg? current-index) nil
                                 (= side :top) (inc current-index)
                                 :else current-index)]

                 (when (some? to-index)
                   (st/emit! (dw/relocate-selected-shapes parent-id to-index))))))))

        on-hold
        (mf/use-fn
         (mf/deps id is-expanded)
         (fn []
           (when-not is-expanded
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
         ;; We don't want to change the structure of component copies
         :draggable? (and
                      is-sortable
                      (not is-read-only)
                      (not (ctn/has-any-copy-parent? objects item))))]

    (mf/with-effect [is-selected selected]
      (let [single? (= (count selected) 1)
            node              (mf/ref-val name-node-ref)
            scroll-node       (dom/get-parent-with-data node "scroll-container")
            parent-node       (dom/get-parent-at node 2)
            first-child-node  (dom/get-first-child parent-node)
            scroll-to-middle? (mf/ref-val scroll-middle-ref)

            subid
            (when (and ^boolean single?
                       ^boolean is-selected
                       ^boolean scroll-to-middle?)
              (ts/schedule
               100
               #(when (and node scroll-node)
                  (let [scroll-distance-ratio (dom/get-scroll-distance-ratio node scroll-node)
                        scroll-behavior (if (> scroll-distance-ratio 1) "instant" "smooth")]
                    (dom/scroll-into-view-if-needed! first-child-node #js {:block "center" :behavior scroll-behavior :inline "start"})
                    (mf/set-ref-val! scroll-middle-ref true)))))]

        #(when (some? subid)
           (rx/dispose! subid))))

    ;; Setup scroll-driven lazy loading when expanded
    ;; and ensures selected children are loaded immediately
    (mf/with-effect [is-expanded (:shapes item) selected]
      (let [shapes-vec (:shapes item)
            total (count shapes-vec)]
        (if is-expanded
          (let [;; Children are rendered in reverse order, so index 0 in render = last in shapes-vec
                ;; Find if any selected id is a direct child and get its render index
                selected-child-render-idx
                (when (and (> total default-chunk-size) (seq selected))
                  (let [shapes-reversed (vec (reverse shapes-vec))]
                    (some (fn [sel-id]
                            (let [idx (.indexOf shapes-reversed sel-id)]
                              (when (>= idx 0) idx)))
                          selected)))
                ;; Load at least enough to include the selected child plus extra
                ;; for context (so it can be centered in the scroll view)
                min-count (if selected-child-render-idx
                            (+ selected-child-render-idx default-chunk-size)
                            default-chunk-size)
                current @children-count*
                new-count (min total (max current default-chunk-size min-count))]
            (reset! children-count* new-count))
          (reset! children-count* 0)))
      (fn []
        (when-let [obs (mf/ref-val observer-ref)]
          (.disconnect obs)
          (mf/set-ref-val! obs nil))))

    ;; Re-observe sentinel whenever children-count changes (sentinel moves)
    ;; and (shapes item) to reconnect observer after shape changes
    (mf/with-effect [children-count is-expanded (:shapes item)]
      (let [total (count (:shapes item))
            node (mf/ref-val name-node-ref)
            scroll-node (dom/get-parent-with-data node "scroll-container")
            lazy-node (mf/ref-val lazy-ref)]

        ;; Disconnect previous observer
        (when-let [obs (mf/ref-val observer-ref)]
          (.disconnect obs)
          (mf/set-ref-val! observer-ref nil))

        ;; Setup new observer if there are more children to load
        (when (and is-expanded
                   (< children-count total)
                   scroll-node
                   lazy-node)
          (let [cb (fn [entries]
                     (when (and (seq entries)
                                (.-isIntersecting (first entries)))
                       ;; Load next chunk when sentinel intersects
                       (let [current @children-count*
                             next-count (min total (+ current default-chunk-size))]
                         (reset! children-count* next-count))))
                observer (js/IntersectionObserver. cb #js {:root scroll-node})]
            (.observe observer lazy-node)
            (mf/set-ref-val! observer-ref observer)))))

    [:& layer-item-inner
     {:ref dref
      :item item
      :depth depth
      :parent-size parent-size
      :name-ref name-node-ref
      :is-read-only is-read-only
      :is-highlighted is-highlighted
      :is-selected is-selected
      :is-component-tree is-component-tree
      :is-filtered is-filtered
      :is-expanded is-expanded
      :dnd-over (= (:over dprops) :center)
      :dnd-over-top (= (:over dprops) :top)
      :dnd-over-bot (= (:over dprops) :bot)
      :on-select-shape select-shape
      :on-context-menu on-context-menu
      :on-pointer-enter on-pointer-enter
      :on-pointer-leave on-pointer-leave
      :on-zoom-to-selected zoom-to-selected
      :on-toggle-collapse toggle-collapse
      :on-enable-drag enable-drag
      :on-disable-drag disable-drag
      :on-toggle-visibility toggle-visibility
      :on-toggle-blocking toggle-blocking
      :style style}

     (when (and render-children
                (:shapes item)
                is-expanded)
       [:div {:class (stl/css-case
                      :element-children true
                      :parent-selected is-selected
                      :sticky-children parent-board?)
              :data-testid (dm/str "children-" id)}
        (let [all-children (reverse (d/enumerate (:shapes item)))
              visible      (take children-count all-children)]
          (for [[index id] visible]
            (when-let [item (get objects id)]
              [:& layer-item
               {:item item
                :highlighted highlighted
                :selected selected
                :index index
                :objects objects
                :key (dm/str id)
                :is-sortable is-sortable
                :depth depth
                :parent-size parent-size
                :is-component-child is-component-tree}])))
        (when (< children-count (count (:shapes item)))
          [:div {:ref lazy-ref
                 :class (stl/css :lazy-load-sentinel)}])])]))
