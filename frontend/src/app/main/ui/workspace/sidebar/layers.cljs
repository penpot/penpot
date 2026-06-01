;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.layers
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.main.ui.workspace.sidebar.layer-item :refer [layer-item*]]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.rxops :refer [throttle-fn]]
   [app.util.shape-icon :as usi]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:highlighted-shapes
  (l/derived (fn [local]
               (-> local
                   (get :highlighted)
                   (not-empty)))
             refs/workspace-local))

(def ^:private ref:shape-for-rename
  (l/derived (l/key :shape-for-rename) refs/workspace-local))

(defn- use-selected-shapes
  "A convencience hook wrapper for get selected shapes"
  []
  (let [selected (mf/deref refs/selected-shapes)]
    (hooks/use-equal-memo selected)))

;; This components is a piece for sharding equality check between top
;; level frames and try to avoid rerender frames that are does not
;; affected by the selected set.
(mf/defc frame-wrapper*
  [{:keys [selected] :as props}]
  (let [pending-selected-ref
        (mf/use-ref selected)

        current-selected
        (mf/use-state selected)

        props
        (mf/spread-object props {:selected @current-selected})

        set-selected
        (mf/with-memo []
          (throttle-fn 50 #(when-let [pending-selected (mf/ref-val pending-selected-ref)]
                             (reset! current-selected pending-selected))))]

    (mf/with-effect [selected set-selected]
      (mf/set-ref-val! pending-selected-ref selected)
      (^function set-selected)
      (fn []
        (mf/set-ref-val! pending-selected-ref nil)
        (rx/dispose! set-selected)))

    [:> layer-item* props]))

(mf/defc layers-tree*
  {::mf/wrap [mf/memo]}
  [{:keys [objects is-filtered parent-size] :as props}]
  (let [selected    (use-selected-shapes)
        highlighted (mf/deref ref:highlighted-shapes)
        root        (get objects uuid/zero)

        rename-id   (mf/deref ref:shape-for-rename)

        shapes      (get root :shapes)
        shapes      (mf/with-memo [shapes objects]
                      (loop [counter 0
                             shapes (seq shapes)
                             result (list)]
                        (if-let [id (first shapes)]
                          (if-let [obj (get objects id)]
                            (do
                              ;; NOTE: this is a bit hacky, but reduces substantially
                              ;; the allocation; If we use enumeration, we allocate
                              ;; new sequence and add one iteration on each render,
                              ;; independently if objects are changed or not. If we
                              ;; store counter on metadata, we still need to create a
                              ;; new allocation for each shape; with this method we
                              ;; bypass this by mutating a private property on the
                              ;; object removing extra allocation and extra iteration
                              ;; on every request.
                              (unchecked-set obj "__$__counter" counter)
                              (recur (inc counter)
                                     (rest shapes)
                                     (conj result obj)))
                            (recur (inc counter)
                                   (rest shapes)
                                   result))
                          result)))]

    [:div {:class (stl/css :element-list)
           :data-testid "layer-item"}
     [:> hooks/sortable-container* {}
      (for [obj shapes]
        (if (cfh/frame-shape? obj)
          [:> frame-wrapper* {:item obj
                              :rename-id rename-id
                              :selected selected
                              :highlighted highlighted
                              :index (unchecked-get obj "__$__counter")
                              :objects objects
                              :key (dm/str (get obj :id))
                              :is-sortable true
                              :is-filtered is-filtered
                              :parent-size parent-size
                              :depth -1}]
          [:> layer-item* {:item obj
                           :rename-id rename-id
                           :selected selected
                           :highlighted highlighted
                           :index (unchecked-get obj "__$__counter")
                           :objects objects
                           :key (dm/str (get obj :id))
                           :is-sortable true
                           :is-filtered is-filtered
                           :depth -1
                           :parent-size parent-size}]))]]))

