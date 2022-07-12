;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.layers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Layer Name

(def shape-for-rename-ref
  (l/derived (l/in [:workspace-local :shape-for-rename]) st/state))

(mf/defc layer-name
  [{:keys [shape on-start-edit  disabled-double-click on-stop-edit name-ref] :as props}]
  (let [local            (mf/use-state {})
        shape-for-rename (mf/deref shape-for-rename-ref)

        start-edit (fn []
                     (when (not disabled-double-click)
                       (on-start-edit)
                       (swap! local assoc :edition true)))

        accept-edit (fn []
                      (let [name-input (mf/ref-val name-ref)
                            name       (dom/get-value name-input)]
                        (on-stop-edit)
                        (swap! local assoc :edition false)
                        (st/emit! (dw/end-rename-shape)
                                  (when-not (str/empty? (str/trim name))
                                    (dw/update-shape (:id shape) {:name (str/trim name)})))))

        cancel-edit (fn []
                      (on-stop-edit)
                      (swap! local assoc :edition false)
                      (st/emit! (dw/end-rename-shape)))

        on-key-down (fn [event]
                      (when (kbd/enter? event) (accept-edit))
                      (when (kbd/esc? event) (cancel-edit)))]

    (mf/with-effect [shape-for-rename]
      (when (and (= shape-for-rename (:id shape))
                 (not (:edition @local)))
        (start-edit)))

    (mf/with-effect [(:edition @local)]
      (when (:edition @local)
        (let [name-input (mf/ref-val name-ref)]
          (dom/select-text! name-input)
          nil)))

    (if (:edition @local)
      [:input.element-name
       {:type "text"
        :ref name-ref
        :on-blur accept-edit
        :on-key-down on-key-down
        :auto-focus true
        :default-value (:name shape "")}]
      [:span.element-name
       {:ref name-ref
        :on-double-click start-edit}
       (:name shape "")
       (when (seq (:touched shape)) " *")])))

(defn- make-collapsed-iref
  [id]
  #(-> (l/in [:expanded id])
       (l/derived refs/workspace-local)))

