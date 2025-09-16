;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.main.ui.workspace.sidebar.assets.common
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.spec :as us]
   [app.common.thumbnails :as thc]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.variant :as ctv]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg component-svg-thumbnail]]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [c tr]]
   [app.util.strings :refer [matches-search]]
   [app.util.timers :as ts]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def assets-filters           (mf/create-context nil))
(def assets-toggle-ordering   (mf/create-context nil))
(def assets-toggle-list-style (mf/create-context nil))

(defn apply-filters
  [coll {:keys [ordering term] :as filters}]
  (let [reverse? (= :desc ordering)]
    (cond->> coll
      (not ^boolean (str/empty? term))
      (filter (fn [item]
                (or (matches-search (:name item "!$!") term)
                    (matches-search (:path item "!$!") term)
                    (matches-search (:value item "!$!") term))))

      ;; Sort by folder order, but putting all "root" items always
      ;; first, independently of sort order.
      :always
      (sort-by (fn [{:keys [path name] :as item}]
                 (let [path (if (str/empty? path)
                              (if reverse? "z" "a")
                              path)]
                   (str/lower (cfh/merge-path-item path name))))
               (if ^boolean reverse? > <))

      :always
      (vec))))

(defn add-group
  [asset group-name]
  (-> (:path asset)
      (cfh/merge-path-item group-name)
      (cfh/merge-path-item (:name asset))))

(defn rename-group
  [asset path last-path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cfh/split-path)
      butlast
      (vec)
      (conj last-path)
      (cfh/join-path)
      (str (str/slice (:path asset) (count path)))
      (cfh/merge-path-item (:name asset))))

(defn ungroup
  [asset path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cfh/split-path)
      butlast
      (cfh/join-path)
      (str (str/slice (:path asset) (count path)))
      (cfh/merge-path-item (:name asset))))

(s/def ::asset-name ::us/not-empty-string)
(s/def ::name-group-form
  (s/keys :req-un [::asset-name]))

(def initial-context-menu-state
  {:open? false :top nil :left nil})

(defn open-context-menu
  [state pos]
  (let [top (:y pos)
        left (+ (:x pos) 10)]
    (assoc state
           :open? true
           :top top
           :left left)))

(defn close-context-menu
  [state]
  (assoc state :open? false))

(mf/defc assets-context-menu
  {::mf/wrap-props false}
  [{:keys [options state on-close]}]
  [:> context-menu*
   {:show (:open? state)
    :fixed (or (not= (:top state) 0) (not= (:left state) 0))
    :on-close on-close
    :top (:top state)
    :left (:left state)
    :options options}])

(defn section-icon
  [section]
  (case section
    :colors "drop"
    :components "component"
    :typographies "text-palette"
    "add"))

(defn should-display-asset-count?
  [section assets-count]
  (or (not (= section :tokens)) (and (< 0 assets-count) (= section :tokens))))

(mf/defc asset-section
  {::mf/wrap-props false}
  [{:keys [children file-id title section assets-count icon open? on-click]}]
  (let [children    (-> (array/normalize-to-array children)
                        (array/without-nils))

        is-button?  #(as-> % $ (= :title-button (.. ^js $ -props -role)))
        is-content? #(as-> % $ (= :content (.. ^js $ -props -role)))

        buttons     (array/filter is-button? children)
        content     (array/filter is-content? children)

        on-collapsed
        (mf/use-fn
         (mf/deps file-id section open? assets-count)
         (fn [_]
           (when (< 0 assets-count)
             (st/emit! (dw/set-assets-section-open file-id section (not open?))))))

        title
        (mf/html
         [:span {:class (stl/css-case :title-name true
                                      :title-tokens (= section :tokens)
                                      :title-tokens-active (and (= section :tokens) (< 0 assets-count)))}
          [:span {:class (stl/css :section-icon)}
           [:> icon* {:icon-id (or icon (section-icon section)) :size "s"}]]
          [:span {:class (stl/css :section-name)}
           title]

          (when (should-display-asset-count? section assets-count)
            [:span {:class (stl/css :num-assets)}
             assets-count])])]

    [:div {:class (stl/css-case :asset-section true
                                :opened (and (< 0 assets-count)
                                             open?))
           :on-click on-click}
     [:> title-bar*
      {:collapsable   (< 0 assets-count)
       :collapsed     (not open?)
       :all-clickable true
       :on-collapsed  on-collapsed
       :add-icon-gap  (= 0 assets-count)
       :title         title}
      buttons]
     (when ^boolean (and (< 0 assets-count)
                         open?)
       [:div {:class (stl/css-case :title-spacing open?)}
        content])]))