(mf/defc layers-tree-wrapper*
  {::mf/private true}
  [{:keys [objects] :as props}]
  ;; This is a performance sensitive componet, so we use lower-level primitives for
  ;; reduce residual allocation for this specific case
  (let [state-tmp   (mf/useState objects)
        objects'    (aget state-tmp 0)
        set-objects (aget state-tmp 1)

        subject-s   (mf/with-memo []
                      (rx/subject))
        changes-s   (mf/with-memo [subject-s]
                      (->> subject-s
                           (rx/debounce 500)))

        props     (mf/spread-props props {:objects objects'})]

    (mf/with-effect [objects subject-s]
      (rx/push! subject-s objects))

    (mf/with-effect [changes-s]
      (let [sub (rx/subscribe changes-s set-objects)]
        #(rx/dispose! sub)))

    [:> layers-tree* props]))

(mf/defc filters-tree*
  {::mf/wrap [mf/memo #(mf/throttle % 200)]
   ::mf/private true}
  [{:keys [objects parent-size]}]
  (let [selected    (use-selected-shapes)
        highlighted (mf/deref ref:highlighted-shapes)
        root        (get objects uuid/zero)]
    [:ul {:class (stl/css :element-list)}
     (for [[index id] (d/enumerate (:shapes root))]
       (when-let [obj (get objects id)]
         [:> layer-item* {:item obj
                          :selected selected
                          :highlighted highlighted
                          :index index
                          :objects objects
                          :key id
                          :is-sortable false
                          :is-filtered true
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

(def ^:private ref:layers-search
  (l/derived (l/key :layers-search) refs/workspace-local))

;; FIXME: optimize
(defn- match-filters?
  [state [id shape]]
  (let [search  (:search-text state)
        scope   (:search-scope state)
        filters (:filters state)
        filters (cond-> filters
                  (contains? filters :shape)
                  (conj :rect :circle :path :bool))
        text-match? (case scope
                      :canvas (and (= :text (:type shape))
                                   (some? (:content shape))
                                   (txt/content-has-text? (:content shape) search))
                      (or (str/includes? (str/lower (:name shape)) (str/lower search))
                          (str/includes? (str/lower (:variant-name shape)) (str/lower search))
                          ;; Dev-only: allow search by id
                          (and *assert* (str/includes? (dm/str (:id shape)) (str/lower search)))))]
    (or (= uuid/zero id)
        (and text-match?
             (or (empty? filters)
                 (and (contains? filters :component) (contains? shape :component-id))
                 (and (contains? filters :image) (some? (cts/has-images? shape)))
                 (let [direct-filters (into #{} (filter #{:frame :rect :circle :path :bool :text}) filters)]
                   (contains? direct-filters (:type shape)))
                 (and (contains? filters :group)
                      (cfh/group-shape? shape)
                      (not (contains? shape :component-id))
                      (or (not (contains? shape :masked-group))
                          (false? (:masked-group shape))))
                 (and (contains? filters :mask) (true? (:masked-group shape))))))))

(mf/defc radio-button*
  {::mf/private true}
  [{:keys [name checked text on-change]}]
  [:label {:class (stl/css-case :radio-label true
                                :selected checked)}
   [:span {:class (stl/css-case :radio-icon true
                                :checked checked)}]
   [:input {:type "radio"
            :name name
            :class (stl/css :radio-input)
            :checked checked
            :on-change on-change}]
   [:span {:class (stl/css :radio-text)}
    text]])

(defn use-search
  [page objects]
  (let [state*                (mf/use-state
                               #(do {:show-search false
                                     :find-replace-mode? false
                                     :search-scope :layers
                                     :show-menu false
                                     :search-text ""
                                     :replace-text ""
                                     :filters #{}
                                     :num-items 100
                                     :current-match-idx 0}))
        layers-search         (mf/deref ref:layers-search)
        state                 (deref state*)
        current-filters       (:filters state)
        current-items         (:num-items state)
        current-search        (:search-text state)
        replace-text          (:replace-text state)
        show-menu?            (:show-menu state)
        show-search?          (:show-search state)
        find-replace-mode?    (:find-replace-mode? state)
        search-scope          (:search-scope state)
        current-match-idx     (:current-match-idx state)
        search-input-ref      (mf/use-ref nil)

        clear-search-text
        (mf/use-fn
         #(swap! state* assoc
                 :search-text ""
                 :num-items 100
                 :current-match-idx 0))

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
           (when (kbd/esc? event)
             (hide-menu))))

        update-search-text
        (mf/use-fn
         (fn [value]
           (swap! state* assoc
                  :search-text value
                  :num-items 100
                  :current-match-idx 0)))

        update-replace-text
        (mf/use-fn
         (fn [event]
           (let [value (dom/get-target-val event)]
             (swap! state* assoc :replace-text value))))

        f-key? (kbd/is-key-ignore-case? "f")
        h-key? (kbd/is-key-ignore-case? "h")

        handle-find-shortcut-keydown
        (mf/use-fn
         (fn [event]
           (when (kbd/mod? event)
             (cond
               (f-key? event)
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (st/emit! (dw/open-layers-search :find)))

               (h-key? event)
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (st/emit! (dw/open-layers-search :find-and-replace)))))))

        set-search-scope
        (mf/use-fn
         (fn [scope]
           (swap! state* assoc
                  :search-scope scope
                  :num-items 100
                  :current-match-idx 0)
           (st/emit! (dw/update-layers-search-scope scope))))

        toggle-mode
        (mf/use-fn
         (mf/deps find-replace-mode?)
         (fn []
           (let [mode (if find-replace-mode? :find :find-and-replace)]
             (st/emit! (dw/open-layers-search mode {:force? true})))))

        toggle-search
        (mf/use-fn
         (mf/deps show-search?)
         (fn [event]
           (let [node (dom/get-current-target event)]
             (dom/blur! node)
             (if show-search?
               (st/emit! dw/close-layers-search)
               (st/emit! (dw/open-layers-search :find {:force? true}))))))

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
                         (dom/get-data "filter") (keyword))]
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

        canvas-match-ids
        (mf/with-memo [objects current-search search-scope]
          (when (and (= :canvas search-scope) (d/not-empty? current-search))
            (reduce-kv (fn [acc id shape]
                         (cond-> acc
                           (and (= :text (:type shape))
                                (some? (:content shape))
                                (txt/content-has-text? (:content shape) current-search))
                           (conj id)))
                       [] objects)))

        layer-match-ids
        (mf/with-memo [objects current-search search-scope]
          (when (and (= :layers search-scope) (d/not-empty? current-search))
            (reduce-kv (fn [acc id shape]
                         (cond-> acc
                           (str/includes? (str/lower (:name shape)) (str/lower current-search))
                           (conj id)))
                       [] objects)))

        text-match-ids    (if (= :canvas search-scope) canvas-match-ids layer-match-ids)
        text-match-count  (count text-match-ids)
        safe-match-idx    (if (pos? text-match-count) (mod current-match-idx text-match-count) 0)

        navigate-next
        (mf/use-fn
         (mf/deps text-match-count)
         (fn [_]
           (when (pos? text-match-count)
             (swap! state* update :current-match-idx
                    (fn [idx]
                      (mod (inc idx) text-match-count))))))

        navigate-prev
        (mf/use-fn
         (mf/deps text-match-count)
         (fn [_]
           (when (pos? text-match-count)
             (swap! state* update :current-match-idx
                    (fn [idx]
                      (mod (+ (dec idx) text-match-count) text-match-count))))))

        handle-replace
        (mf/use-fn
         (mf/deps text-match-ids safe-match-idx replace-text current-search search-scope)
         (fn [_]
           (when (and (pos? text-match-count) (d/not-empty? current-search))
             (let [id (nth text-match-ids safe-match-idx)]
               (if (= :canvas search-scope)
                 (st/emit! (dwt/replace-text-in-shapes [id] current-search replace-text))
                 (st/emit! (dwt/replace-layer-names-in-shapes [id] current-search replace-text)))))))

        handle-replace-all
        (mf/use-fn
         (mf/deps text-match-ids replace-text current-search search-scope)
         (fn [_]
           (when (and (pos? text-match-count) (d/not-empty? current-search))
             (if (= :canvas search-scope)
               (st/emit! (dwt/replace-text-in-shapes text-match-ids current-search replace-text))
               (st/emit! (dwt/replace-layer-names-in-shapes text-match-ids current-search replace-text))))))

        on-replace-keydown
        (mf/use-fn
         (mf/deps handle-replace)
         (fn [event]
           (when (or (kbd/enter? event) (kbd/space? event))
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (handle-replace event))))

        on-replace-all-keydown
        (mf/use-fn
         (mf/deps handle-replace-all)
         (fn [event]
           (when (or (kbd/enter? event) (kbd/space? event))
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (handle-replace-all event))))

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
      (let [key1 (events/listen globals/document "keydown" on-key-down)
            key2 (events/listen globals/document "click" hide-menu)]
        (fn []
          (events/unlistenByKey key1)
          (events/unlistenByKey key2))))

    (mf/with-effect [layers-search]
      (if-let [{:keys [open? find-replace-mode? scope]} layers-search]
        (when open?
          (swap! state* (fn [s]
                          (let [mode-changed? (not= (:find-replace-mode? s) find-replace-mode?)
                                opening?      (not (:show-search s))]
                            (-> s
                                (assoc :show-search true
                                       :find-replace-mode? find-replace-mode?
                                       :search-scope scope)
                                (cond-> (or opening? mode-changed?)
                                  (assoc :search-text "" :replace-text "" :current-match-idx 0)))))))
        (swap! state* (fn [state]
                        (-> state
                            (assoc :search-text ""
                                   :replace-text ""
                                   :filters #{})
                            (assoc :show-menu false
                                   :find-replace-mode? false)
                            (assoc :search-scope :layers
                                   :num-items 100
                                   :current-match-idx 0)
                            (assoc :show-search false))))))

    (mf/with-effect [(get layers-search :scope)]
      (when (and layers-search (:open? layers-search))
        (swap! state* assoc :search-scope (:scope layers-search))))

    (mf/with-effect [layers-search show-search?]
      (when (and layers-search (:open? layers-search) show-search?)
        (ts/raf
         (fn []
           (when-let [node (mf/ref-val search-input-ref)]
             (dom/focus! node))))))

    (mf/with-effect [find-replace-mode? show-search? safe-match-idx text-match-ids]
      (let [match-ids text-match-ids]
        (when (and find-replace-mode? show-search? (seq match-ids))
          (let [current-id (nth match-ids safe-match-idx)]
            (st/emit! (dw/set-search-match-highlight current-id match-ids))))
        (fn []
          (when (seq match-ids)
            (st/emit! (dw/clear-search-match-highlight match-ids))))))

    [filtered-objects
     handle-show-more
     #(mf/html
       (if show-search?
         [:*
          [:div {:class (stl/css :tool-window-bar)}
           [:> search-bar* {:input-ref search-input-ref
                            :class (stl/css :search-item)
                            :on-change update-search-text
                            :value current-search
                            :on-clear clear-search-text
                            :on-key-down handle-find-shortcut-keydown
                            :placeholder (tr "workspace.sidebar.layers.search")}
            [:> icon-button* {:variant "secondary"
                              :class (stl/css :filter-button)
                              :aria-pressed show-menu?
                              :aria-label (tr "workspace.sidebar.layers.filter")
                              :on-click on-toggle-filters-click
                              :icon i/filter}]]
           [:> icon-button* {:variant "ghost"
                             :aria-pressed find-replace-mode?
                             :aria-label (tr "workspace.sidebar.layers.search-and-replace")
                             :on-click toggle-mode
                             :icon i/menu}]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click toggle-search
                             :icon i/close}]]

          [:div {:class (stl/css :replace-wrapper)}
           (when ^boolean find-replace-mode?
             [:div {:class (stl/css :replace-row)}
              [:> input* {:type "text"
                          :placeholder (tr "workspace.sidebar.layers.replace-placeholder")
                          :on-key-down handle-find-shortcut-keydown
                          :on-change update-replace-text}]

              (when (d/not-empty? current-search)
                (if (pos? text-match-count)
                  [:div {:class (stl/css :replace-match-navigation)}
                   [:span {:class (stl/css :replace-match-count)}
                    (dm/str (inc safe-match-idx) " / " text-match-count)]
                   [:> icon-button* {:variant "ghost" :aria-label (tr "labels.previous")
                                     :on-click navigate-prev :icon i/arrow-up}]
                   [:> icon-button* {:variant "ghost" :aria-label (tr "labels.next")
                                     :on-click navigate-next :icon i/arrow-down}]]
                  [:div {:class (stl/css :replace-match-count)}
                   (tr "workspace.sidebar.layers.no-matches")]))])

           [:div {:class (stl/css :replace-scope-row)}
            [:> radio-button* {:name "search-scope"
                               :checked (= :canvas search-scope)
                               :text (tr "workspace.sidebar.layers.search-scope-canvas")
                               :on-change (partial set-search-scope :canvas)}]
            [:> radio-button* {:name "search-scope"
                               :checked (= :layers search-scope)
                               :text (tr "workspace.sidebar.layers.search-scope-layers")
                               :on-change (partial set-search-scope :layers)}]]

           (when ^boolean find-replace-mode?
             [:div {:class (stl/css :replace-actions-row)}
              [:> button* {:variant "secondary"
                           :class (stl/css :replace-actions-button)
                           :on-click handle-replace
                           :on-key-down on-replace-keydown
                           :disabled (or (zero? text-match-count) (str/empty? current-search))}
               (tr "workspace.sidebar.layers.replace")]
              [:> button* {:variant "secondary"
                           :class (stl/css :replace-actions-button)
                           :on-click handle-replace-all
                           :on-key-down on-replace-all-keydown
                           :disabled (or (zero? text-match-count) (str/empty? current-search))}
               (tr "workspace.sidebar.layers.replace-all")]])

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
                            (tr fkey))
                    filter-icon (usi/get-shape-icon-by-type fkey)]

                [:button {:class (stl/css :layer-filter)
                          :key fname
                          :data-filter fname
                          :on-click remove-filter}
                 [:> icon* {:icon-id filter-icon :size "s" :class (stl/css :layer-filter-icon)}]
                 [:span {:class (stl/css :layer-filter-name)}
                  name]
                 [:> icon* {:icon-id i/close-small :class (stl/css :layer-filter-close)}]]))]

           (when ^boolean show-menu?
             [:ul {:class (stl/css :filters-container)}
              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :frame))
                    :data-filter "frame"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/board :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.frames")]]

               (when (contains? current-filters :frame)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]

              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :group))
                    :data-filter "group"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/group :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.groups")]]

               (when (contains? current-filters :group)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]

              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :mask))
                    :data-filter "mask"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/mask :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.masks")]]

               (when (contains? current-filters :mask)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]

              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :component))
                    :data-filter "component"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/component :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.components")]]

               (when (contains? current-filters :component)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]

              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :text))
                    :data-filter "text"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/text :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.texts")]]

               (when (contains? current-filters :text)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]

              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :image))
                    :data-filter "image"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/img :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.images")]]

               (when (contains? current-filters :image)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]

              [:li {:class (stl/css-case :filter-menu-item true
                                         :selected (contains? current-filters :shape))
                    :data-filter "shape"
                    :on-click add-filter}
               [:div {:class (stl/css :filter-menu-item-name-wrapper)}
                [:> icon* {:icon-id i/path :size "s" :class (stl/css :filter-menu-item-icon)}]
                [:span {:class (stl/css :filter-menu-item-name)}
                 (tr "workspace.sidebar.layers.shapes")]]

               (when (contains? current-filters :shape)
                 [:> icon* {:icon-id i/tick :size "s" :class (stl/css :filter-menu-item-tick)}])]])]]

         [:div {:class (stl/css :tool-window-bar)}
          [:> title-bar* {:collapsable  false
                          :class        (stl/css :tool-window-bar-title)
                          :title        (:name page)
                          :on-btn-click toggle-search
                          :btn-icon     "search"
                          :btn-title    (tr "labels.search")}]]))]))

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


