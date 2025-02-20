;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.layers
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.shape-icon :as sic]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.main.ui.workspace.sidebar.layer-item :refer [layer-item]]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.rxops :refer [throttle-fn]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

;; This components is a piece for sharding equality check between top
;; level frames and try to avoid rerender frames that are does not
;; affected by the selected set.
(mf/defc frame-wrapper
  {::mf/props :obj}
  [{:keys [selected] :as props}]
  (let [pending-selected (mf/use-var selected)
        current-selected (mf/use-state selected)
        props            (mf/spread-object props {:selected @current-selected})

        set-selected
        (mf/use-memo
         (fn []
           (throttle-fn
            50
            #(when-let [pending-selected @pending-selected]
               (reset! current-selected pending-selected)))))]

    (mf/with-effect [selected set-selected]
      (reset! pending-selected selected)
      (set-selected)
      (fn []
        (reset! pending-selected nil)
        #(rx/dispose! set-selected)))

    [:> layer-item props]))

(mf/defc layers-tree
  {::mf/wrap [mf/memo #(mf/throttle % 200)]
   ::mf/wrap-props false}
  [{:keys [objects filtered? parent-size] :as props}]
  (let [selected       (mf/deref refs/selected-shapes)
        selected       (hooks/use-equal-memo selected)
        highlighted    (mf/deref refs/highlighted-shapes)
        highlighted    (hooks/use-equal-memo highlighted)
        root           (get objects uuid/zero)]
    [:div {:class (stl/css :element-list) :data-testid "layer-item"}
     [:& hooks/sortable-container {}
      (for [[index id] (reverse (d/enumerate (:shapes root)))]
        (when-let [obj (get objects id)]
          (if (cfh/frame-shape? obj)
            [:& frame-wrapper
             {:item obj
              :selected selected
              :highlighted highlighted
              :index index
              :objects objects
              :key id
              :sortable? true
              :filtered? filtered?
              :parent-size parent-size
              :depth -1}]
            [:& layer-item
             {:item obj
              :selected selected
              :highlighted highlighted
              :index index
              :objects objects
              :key id
              :sortable? true
              :filtered? filtered?
              :depth -1
              :parent-size parent-size}])))]]))

(mf/defc filters-tree
  {::mf/wrap [mf/memo #(mf/throttle % 200)]
   ::mf/wrap-props false}
  [{:keys [objects parent-size]}]
  (let [selected       (mf/deref refs/selected-shapes)
        selected       (hooks/use-equal-memo selected)
        root           (get objects uuid/zero)]
    [:ul {:class (stl/css :element-list)}
     (for [[index id] (d/enumerate (:shapes root))]
       (when-let [obj (get objects id)]
         [:& layer-item
          {:item obj
           :selected selected
           :index index
           :objects objects
           :key id
           :sortable? false
           :filtered? true
           :depth -1
           :parent-size parent-size}]))]))

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

;; FIXME: optimize
(defn- match-filters?
  [state [id shape]]
  (let [search  (:search-text state)
        filters (:filters state)
        filters (cond-> filters
                  (contains? filters :shape)
                  (conj :rect :circle :path :bool))]
    (or (= uuid/zero id)
        (and (or (str/includes? (str/lower (:name shape)) (str/lower search))
                 ;; Only for local development we allow search for ids. Otherwise will be hard
                 ;; search for numbers or single letter shape names (ie: "A")
                 (and *assert*
                      (str/includes? (dm/str (:id shape)) (str/lower search))))
             (or (empty? filters)
                 (and (contains? filters :component)
                      (contains? shape :component-id))
                 (and (contains? filters :image)
                      (some? (cts/has-images? shape)))

                 (let [direct-filters (into #{} (filter #{:frame :rect :circle :path :bool :text}) filters)]
                   (contains? direct-filters (:type shape)))
                 (and (contains? filters :group)
                      (and (cfh/group-shape? shape)
                           (not (contains? shape :component-id))
                           (or (not (contains? shape :masked-group))
                               (false? (:masked-group shape)))))
                 (and (contains? filters :mask)
                      (true? (:masked-group shape))))))))

(defn use-search
  [page objects]
  (let [state*          (mf/use-state
                         #(do {:show-search false
                               :show-menu false
                               :search-text ""
                               :filters #{}
                               :num-items 100}))

        state           (deref state*)
        current-filters (:filters state)
        current-items   (:num-items state)
        current-search  (:search-text state)
        show-menu?      (:show-menu state)
        show-search?    (:show-search state)

        clear-search-text
        (mf/use-fn
         #(swap! state* assoc :search-text "" :num-items 100))


        toggle-filters
        (mf/use-fn
         #(swap! state* update :show-menu not))

        on-toggle-filters-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (toggle-filters)))

        hide-menu
        (mf/use-fn
         #(swap! state* assoc :show-menu false))

        on-key-down
        (mf/use-fn
         (fn [event]
           (when (kbd/esc? event) (hide-menu))))

        update-search-text
        (mf/use-fn
         (fn [value _event]
           (swap! state* assoc :search-text value :num-items 100)))

        toggle-search
        (mf/use-fn
         (fn [event]
           (let [node (dom/get-current-target event)]
             (dom/blur! node)
             (swap! state* (fn [state]
                             (-> state
                                 (assoc :search-text "")
                                 (assoc :filters #{})
                                 (assoc :show-menu false)
                                 (assoc :num-items 100)
                                 (update :show-search not)))))))

        remove-filter
        (mf/use-fn
         (fn [event]
           (let [fkey (-> (dom/get-current-target event)
                          (dom/get-data "filter")
                          (keyword))]
             (swap! state* (fn [state]
                             (-> state
                                 (update :filters disj fkey)
                                 (assoc :num-items 100)))))))

        add-filter
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [key (-> (dom/get-current-target event)
                         (dom/get-data "filter")
                         (keyword))]
             (swap! state* (fn [state]
                             (-> state
                                 (update :filters conj key)
                                 (update :show-menu not)
                                 (assoc :num-items 100)))))))

        active?
        (and ^boolean show-search?
             (or ^boolean (d/not-empty? current-search)
                 ^boolean (d/not-empty? current-filters)))

        filtered-objects-all
        (mf/with-memo [active? objects state]
          (when active?
            (into [] (filter (partial match-filters? state)) objects)))

        filtered-objects-total
        (count filtered-objects-all)

        filtered-objects
        (mf/with-memo [active? filtered-objects-all current-items]
          (when active?
            (->> filtered-objects-all
                 (into {} (take current-items))
                 (calc-reparented-objects))))

        handle-show-more
        (mf/use-fn
         (mf/deps filtered-objects-total current-items)
         (fn [_]
           (when (<= current-items filtered-objects-total)
             (swap! state* update :num-items + 100))))]

    (mf/with-effect []
      (let [keys [(events/listen globals/document EventType.KEYDOWN on-key-down)
                  (events/listen globals/document EventType.CLICK hide-menu)]]
        (fn [] (doseq [key keys] (events/unlistenByKey key)))))

    [filtered-objects
     handle-show-more
     #(mf/html
       (if show-search?
         [:*
          [:div {:class (stl/css :tool-window-bar :search)}
           [:& search-bar {:on-change update-search-text
                           :value current-search
                           :on-clear clear-search-text
                           :placeholder (tr "workspace.sidebar.layers.search")}
            [:button {:on-click on-toggle-filters-click
                      :class (stl/css-case
                              :filter-button true
                              :opened show-menu?
                              :active active?)}
             i/filter-icon]]

           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click toggle-search
                             :icon "close"}]]

          [:div {:class (stl/css :active-filters)}
           (for [fkey current-filters]
             (let [fname (d/name fkey)
                   name  (case fkey
                           :frame     (tr "workspace.sidebar.layers.frames")
                           :group     (tr "workspace.sidebar.layers.groups")
                           :mask      (tr "workspace.sidebar.layers.masks")
                           :component (tr "workspace.sidebar.layers.components")
                           :text      (tr "workspace.sidebar.layers.texts")
                           :image     (tr "workspace.sidebar.layers.images")
                           :shape     (tr "workspace.sidebar.layers.shapes")
                           (tr fkey))]

               [:button {:class (stl/css :layer-filter)
                         :key fname
                         :data-filter fname
                         :on-click remove-filter}

                [:span {:class (stl/css :layer-filter-icon)}
                 [:& sic/element-icon-by-type
                  {:type fkey
                   :main-instance? (= fkey :component)}]]
                [:span {:class (stl/css :layer-filter-name)}
                 name]
                [:span {:class (stl/css :layer-filter-close)}
                 i/close-small]]))]

          (when ^boolean show-menu?
            [:ul {:class (stl/css :filters-container)}
             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :frame))
                   :data-filter "frame"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/board]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.frames")]]

              (when (contains? current-filters :frame)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]

             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :group))
                   :data-filter "group"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/group]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.groups")]]

              (when (contains? current-filters :group)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]

             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :mask))
                   :data-filter "mask"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/mask]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.masks")]]

              (when (contains? current-filters :mask)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]

             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :component))
                   :data-filter "component"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/component]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.components")]]

              (when (contains? current-filters :component)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]

             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :text))
                   :data-filter "text"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/text]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.texts")]]

              (when (contains? current-filters :text)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]

             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :image))
                   :data-filter "image"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/img]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.images")]]

              (when (contains? current-filters :image)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]

             [:li {:class (stl/css-case :filter-menu-item true
                                        :selected (contains? current-filters :shape))
                   :data-filter "shape"
                   :on-click add-filter}
              [:div {:class (stl/css :filter-menu-item-name-wrapper)}
               [:span {:class (stl/css :filter-menu-item-icon)}
                i/path]
               [:span {:class (stl/css :filter-menu-item-name)}
                (tr "workspace.sidebar.layers.shapes")]]

              (when (contains? current-filters :shape)
                [:span {:class (stl/css :filter-menu-item-tick)}
                 i/tick])]])]

         [:div {:class (stl/css :tool-window-bar)}
          [:& title-bar {:collapsable  false
                         :title        (:name page)
                         :on-btn-click toggle-search
                         :btn-children i/search}]]))]))


