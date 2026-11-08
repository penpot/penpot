;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.layer-name :refer [layer-name*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.shape-icon :as usi]
   [app.util.timers :as ts]
   [clojure.set :as set]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coalesce sidebar hover highlights to 1 frame to avoid long tasks
(defonce ^:private sidebar-hover-queue (atom {:enter #{} :leave #{}}))
(defonce ^:private sidebar-hover-pending? (atom false))

(def ^:private ref:highlighted-shapes
  (l/derived (fn [local]
               (-> local
                   (get :highlighted)
                   (not-empty)))
             refs/workspace-local))

(def ref:shape-for-rename
  (l/derived (l/key :shape-for-rename) refs/workspace-local))

;; Holds an atom with the objects shown in a layers tree. Rows subscribe
;; to their own shape through it, so a change in the objects map only
;; re-renders the rows whose shape changed.
(def layer-objects-ctx
  (mf/create-context nil))

(def sidebar-selected-id
  "Id of the last layer row clicked, so the tree does not scroll to the
  row the user just clicked."
  (volatile! nil))

(defn use-objects-ref
  [objects]
  (let [objects-ref  (mf/with-memo [] (atom objects))
        notified-ref (mf/use-ref objects)]
    ;; Rows mounting in this render read the atom before they subscribe to
    ;; it, so it must hold the new objects now; a change notified before
    ;; they subscribe would be lost. Mounted rows are notified on commit.
    (set! (.-state objects-ref) objects)
    (mf/with-layout-effect [objects]
      (let [prev (mf/ref-val notified-ref)]
        (mf/set-ref-val! notified-ref objects)
        (-notify-watches objects-ref prev objects)))
    objects-ref))

(defn- schedule-sidebar-hover-flush []
  (when (compare-and-set! sidebar-hover-pending? false true)
    (ts/raf
     (fn []
       (let [{:keys [enter leave]} @sidebar-hover-queue

             enter (set/difference enter leave)
             leave (set/difference leave enter)
             search-match (get-in @st/state [:workspace-local :search-match-highlight])
             leave (cond-> leave
                     (some? search-match) (disj search-match))]

         (reset! sidebar-hover-queue {:enter #{} :leave #{}})
         (reset! sidebar-hover-pending? false)
         (when (seq leave)
           (apply st/emit! (map dw/dehighlight-shape leave)))
         (when (seq enter)
           (apply st/emit! (map dw/highlight-shape enter))))))))

(mf/defc layer-item-inner*
  [{:keys [item depth parent-size children ref style rename-id
           ;; Flags
           is-read-only is-highlighted is-selected is-component-tree
           is-filtered is-expanded is-parent-selected dnd-over dnd-over-top dnd-over-bot hide-toggle
           ;; Callbacks
           on-select-shape on-context-menu on-pointer-enter on-pointer-leave on-zoom-to-selected
           on-toggle-collapse on-enable-drag on-disable-drag on-toggle-visibility on-toggle-blocking
           on-tab-press]}]

  (let [id                    (:id item)
        name                  (:name item)
        blocked?              (:blocked item)
        hidden?               (:hidden item)
        has-shapes?           (-> item :shapes seq boolean)
        touched?              (-> item :touched seq boolean)
        root-board?           (and (cfh/frame-shape? item)
                                   (= uuid/zero (:parent-id item)))
        absolute?             (ctl/item-absolute? item)
        is-variant?           (ctk/is-variant? item)
        is-variant-container? (ctk/is-variant-container? item)
        variant-id            (when is-variant? (:variant-id item))
        variant-name          (when is-variant? (:variant-name item))
        variant-error         (when is-variant? (:variant-error item))

        component-id          (get item :component-id)
        variant-props-ref     (mf/with-memo [component-id]
                                (l/derived #(-> (ctkl/get-component % component-id)
                                                (get :variant-properties))
                                           refs/workspace-data))
        variant-properties    (mf/deref variant-props-ref)

        icon-shape            (usi/get-shape-icon item)]

    [:*
     [:div {:id id
            :ref ref
            :on-click on-select-shape
            :on-context-menu on-context-menu
            :data-testid "layer-row"
            :role "checkbox"
            :aria-checked is-selected
            :class (stl/css-case
                    :layer-row true
                    :highlight is-highlighted
                    :component (ctk/instance-head? item)
                    :masked (:masked-group item)
                    :selected is-selected
                    :parent-selected is-parent-selected
                    :type-frame (cfh/frame-shape? item)
                    :type-bool (cfh/bool-shape? item)
                    :type-comp (or is-component-tree is-variant-container?)
                    :hidden hidden?
                    :dnd-over dnd-over
                    :dnd-over-top dnd-over-top
                    :dnd-over-bot dnd-over-bot
                    :root-board root-board?)
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
             [:> icon* {:icon-id i/arrow
                        :size "s"
                        :class (stl/css :toggle-content-icon)}]])

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

       [:> layer-name* {:rename-id rename-id
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
                        :component-id component-id
                        :is-hidden hidden?
                        :on-tab-press on-tab-press}]]
      (when (not ^boolean is-read-only)
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
          [:> icon* {:icon-id (if ^boolean hidden? i/hide i/shown)
                     :size "s"
                     :class (stl/css :element-action-icon)}]]
         [:button {:class (stl/css-case
                           :block-element true
                           :selected blocked?)
                   :title (if (:blocked item)
                            (tr "workspace.shape.menu.unlock")
                            (tr "workspace.shape.menu.lock"))
                   :on-click on-toggle-blocking}
          [:> icon* {:icon-id (if ^boolean blocked? i/lock i/unlock)
                     :size "s"
                     :class (stl/css :element-action-icon)}]]])]

     children]))

