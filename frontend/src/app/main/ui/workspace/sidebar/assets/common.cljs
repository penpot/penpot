;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.main.ui.workspace.sidebar.assets.common
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.spec :as us]
   [app.common.thumbnails :as thc]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
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
               (if ^boolean reverse? > <)))))

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
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:& context-menu-a11y
       {:show (:open? state)
        :fixed? (or (not= (:top state) 0) (not= (:left state) 0))
        :on-close on-close
        :top (:top state)
        :left (:left state)
        :options options
        :workspace? true}]

      [:& context-menu
       {:selectable false
        :show (:open? state)
        :on-close on-close
        :top (:top state)
        :left (:left state)
        :options options}])))

(mf/defc section-icon
  [{:keys [section] :as props}]
  (case section
    :colors i/drop-refactor
    :components i/component-refactor
    :typographies i/text-palette-refactor
    i/add-refactor))

(mf/defc asset-section
  {::mf/wrap-props false}
  [{:keys [children file-id title section assets-count open?]}]
  (let [children       (->> (if (array? children) children [children])
                            (filter some?))
        get-role       #(.. % -props -role)
        title-buttons  (filter #(= (get-role %) :title-button) children)
        content        (filter #(= (get-role %) :content) children)
        new-css-system (mf/use-ctx ctx/new-css-system)]
    (if ^boolean new-css-system
      [:div {:class (dom/classnames (css :asset-section) true)}
       [:& title-bar {:collapsable? true
                      :collapsed?   (not open?)
                      :clickable-all? true
                      :on-collapsed #(st/emit! (dw/set-assets-section-open file-id section (not open?)))
                      :class        (css :title-spacing)
                      :title        (mf/html [:span {:class (dom/classnames (css :title-name) true)}
                                              [:span {:class (dom/classnames (css :section-icon) true)}
                                               [:& section-icon {:section section}]]
                                              [:span {:class (dom/classnames (css :section-name) true)}
                                               title]

                                              [:span {:class (dom/classnames (css :num-assets) true)}
                                               assets-count]])}
        title-buttons]
       (when ^boolean open?
         content)]
      [:div.asset-section
       [:div.asset-title {:class (when (not ^boolean open?) "closed")}
        [:span {:on-click #(st/emit! (dw/set-assets-section-open file-id section (not open?)))}
         i/arrow-slide title]
        [:span.num-assets (dm/str "\u00A0(") assets-count ")"] ;; Unicode 00A0 is non-breaking space
        title-buttons]
       (when ^boolean open?
         content)])))

(mf/defc asset-section-block
  [{:keys [children]}]
  [:* children])

(defn create-assets-group
  [rename components-to-group group-name]
  (let [undo-id (js/Symbol)]
    (st/emit! (dwu/start-undo-transaction undo-id))
    (apply st/emit!
           (->> components-to-group
                (map #(rename
                       (:id %)
                       (add-group % group-name)))))
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
    (dom/set-property! counter-el "class" "drag-counter")
    (dom/set-text! counter-el (str asset-count))
    counter-el))

(defn set-drag-image
  [event item-ref num-selected]
  (let [offset          (dom/get-offset-position (.-nativeEvent event))
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


(defn- get-component-thumbnail-uri
  "Returns the component thumbnail uri"
  [file-id component]
  (let [page-id   (:main-instance-page component)
        root-id   (:main-instance-id component)
        object-id (thc/fmt-object-id file-id page-id root-id "component")]
    (if (= file-id (:id @refs/workspace-file))
      (mf/deref (refs/workspace-thumbnail-by-id object-id))
      (let [thumbnails (dm/get-in @refs/workspace-libraries [file-id :thumbnails (dm/str object-id)])]
        thumbnails))))

(mf/defc component-item-thumbnail
  "Component that renders the thumbnail image or the original SVG."
  {::mf/wrap-props false}
  [{:keys [file-id root-shape component container]}]
  (let [retry (mf/use-state 0)
        thumbnail-uri (get-component-thumbnail-uri file-id component)]
    (if (some? thumbnail-uri)
      [:img {:src thumbnail-uri
             :on-error (fn []
                         (when (@retry < 3)
                           (inc retry)))
             :loading "lazy"
             :decoding "async"
             :class (dom/classnames (css :thumbnail) true)}]
      [:& component-svg {:root-shape root-shape
                         :objects (:objects container)}])))


(defn generate-components-menu-entries
  [shapes components-v2]
  (let [multi               (> (count shapes) 1)
        copies              (filter ctk/in-component-copy? shapes)

        current-file-id     (mf/use-ctx ctx/current-file-id)
        objects             (deref refs/workspace-page-objects)
        workspace-data      (deref refs/workspace-data)
        workspace-libraries (deref refs/workspace-libraries)
        current-file        {:id current-file-id :data workspace-data}

        find-component      #(ctf/resolve-component % current-file workspace-libraries)

        local-or-exists     (fn [shape]
                              (let [library-id (:component-file shape)]
                                (or (= library-id current-file-id)
                                    (some? (get workspace-libraries library-id)))))

        restorable-copies   (->> copies
                                 (filter #(nil? (find-component %)))
                                 (filter #(local-or-exists %)))

        touched-not-dangling (filter #(and (cfh/component-touched? objects (:id %))
                                           (find-component %)) copies)
        can-reset-overrides? (or (not components-v2) (seq touched-not-dangling))


        ;; For when it's only one shape
        shape               (first shapes)
        id                  (:id shape)
        main-instance?      (if components-v2 (ctk/main-instance? shape) true)

        component-id        (:component-id shape)
        library-id          (:component-file shape)

        local-component?    (= library-id current-file-id)
        component           (find-component shape)
        lacks-annotation?   (nil? (:annotation component))
        is-dangling?        (nil? component)

        can-show-component? (and (not multi)
                                 (not main-instance?)
                                 (not is-dangling?))

        can-update-main?    (and (not multi)
                                 (not is-dangling?)
                                 (or (not components-v2)
                                     (and (not main-instance?)
                                          (cfh/component-touched? objects (:id shape)))))


        do-detach-component
        #(st/emit! (dwl/detach-components (map :id copies)))

        do-reset-component
        #(st/emit! (dwl/reset-components (map :id touched-not-dangling)))

        do-restore-component
        #(let [;; Extract a map of component-id -> component-file in order to avoid duplicates
               comps-to-restore (reduce (fn [id-file-map {:keys [component-id component-file]}]
                                          (assoc id-file-map component-id component-file))
                                        {}
                                        restorable-copies)]

           (st/emit! (dwl/restore-components comps-to-restore)
                     (when (= 1 (count comps-to-restore))
                       (dw/go-to-main-instance (val (first comps-to-restore)) (key (first comps-to-restore))))))

        do-update-component-sync
        #(st/emit! (dwl/update-component-sync id library-id))

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

        do-show-local-component
        #(st/emit! (dw/go-to-component component-id))

        do-show-in-assets
        #(st/emit! (if components-v2
                     (dw/show-component-in-assets component-id)
                     (dw/go-to-component component-id)))
        do-create-annotation
        #(st/emit! (dw/set-annotations-id-for-create id))

        do-navigate-component-file
        #(st/emit! (dw/go-to-main-instance library-id component-id))

        do-show-component
        #(if local-component?
           (do-show-local-component)
           (do-navigate-component-file))

        menu-entries [(when (and (not multi) main-instance?)
                        {:msg "workspace.shape.menu.show-in-assets"
                         :action do-show-in-assets})
                      (when (and (not multi) main-instance? local-component? lacks-annotation? components-v2)
                        {:msg "workspace.shape.menu.create-annotation"
                         :action do-create-annotation})
                      (when (seq copies)
                        {:msg (if (> (count copies) 1)
                                "workspace.shape.menu.detach-instances-in-bulk"
                                "workspace.shape.menu.detach-instance")
                         :action do-detach-component
                         :shortcut :detach-component})
                      (when can-reset-overrides?
                        {:msg "workspace.shape.menu.reset-overrides"
                         :action do-reset-component})
                      (when (and (seq restorable-copies) components-v2)
                        {:msg "workspace.shape.menu.restore-main"
                         :action do-restore-component})
                      (when can-show-component?
                        {:msg "workspace.shape.menu.show-main"
                         :action do-show-component})
                      (when can-update-main?
                        {:msg "workspace.shape.menu.update-main"
                         :action do-update-component})]]
    (filter (complement nil?) menu-entries)))