(mf/defc asset-section-block
  {::mf/wrap-props false}
  [{:keys [children]}]
  [:* children])

(defn create-assets-group
  [rename components-to-group group-name]
  (let [undo-id (js/Symbol)]
    (st/emit! (dwu/start-undo-transaction undo-id))
    (->> components-to-group
         (map #(rename
                (:id %)
                (add-group % group-name)))
         (run! st/emit!))
    (st/emit! (dwu/commit-undo-transaction undo-id))))

(defn on-drop-asset
  [event asset dragging* selected selected-full selected-paths rename]
  (let [create-typed-assets-group (partial create-assets-group rename)]
    (when (not (dnd/from-child? event))
      (reset! dragging* false)
      (when
       (and (not (contains? selected (:id asset)))
            (every? #(= % (:path asset)) selected-paths))
        (let [components-to-group (conj selected-full asset)
              create-typed-assets-group (partial create-typed-assets-group components-to-group)]
          (modal/show! :name-group-dialog {:accept create-typed-assets-group}))))))

(defn on-drag-enter-asset
  [event asset dragging* selected selected-paths]
  (when (and
         (not (dnd/from-child? event))
         (every? #(= % (:path asset)) selected-paths)
         (not (contains? selected (:id asset))))
    (reset! dragging* true)))

(defn on-drag-leave-asset
  [event dragging*]
  (when (not (dnd/from-child? event))
    (reset! dragging* false)))

(defn create-counter-element
  [asset-count]
  (let [counter-el (dom/create-element "div")]
    (dom/set-property! counter-el "class" (stl/css :drag-counter))
    (dom/set-text! counter-el (tr "workspace.assets.sidebar.components" (c asset-count)))
    counter-el))

(defn set-drag-image
  [event item-ref num-selected]
  (let [offset          (dom/get-offset-position
                         (dom/event->native-event event))
        item-el         (mf/ref-val item-ref)
        counter-el      (create-counter-element num-selected)]

    ;; set-drag-image requires that the element is rendered and
    ;; visible to the user at the moment of creating the ghost
    ;; image (to make a snapshot), but you may remove it right
    ;; afterwards, in the next render cycle.
    (dom/append-child! item-el counter-el)
    (dnd/set-drag-image! event item-el (:x offset) (:y offset))
    (ts/raf #(.removeChild ^js item-el counter-el))))

(defn on-asset-drag-start
  [event file-id asset selected item-ref asset-type on-drag-start]
  (let [id-asset     (:id asset)
        num-selected (if (contains? selected id-asset)
                       (count selected)
                       1)]
    (when (not (contains? selected id-asset))
      (st/emit! (dw/unselect-all-assets file-id)
                (dw/toggle-selected-assets file-id id-asset asset-type)))
    (on-drag-start asset event)
    (when (> num-selected 1)
      (set-drag-image event item-ref num-selected))))

(defn on-drag-enter-asset-group
  [event dragging* prefix selected-paths]
  (dom/stop-propagation event)
  (when (and (not (dnd/from-child? event))
             (not (every? #(= % prefix) selected-paths)))
    (reset! dragging* true)))

(defn on-drop-asset-group
  [event dragging* prefix selected-paths selected-full rename]
  (dom/stop-propagation event)
  (when (not (dnd/from-child? event))
    (reset! dragging* false)
    (when (not (every? #(= % prefix) selected-paths))
      (doseq [target-asset selected-full]
        (st/emit!
         (rename
          (:id target-asset)
          (cfh/merge-path-item prefix (:name target-asset))))))))

(mf/defc component-item-thumbnail*
  "Component that renders the thumbnail image or the original SVG."
  [{:keys [file-id root-shape component container class is-hidden]}]
  (let [page-id (:main-instance-page component)
        root-id (:main-instance-id component)
        retry   (mf/use-state 0)

        thumbnail-uri*
        (mf/with-memo [file-id page-id root-id]
          (let [object-id (thc/fmt-object-id file-id page-id root-id "component")]
            (refs/workspace-thumbnail-by-id object-id)))

        thumbnail-uri
        (mf/deref thumbnail-uri*)

        on-error
        (mf/use-fn
         (mf/deps @retry)
         (fn []
           (when (< @retry 3)
             (inc retry))))]

    (if (and (some? thumbnail-uri)
             (contains? cf/flags :component-thumbnails))
      [:& component-svg-thumbnail
       {:thumbnail-uri thumbnail-uri
        :class class
        :on-error on-error
        :root-shape root-shape
        :objects (:objects container)
        :show-grids? true}]

      [:& component-svg
       {:root-shape root-shape
        :class class
        :objects (:objects container)
        :show-grids? true
        :is-hidden is-hidden}])))

(defn generate-components-menu-entries
  [shapes & {:keys [for-design-tab?]}]
  (let [multi               (> (count shapes) 1)
        copies              (filter ctk/in-component-copy? shapes)

        current-file-id     (mf/use-ctx ctx/current-file-id)
        current-page-id     (mf/use-ctx ctx/current-page-id)

        libraries           (deref refs/files)
        current-file        (get libraries current-file-id)

        objects             (-> (dsh/get-page (:data current-file) current-page-id)
                                (get :objects))

        find-component      (fn [shape include-deleted?]
                              (ctf/resolve-component
                               shape current-file libraries {:include-deleted? include-deleted?}))

        local-or-exists     (fn [shape]
                              (let [library-id (:component-file shape)]
                                (or (= library-id current-file-id)
                                    (some? (get libraries library-id)))))

        restorable-copies   (->> copies
                                 (filter #(nil? (find-component % false)))
                                 (filter #(local-or-exists %)))

        touched-not-dangling (filter #(and (cfh/component-touched? objects (:id %))
                                           (find-component % false)) copies)
        can-reset-overrides? (seq touched-not-dangling)


        ;; For when it's only one shape


        shape               (first shapes)
        shape-id            (:id shape)

        main-instance?      (ctk/main-instance? shape)
        variant-container?  (ctk/is-variant-container? shape)

        component-id        (:component-id shape)
        variant-id          (:variant-id shape)
        library-id          (:component-file shape)

        local-component?    (= library-id current-file-id)
        component           (find-component shape false)
        lacks-annotation?   (nil? (:annotation component))
        is-dangling?        (nil? component)

        can-show-component? (and (not multi)
                                 (not main-instance?)
                                 (not is-dangling?))

        can-update-main?    (and (not multi)
                                 (not is-dangling?)
                                 (and (not main-instance?)
                                      (not (ctn/has-any-copy-parent? objects shape))
                                      (cfh/component-touched? objects (:id shape))))

        can-detach? (and (seq copies)
                         (every? #(not (ctn/has-any-copy-parent? objects %)) copies))

        same-variant? (ctv/same-variant? shapes)

        is-restorable-variant?
        ;; A shape is a restorable variant if its component is deleted, is a variant,
        ;; and the variant-container in which it will be restored still exists
        (fn [shape]
          (let [component (find-component shape true)
                main      (ctk/get-component-root component)
                objects   (dm/get-in libraries [(:component-file shape)
                                                :data
                                                :pages-index
                                                (:main-instance-page component)
                                                :objects])

                parent    (get objects (:parent-id main))]
            (and (:deleted component) (ctk/is-variant? component) parent)))

        restorable-variants? (every? is-restorable-variant? restorable-copies)

        do-detach-component
        #(st/emit! (dwl/detach-components (map :id copies)))

        do-reset-component
        #(st/emit! (dwl/reset-components (map :id touched-not-dangling)))

        do-update-component-sync
        #(st/emit! (dwl/update-component-sync shape-id library-id))

        do-update-remote-component
        (fn []
          (st/emit! (modal/show
                     {:type :confirm
                      :message ""
                      :title (tr "modals.update-remote-component.message")
                      :hint (tr "modals.update-remote-component.hint")
                      :cancel-label (tr "modals.update-remote-component.cancel")
                      :accept-label (tr "modals.update-remote-component.accept")
                      :accept-style :primary
                      :on-accept do-update-component-sync})))

        do-update-component
        #(if local-component?
           (do-update-component-sync)
           (do-update-remote-component))

        do-show-in-assets
        (let [component-id (if variant-container?
                             (->> (:shapes shape) (mapv #(get objects %)) first :component-id)
                             component-id)]
          #(st/emit! (dw/show-component-in-assets component-id)))

        do-create-annotation
        #(st/emit! (dw/set-annotations-id-for-create shape-id))

        do-add-variant
        #(if (ctk/is-variant? shape)
           (st/emit!
            (ev/event {::ev/name "add-new-variant"
                       ::ev/origin (if for-design-tab? "workspace:design-tab-menu-variant" "workspace:context-menu-variant")})
            (dwv/add-new-variant shape-id))
           (st/emit!
            (ev/event {::ev/name "transform-in-variant"
                       ::ev/origin (if for-design-tab? "workspace:design-tab-menu" "workspace:context-menu")})
            (dwv/transform-in-variant shape-id)))

        do-add-new-property
        #(st/emit!
          (ev/event {::ev/name "add-new-property" ::ev/origin "workspace:design-tab-menu-variant"})
          (dwv/add-new-property variant-id {:property-value "Value 1" :editing? true}))

        do-show-local-component
        #(st/emit! (dwl/go-to-local-component :id component-id))

        ;; When the show-remote is after a restore, the component may still be deleted
        do-show-remote-component
        (fn [update-layout?]
          (when-let [comp (find-component shape true)]
            (st/emit! (dwl/go-to-component-file library-id comp update-layout?))))

        do-show-component
        (fn [_ update-layout?]
          (st/emit! dw/hide-context-menu)
          (if local-component?
            (do-show-local-component)
            (do-show-remote-component update-layout?)))

        do-restore-component
        (fn []
          (let [;; Extract a map of component-id -> component-file in order to avoid duplicates
                comps-to-restore (reduce (fn [id-file-map {:keys [component-id component-file]}]
                                           (assoc id-file-map component-id component-file))
                                         {}
                                         restorable-copies)]

            (st/emit! (dwl/restore-components comps-to-restore))
            (when (= 1 (count comps-to-restore))
              (ts/schedule 1000 #(do-show-component nil true)))))

        menu-entries [(when (or (and (not multi) (or variant-container? main-instance?))
                                (and multi same-variant?))
                        {:title (tr "workspace.shape.menu.show-in-assets")
                         :action do-show-in-assets})
                      (when (and (not multi) main-instance? local-component? lacks-annotation?)
                        {:title (tr "workspace.shape.menu.create-annotation")
                         :action do-create-annotation})
                      (when can-detach?
                        {:title (if (> (count copies) 1)
                                  (tr "workspace.shape.menu.detach-instances-in-bulk")
                                  (tr "workspace.shape.menu.detach-instance"))
                         :action do-detach-component
                         :shortcut :detach-component})
                      (when can-reset-overrides?
                        {:title (tr "workspace.shape.menu.reset-overrides")
                         :action do-reset-component})
                      (when (seq restorable-copies)
                        {:title
                         (if restorable-variants?
                           (tr "workspace.shape.menu.restore-variant")
                           (tr "workspace.shape.menu.restore-main"))
                         :action do-restore-component})
                      (when can-show-component?
                        {:title (tr "workspace.shape.menu.show-main")
                         :action do-show-component})
                      (when can-update-main?
                        {:title (tr "workspace.shape.menu.update-main")
                         :action do-update-component})
                      (when (and (or (not multi) same-variant?) main-instance?)
                        {:title (tr "workspace.shape.menu.add-variant")
                         :shortcut :create-component-variant
                         :action do-add-variant})
                      (when (and same-variant? main-instance? variant-id for-design-tab?)
                        {:title (tr "workspace.shape.menu.add-variant-property")
                         :action do-add-new-property})]]
    (filter (complement nil?) menu-entries)))
