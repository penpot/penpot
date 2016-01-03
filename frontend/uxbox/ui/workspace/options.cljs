(ns uxbox.ui.workspace.options
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.shapes :as shapes]
            [uxbox.ui.icons :as i]
            [uxbox.ui.util :as util]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]))

(def +menus-map+
  {:builtin/icon [:menu/size-and-pos]
   :builtin/icon-svg [:menu/size-and-pos]})

(def +menus-by-id+
  {:menu/size-and-pos
   {:name "Size & position"
    :icon i/infocard
    :id (gensym "menu")}})

(defn viewportcoord->clientcoord
  [pageid viewport-x viewport-y]
  (let [[offset-x offset-y] (get @wb/bounding-rect pageid)
        new-x (+ viewport-x offset-x)
        new-y (+ viewport-y offset-y)]
    [new-x new-y]))

(defn get-position
  [{:keys [x y width page]}]
  (let [vx (+ x width 50)
        vy (- y 50)]
    (viewportcoord->clientcoord page vx vy)))

(defmulti -render-menu
  (fn [type shape] type))

(defmethod -render-menu :size-and-position
  [_ shape]
  (html [:p "hello world"]))

(defn element-opts-render
  [own shape]
  (let [local (:rum/local own)
        shape (rum/react shape)
        [popup-x popup-y] (get-position shape)
        zoom 1]
    (html
     [:div#element-options.element-options
      {:style {:left (* popup-x zoom) :top (* popup-y zoom)}}
      [:ul.element-icons
       (for [menu-id (get +menus-map+ (:type shape))
             :let [menu (get +menus-by-id+ menu-id)]]
         [:li#e-info
          {:on-click (constantly nil)
           :key (str "menu-" (:id menu))
           :class nil #_"selected"}
          (:icon menu)])]
      (for [menu-id (get +menus-map+ (:type shape))
            :let [menu (get +menus-by-id+ menu-id)]]
        [:div#element-basics.element-set
         {:key (str (:id menu))
          :class nil }
         [:div.element-set-title (:name menu)]
         [:div.element-set-content

          ;; SLIDEBAR FOR ROTATION AND OPACITY
          [:span "Rotation"]
          [:div.row-flex
           [:input.slidebar {:type "range"}]]

          ;; RECENT COLORS
          [:span "Recent colors"]
          [:div.row-flex
           [:span.color-th]
           [:span.color-th {:style {:background "#c5cb7f"}}]
           [:span.color-th {:style {:background "#6cb533"}}]
           [:span.color-th {:style {:background "#67c6b5"}}]
           [:span.color-th {:style {:background "#a178e3"}}]
           [:span.color-th.palette-th i/palette]]]])])))

(def ^:static element-opts
  (util/component
   {:render element-opts-render
    :name "element-opts"
    :mixins [rum/reactive (mx/local {})]}))


