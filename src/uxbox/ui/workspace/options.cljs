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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private +menus-map+
  {:builtin/icon [:menu/icon-measures :menu/fill :menu/stroke]
   :builtin/rect [:menu/rect-measures :menu/fill :menu/stroke]
   :builtin/line [:menu/line-measures :menu/stroke]
   :builtin/circle [:menu/circle-measures :menu/fill :menu/stroke]
   :builtin/group []})

(def ^:static ^:private +menus-by-id+
  {:menu/icon-measures
   {:name "Size, position & rotation"
    :icon i/infocard}

   :menu/rect-measures
   {:name "Size, position & rotation"
    :icon i/infocard}

   :menu/line-measures
   {:name "Size, position & rotation"
    :icon i/infocard}

   :menu/circle-measures
   {:name "Size, position & rotation"
    :icon i/infocard}

   :menu/fill
   {:name "Fill"
    :icon i/fill}

   :menu/stroke
   {:name "Stroke"
    :icon i/stroke}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti -render-menu
  (fn [menu own shape] (:id menu)))

(defmethod -render-menu :menu/stroke
  [menu own shape]
  (letfn [(change-stroke [value]
            (let [sid (:id shape)]
              (rs/emit! (dw/update-stroke-attrs sid value))))
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
              (rs/emit! (dw/update-fill-attrs sid value))))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (change-fill {:fill value})))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)]
              (change-fill {:opacity value})))
          (on-color-picker-event [{:keys [hex]}]
            (change-fill {:color hex}))]
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

(defmethod -render-menu :menu/rect-measures
  [menu own shape local]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-size sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (dw/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-position sid props))))
          (on-border-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-radius-attrs sid props))))]
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

       [:span "Border radius"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "rx"
          :type "number"
          :value (:rx shape "")
          :on-change (partial on-border-change :rx)}]
        [:div.lock-size i/lock]
        [:input#width.input-text
         {:placeholder "ry"
          :type "number"
          :value (:ry shape "")
          :on-change (partial on-border-change :ry)}]]

       [:span "Rotation"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min 0
          :max 360
          :value (:rotation shape 0)
          :on-change on-rotation-change}]]

       [:div.row-flex
        [:input#width.input-text
         {:placeholder ""
          :type "number"
          :min 0
          :max 360
          :value (:rotation shape "0")
          :on-change on-rotation-change
          }]
        [:input.input-text
         {:style {:visibility "hidden"}}]
        ]]]
     )))


(defmethod -render-menu :menu/icon-measures
  [menu own shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-size sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (dw/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-position sid props))))]
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
          :on-change on-rotation-change}]]

       [:div.row-flex
        [:input#width.input-text
         {:placeholder ""
          :type "number"
          :min 0
          :max 360
          :value (:rotation shape "0")
          :on-change on-rotation-change
          }]
        [:input.input-text
         {:style {:visibility "hidden"}}]
        ]]]
     )))

(defmethod -render-menu :menu/circle-measures
  [menu own shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-radius-attrs sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (dw/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-position sid props))))]
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
          :value (:rx shape)
          :on-change (partial on-size-change :rx)}]
        [:div.lock-size i/lock]
        [:input#width.input-text
         {:placeholder "Height"
          :type "number"
          :min "0"
          :value (:ry shape)
          :on-change (partial on-size-change :ry)}]]

       [:span "Position"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "cx"
          :type "number"
          :value (:cx shape "")
          :on-change (partial on-pos-change :x)}]
        [:input#width.input-text
         {:placeholder "cy"
          :type "number"
          :value (:cy shape "")
          :on-change (partial on-pos-change :y)}]]

       [:span "Rotation"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min 0
          :max 360
          :value (:rotation shape 0)
          :on-change on-rotation-change}]]

       [:div.row-flex
        [:input#width.input-text
         {:placeholder ""
          :type "number"
          :min 0
          :max 360
          :value (:rotation shape "0")
          :on-change on-rotation-change
          }]
        [:input.input-text
         {:style {:visibility "hidden"}}]
        ]]]
     )))

(defmethod -render-menu :menu/line-measures
  [menu own shape]
  (letfn [(on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (dw/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (dw/update-line-attrs sid props))))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       [:span "Position"]
       [:div.row-flex
        [:input#width.input-text
         {:placeholder "x1"
          :type "number"
          :value (:x1 shape "")
          :on-change (partial on-pos-change :x1)}]
        [:input#width.input-text
         {:placeholder "y1"
          :type "number"
          :value (:y1 shape "")
          :on-change (partial on-pos-change :y1)}]]

       [:div.row-flex
        [:input#width.input-text
         {:placeholder "x2"
          :type "number"
          :value (:x2 shape "")
          :on-change (partial on-pos-change :x2)}]
        [:input#width.input-text
         {:placeholder "y2"
          :type "number"
          :value (:y2 shape "")
          :on-change (partial on-pos-change :y2)}]]

       [:span "Rotation"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min 0
          :max 360
          :value (:rotation shape 0)
          :on-change on-rotation-change}]]

       [:div.row-flex
        [:input#width.input-text
         {:placeholder ""
          :type "number"
          :min 0
          :max 360
          :value (:rotation shape "0")
          :on-change on-rotation-change
          }]
        [:input.input-text
         {:style {:visibility "hidden"}}]
        ]]]
     )))



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
                     menu (assoc menu :id menu-id)
                     selected? (= active-menu menu-id)]]
           [:li#e-info {:on-click #(swap! local assoc :menu menu-id)
                        :key (str "menu-" (:id menu))
                        :class (when selected? "selected")}
            (:icon menu)])]
        (let [menu (get +menus-by-id+ active-menu)
              menu (assoc menu :id active-menu)]
          (-render-menu menu own shape local))]))))

(def ^:static element-opts
  (mx/component
   {:render element-opts-render
    :name "element-opts"
    :mixins [rum/reactive (mx/local {})]}))
