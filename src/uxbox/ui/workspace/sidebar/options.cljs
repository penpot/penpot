;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.sidebar.options
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.library :as library]
            [uxbox.data.workspace :as udw]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.colorpicker :refer (colorpicker)]
            [uxbox.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.lens :as ul]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const ^:private +menus-map+
  {:builtin/icon [:menu/icon-measures :menu/fill :menu/stroke]
   :builtin/rect [:menu/rect-measures :menu/fill :menu/stroke]
   :builtin/line [:menu/line-measures :menu/stroke]
   :builtin/circle [:menu/circle-measures :menu/fill :menu/stroke]
   :builtin/text [:menu/text]
   :builtin/group []})

(def ^:const ^:private +menus-by-id+
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
    :icon i/stroke}

   :menu/text
   {:name "Text"
    :icon i/text}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti -render-menu
  (fn [menu own shape] (:id menu)))

(defmethod -render-menu :menu/stroke
  [menu own shape]
  (letfn [(change-stroke [value]
            (let [sid (:id shape)]
              (rs/emit! (uds/update-stroke-attrs sid value))))
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
        [:select#style.input-select {:placeholder "Style"
                                     :value (:stroke-type shape)
                                     :on-change on-stroke-style-change}
         [:option {:value ":none"} "None"]
         [:option {:value ":solid"} "Solid"]
         [:option {:value ":dotted"} "Dotted"]
         [:option {:value ":dashed"} "Dashed"]
         [:option {:value ":mixed"} "Mixed"]]
        [:input.input-text
         {:placeholder "Width"
          :type "number"
          :min "0"
          :value (:stroke-width shape "1")
          :on-change on-width-change}]]

       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Color"]
       (colorpicker :options #(change-stroke {:color (:hex %)}))

       [:div.row-flex
        [:input.input-text
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
              (rs/emit! (uds/update-fill-attrs sid value))))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (change-fill {:color value})))
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
        [:input.input-text
         {:placeholder "#"
          :type "text"
          :value (:fill shape "")
          :on-change on-color-change}]]

       (recent-colors shape #(change-fill {:color %}))

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
              (rs/emit! (uds/update-size sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-position sid props))))
          (on-border-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-radius-attrs sid props))))]
    (let [size (sh/size shape)]
      (html
       [:div.element-set {:key (str (:id menu))}
        [:div.element-set-title (:name menu)]
        [:div.element-set-content
         ;; SLIDEBAR FOR ROTATION AND OPACITY
         [:span "Size"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "Width"
            :type "number"
            :min "0"
            :value (:width size)
            :on-change (partial on-size-change :width)}]
          [:div.lock-size i/lock]
          [:input.input-text
           {:placeholder "Height"
            :type "number"
            :min "0"
            :value (:height size)
            :on-change (partial on-size-change :height)}]]

         [:span "Position"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "x"
            :type "number"
            :value (:x1 shape "")
            :on-change (partial on-pos-change :x)}]
          [:input.input-text
           {:placeholder "y"
            :type "number"
            :value (:y1 shape "")
            :on-change (partial on-pos-change :y)}]]

         [:span "Border radius"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "rx"
            :type "number"
            :value (:rx shape "")
            :on-change (partial on-border-change :rx)}]
          [:div.lock-size i/lock]
          [:input.input-text
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
          [:input.input-text
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
       ))))


(defmethod -render-menu :menu/icon-measures
  [menu own shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-size sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-position sid props))))]
    (let [size (sh/size shape)]
      (html
       [:div.element-set {:key (str (:id menu))}
        [:div.element-set-title (:name menu)]
        [:div.element-set-content
         ;; SLIDEBAR FOR ROTATION AND OPACITY
         [:span "Size"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "Width"
            :type "number"
            :min "0"
            :value (:width size)
            :on-change (partial on-size-change :width)}]
          [:div.lock-size i/lock]
          [:input.input-text
           {:placeholder "Height"
            :type "number"
            :min "0"
            :value (:height size)
            :on-change (partial on-size-change :height)}]]

         [:span "Position"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "x"
            :type "number"
            :value (:x1 shape "")
            :on-change (partial on-pos-change :x)}]
          [:input.input-text
           {:placeholder "y"
            :type "number"
            :value (:y1 shape "")
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
          [:input.input-text
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
       ))))