(mf/defc layers-toolbox*
  {::mf/wrap [mf/memo]}
  [{:keys [size-parent]}]
  (let [page           (mf/deref refs/workspace-page)
        page-id        (get page :id)

        focus          (mf/deref refs/workspace-focus-selected)

        objects        (hooks/with-focus-objects (:objects page) focus)
        title          (when (= 1 (count focus))
                         (dm/get-in objects [(first focus) :name]))

        observer-var   (mf/use-var nil)
        lazy-load-ref  (mf/use-ref nil)

        [filtered-objects show-more filter-component]
        (use-search page objects)

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

    [:div {:id "layers"
           :class (stl/css :layers)
           :data-testid "layer-tree"}

     (if (d/not-empty? focus)
       [:div {:class (stl/css :tool-window-bar)}
        [:button {:class (stl/css :focus-title)
                  :on-click toogle-focus-mode}
         [:span {:class (stl/css :focus-back-button)}
          [:> icon* {:icon-id i/arrow-left}]]

         [:div {:class (stl/css :focus-name)}
          (or title (tr "workspace.sidebar.layers"))]

         [:div {:class (stl/css :focus-mode-tag-wrapper)}
          [:& badge-notification {:content (tr "workspace.focus.focus-mode")
                                  :size :small
                                  :is-focus true}]]]]

       (filter-component))

     (if (some? filtered-objects)
       [:*
        [:div {:class (stl/css :tool-window-content)
               :data-scroll-container true
               :ref on-render-container}
         [:> filters-tree* {:objects filtered-objects
                            :key (dm/str page-id)
                            :parent-size size-parent}]
         [:div {:ref lazy-load-ref}]]

        [:div {:on-scroll on-scroll
               :class (stl/css :tool-window-content)
               :data-scroll-container true
               :style {:display (when (some? filtered-objects) "none")}}
         [:> layers-tree-wrapper* {:objects filtered-objects
                                   :key (dm/str page-id)
                                   :is-filtered true
                                   :parent-size size-parent}]]]

       [:div {:on-scroll on-scroll
              :class (stl/css :tool-window-content)
              :data-scroll-container true
              :style {:display (when (some? filtered-objects) "none")}}
        [:> layers-tree-wrapper* {:objects objects
                                  :key (dm/str page-id)
                                  :is-filtered false
                                  :parent-size size-parent}]])]))
