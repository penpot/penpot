;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.main.ui.workspace.sidebar.assets.common
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
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
  (let [reverse? (= :desc ordering)
        comp-fn  (if ^boolean reverse? > <)]
    (->> coll
         (filter (fn [item]
                   (or (matches-search (:name item "!$!") term)
                       (matches-search (:value item "!$!") term))))
                                        ; Sort by folder order, but
                                        ; putting all "root" items
                                        ; always first, independently
                                        ; of sort order.
         (sort-by #(str/lower (cph/merge-path-item (if (empty? (:path %))
                                                     (if reverse? "z" "a")
                                                     (:path %))
                                                   (:name %)))
                  comp-fn))))

(defn add-group
  [asset group-name]
  (-> (:path asset)
      (cph/merge-path-item group-name)
      (cph/merge-path-item (:name asset))))

(defn rename-group
  [asset path last-path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cph/split-path)
      butlast
      (vec)
      (conj last-path)
      (cph/join-path)
      (str (str/slice (:path asset) (count path)))
      (cph/merge-path-item (:name asset))))

(defn ungroup
  [asset path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cph/split-path)
      butlast
      (cph/join-path)
      (str (str/slice (:path asset) (count path)))
      (cph/merge-path-item (:name asset))))

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
                      :on-collapsed #(st/emit! (dw/set-assets-section-open file-id section (not open?)))
                      :klass        :title-spacing
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
          (cph/merge-path-item prefix (:name target-asset))))))))
