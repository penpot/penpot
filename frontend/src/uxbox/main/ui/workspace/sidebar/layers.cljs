;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.layers
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [goog.events :as events]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.data :refer (read-string classnames)]
            [uxbox.util.router :as r]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom.dnd :as dnd]
            [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

;; --- Helpers

(defn- focus-page
  [id]
  (-> (l/in [:pages id])
      (l/derive st/state)))

(defn- select-shape
  [selected item event]
  (dom/prevent-default event)
  (let [id (:id item)]
    (cond
      (or (:blocked item)
          (:hidden item))
      nil

      (.-ctrlKey event)
      (st/emit! (uds/select-shape id))

      (> (count selected) 1)
      (st/emit! (uds/deselect-all)
                (uds/select-shape id))

      (contains? selected id)
      (st/emit! (uds/select-shape id))

      :else
      (st/emit! (uds/deselect-all)
                (uds/select-shape id)))))

(defn- toggle-visibility
  [selected item event]
  (dom/stop-propagation event)
  (let [id (:id item)
        hidden? (:hidden item)]
    (if hidden?
      (st/emit! (uds/show-shape id))
      (st/emit! (uds/hide-shape id)))
    (when (contains? selected id)
      (st/emit! (uds/select-shape id)))))

(defn- toggle-blocking
  [item event]
  (dom/stop-propagation event)
  (let [id (:id item)
        blocked? (:blocked item)]
    (if blocked?
      (st/emit! (uds/unblock-shape id))
      (st/emit! (uds/block-shape id)))))

(defn- element-icon
  [item]
  (case (:type item)
    :icon (icon/icon-svg item)
    :image i/image
    :line i/line
    :circle i/circle
    :path i/curve
    :rect i/box
    :text i/text
    :group i/folder))

;; --- Shape Name (Component)

(mx/defcs shape-name
  "A generic component that displays the shape name
  if it is available and allows inline edition of it."
  {:mixins [mx/static (mx/local)]}
  [{:keys [rum/local]} {:keys [id] :as shape}]
  (letfn [(on-blur [event]
            (let [target (dom/event->target event)
                  parent (.-parentNode target)
                  name (dom/get-value target)]
              (set! (.-draggable parent) true)
              (st/emit! (uds/rename-shape id name))
              (swap! local assoc :edition false)))
          (on-key-down [event]
            (js/console.log event)
            (when (kbd/enter? event)
              (on-blur event)))
          (on-click [event]
            (dom/prevent-default event)
            (let [parent (.-parentNode (.-target event))]
              (set! (.-draggable parent) false))
            (swap! local assoc :edition true))]
    (if (:edition @local)
      [:input.element-name
       {:type "text"
        :on-blur on-blur
        :on-key-down on-key-down
        :auto-focus true
        :default-value (:name shape "")}]
      [:span.element-name
       {:on-double-click on-click}
       (:name shape "")])))

;; --- Layer Simple (Component)

(mx/defcs layer-simple
  {:mixins [mx/static (mx/local)]}
  [{:keys [rum/local]} item selected]
  (let [selected? (contains? selected (:id item))
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)
        li-classes (classnames
                    :selected selected?
                    :hide (:dragging @local))
        body-classes (classnames
                      :selected selected?
                      :drag-active (:dragging @local)
                      :drag-top (= :top (:over @local))
                      :drag-bottom (= :bottom (:over @local))
                      :drag-inside (= :middle (:over @local)))]
    (letfn [(on-drag-start [event]
              (let [target (dom/event->target event)]
                (dnd/set-allowed-effect! event "move")
                (dnd/set-data! event (:id item))
                (dnd/set-image! event target 50 10)
                (swap! local assoc :dragging true)))
            (on-drag-end [event]
              (swap! local assoc :dragging false :over nil))
            (on-drop [event]
              (dom/stop-propagation event)
              (let [id (dnd/get-data event)
                    over (:over @local)]
                (case (:over @local)
                  :top (st/emit! (uds/drop-shape id (:id item) :before))
                  :bottom (st/emit! (uds/drop-shape id (:id item) :after)))
                (swap! local assoc :dragging false :over nil)))
            (on-drag-over [event]
              (dom/prevent-default event)
              (dnd/set-drop-effect! event "move")
              (let [over (dnd/get-hover-position event false)]
                (swap! local assoc :over over)))
            (on-drag-enter [event]
              (swap! local assoc :over true))
            (on-drag-leave [event]
              (swap! local assoc :over false))]
      [:li {:class li-classes}
       [:div.element-list-body
        {:class body-classes
         :style {:opacity (if (:dragging @local)
                            "0.5"
                            "1")}
         :on-click select
         :on-double-click #(dom/stop-propagation %)
         :on-drag-start on-drag-start
         :on-drag-enter on-drag-enter
         :on-drag-leave on-drag-leave
         :on-drag-over on-drag-over
         :on-drag-end on-drag-end
         :on-drop on-drop
         :draggable true}

        [:div.element-actions
         [:div.toggle-element
          {:class (when-not (:hidden item) "selected")
           :on-click toggle-visibility}
          i/eye]
         [:div.block-element
          {:class (when (:blocked item) "selected")
           :on-click toggle-blocking}
          i/lock]]
        [:div.element-icon (element-icon item)]
        (shape-name item)]])))

;; --- Layer Group (Component)

