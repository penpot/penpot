(ns uxbox.ui.workspace.toolboxes.layers
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]))

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
      (rs/emit! (dw/select-shape id))

      (> (count selected) 1)
      (rs/emit! (dw/deselect-all)
                (dw/select-shape id))

      (contains? selected id)
      (rs/emit! (dw/select-shape id))

      :else
      (rs/emit! (dw/deselect-all)
                (dw/select-shape id)))))

(defn- toggle-visibility
  [selected item event]
  (dom/stop-propagation event)
  (let [id (:id item)]
    (rs/emit! (dw/toggle-shape-visibility id))
    (when (contains? selected id)
      (rs/emit! (dw/select-shape id)))))

(defn- toggle-blocking
  [item event]
  (dom/stop-propagation event)
  (let [id (:id item)]
    (rs/emit! (dw/toggle-shape-blocking id))))

(defn- toggle-locking
  [item event]
  (dom/stop-propagation event)
  (let [id (:id item)]
    (rs/emit! (dw/toggle-shape-locking id))))

(defn- element-icon
  [item]
  (case (:type item)
    :builtin/icon (shapes/-render-svg item)
    :builtin/line i/line
    :builtin/rect i/box
    :builtin/group i/folder))

(defn- layer-element-render
  [own item selected]
  (let [selected? (contains? selected (:id item))
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)]
    (html
     [:li {:key (str (:id item))
           :on-click select
           :class (when selected? "selected")}
      [:div.element-list-body
       {:class (when selected? "selected")}
       [:div.element-actions
        [:div.toggle-element
         {:class (when-not (:hidden item) "selected")
          :on-click toggle-visibility}
         i/eye]
        [:div.block-element
         {:class (when (:blocked item) "selected")
          :on-click toggle-blocking}
         i/lock]]
       (if (:group item)
         [:div.sublevel-element i/sublevel])
       [:div.element-icon (element-icon item)]
       [:span (:name item "Unnamed")]]])))

(def ^:static ^:private layer-element
  (mx/component
   {:render layer-element-render
    :name "layer-element"
    :mixins [mx/static]}))

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
        shapes-by-id (rum/react wb/shapes-by-id)]
    (html
     [:li.group {:class (when open? "open")}
      [:div.element-list-body
       {:class (when selected? "selected")
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
       [:span.toggle-content {:on-click toggle-open}
        i/arrow-slide]]
      (if open?
        [:ul
         (for [shape (map #(get shapes-by-id %) (:items item))
               :let [key (str (:id shape))]]
           (if (= (:type shape) :builtin/group)
             (-> (layer-group shape selected)
                 (rum/with-key key))
             (-> (layer-element shape selected)
                 (rum/with-key key))))])])))

(def ^:static ^:private layer-group
  (mx/component
   {:render layer-group-render
    :name "layer-group"
    :mixins [mx/static rum/reactive (mx/local)]}))

(defn layers-render
  [own]
  (let [workspace (rum/react wb/workspace-l)
        selected (:selected workspace)
        shapes-by-id (rum/react wb/shapes-by-id)
        page (rum/react (focus-page (:page workspace)))
        close #(rs/emit! (dw/toggle-toolbox :layers))
        ;; copy #(rs/emit! (dw/copy-selected))
        group #(rs/emit! (dw/group-selected))
        delete #(rs/emit! (dw/delete-selected))]
    (html
     [:div#layers.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/layers]
       [:span "Layers"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:ul.element-list
        (for [shape (map #(get shapes-by-id %) (:shapes page))
              :let [key (str (:id shape))]]
          (if (= (:type shape) :builtin/group)
            (-> (layer-group shape selected)
                (rum/with-key key))
            (-> (layer-element shape selected)
                (rum/with-key key))))]]
      [:div.layers-tools
       [:ul.layers-tools-content
        [:li.clone-layer #_{:on-click copy} i/copy]
        [:li.group-layer {:on-click group} i/folder]
        [:li.delete-layer {:on-click delete} i/trash]]]])))

(def ^:static layers-toolbox
  (mx/component
   {:render layers-render
    :name "layers"
    :mixins [rum/reactive]}))

