;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.layers
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.main.ui.workspace.sidebar.layer-item :refer [layer-item* layer-objects-ctx use-objects-ref
                                                     ref:shape-for-rename sidebar-selected-id]]
   [app.main.ui.workspace.sidebar.layer-rows :as lr]
   [app.main.ui.workspace.sidebar.scroll :as sc]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.shape-icon :as usi]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private default-row-height 36)

;; Rows rendered above and below the visible ones
(def ^:private overscan 10)

(def ^:private ref:expanded
  (l/derived :expanded refs/workspace-local))

(def ^:private ref:single-selected
  (l/derived #(when (= 1 (count %)) (first %)) refs/selected-shapes))

(defn- get-scroll-node
  [list-ref]
  (dom/get-parent-with-data (mf/ref-val list-ref) "scroll-container"))

(defn- use-viewport
  "First visible row and number of visible rows of the scroll container
  around `list-ref`."
  [list-ref row-height]
  (let [viewport* (mf/use-state [0 0])]
    (mf/with-layout-effect [row-height]
      (let [node     (get-scroll-node list-ref)
            sync     (fn []
                       (let [viewport [(mth/floor (/ (.-scrollTop node) row-height))
                                       (mth/ceil (/ (.-clientHeight node) row-height))]]
                         (swap! viewport* #(if (= % viewport) % viewport))))
            observer (js/ResizeObserver. sync)]
        (sync)
        (.addEventListener node "scroll" sync #js {:passive true})
        (.observe observer node)
        (fn []
          (.removeEventListener node "scroll" sync)
          (.disconnect observer))))
    (deref viewport*)))

(defn- scroll-to-row
  [node index row-height]
  (let [row-top  (* index row-height)
        view-top (.-scrollTop node)
        height   (.-clientHeight node)]
    (when (or (< row-top view-top)
              (> (+ row-top row-height) (+ view-top height)))
      (set! (.-scrollTop node) (- row-top (/ (- height row-height) 2))))))

(mf/defc layers-tree*
  {::mf/wrap [mf/memo]}
  [{:keys [objects is-filtered parent-size]}]
  (let [objects-ref   (use-objects-ref objects)
        expanded      (mf/deref ref:expanded)
        single-id     (mf/deref ref:single-selected)
        rename-id     (mf/deref ref:shape-for-rename)

        list-ref      (mf/use-ref nil)
        cache-ref     (mf/use-ref (js/Map.))
        scroll-to-ref (mf/use-ref nil)
        row-height*   (mf/use-state default-row-height)
        row-height    (deref row-height*)

        root          (mf/with-memo [objects expanded]
                        (let [[root cache] (lr/build-tree (mf/ref-val cache-ref) objects expanded)]
                          (mf/set-ref-val! cache-ref cache)
                          root))
        total         (lr/get-size root)

        [first-row visible] (use-viewport list-ref row-height)
        start         (mth/max 0 (- first-row overscan))
        end           (mth/min total (+ first-row visible overscan))
        rows          (lr/get-rows root start end)

        ;; The root board holding the first visible row stays on top
        ;; while its children scroll under it
        [top-row top-index] (lr/get-top-row root first-row)
        sticky-id     (when (and (some? top-row)
                                 (lr/open? top-row)
                                 (cfh/frame-shape? (lr/get-shape top-row)))
                        (lr/get-id top-row))

        render-row
        (fn [row]
          (mf/html
           [:> layer-item* {:id (lr/get-id row)
                            :index (lr/get-position row)
                            :depth (lr/get-depth row)
                            :is-component-child (lr/component-child? row)
                            :has-copy-parent (lr/copy-parent? row)
                            :key (dm/str (lr/get-id row))
                            :is-sortable true
                            :is-filtered is-filtered
                            :parent-size parent-size}]))]

    (mf/with-layout-effect [total]
      (when-let [node (dom/query (mf/ref-val list-ref) "[data-testid='layer-row']")]
        (let [height (.-offsetHeight node)]
          (when (pos? height)
            (reset! row-height* height)))))

    (mf/with-effect [single-id]
      (let [clicked-id @sidebar-selected-id]
        (vreset! sidebar-selected-id nil)
        (mf/set-ref-val! scroll-to-ref (when (not= single-id clicked-id) single-id))))

    (mf/with-effect [rename-id]
      (when (some? rename-id)
        (mf/set-ref-val! scroll-to-ref rename-id)))

    (mf/with-effect [root single-id rename-id row-height]
      (when-let [id (mf/ref-val scroll-to-ref)]
        (when-let [index (lr/get-index root @objects-ref id)]
          (mf/set-ref-val! scroll-to-ref nil)
          (scroll-to-row (get-scroll-node list-ref) index row-height))))

    [:> (mf/provider layer-objects-ctx) {:value objects-ref}
     [:div {:class (stl/css :element-list)
            :ref list-ref
            :data-testid "layer-item"}
      [:> hooks/sortable-container* {}
       (if (and (some? sticky-id) (< top-index start))
         [:*
          [:div {:style {:block-size (* top-index row-height)}}]
          [:div {:class (stl/css :sticky-row)}
           (render-row top-row)]
          [:div {:style {:block-size (* (- start top-index 1) row-height)}}]]
         [:div {:style {:block-size (* start row-height)}}])

       ;; Root rows keep one wrapper so turning sticky does not remount them
       (for [row rows]
         (let [id (lr/get-id row)]
           (if (= -1 (lr/get-depth row))
             [:div {:class (stl/css-case :sticky-row (= sticky-id id))
                    :key (dm/str id)}
              (render-row row)]
             (render-row row))))

       [:div {:style {:block-size (* (- total end) row-height)}}]]]]))

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
  {::mf/wrap [mf/memo #(mf/throttle % 50)]
   ::mf/private true}
  [{:keys [objects parent-size]}]
  (let [objects-ref (use-objects-ref objects)]
    [:> (mf/provider layer-objects-ctx) {:value objects-ref}
     [:ul {:class (stl/css :element-list)}
      (for [[index id] (d/enumerate (dm/get-in objects [uuid/zero :shapes]))]
        [:> layer-item* {:id id
                         :index index
                         :key id
                         :is-sortable false
                         :is-filtered true
                         :depth -1
                         :parent-size parent-size}])]]))

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

(def ^:private default-search-state
  {:show-search false
   :find-replace-mode? false
   :search-scope :layers
   :show-menu false
   :search-text ""
   :replace-text ""
   :filters #{}
   :num-items 100
   :current-match-idx 0})

;; Per-file, session-scoped (in-memory only) so an open search and its
;; filters survive switching between the sidebar tabs, which unmounts the
;; layers panel, without leaking across files or persisting across reloads.
(defonce ^:private session-search*
  (atom {}))

(defn use-search
  [page objects]
  (let [file-id               (mf/use-ctx ctx/current-file-id)
        state*                (mf/use-state
                               #(-> default-search-state
                                    (merge (get @session-search* file-id))
                                    (assoc :show-menu false)))
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
        can-edit?             (:can-edit (deref refs/permissions))

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

        close-search
        (mf/use-fn
         #(st/emit! dw/close-layers-search))

        dismiss-filters-menu
        (mf/use-fn
         (mf/deps show-menu? hide-menu)
         (fn []
           (when ^boolean show-menu?
             (hide-menu)
             true)))

        on-search-key-down
        (hooks/use-escape-to-close close-search dismiss-filters-menu)

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

        text-match-ids-ref (mf/use-ref text-match-ids)
        match-idx-ref      (mf/use-ref 0)

        _                  (mf/set-ref-val! text-match-ids-ref text-match-ids)

        navigate-next
        (mf/use-fn
         (mf/deps text-match-ids text-match-count)
         (fn [_]
           (when (pos? text-match-count)
             (let [ids      (mf/ref-val text-match-ids-ref)
                   next-idx (mod (inc (mf/ref-val match-idx-ref)) text-match-count)
                   id       (nth ids next-idx)]
               (mf/set-ref-val! match-idx-ref next-idx)
               (swap! state* assoc :current-match-idx next-idx)
               (st/emit! (dw/select-shape id)
                         (dw/center-on-shape id))))))

        navigate-prev
        (mf/use-fn
         (mf/deps text-match-ids text-match-count)
         (fn [_]
           (when (pos? text-match-count)
             (let [ids      (mf/ref-val text-match-ids-ref)
                   prev-idx (mod (+ (dec (mf/ref-val match-idx-ref)) text-match-count) text-match-count)
                   id       (nth ids prev-idx)]
               (mf/set-ref-val! match-idx-ref prev-idx)
               (swap! state* assoc :current-match-idx prev-idx)
               (st/emit! (dw/select-shape id)
                         (dw/center-on-shape id))))))

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

    (mf/with-effect [file-id state]
      (swap! session-search* assoc file-id state))

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
          [:div {:class (stl/css :tool-window-bar)
                 :on-key-down on-search-key-down}
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
           (when can-edit?
             [:> icon-button* {:variant "ghost"
                               :aria-pressed find-replace-mode?
                               :aria-label (tr "workspace.sidebar.layers.search-and-replace")
                               :on-click toggle-mode
                               :icon i/menu}])
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click toggle-search
                             :icon i/close}]]

          [:div {:class (stl/css :replace-wrapper)
                 :on-key-down on-search-key-down}
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
                            ;; Unreachable from the UI (the menu only emits the
                            ;; above); fall back to the raw name, which is what
                            ;; tr would render for an unknown key anyway.
                            fname)
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

(mf/defc layers-toolbox*
  {::mf/wrap [mf/memo]}
  [{:keys [size-parent scroll-store]}]
  (let [page           (mf/deref refs/workspace-page)
        page-id        (get page :id)

        focus          (mf/deref refs/workspace-focus-selected)

        objects        (hooks/with-focus-objects (:objects page) focus)
        title          (when (= 1 (count focus))
                         (dm/get-in objects [(first focus) :name]))

        observer-var   (mf/use-var nil)
        lazy-load-ref  (mf/use-ref nil)
        tree-ref       (mf/use-ref nil)
        search-ref     (mf/use-ref nil)

        [filtered-objects show-more filter-component]
        (use-search page objects)

        searching?     (some? filtered-objects)

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

        ;; The search-results container reuses the lazy-load observer root
        ;; and additionally tracks its node for scroll restore.
        on-render-search-container
        (fn [element]
          (mf/set-ref-val! search-ref element)
          (on-render-container element))

        on-scroll-with-save
        (mf/use-fn
         (mf/deps page-id)
         (fn [event]
           (sc/save-scroll! scroll-store [:layers page-id] event)))

        on-search-scroll
        (mf/use-fn
         (mf/deps page-id)
         (fn [event]
           (sc/save-scroll! scroll-store [:layers-search page-id] event)))

        toogle-focus-mode
        (mf/use-fn
         #(st/emit! (dw/toggle-focus-mode)))]

    (sc/use-restore-scroll scroll-store :layers page-id tree-ref (not searching?))
    (sc/use-restore-scroll scroll-store :layers-search page-id search-ref searching?)

    (mf/with-effect [page-id searching?]
      (when-not ^boolean searching?
        (sc/forget-scroll! scroll-store [:layers-search page-id])))

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
               :on-scroll on-search-scroll
               :ref on-render-search-container}
         [:> filters-tree* {:objects filtered-objects
                            :key (dm/str page-id)
                            :parent-size size-parent}]
         [:div {:ref lazy-load-ref}]]

        [:div {:on-scroll on-scroll-with-save
               :ref tree-ref
               :class (stl/css :tool-window-content)
               :data-scroll-container true
               :style {:display (when (some? filtered-objects) "none")}}
         [:> layers-tree-wrapper* {:objects filtered-objects
                                   :key (dm/str page-id)
                                   :is-filtered true
                                   :parent-size size-parent}]]]

       [:div {:on-scroll on-scroll-with-save
              :ref tree-ref
              :class (stl/css :tool-window-content)
              :data-scroll-container true
              :style {:display (when (some? filtered-objects) "none")}}
        [:> layers-tree-wrapper* {:objects objects
                                  :key (dm/str page-id)
                                  :is-filtered false
                                  :parent-size size-parent}]])]))