(mf/defc layer-item
  [{:keys [index item selected objects] :as props}]
  (let [id         (:id item)
        selected?  (contains? selected id)
        container? (or (cph/frame-shape? item)
                       (cph/group-shape? item))

        disable-drag      (mf/use-state false)
        scroll-to-middle? (mf/use-var true)

        expanded-iref (mf/use-memo
                       (mf/deps id)
                       (make-collapsed-iref id))

        expanded? (mf/deref expanded-iref)

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (if (and expanded? (kbd/shift? event))
            (st/emit! (dwc/collapse-all))
            (st/emit! (dwc/toggle-collapse id))))

        toggle-blocking
        (fn [event]
          (dom/stop-propagation event)
          (if (:blocked item)
            (st/emit! (dw/update-shape-flags [id] {:blocked false}))
            (st/emit! (dw/update-shape-flags [id] {:blocked true})
                      (dw/deselect-shape id))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden item)
            (st/emit! (dw/update-shape-flags [id] {:hidden false}))
            (st/emit! (dw/update-shape-flags [id] {:hidden true}))))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (reset! scroll-to-middle? false)
          (let [id (:id item)]
            (cond
              (kbd/shift? event)
              (st/emit! (dw/shift-select-shapes id))

              (kbd/mod? event)
              (st/emit! (dw/select-shape id true))

              (> (count selected) 1)
              (st/emit! (dw/select-shape id))
              :else
              (st/emit! (dw/select-shape id)))))

        on-context-menu
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [pos (dom/get-client-position event)]
            (st/emit! (dw/show-shape-context-menu {:position pos
                                                   :shape item}))))

        on-drag
        (fn [{:keys [id]}]
          (when (not (contains? selected id))
            (st/emit! (dw/select-shape id))))

        on-drop
        (fn [side _data]
          (if (= side :center)
            (st/emit! (dw/relocate-selected-shapes (:id item) 0))
            (let [to-index  (if (= side :top) (inc index) index)
                  parent-id (cph/get-parent-id objects (:id item))]
              (st/emit! (dw/relocate-selected-shapes parent-id to-index)))))

        on-hold
        (fn []
          (when-not expanded?
            (st/emit! (dwc/toggle-collapse (:id item)))))

        [dprops dref] (hooks/use-sortable
                       :data-type "penpot/layer"
                       :on-drop on-drop
                       :on-drag on-drag
                       :on-hold on-hold
                       :disabled @disable-drag
                       :detect-center? container?
                       :data {:id (:id item)
                              :index index
                              :name (:name item)})

        ref         (mf/use-ref)]

    (mf/use-effect
     (mf/deps selected? selected)
     (fn []
       (let [single? (= (count selected) 1)
             node (mf/ref-val ref)

             subid
             (when (and single? selected?)
               (let [scroll-to @scroll-to-middle?]
                 (ts/schedule
                  100
                  #(if scroll-to
                     (dom/scroll-into-view! node #js {:block "center", :behavior "smooth"})
                     (do
                       (dom/scroll-into-view-if-needed! node #js {:block "center", :behavior "smooth"})
                       (reset! scroll-to-middle? true))))))]

         #(when (some? subid)
            (rx/dispose! subid)))))

    [:li {:on-context-menu on-context-menu
          :ref dref
          :class (dom/classnames
                  :component (not (nil? (:component-id item)))
                  :masked (:masked-group? item)
                  :dnd-over (= (:over dprops) :center)
                  :dnd-over-top (= (:over dprops) :top)
                  :dnd-over-bot (= (:over dprops) :bot)
                  :selected selected?
                  :type-frame (= :frame (:type item)))}

     [:div.element-list-body {:class (dom/classnames :selected selected?
                                                     :icon-layer (= (:type item) :icon))
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:& si/element-icon {:shape item}]
      [:& layer-name {:shape item
                      :name-ref ref
                      :on-start-edit #(reset! disable-drag true)
                      :on-stop-edit #(reset! disable-drag false)}]

      [:div.element-actions {:class (when (:shapes item) "is-parent")}
       [:div.toggle-element {:class (when (:hidden item) "selected")
                             :on-click toggle-visibility}
        (if (:hidden item) i/eye-closed i/eye)]
       [:div.block-element {:class (when (:blocked item) "selected")
                            :on-click toggle-blocking}
        (if (:blocked item) i/lock i/unlock)]]

      (when (:shapes item)
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

;; This components is a piece for sharding equality check between top
;; level frames and try to avoid rerender frames that are does not
;; affected by the selected set.

(mf/defc frame-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo
              #(mf/deferred % ts/idle-then-raf)]}
  [props]
  [:> layer-item props])

(mf/defc layers-tree
  {::mf/wrap [#(mf/memo % =)
              #(mf/throttle % 200)]}
  [{:keys [objects] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected (hooks/use-equal-memo selected)
        root (get objects uuid/zero)]
    [:ul.element-list
     [:& hooks/sortable-container {}
      (for [[index id] (reverse (d/enumerate (:shapes root)))]
        (when-let [obj (get objects id)]
          (if (= (:type obj) :frame)
            [:& frame-wrapper
             {:item obj
              :selected selected
              :index index
              :objects objects
              :key id}]
            [:& layer-item
             {:item obj
              :selected selected
              :index index
              :objects objects
              :key id}])))]]))

(mf/defc filters-tree
  {::mf/wrap [#(mf/memo % =)
              #(mf/throttle % 200)]}
  [{:keys [objects] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected (hooks/use-equal-memo selected)
        root (get objects uuid/zero)]
    [:ul.element-list
     (for [[index id] (d/enumerate (:shapes root))]
       (when-let [obj (get objects id)]
         [:& layer-item
          {:item obj
           :selected selected
           :index index
           :objects objects
           :key id}]))]))


(defn calc-reparented-objects
  [objects]

  (let [reparented-objects
        (d/mapm (fn [_ val]
                  (assoc val :parent-id uuid/zero :shapes nil))
                objects)

        reparented-shapes
        (->> reparented-objects
             keys
             (filter #(not= uuid/zero %))
             vec)]
    (update reparented-objects uuid/zero assoc :shapes reparented-shapes)))

;; --- Layers Toolbox

(defn use-search
  [page objects]
  (let [filter-state (mf/use-state {:show-search-box false
                                    :show-filters-menu false
                                    :search-text ""
                                    :active-filters #{}
                                    :num-items 100})

        clear-search-text
        (mf/use-callback
         (fn []
           (swap! filter-state assoc :search-text "" :num-items 100)))

        update-search-text
        (mf/use-callback
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value)]
             (swap! filter-state assoc :search-text value :num-items 100))))

        toggle-search
        (mf/use-callback
         (fn []
           (swap! filter-state assoc :search-text "")
           (swap! filter-state assoc :active-filters #{})
           (swap! filter-state assoc :show-filters-menu false)
           (swap! filter-state assoc :num-items 100)
           (swap! filter-state update :show-search-box not)))

        toggle-filters
        (mf/use-callback
         (fn []
           (swap! filter-state update :show-filters-menu not)))

        remove-filter
        (mf/use-callback
         (mf/deps @filter-state)
         (fn [key]
           (fn [_]
             (swap! filter-state update :active-filters disj key)
             (swap! filter-state assoc :num-items 100))))

        add-filter
        (mf/use-callback
         (mf/deps @filter-state (:show-filters-menu @filter-state))
         (fn [key]
           (fn [_]
             (swap! filter-state update :active-filters conj key)
             (swap! filter-state assoc :num-items 100)
             (toggle-filters))))

        active?
        (and
         (:show-search-box @filter-state)
         (or (d/not-empty? (:search-text @filter-state))
             (d/not-empty? (:active-filters @filter-state))))        

        search-and-filters
        (fn [[id shape]]
          (let [search (:search-text @filter-state)
                filters (:active-filters @filter-state)
                filters (cond-> filters
                          (some #{:shape} filters)
                          (conj :rect :circle :path :bool))]
            (or
             (= uuid/zero id)
             (and
              (str/includes? (str/lower (:name shape)) (str/lower search))
              (or
               (empty? filters)
               (and
                (some #{:component} filters)
                (contains? shape :component-id))
               (let [direct_filters (filter #{:frame :rect :circle :path :bool :image :text} filters)]
                 (some #{(:type shape)} direct_filters))
               (and
                (some #{:group} filters)
                (and (= :group (:type shape))
                     (not (contains? shape :component-id))
                     (or (not (contains? shape :masked-group?)) (false? (:masked-group? shape)))))
               (and
                (some #{:mask} filters)
                (true? (:masked-group? shape))))))))

        filtered-objects-total
        (mf/use-memo
         (mf/deps objects active? @filter-state)
         #(when active?
            ;; filterv so count is constant time
            (filterv search-and-filters objects)))

        filtered-objects
        (mf/use-memo
         (mf/deps filtered-objects-total)
         #(when active?
            (calc-reparented-objects
             (into {}
                   (take (:num-items @filter-state))
                   filtered-objects-total))))


        handle-show-more
        (fn []
          (when (<= (:num-items @filter-state) (count filtered-objects-total))
            (swap! filter-state update :num-items + 100)))]

    [filtered-objects
     handle-show-more
     
     (mf/html
      (if (:show-search-box @filter-state)
        [:*
         [:div.tool-window-bar.search
          [:span.search-box
           [:span.filter {:on-click toggle-filters :class (dom/classnames :active active?)} i/icon-filter]
           [:span
            [:input {:on-change update-search-text
                     :value (:search-text @filter-state)
                     :auto-focus (:show-search-box @filter-state)
                     :placeholder (tr "workspace.sidebar.layers.search")}]]
           (when (not (= "" (:search-text @filter-state)))
             [:span.clear {:on-click clear-search-text} i/exclude])]
          [:span {:on-click toggle-search} i/cross]]

         [:div.active-filters
          (for [f (:active-filters @filter-state)]
            (let [name (case f
                         :frame (tr "workspace.sidebar.layers.frames")
                         :group (tr "workspace.sidebar.layers.groups")
                         :mask (tr "workspace.sidebar.layers.masks")
                         :component (tr "workspace.sidebar.layers.components")
                         :text (tr "workspace.sidebar.layers.texts")
                         :image (tr "workspace.sidebar.layers.images")
                         :shape (tr "workspace.sidebar.layers.shapes")
                         (tr f))]
              [:span {:on-click (remove-filter f)}
               name i/cross]))]

         (when (:show-filters-menu @filter-state)
           [:div.filters-container
            [:span{:on-click (add-filter :frame)} i/artboard (tr "workspace.sidebar.layers.frames")]
            [:span{:on-click (add-filter :group)} i/folder (tr "workspace.sidebar.layers.groups")]
            [:span{:on-click (add-filter :mask)} i/mask (tr "workspace.sidebar.layers.masks")]
            [:span{:on-click (add-filter :component)} i/component (tr "workspace.sidebar.layers.components")]
            [:span{:on-click (add-filter :text)} i/text (tr "workspace.sidebar.layers.texts")]
            [:span{:on-click (add-filter :image)} i/image (tr "workspace.sidebar.layers.images")]
            [:span{:on-click (add-filter :shape)} i/curve (tr "workspace.sidebar.layers.shapes")]])]

        [:div.tool-window-bar
         [:span (:name page)]
         [:span {:on-click toggle-search} i/search]]))]))

(mf/defc layers-toolbox
  {:wrap [mf/memo]}
  []
  (let [page  (mf/deref refs/workspace-page)
        focus (mf/deref refs/workspace-focus-selected)
        objects (hooks/with-focus-objects (:objects page) focus)
        title (when (= 1 (count focus)) (get-in objects [(first focus) :name]))

        observer-var (mf/use-var nil)
        lazy-load-ref (mf/use-ref nil)

        [filtered-objects show-more filter-component] (use-search page objects)

        intersection-callback
        (fn [entries]
          (when (and (.-isIntersecting (first entries)) (some? show-more))
            (show-more)))
        
        on-render-container
        (fn [element]
          (let [options #js {:root element}
                lazy-el (mf/ref-val lazy-load-ref)]
            (cond
              (and (some? element) (not (some? @observer-var)))
              (let [observer (js/IntersectionObserver. intersection-callback options)]
                (.observe observer lazy-el)
                (reset! observer-var observer))

              (and (nil? element) (some? @observer-var))
              (do (.disconnect @observer-var)
                  (reset! observer-var nil)))))

        on-scroll
        (fn [event]
          (let [target (dom/get-target event)
                target-top (:top (dom/get-bounding-rect target))
                frames (dom/get-elements-by-class "type-frame")
                last-hidden-frame (->> frames
                                       (filter #(< (- (:top (dom/get-bounding-rect %)) target-top) 0))
                                       last)]
            (doseq [frame frames]
              (dom/remove-class! frame "sticky"))

            (when last-hidden-frame
              (dom/add-class! last-hidden-frame "sticky"))))]


    [:div#layers.tool-window
     (if (d/not-empty? focus)
       [:div.tool-window-bar
        [:div.focus-title {:on-click #(st/emit! (dw/toggle-focus-mode))}
         [:button.back-button i/arrow-slide]
         [:div.focus-name (or title (tr "workspace.focus.selection"))]
         [:div.focus-mode (tr "workspace.focus.focus-mode")]]]

       filter-component)

     (when (some? filtered-objects)
       [:div.tool-window-content {:ref on-render-container  :key "filters"}
        [:& filters-tree {:objects filtered-objects
                         :key (dm/str (:id page))}]
        [:div.lazy {:ref lazy-load-ref
                    :key "lazy-load"
                    :style {:min-height 16}}]])

     [:div.tool-window-content {:on-scroll on-scroll
                                :style {:display (when (some? filtered-objects) "none")}}
      [:& layers-tree {:objects objects
                       :key (dm/str (:id page))}]]]))