(mf/defc layer-item-row*
  {::mf/private true}
  [{:keys [index item objects-ref is-sortable is-filtered depth
           is-component-child has-copy-parent style parent-size]}]
  (let [id                (get item :id)
        blocked?          (get item :blocked)
        hidden?           (get item :hidden)

        drag-disabled*    (mf/use-state false)
        drag-disabled?    (deref drag-disabled*)

        expanded-iref     (mf/with-memo [id]
                            (l/derived #(dm/get-in % [:expanded id]) refs/workspace-local))
        is-expanded       (mf/deref expanded-iref)

        selected-iref     (mf/with-memo [id]
                            (refs/make-selected-ref id))
        is-selected       (mf/deref selected-iref)

        highlighted-iref  (mf/with-memo [id]
                            (l/derived #(contains? % id) ref:highlighted-shapes))
        is-highlighted    (mf/deref highlighted-iref)

        rename-iref       (mf/with-memo [id]
                            (l/derived #(when (= % id) %) ref:shape-for-rename))
        rename-id         (mf/deref rename-iref)

        parent-selected-iref
        (mf/with-memo [id objects-ref]
          (l/derived (fn [selected]
                       (boolean
                        (and (d/not-empty? selected)
                             (some #(contains? selected %)
                                   (cfh/get-parent-ids @objects-ref id)))))
                     refs/selected-shapes))
        is-parent-selected (mf/deref parent-selected-iref)

        container?        (or (cfh/frame-shape? item)
                              (cfh/group-shape? item))

        is-read-only      (mf/use-ctx ctx/workspace-read-only?)

        depth             (+ depth 1)

        is-component-tree (or ^boolean is-component-child
                              ^boolean (ctk/instance-root? item)
                              ^boolean (ctk/instance-head? item))

        enable-drag       (mf/use-fn #(reset! drag-disabled* false))
        disable-drag      (mf/use-fn #(reset! drag-disabled* true))

        toggle-collapse
        (mf/use-fn
         (mf/deps is-expanded id objects-ref)
         (fn [event]
           (dom/stop-propagation event)
           (cond
             ;; Shift+click while expanded collapses every layer in the sidebar
             (and is-expanded (kbd/shift? event))
             (st/emit! (dwc/collapse-all))

             ;; Alt+click while collapsed expands the entire subtree rooted at this id
             (and (not is-expanded) (kbd/alt? event))
             (st/emit! (dwc/expand-subtree id @objects-ref))

             :else
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
         (mf/deps id is-filtered objects-ref)
         (fn [event]
           (dom/prevent-default event)
           (vreset! sidebar-selected-id id)
           (cond
             (kbd/shift? event)
             (if is-filtered
               (st/emit! (dw/shift-select-shapes id @objects-ref))
               (st/emit! (dw/shift-select-shapes id)))

             (kbd/mod? event)
             (st/emit! (dw/select-shape id true))

             :else
             (st/emit! (dw/select-shape id)))))

        on-pointer-enter
        (mf/use-fn
         (mf/deps id)
         (fn [_]
           (swap! sidebar-hover-queue
                  (fn [q]
                    (-> q
                        (update :enter (fnil conj #{}) id)
                        (update :leave (fnil disj #{}) id))))
           (schedule-sidebar-hover-flush)))

        on-pointer-leave
        (mf/use-fn
         (mf/deps id)
         (fn [_]
           (swap! sidebar-hover-queue
                  (fn [q]
                    (-> q
                        (update :enter (fnil disj #{}) id)
                        (update :leave (fnil conj #{}) id))))
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
         (fn [{:keys [id]}]
           (when (not (contains? @refs/selected-shapes id))
             (st/emit! (dw/select-shape id)))))

        on-drop
        (mf/use-fn
         (mf/deps id objects-ref is-expanded)
         (fn [side _data]
           (let [objects @objects-ref
                 selected @refs/selected-shapes
                 single? (= (count selected) 1)
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

                     [parent-id _]
                     (ctn/find-valid-parent-and-frame-ids parent-id objects (map #(get objects %) selected) false files)

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
         :draggable? (and ^boolean is-sortable
                          ^boolean (not is-read-only)
                          ^boolean (not has-copy-parent)))

        on-tab-press
        (mf/use-fn
         (mf/deps id objects-ref)
         (fn [event]
           (let [objects   @objects-ref
                 shift?    (kbd/shift? event)
                 shape     (get objects id)
                 parent    (get objects (:parent-id shape))
                 siblings  (:shapes parent)
                 pos       (d/index-of siblings id)]
             (when (some? pos)
               (let [;; Layers render in reverse: Tab (visually down) = dec index,
                     ;; Shift+Tab (visually up) = inc index
                     target-id (if shift?
                                 (get siblings (inc pos))
                                 (get siblings (dec pos)))]
                 (when (some? target-id)
                   (st/emit! (dw/start-rename-shape target-id))))))))]

    [:> layer-item-inner*
     {:ref dref
      :item item
      :depth depth
      :parent-size parent-size
      :rename-id rename-id
      :is-read-only is-read-only
      :is-highlighted is-highlighted
      :is-selected is-selected
      :is-component-tree is-component-tree
      :is-filtered is-filtered
      :is-expanded is-expanded
      :is-parent-selected is-parent-selected
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
      :on-tab-press on-tab-press
      :style style}]))

(mf/defc layer-item*
  {::mf/wrap [mf/memo]}
  [{:keys [id] :as props}]
  (let [objects-ref (mf/use-ctx layer-objects-ctx)
        item-ref    (mf/with-memo [id objects-ref]
                      (l/derived #(get % id) objects-ref))
        item        (mf/deref item-ref)]
    (when (some? item)
      [:> layer-item-row* (mf/spread-props props {:item item :objects-ref objects-ref})])))
