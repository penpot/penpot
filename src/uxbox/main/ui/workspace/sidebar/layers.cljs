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
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.util.data :refer (read-string classnames)]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom.dnd :as dnd]
            [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

;; --- Helpers

(defn- focus-page
  [id]
  (-> (l/in [:pages-by-id id])
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
      (rs/emit! (uds/select-shape id))

      (> (count selected) 1)
      (rs/emit! (uds/deselect-all)
                (uds/select-shape id))

      (contains? selected id)
      (rs/emit! (uds/select-shape id))

      :else
      (rs/emit! (uds/deselect-all)
                (uds/select-shape id)))))

(defn- toggle-visibility
  [selected item event]
  (dom/stop-propagation event)
  (let [id (:id item)
        hidden? (:hidden item)]
    (if hidden?
      (rs/emit! (uds/show-shape id))
      (rs/emit! (uds/hide-shape id)))
    (when (contains? selected id)
      (rs/emit! (uds/select-shape id)))))

(defn- toggle-blocking
  [item event]
  (dom/stop-propagation event)
  (let [id (:id item)
        blocked? (:blocked item)]
    (if blocked?
      (rs/emit! (uds/unblock-shape id))
      (rs/emit! (uds/block-shape id)))))

(defn- element-icon
  [item]
  (case (:type item)
    :icon (icon/icon-svg item)
    :line i/line
    :circle i/circle
    :path i/curve
    :rect i/box
    :text i/text
    :group i/folder))

(defn- get-hover-position
  [event group?]
  (let [target (.-currentTarget event)
        brect (.getBoundingClientRect target)
        width (.-offsetHeight target)
        y (- (.-clientY event) (.-top brect))
        part (/ (* 30 width) 100)]
    (if group?
      (cond
        (> part y) :top
        (< (- width part) y) :bottom
        :else :middle)
      (if (>= y (/ width 2))
        :bottom
        :top))))

;; --- Shape Name (Component)

(mx/defcs shape-name
  "A generic component that displays the shape name
  if it is available and allows inline edition of it."
  {:mixins [mx/static (mx/local)]}
  [own shape]
  (let [local (:rum/local own)]
    (letfn [(on-blur [event]
              (let [target (dom/event->target event)
                    parent (.-parentNode target)
                    data {:id (:id shape)
                          :name (dom/get-value target)}]
                (set! (.-draggable parent) true)
                (rs/emit! (uds/update-shape data))
                (swap! local assoc :edition false)))
            (on-key-down [event]
              (js/console.log event)
              (when (kbd/enter? event)
                (on-blur event)))
            (on-click [event]
              (dom/stop-propagation event)
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
         {:on-click on-click}
         (:name shape "")]))))

;; --- Layer Simple (Component)

(mx/defcs layer-simple
  {:mixins [mx/static (mx/local)]}
  [own item selected]
  (let [selected? (contains? selected (:id item))
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)
        local (:rum/local own)
        classes (classnames
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
                  :top (rs/emit! (uds/drop-shape id (:id item) :before))
                  :bottom (rs/emit! (uds/drop-shape id (:id item) :after)))
                (swap! local assoc :dragging false :over nil)))
            (on-drag-over [event]
              (dom/prevent-default event)
              (dnd/set-drop-effect! event "move")
              (let [over (get-hover-position event false)]
                (swap! local assoc :over over)))
            (on-drag-enter [event]
              (swap! local assoc :over true))
            (on-drag-leave [event]
              (swap! local assoc :over false))]
      [:li {:key (str (:id item))
            :class (when selected? "selected")}
       [:div.element-list-body
        {:class classes
         :style {:opacity (if (:dragging @local)
                            "0.5"
                            "1")}
         :on-click select
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
  [own {:keys [id] :as item} selected]
  (let [local (:rum/local own)
        selected? (contains? selected (:id item))
        collapsed? (:collapsed item true)
        shapes-map (mx/react wb/shapes-by-id-ref)
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
              (if (:collapsed item)
                (rs/emit! (uds/uncollapse-shape id))
                (rs/emit! (uds/collapse-shape id))))
            (toggle-locking [event]
              (dom/stop-propagation event)
              (if (:locked item)
                (rs/emit! (uds/unlock-shape id))
                (rs/emit! (uds/lock-shape id))))
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
                  :top (rs/emit! (uds/drop-shape coming-id id :before))
                  :bottom (rs/emit! (uds/drop-shape coming-id id :after))
                  :middle (rs/emit! (uds/drop-shape coming-id id :inside)))
                (swap! local assoc :dragging false :over nil)))
            (on-drag-over [event]
              (dom/prevent-default event)
              (dnd/set-drop-effect! event "move")
              (let [over (get-hover-position event true)]
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

;; --- Layers Toolbox (Component)

(mx/defc layers-toolbox
  {:mixins [mx/reactive]}
  []
  (let [workspace (mx/react wb/workspace-ref)
        selected (:selected workspace)
        shapes-map (mx/react wb/shapes-by-id-ref)
        page (mx/react (focus-page (:page workspace)))
        close #(rs/emit! (udw/toggle-flag :layers))
        duplicate #(rs/emit! (uds/duplicate-selected))
        group #(rs/emit! (uds/group-selected))
        degroup #(rs/emit! (uds/degroup-selected))
        delete #(rs/emit! (uds/delete-selected))
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
     [:div.layers-tools
      [:ul.layers-tools-content
       [:li.clone-layer {:on-click duplicate} i/copy]
       [:li.group-layer {:on-click group} i/folder]
       [:li.degroup-layer {:on-click degroup} i/ungroup]
       [:li.delete-layer {:on-click delete} i/trash]]]]))