(defn- on-scroll
  [event]
  (let [children (dom/get-elements-by-class "sticky-children")
        length   (alength children)]
    (when (pos? length)
      (let [target     (dom/get-target event)
            target-top (:top (dom/get-bounding-rect target))
            frames     (dom/get-elements-by-class "root-board")

            last-hidden-frame
            (->> frames
                 (filter #(<= (- (:top (dom/get-bounding-rect %)) target-top) 0))
                 last)

            frame-id (dom/get-attribute last-hidden-frame "id")

            last-hidden-children
            (->> children
                 (filter #(< (- (:top (dom/get-bounding-rect %)) target-top) 0))
                 last)

            is-children-shown?
            (and last-hidden-children
                 (> (- (:bottom (dom/get-bounding-rect last-hidden-children)) target-top) 0))

            children-frame-id (dom/get-attribute last-hidden-children "data-id")

            ;; We want to check that root-board is out of view but its children are not.
            ;; only in that case we make root board sticky.
            sticky? (and last-hidden-frame
                         is-children-shown?
                         (= frame-id children-frame-id))]

        (run! #(dom/remove-class! % "sticky") frames)

        (when sticky?
          (dom/add-class! last-hidden-frame "sticky"))))))


(mf/defc layers-toolbox
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [size-parent]}]
  (let [page           (mf/deref refs/workspace-page)
        focus          (mf/deref refs/workspace-focus-selected)

        objects        (hooks/with-focus-objects (:objects page) focus)
        title          (when (= 1 (count focus))
                         (dm/get-in objects [(first focus) :name]))

        observer-var   (mf/use-var nil)
        lazy-load-ref  (mf/use-ref nil)

        [filtered-objects show-more filter-component] (use-search page objects)

        intersection-callback
        (fn [entries]
          (when (and (.-isIntersecting (first entries)) (some? show-more))
            (show-more)))

        on-render-container
        (fn [element]
          (when-let [lazy-node (mf/ref-val lazy-load-ref)]
            (cond
              (and (some? element) (not (some? @observer-var)))
              (let [observer (js/IntersectionObserver. intersection-callback
                                                       #js {:root element})]
                (.observe observer lazy-node)
                (reset! observer-var observer))

              (and (nil? element) (some? @observer-var))
              (do (.disconnect ^js @observer-var)
                  (reset! observer-var nil)))))

        toogle-focus-mode
        (mf/use-fn
         #(st/emit! (dw/toggle-focus-mode)))]

    [:div#layers {:class (stl/css :layers) :data-testid "layer-tree"}
     (if (d/not-empty? focus)
       [:div {:class (stl/css :tool-window-bar)}
        [:button {:class (stl/css :focus-title)
                  :on-click toogle-focus-mode}
         [:span {:class (stl/css :back-button)}
          i/arrow]

         [:div {:class (stl/css :focus-name)}
          (or title (tr "workspace.sidebar.layers"))]

         [:div {:class (stl/css :focus-mode-tag-wrapper)}
          [:& badge-notification {:content (tr "workspace.focus.focus-mode") :size :small :is-focus true}]]]]

       (filter-component))

     (if (some? filtered-objects)
       [:*
        [:div {:class (stl/css :tool-window-content)
               :data-scroll-container true
               :ref on-render-container}
         [:& filters-tree {:objects filtered-objects
                           :key (dm/str (:id page))
                           :parent-size size-parent}]
         [:div {:ref lazy-load-ref
                :style {:min-height 16}}]]
        [:div {:on-scroll on-scroll
               :class (stl/css :tool-window-content)
               :data-scroll-container true
               :style {:display (when (some? filtered-objects) "none")}}

         [:& layers-tree {:objects filtered-objects
                          :key (dm/str (:id page))
                          :filtered? true
                          :parent-size size-parent}]]]

       [:div {:on-scroll on-scroll
              :class (stl/css :tool-window-content)
              :data-scroll-container true
              :style {:display (when (some? filtered-objects) "none")}}
        [:& layers-tree {:objects objects
                         :key (dm/str (:id page))
                         :filtered? false
                         :parent-size size-parent}]])]))
