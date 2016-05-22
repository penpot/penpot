;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.sidebar.layers
  (:require-macros [uxbox.util.syntax :refer (defer)])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string classnames)]
            [uxbox.data.workspace :as udw]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.shapes.icon :as icon]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom.dnd :as dnd]
            [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- focus-page
  [pageid]
  (as-> (l/in [:pages-by-id pageid]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn- toggle-locking
  [item event]
  (dom/stop-propagation event)
  (let [id (:id item)
        locked? (:locked item)]
    (if locked?
      (rs/emit! (uds/unlock-shape id))
      (rs/emit! (uds/lock-shape id)))))

(defn- element-icon
  [item]
  (case (:type item)
    :icon (icon/icon-svg item)
    :line i/line
    :circle i/circle
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

(defn- layer-element-render
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
      (html
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
         [:span (:name item "Unnamed")]]]))))

(def ^:static ^:private layer-element
  (mx/component
   {:render layer-element-render
    :name "layer-element"
    :mixins [mx/static (mx/local)]}))

(declare layer-group)

(defn- layer-group-render
  [own item selected]
  (let [local (:rum/local own)
        selected? (contains? selected (:id item))
        open? (:open @local true)
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)
        toggle-locking #(toggle-locking item %)
        toggle-open (fn [event]
                      (dom/stop-propagation event)
                      (swap! local assoc :open (not open?)))
        shapes-by-id (rum/react wb/shapes-by-id-l)
        classes (classnames
                 :selected selected?
                 :drag-top (= :top (:over @local))
                 :drag-bottom (= :bottom (:over @local))
                 :drag-inside (= :middle (:over @local)))]
    (letfn [(on-drag-start [event]
              (let [target (dom/event->target event)]
                (dnd/set-allowed-effect! event "move")
                (dnd/set-data! event (:id item))
                (swap! local assoc :dragging true)))
            (on-drag-end [event]
              (swap! local assoc :dragging false :over nil))
            (on-drop [event]
              (dom/stop-propagation event)
              (let [id (dnd/get-data event)
                    over (:over @local)]
                (case (:over @local)
                  :top (rs/emit! (uds/drop-shape id (:id item) :before))
                  :bottom (rs/emit! (uds/drop-shape id (:id item) :after))
                  :middle (rs/emit! (uds/drop-shape id (:id item) :inside)))
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
      (html
       [:li.group {:class (when open? "open")}
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
         [:span (:name item "Unnamed group")]
         [:span.toggle-content
          {:on-click toggle-open
           :class (when open? "inverse")}
          i/arrow-slide]]
        (if open?
          [:ul
           (for [shape (map #(get shapes-by-id %) (:items item))
                 :let [key (str (:id shape))]]
             (if (= (:type shape) :group)
               (-> (layer-group shape selected)
                   (rum/with-key key))
               (-> (layer-element shape selected)
                   (rum/with-key key))))])]))))

(def ^:static ^:private layer-group
  (mx/component
   {:render layer-group-render
    :name "layer-group"
    :mixins [mx/static rum/reactive (mx/local)]}))

(defn layers-render
  [own]
  (let [workspace (rum/react wb/workspace-l)
        selected (:selected workspace)
        shapes-by-id (rum/react wb/shapes-by-id-l)
        page (rum/react (focus-page (:page workspace)))
        close #(rs/emit! (udw/toggle-flag :layers))
        duplicate #(rs/emit! (uds/duplicate-selected))
        group #(rs/emit! (uds/group-selected))
        degroup #(rs/emit! (uds/degroup-selected))
        delete #(rs/emit! (uds/delete-selected))
        dragel (volatile! nil)]
    (html
     [:div#layers.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/layers]
       [:span "Layers"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:ul.element-list {}
        (for [shape (map #(get shapes-by-id %) (:shapes page))
              :let [key (str (:id shape))]]
          (if (= (:type shape) :group)
            (-> (layer-group shape selected)
                (rum/with-key key))
            (-> (layer-element shape selected)
                (rum/with-key key))))]]
      [:div.layers-tools
       [:ul.layers-tools-content
        [:li.clone-layer {:on-click duplicate} i/copy]
        [:li.group-layer {:on-click group} i/folder]
        [:li.degroup-layer {:on-click degroup} i/ungroup]
        [:li.delete-layer {:on-click delete} i/trash]]]])))

(def ^:static layers-toolbox
  (mx/component
   {:render layers-render
    :name "layers"
    :mixins [rum/reactive]}))