(defmethod -render-menu :menu/circle-measures
  [menu own shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-radius-attrs sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-position sid props))))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Size"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "Width"
          :type "number"
          :min "0"
          :value (:rx shape)
          :on-change (partial on-size-change :rx)}]
        [:div.lock-size i/lock]
        [:input.input-text
         {:placeholder "Height"
          :type "number"
          :min "0"
          :value (:ry shape)
          :on-change (partial on-size-change :ry)}]]

       [:span "Position"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "cx"
          :type "number"
          :value (:cx shape "")
          :on-change (partial on-pos-change :x)}]
        [:input.input-text
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
        [:input.input-text
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
              (rs/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-line-attrs sid props))))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       [:span "Position"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "x1"
          :type "number"
          :value (:x1 shape "")
          :on-change (partial on-pos-change :x1)}]
        [:input.input-text
         {:placeholder "y1"
          :type "number"
          :value (:y1 shape "")
          :on-change (partial on-pos-change :y1)}]]

       [:div.row-flex
        [:input.input-text
         {:placeholder "x2"
          :type "number"
          :value (:x2 shape "")
          :on-change (partial on-pos-change :x2)}]
        [:input.input-text
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
        [:input.input-text
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

(defmethod -render-menu :menu/text
  [menu own shape]
  (letfn [(on-font-family-change [event]
            (let [value (dom/event->value event)
                  sid (:id shape)]
              (println "on-font-family-change" value)))
          (on-font-size-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value)
                  sid (:id shape)]
              (println "on-font-size-change" value)))
          (on-font-weight-change [event]
            (let [value (dom/event->value event)
                  value (read-string value)
                  sid (:id shape)]
              (println "on-font-size-change" value)))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       [:span "Font family"]
       [:div.row-flex
        [:select.input-select {:value (:font-family shape "sans-serif")
                                     :on-change on-font-family-change}
         [:option {:value "sans-serif"} "Sans Serif"]
         [:option {:value "monospace"} "Monospace"]]]

       [:span "Size and Weight"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "Font Size"
          :type "number"
          :min "0"
          :max "200"
          :value (:font-size shape "16")
          :on-change on-font-size-change}]
        [:select.input-select {:value (:font-weight shape ":normal")
                                     :on-change on-font-weight-change}
         [:option {:value ":normal"} "Normal"]
         [:option {:value ":bold"} "Solid"]]]
       [:span "Text align"]
       [:div.row-flex.align-icons
        [:span.current i/align-left]
        [:span i/align-right]
        [:span i/align-center]
        [:span i/align-justify]
         ]]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn options-menus-render
  [own shape]
  (let [local (:rum/local own)
        menus (get +menus-map+ (:type shape))
        active-menu (:menu @local (first menus))]
    (html
     [:div
      [:ul.element-icons
       (for [menu-id (get +menus-map+ (:type shape))
             :let [menu (get +menus-by-id+ menu-id)
                   menu (assoc menu :id menu-id)
                   selected? (= active-menu menu-id)]]
         [:li#e-info {:on-click #(swap! local assoc :menu menu-id)
                      :key (str "menu-" (:id menu))
                      :class (when selected? "selected")}
          (:icon menu)])]
      (when-let [menu (get +menus-by-id+ active-menu)]
        (let [menu (assoc menu :id active-menu)]
          (-render-menu menu own shape local)))])))

(def ^:static ^:private options-menus
  (mx/component
   {:render options-menus-render
    :name "options-menus"
    :mixins [mx/static (mx/local)]}))

(def ^:const selected-shape-l
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (when (= 1 (count selected))
                (get-in state [:shapes-by-id (first selected)]))))]
    (as-> (ul/getter getter) $
      (l/focus-atom $ st/state))))

(defn options-toolbox-render
  [own]
  (let [shape (rum/react selected-shape-l)
        close #(rs/emit! (udw/toggle-flag :element-options))]
    (html
     [:div.elementa-options.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/options]
       [:span (tr "ds.element-options")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:div.element-options
        (if shape
          (options-menus shape))]]])))

(def ^:static options-toolbox
  (mx/component
   {:render options-toolbox-render
    :name "options-toolbox"
    :mixins [mx/static rum/reactive (mx/local)]}))
