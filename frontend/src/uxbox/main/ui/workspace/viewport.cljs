;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2019 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.viewport
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.workspace.grid :refer [grid]]
   [uxbox.main.ui.workspace.ruler :refer [ruler]]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.main.ui.workspace.drawarea :refer [start-drawing]]

   [uxbox.main.ui.shapes :refer [shape-wrapper]]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.selection :refer [selection-handlers]]

   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.components :refer [use-rxsub]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt])
  (:import goog.events.EventType))

;; --- Coordinates Widget

(mf/defc coordinates
  [{:keys [zoom] :as props}]
  (let [coords (some-> (use-rxsub uws/mouse-position)
                       (gpt/divide zoom)
                       (gpt/round 0))]
    [:ul.coordinates
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

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

;; (mf/defc cursor-tooltip
;;   {:wrap [mf/wrap-memo]}
;;   [{:keys [tooltip]}]
;;   (let [coords (mf/deref refs/window-mouse-position)]
;;     [:span.cursor-tooltip
;;      {:style
;;       {:position "fixed"
;;        :left (str (+ (:x coords) 5) "px")
;;        :top (str (- (:y coords) 25) "px")}}
;;      tooltip]))

;; --- Selection Rect

(defn- selection->rect
  [data]
  (let [start (:start data)
        stop (:stop data)
        start-x (min (:x start) (:x stop))
        start-y (min (:y start) (:y stop))
        end-x (max (:x start) (:x stop))
        end-y (max (:y start) (:y stop))]
    (assoc data
           :x1 start-x
           :y1 start-y
           :x2 end-x
           :y2 end-y
           :type :rect)))

(def ^:private handle-selrect
  (letfn [(update-state [state position]
            (let [selrect (get-in state [:workspace-local :selrect])]
              (if selrect
                (assoc-in state [:workspace-local :selrect]
                          (selection->rect (assoc selrect :stop position)))
                (assoc-in state [:workspace-local :selrect]
                          (selection->rect {:start position :stop position})))))

          (clear-state [state]
            (update state :workspace-local dissoc :selrect))]
    (ptk/reify ::handle-selrect
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper (rx/filter #(or (dw/interrupt? %) (uws/mouse-up? %)) stream)]
          (rx/concat
           (rx/of dw/deselect-all)
           (->> uws/mouse-position
                (rx/map (fn [pos] #(update-state % pos)))
                (rx/take-until stoper))
           (rx/of dw/select-shapes-by-current-selrect
                  clear-state)))))))

(mf/defc selrect
  {:wrap [mf/wrap-memo]}
  [{:keys [data] :as props}]
  (when data
    (let [{:keys [x1 y1 width height]} (geom/size data)]
      [:rect.selection-rect
       {:x x1
        :y y1
        :width width
        :height height}])))


;; --- Viewport Positioning

(def handle-viewport-positioning
  (letfn [(on-point [dom reference point]
            (let [{:keys [x y]} (gpt/subtract point reference)
                  cx (.-scrollLeft dom)
                  cy (.-scrollTop dom)]
              (set! (.-scrollLeft dom) (- cx x))
              (set! (.-scrollTop dom) (- cy y))))]
    (ptk/reify ::handle-viewport-positioning
      ptk/EffectEvent
      (effect [_ state stream]
        (let [stoper (rx/filter #(= ::finish-positioning %) stream)
              reference @uws/mouse-position
              dom (dom/get-element "workspace-viewport")]
          (-> (rx/take-until stoper uws/mouse-position)
              (rx/subscribe #(on-point dom reference %))))))))

;; --- Viewport

(declare remote-user-cursors)

(mf/defc canvas-and-shapes
  {:wrap [mf/wrap-memo]}
  [props]
  (let [data (mf/deref refs/workspace-data)
        shapes-by-id (:shapes-by-id data)
        shapes (map #(get shapes-by-id %) (:shapes data []))
        canvas (map #(get shapes-by-id %) (:canvas data []))]
    [:*
     (for [item canvas]
       [:& shape-wrapper {:shape item :key (:id item)}])
     (for [item (reverse shapes)]
       [:& shape-wrapper {:shape item :key (:id item)}])]))

(mf/defc viewport
  [{:keys [page] :as props}]
  (let [{:keys [drawing-tool
                zoom
                flags
                edition
                selected]
         :as local} (mf/deref refs/workspace-local)
        viewport-ref (mf/use-ref nil)
        zoom (or zoom 1)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :down ctrl? shift?)))
              (when (not edition)
                (if drawing-tool
                  (st/emit! (start-drawing drawing-tool))
                  (st/emit! handle-selrect))))

            (on-context-menu [event]
              (dom/prevent-default event)
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :context-menu ctrl? shift?))))

            (on-mouse-up [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :up ctrl? shift?))))

            (on-click [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :click ctrl? shift?))))

            (on-double-click [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :double-click ctrl? shift?))))

            (translate-point-to-viewport [pt]
              (let [viewport (mf/ref-node viewport-ref)
                    brect (.getBoundingClientRect viewport)
                    brect (gpt/point (parse-int (.-left brect))
                                     (parse-int (.-top brect)))]
                (gpt/subtract pt brect)))

            (on-key-down [event]
              (let [bevent (.getBrowserEvent event)
                    key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when-not (.-repeat bevent)
                  (st/emit! (uws/keyboard-event :down key ctrl? shift?))
                  (when (kbd/space? event)
                    (st/emit! handle-viewport-positioning)
                    #_(st/emit! (dw/start-viewport-positioning))))))

            (on-key-up [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when (kbd/space? event)
                  (st/emit! ::finish-positioning #_(dw/stop-viewport-positioning)))
                (st/emit! (uws/keyboard-event :up key ctrl? shift?))))

            (on-mouse-move [event]
              (let [pt (gpt/point (.-clientX event)
                                  (.-clientY event))
                    pt (translate-point-to-viewport pt)]
                (st/emit! (uws/->PointerEvent :viewport pt
                                              (kbd/ctrl? event)
                                              (kbd/shift? event)))))

            (on-mount []
              (let [key1 (events/listen js/document EventType.KEYDOWN on-key-down)
                    key2 (events/listen js/document EventType.KEYUP on-key-up)]
                (fn []
                  (events/unlistenByKey key1)
                  (events/unlistenByKey key2))))]
      (mf/use-effect on-mount)
      ;; (prn "viewport$render")
      [:*
       [:& coordinates {:zoom zoom}]
       [:svg.viewport {:width (* c/viewport-width zoom)
                       :height (* c/viewport-height zoom)
                       :ref viewport-ref
                       :class (when drawing-tool "drawing")
                       :on-context-menu on-context-menu
                       :on-click on-click
                       :on-double-click on-double-click
                       :on-mouse-move on-mouse-move
                       :on-mouse-down on-mouse-down
                       :on-mouse-up on-mouse-up}
        [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
         [:*
          [:& canvas-and-shapes]

          (when (seq selected)
            [:& selection-handlers {:selected selected
                                    :zoom zoom
                                    :edition edition}])

          (when-let [drawing-shape (:drawing local)]
            [:& draw-area {:shape drawing-shape
                           :zoom zoom
                           :modifiers (:modifiers local)}])]

         (if (contains? flags :grid)
           [:& grid {:page page}])]

        (when (contains? flags :ruler)
          [:& ruler {:zoom zoom :ruler (:ruler local)}])


        ;; -- METER CURSOR MULTIUSUARIO
        [:& remote-user-cursors {:page page}]

        [:& selrect {:data (:selrect local)}]]])))


(mf/defc remote-user-cursor
  [{:keys [pointer user] :as props}]
  [:g.multiuser-cursor {:key (:user-id pointer)
                        :transform (str "translate(" (:x pointer) "," (:y pointer) ") scale(4)")}
   [:path {:fill (:color user)
           :d "M5.292 4.027L1.524.26l-.05-.01L0 0l.258 1.524 3.769 3.768zm-.45 0l-.313.314L1.139.95l.314-.314zm-.5.5l-.315.316-3.39-3.39.315-.315 3.39 3.39zM1.192.526l-.668.667L.431.646.64.43l.552.094z"
           :font-family "sans-serif"}]
   [:g {:transform "translate(0 -291.708)"}
    [:rect {:width "21.415"
            :height "5.292"
            :x "6.849"
            :y "291.755"
            :fill (:color user)
            :fill-opacity ".893"
            :paint-order "stroke fill markers"
            :rx ".794"
            :ry ".794"}]
    [:text {:x "9.811"
            :y "295.216"
            :fill "#fff"
            :stroke-width ".265"
            :font-family "Open Sans"
            :font-size"2.91"
            :font-weight "400"
            :letter-spacing"0"
            :style {:line-height "1.25"}
            :word-spacing "0"
            ;; :style="line-height:1
            }
     (:fullname user)]]])

(mf/defc remote-user-cursors
  [{:keys [page] :as props}]
  (let [users (mf/deref refs/workspace-users)
        pointers (->> (vals (:pointer users))
                      (remove #(not= (:id page) (:page-id %)))
                      (filter #((:active users) (:user-id %))))]
    (for [pointer pointers]
      (let [user (get-in users [:by-id (:user-id pointer)])]
        [:& remote-user-cursor {:pointer pointer
                                :user user
                                :key (:user-id pointer)}]))))

