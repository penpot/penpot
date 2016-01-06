(ns uxbox.ui.workspace.options
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.rstore :as rs]
            [uxbox.shapes :as shapes]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.icons :as i]
            [uxbox.ui.util :as util]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.data :refer (parse-int parse-float)]))

(def +menus-map+
  {:builtin/icon [:menu/measures :menu/fill]
   :builtin/icon-svg [:menu/measures]})

(def +menus-by-id+
  {:menu/measures
   {:name "Size & position"
    :icon i/infocard
    :id :menu/measures}

   :menu/fill
   {:name "Fill"
    :icon i/fill
    :id :menu/fill}})

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
  (fn [menu own shape] (:id menu)))

(defmethod -render-menu :menu/fill
  [menu own shape]
  (letfn [(on-color-change [event]
            (let [value (dom/event->value event)
                  sid (:id shape)]
              (-> (dw/update-shape-color sid value)
                  (rs/emit!))))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)]
              (println "opacity:" value)))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Color"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "#"
          :type "text"
          :value (:fill shape "")
          :on-change on-color-change}]]

       ;; RECENT COLORS
       [:span "Recent colors"]
       [:div.row-flex
        [:span.color-th]
        [:span.color-th {:style {:background "#c5cb7f"}}]
        [:span.color-th {:style {:background "#6cb533"}}]
        [:span.color-th {:style {:background "#67c6b5"}}]
        [:span.color-th {:style {:background "#a178e3"}}]
        [:span.color-th.palette-th i/palette]]

       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Opacity"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min "0"
          :max "1"
          :step "0.02"
          :on-change on-opacity-change}]]]])))

(defmethod -render-menu :menu/measures
  [menu own shape]
  (letfn [(on-width-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (-> (dw/update-shape-size sid {:width value})
                  (rs/emit!))))
          (on-height-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (-> (dw/update-shape-size sid {:height value})
                  (rs/emit!))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (-> (dw/update-shape-rotation sid value)
                  (rs/emit!))))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Size"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "Width"
          :type "number"
          :min "0"
          :value (:width shape)
          :on-change on-width-change}]
        [:div.lock-size i/lock]
        [:input#width.input-text
         {:placeholder "Height"
          :type "number"
          :min "0"
          :value (:height shape)
          :on-change on-height-change}]]

       [:span "Position"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "x"
          :type "number"
          :value (:x shape)
          :on-change (constantly nil)}]
        [:input#width.input-text
         {:placeholder "y"
          :type "number"
          :value (:x shape)
          :on-change (constantly nil)}]]

       [:span "Rotation"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min 0
          :max 360
          :value (:rotation shape 0)
          :on-change on-rotation-change}]]]])))

(defn element-opts-render
  [own shape]
  (let [local (:rum/local own)
        shape (rum/react shape)
        [popup-x popup-y] (get-position shape)
        scroll (or (rum/react wb/scroll-top) 0)
        zoom 1
        menus (get +menus-map+ (:type shape))
        active-menu (:menu @local (first menus))]
    (html
     [:div#element-options.element-options
      {:style {:left (* popup-x zoom) :top (- (* popup-y zoom) scroll)}}
      [:ul.element-icons
       (for [menu-id (get +menus-map+ (:type shape))
             :let [menu (get +menus-by-id+ menu-id)
                   selected? (= active-menu menu-id)]]
         [:li#e-info {:on-click #(swap! local assoc :menu menu-id)
                      :key (str "menu-" (:id menu))
                      :class (when selected? "selected")}
          (:icon menu)])]
      (let [menu (get +menus-by-id+ active-menu)]
        (-render-menu menu own shape))])))

(def ^:static element-opts
  (util/component
   {:render element-opts-render
    :name "element-opts"
    :mixins [rum/reactive (mx/local {})]}))


