;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2019 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.viewport
  (:require
   [goog.events :as events]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.data.workspace-drawing :as udwd]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.workspace.canvas :refer [canvas]]
   [uxbox.main.ui.workspace.grid :refer [grid]]
   [uxbox.main.ui.workspace.ruler :refer [ruler]]
   [uxbox.main.user-events :as uev]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt])
  (:import goog.events.EventType))

;; --- Coordinates Widget

(mf/def coordinates
  :mixins [mf/reactive mf/memo]
  :render
  (fn [own {:keys [zoom] :as props}]
    (let [coords (some-> (mf/react refs/canvas-mouse-position)
                         (gpt/divide zoom)
                         (gpt/round 0))]
      [:ul.coordinates
       [:span {:alt "x"}
        (str "X: " (:x coords "-"))]
       [:span {:alt "y"}
        (str "Y: " (:y coords "-"))]])))

;; --- Cursor tooltip

(defn- get-shape-tooltip
  "Return the shape tooltip text"
  [shape]
  (case (:type shape)
    :icon "Click to place the Icon"
    :image "Click to place the Image"
    :rect "Drag to draw a Box"
    :text "Drag to draw a Text Box"
    :path "Click to draw a Path"
    :circle "Drag to draw a Circle"
    nil))

(mf/defc cursor-tooltip
  {:wrap [mf/wrap-memo]}
  [{:keys [tooltip]}]
  (let [coords (mf/deref refs/window-mouse-position)]
    [:span.cursor-tooltip
     {:style
      {:position "fixed"
       :left (str (+ (:x coords) 5) "px")
       :top (str (- (:y coords) 25) "px")}}
     tooltip]))

;; --- Selection Rect

(mf/defc selrect
  {:wrap [mf/wrap-memo]}
  [{rect :value}]
  (when rect
    (let [{:keys [x1 y1 width height]} (geom/size rect)]
      [:rect.selection-rect
       {:x x1
        :y y1
        :width width
        :height height}])))

;; --- Viewport

(mf/def viewport
  :init
  (fn [own props]
    (assoc own ::viewport (mf/create-ref)))

  :did-mount
  (fn [own]
    (letfn [(translate-point-to-viewport [pt]
              (let [viewport (mf/ref-node (::viewport own))
                    brect (.getBoundingClientRect viewport)
                    brect (gpt/point (parse-int (.-left brect))
                                     (parse-int (.-top brect)))]
                (gpt/subtract pt brect)))

            (translate-point-to-canvas [pt]
              (let [viewport (mf/ref-node (::viewport own))]
                (when-let [canvas (dom/get-element-by-class "page-canvas" viewport)]
                  (let [brect (.getBoundingClientRect canvas)
                        bbox (.getBBox canvas)
                        brect (gpt/point (parse-int (.-left brect))
                                         (parse-int (.-top brect)))
                        bbox (gpt/point (.-x bbox) (.-y bbox))]
                    (-> (gpt/add pt bbox)
                        (gpt/subtract brect))))))

            (on-key-down [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uev/keyboard-event :down key ctrl? shift?))
                (when (kbd/space? event)
                  (st/emit! (udw/start-viewport-positioning)))))

            (on-key-up [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when (kbd/space? event)
                  (st/emit! (udw/stop-viewport-positioning)))
                (st/emit! (uev/keyboard-event :up key ctrl? shift?))))

            (on-mousemove [event]
              (let [wpt (gpt/point (.-clientX event)
                                   (.-clientY event))
                    vpt (translate-point-to-viewport wpt)
                    cpt (translate-point-to-canvas wpt)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    event {:ctrl ctrl?
                           :shift shift?
                           :window-coords wpt
                           :viewport-coords vpt
                           :canvas-coords cpt}]
                (st/emit! (uev/pointer-event wpt vpt cpt ctrl? shift?))))]

      (let [key1 (events/listen js/document EventType.MOUSEMOVE on-mousemove)
            key2 (events/listen js/document EventType.KEYDOWN on-key-down)
            key3 (events/listen js/document EventType.KEYUP on-key-up)]
        (assoc own
               ::key1 key1
               ::key2 key2
               ::key3 key3))))

  :will-unmount
  (fn [own]
    (events/unlistenByKey (::key1 own))
    (events/unlistenByKey (::key2 own))
    (events/unlistenByKey (::key3 own))
    (dissoc own ::key1 ::key2 ::key3))

  :render
  (fn [own {:keys [page wst] :as props}]
    (let [{:keys [drawing-tool tooltip zoom flags edition]} wst
          tooltip (or tooltip (get-shape-tooltip drawing-tool))
          zoom (or zoom 1)]
      (letfn [(on-mouse-down [event]
                (prn "viewport.on-mouse-down")
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :down ctrl? shift?)))
                (when (not edition)
                  (if drawing-tool
                    (st/emit! (udwd/start-drawing drawing-tool))
                    (st/emit! ::uev/interrupt (udw/start-selrect)))))
              (on-context-menu [event]
                (dom/prevent-default event)
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :context-menu ctrl? shift?))))
              (on-mouse-up [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :up ctrl? shift?))))
              (on-click [event]
                (js/console.log "viewport.on-click" event)
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :click ctrl? shift?))))
              (on-double-click [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :double-click ctrl? shift?))))]
        [:*
         [:& coordinates {:zoom zoom}]
         [:div.tooltip-container
          (when tooltip
            [:& cursor-tooltip {:tooltip tooltip}])]
         [:svg.viewport {:width (* c/viewport-width zoom)
                         :height (* c/viewport-height zoom)
                         :ref (::viewport own)
                         :class (when drawing-tool "drawing")
                         :on-context-menu on-context-menu
                         :on-click on-click
                         :on-double-click on-double-click
                         :on-mouse-down on-mouse-down
                         :on-mouse-up on-mouse-up
                         }
          [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
           (when page
             [:& canvas {:page page :wst wst}])
           (if (contains? flags :grid)
             [:& grid {:page page}])]
          (when (contains? flags :ruler)
            [:& ruler {:zoom zoom :ruler (:ruler wst)}])
          [:& selrect {:value (:selrect wst)}]]]))))