(mx/defcs layer-group
  {:mixins [mx/static mx/reactive (mx/local)]}
  [{:keys [rum/local]} {:keys [id] :as item} selected]
  (let [selected? (contains? selected (:id item))
        collapsed? (:collapsed item true)
        shapes-map (mx/react refs/shapes-by-id)
        classes (classnames
                 :selected selected?
                 :drag-top (= :top (:over @local))
                 :drag-bottom (= :bottom (:over @local))
                 :drag-inside (= :middle (:over @local)))
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)]
    (letfn [(toggle-collapse [event]
              (dom/stop-propagation event)
              (if (:collapsed item true)
                (st/emit! (uds/uncollapse-shape id))
                (st/emit! (uds/collapse-shape id))))
            (toggle-locking [event]
              (dom/stop-propagation event)
              (if (:locked item)
                (st/emit! (uds/unlock-shape id))
                (st/emit! (uds/lock-shape id))))
            (on-drag-start [event]
              (let [target (dom/event->target event)]
                (dnd/set-allowed-effect! event "move")
                (dnd/set-data! event (:id item))
                (swap! local assoc :dragging true)))
            (on-drag-end [event]
              (swap! local assoc :dragging false :over nil))
            (on-drop [event]
              (dom/stop-propagation event)
              (let [coming-id (dnd/get-data event)
                    over (:over @local)]
                (case (:over @local)
                  :top (st/emit! (uds/drop-shape coming-id id :before))
                  :bottom (st/emit! (uds/drop-shape coming-id id :after))
                  :middle (st/emit! (uds/drop-shape coming-id id :inside)))
                (swap! local assoc :dragging false :over nil)))
            (on-drag-over [event]
              (dom/prevent-default event)
              (dnd/set-drop-effect! event "move")
              (let [over (dnd/get-hover-position event true)]
                (swap! local assoc :over over)))
            (on-drag-enter [event]
              (swap! local assoc :over true))
            (on-drag-leave [event]
              (swap! local assoc :over false))]
      [:li.group {:class (when-not collapsed? "open")}
       [:div.element-list-body
        {:class classes
         :draggable true
         :on-drag-start on-drag-start
         :on-drag-enter on-drag-enter
         :on-drag-leave on-drag-leave
         :on-drag-over on-drag-over
         :on-drag-end on-drag-end
         :on-drop on-drop
         :on-click select}
        [:div.element-actions
         [:div.toggle-element
          {:class (when-not (:hidden item) "selected")
           :on-click toggle-visibility}
          i/eye]
         [:div.block-element
          {:class (when (:blocked item) "selected")
           :on-click toggle-blocking}
          i/lock]
         [:div.chain-element
          {:class (when (:locked item) "selected")
           :on-click toggle-locking}
          i/chain]]
        [:div.element-icon i/folder]
        (shape-name item)
        [:span.toggle-content
         {:on-click toggle-collapse
          :class (when-not collapsed? "inverse")}
         i/arrow-slide]]
       (if-not collapsed?
         [:ul
          (for [shape (map #(get shapes-map %) (:items item))
                :let [key (str (:id shape))]]
            (if (= (:type shape) :group)
              (-> (layer-group shape selected)
                  (mx/with-key key))
              (-> (layer-simple shape selected)
                  (mx/with-key key))))])])))

;; --- Layers Tools (Buttons Component)

(defn- allow-grouping?
  "Check if the current situation allows grouping
  of the currently selected shapes."
  [selected shapes-map]
  (let [xform (comp (map shapes-map)
                    (map :group))
        groups (into #{} xform selected)]
    (= 1 (count groups))))

(defn- allow-ungrouping?
  "Check if the current situation allows ungrouping
  of the currently selected shapes."
  [selected shapes-map]
  (let [shapes (into #{} (map shapes-map) selected)
        groups (into #{} (map :group) shapes)]
    (or (and (= 1 (count shapes))
             (= :group (:type (first shapes))))
        (and (= 1 (count groups))
             (not (nil? (first groups)))))))

(mx/defc layers-tools
  "Layers widget options buttons."
  [selected shapes-map]
  (let [duplicate #(st/emit! (uds/duplicate-selected))
        group #(st/emit! (uds/group-selected))
        ungroup #(st/emit! (uds/ungroup-selected))
        delete #(st/emit! (uds/delete-selected))

        allow-grouping? (allow-grouping? selected shapes-map)
        allow-ungrouping? (allow-ungrouping? selected shapes-map)
        allow-duplicate? (= 1 (count selected))
        allow-deletion? (pos? (count selected))]
    [:div.layers-tools
     [:ul.layers-tools-content
      [:li.clone-layer.tooltip.tooltip-top
       {:alt "Duplicate"
        :class (when-not allow-duplicate? "disable")
        :on-click duplicate}
       i/copy]
      [:li.group-layer.tooltip.tooltip-top
       {:alt "Group"
        :class (when-not allow-grouping? "disable")
        :on-click group}
       i/folder]
      [:li.degroup-layer.tooltip.tooltip-top
       {:alt "Ungroup"
        :class (when-not allow-ungrouping? "disable")
        :on-click ungroup}
       i/ungroup]
      [:li.delete-layer.tooltip.tooltip-top
       {:alt "Delete"
        :class (when-not allow-deletion? "disable")
        :on-click delete}
       i/trash]]]))

;; --- Layers Toolbox (Component)

(mx/defc layers-toolbox
  {:mixins [mx/static mx/reactive]}
  []
  (let [selected (mx/react refs/selected-shapes)
        page (mx/react refs/selected-page)

        ;; TODO: dont react to the whole shapes-by-id
        shapes-map (mx/react refs/shapes-by-id)
        close #(st/emit! (udw/toggle-flag :layers))
        dragel (volatile! nil)]
    [:div#layers.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/layers]
      [:span "Layers"]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:ul.element-list {}
       (for [shape (map #(get shapes-map %) (:shapes page))
             :let [key (str (:id shape))]]
         (if (= (:type shape) :group)
           (-> (layer-group shape selected)
               (mx/with-key key))
           (-> (layer-simple shape selected)
               (mx/with-key key))))]]
     (layers-tools selected shapes-map)]))
