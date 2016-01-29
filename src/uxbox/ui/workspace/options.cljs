(ns uxbox.ui.workspace.options
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.colorpicker :refer (colorpicker)]
            [uxbox.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(def +menus-map+
  {:builtin/icon [:menu/icon-measures :menu/fill :menu/stroke]
   :builtin/line [:menu/stroke]
   :builtin/rect [:menu/icon-measures :menu/stroke :menu/fill]
   :builtin/group []})

(def +menus-by-id+
  {:menu/icon-measures
   {:name "Size & position"
    :icon i/infocard
    :id :menu/icon-measures}

   :menu/fill
   {:name "Fill"
    :icon i/fill
    :id :menu/fill}

   :menu/stroke
   {:name "Stroke"
    :icon i/stroke
    :id :menu/stroke}})

(defn- viewportcoord->clientcoord
  [pageid viewport-x viewport-y]
  (let [[offset-x offset-y] (get @wb/bounding-rect pageid)
        new-x (+ viewport-x offset-x)
        new-y (+ viewport-y offset-y)]
    [new-x new-y]))

(defn- get-position
  [{:keys [page] :as shape}]
  (let [{:keys [x y width]} (sh/-outer-rect shape)
        vx (+ x width 50)
        vy (- y 50)]
    (viewportcoord->clientcoord page vx vy)))

(defmulti -render-menu
  (fn [menu own shape] (:id menu)))

(defmethod -render-menu :menu/stroke
  [menu own shape]
  (letfn [(change-stroke [value]
            (let [sid (:id shape)]
              (rs/emit! (dw/update-shape-stroke sid value))))
          (on-width-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)]
              (change-stroke {:width value})))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)]
              (change-stroke {:opacity value})))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (change-stroke {:color value})))
          (on-stroke-style-change [event]
            (let [value (dom/event->value event)
                  value (read-string value)]
              (change-stroke {:type value})))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       [:span "Style"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "Width"
          :type "number"
          :min "0"
          :value (:stroke-width shape "")
          :on-change on-width-change}]
        [:select#style {:placeholder "Style"
                        :on-change on-stroke-style-change}
         [:option {:value "nil"} "Solid"]
         [:option {:value ":dotted"} "Dotted"]
         [:option {:value ":dashed"} "Dashed"]]]

       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Color"]
       (colorpicker :options #(change-stroke {:color (:hex %)}))

       [:div.row-flex
        [:input#width.input-text
         {:placeholder "#"
          :type "text"
          :value (:stroke shape "")
          :on-change on-color-change}]]

       (recent-colors shape #(change-stroke {:color %}))
       [:span "Border radius"]
       [:div.row-flex
        [:input.input-text {:type "text" :placeholder "rx"}]
        [:span.lock-size i/lock]
        [:input.input-text {:type "text" :placeholder "ry"}]]

       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Opacity"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min "0"
          :max "1"
          :value (:stroke-opacity shape "1")
          :step "0.0001"
          :on-change on-opacity-change}]]]])))

(defmethod -render-menu :menu/fill
  [menu own shape]
  (letfn [(change-fill [value]
            (let [sid (:id shape)]
              (-> (dw/update-shape-fill sid value)
                  (rs/emit!))))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (change-fill {:fill value})))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)]
              (change-fill {:opacity value})))
          (on-color-picker-event [{:keys [hex]}]
            (change-fill {:fill hex}))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Color"]
       (colorpicker :options on-color-picker-event)
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "#"
          :type "text"
          :value (:fill shape "")
          :on-change on-color-change}]]

       (recent-colors shape #(change-fill {:fill %}))

       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Opacity"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min "0"
          :max "1"
          :value (:opacity shape "1")
          :step "0.0001"
          :on-change on-opacity-change}]]]])))

(defmethod -render-menu :menu/icon-measures
  [menu own shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-shape-size sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (dw/update-shape-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-shape-position sid props))))]
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
          :on-change (partial on-size-change :width)}]
        [:div.lock-size i/lock]
        [:input#width.input-text
         {:placeholder "Height"
          :type "number"
          :min "0"
          :value (:height shape)
          :on-change (partial on-size-change :height)}]]

       [:span "Position"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "x"
          :type "number"
          :value (:x shape "")
          :on-change (partial on-pos-change :x)}]
        [:input#width.input-text
         {:placeholder "y"
          :type "number"
          :value (:y shape "")
          :on-change (partial on-pos-change :y)}]]

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
    (when (seq menus)
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
          (-render-menu menu own shape))]))))

(def ^:static element-opts
  (mx/component
   {:render element-opts-render
    :name "element-opts"
    :mixins [rum/reactive (mx/local {})]}))
