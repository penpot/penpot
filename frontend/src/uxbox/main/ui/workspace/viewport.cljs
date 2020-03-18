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
   [goog.object :as gobj]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.react-hooks :refer [use-rxsub]]
   [uxbox.main.ui.shapes :refer [shape-wrapper frame-wrapper]]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.drawarea :refer [start-drawing]]
   [uxbox.main.ui.workspace.grid :refer [grid]]
   [uxbox.main.ui.workspace.ruler :refer [ruler]]
   [uxbox.main.ui.workspace.selection :refer [selection-handlers]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.perf :as perf]
   [uxbox.util.uuid :as uuid])
  (:import goog.events.EventType))

;; --- Coordinates Widget

(mf/defc coordinates
  [{:keys [zoom] :as props}]
  (let [coords (some-> (use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom))
                       (gpt/round 0))]
    [:ul.coordinates
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

(mf/defc cursor-tooltip
  [{:keys [zoom tooltip] :as props}]
  (let [coords (some-> (use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom)))
        pos-x (- (:x coords) 100)
        pos-y (+ (:y coords) 30)]
    [:g {:transform (str "translate(" pos-x "," pos-y ")")}
     [:foreignObject {:width 200 :height 100 :style {:text-align "center"}}
      [:span tooltip]]]))

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

;; --- Selection Rect

(mf/defc selection-rect
  {:wrap [mf/wrap-memo]}
  [{:keys [data] :as props}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :width (:width data)
      :height (:height data)}]))

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
              reference @ms/mouse-position
              dom (dom/get-element "workspace-viewport")]
          (-> (rx/take-until stoper ms/mouse-position)
              (rx/subscribe #(on-point dom reference %))))))))

;; --- Viewport

(declare remote-user-cursors)
(declare frames)

(defn- build-workspace-data-iref
  [page-id]
  (-> (l/in [:workspace-data page-id])
      (l/derive st/state)))

(mf/defrc frames-wrapper
  ;; {:wrap [mf/wrap-memo]}
  [props]
  (let [page     (gobj/get props "page")
        page-id  (:id page)
        data-ref (mf/use-memo {:fn #(-> (l/in [:workspace-data page-id])
                                        (l/derive st/state))
                               :deps (mf/deps page-id)})
        data (mf/deref data-ref)]
    [:& frames {:data data}]))

(mf/defc frames
  {:wrap [mf/wrap-memo]}
  [{:keys [data] :as props}]
  (let [objects (:objects data)
        root    (get objects uuid/zero)
        shapes  (->> (:shapes root)
                     (map #(get objects %)))]
    [:g.shapes
     (for [item (reverse shapes)]
       (if (= (:type item) :frame)
         [:& frame-wrapper {:shape item
                            :key (:id item)
                            :objects objects}]
         [:& shape-wrapper {:shape item
                            :key (:id item)}]))]))


(mf/defc viewport
  [{:keys [page] :as props}]
  (let [{:keys [drawing-tool
                zoom
                flags
                edition
                tooltip
                selected]
         :as local} (mf/deref refs/workspace-local)
        viewport-ref (mf/use-ref nil)
        zoom (or zoom 1)

        on-mouse-down
        (fn [event]
          (dom/stop-propagation event)
          (let [ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:shift? shift?
                      :ctrl? ctrl?}
                button (.-which (.-nativeEvent event))]
            (st/emit! (ms/->MouseEvent :down button ctrl? shift?))
            (when (and (not edition)
                       (= 1 (.-which (.-nativeEvent event))))
              (if drawing-tool
                (st/emit! (start-drawing drawing-tool))
                (st/emit! dw/handle-selection)))))

        on-context-menu
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:shift? shift?
                      :ctrl? ctrl?}
                cpos (dom/get-client-position event)]
            (st/emit! (ms/->MouseEvent :context-menu 3 ctrl? shift?)
                      (dw/show-context-menu {:position cpos}))))

        on-mouse-up
        (fn [event]
          (dom/stop-propagation event)
          (let [ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:shift? shift?
                      :ctrl? ctrl?}
                button (.-which (.-nativeEvent event))]
            (st/emit! (ms/->MouseEvent :up button ctrl? shift?))))

        on-click
        (fn [event]
          (dom/stop-propagation event)
          (let [ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:shift? shift?
                      :ctrl? ctrl?}
                button (.-which (.-nativeEvent event))]
            (st/emit! (ms/->MouseEvent :click button ctrl? shift?))))

        on-double-click
        (fn [event]
          (dom/stop-propagation event)
          (let [ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:shift? shift?
                      :ctrl? ctrl?}
                button (.-which (.-nativeEvent event))]
            (st/emit! (ms/->MouseEvent :double-click button ctrl? shift?))))

        on-key-down
        (fn [event]
          (let [bevent (.getBrowserEvent event)
                key (.-keyCode event)
                ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:key key
                      :shift? shift?
                      :ctrl? ctrl?}]
            (when-not (.-repeat bevent)
              (st/emit! (ms/->KeyboardEvent :down key ctrl? shift?))
              (when (kbd/space? event)
                (st/emit! handle-viewport-positioning)
                #_(st/emit! (dw/start-viewport-positioning))))))

        on-key-up
        (fn [event]
          (let [key (.-keyCode event)
                ctrl? (kbd/ctrl? event)
                shift? (kbd/shift? event)
                opts {:key key
                      :shift? shift?
                      :ctrl? ctrl?}]
            (when (kbd/space? event)
              (st/emit! ::finish-positioning #_(dw/stop-viewport-positioning)))
            (st/emit! (ms/->KeyboardEvent :up key ctrl? shift?))))

        ;; translate-point-to-viewport
        ;; (fn [pt]
        ;;   (let [viewport (mf/ref-node viewport-ref)
        ;;         brect (.getBoundingClientRect viewport)
        ;;         brect (gpt/point (parse-int (.-left brect))
        ;;                          (parse-int (.-top brect)))]
        ;;     (gpt/subtract pt brect)))

        on-mouse-move
        (fn [event]
          ;; NOTE: offsetX and offsetY are marked as "experimental" on
          ;; MDN site but seems like they are supported on all
          ;; browsers so we can avoid translation opetation just using
          ;; this attributes.
          (let [;; pt (translate-point-to-viewport pt)
                pt (gpt/point (.-offsetX (.-nativeEvent event))
                               (.-offsetY (.-nativeEvent event)))]
            (st/emit! (ms/->PointerEvent :viewport pt
                                         (kbd/ctrl? event)
                                         (kbd/shift? event)))))

        on-mouse-move'
        (fn [event]
          (let [pt (gpt/point (.-clientX event)
                              (.-clientY event))]
            (st/emit! (ms/->PointerEvent :workspace pt
                                         (kbd/ctrl? event)
                                         (kbd/shift? event)))))

        on-mount
        (fn []
          (let [key1 (events/listen js/document EventType.KEYDOWN on-key-down)
                key2 (events/listen js/document EventType.KEYUP on-key-up)
                key3 (events/listen js/document EventType.MOUSEMOVE on-mouse-move')]
            (fn []
              (events/unlistenByKey key1)
              (events/unlistenByKey key2)
              (events/unlistenByKey key3)
              )))]

    (mf/use-effect on-mount)
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
       ;; [:> js/React.Profiler
       ;;  {:id "foobar"
       ;;   :on-render (perf/react-on-profile)}
       ;;  [:& frame-and-shapes]]
       [:& frames-wrapper {:page page}]

       (when (seq selected)
         [:& selection-handlers {:selected selected
                                 :zoom zoom
                                 :edition edition}])

       (when-let [drawing-shape (:drawing local)]
         [:& draw-area {:shape drawing-shape
                        :zoom zoom
                        :modifiers (:modifiers local)}])

       (if (contains? flags :grid)
         [:& grid])]

      (when tooltip
        [:& cursor-tooltip {:zoom zoom :tooltip tooltip}])

      (when (contains? flags :ruler)
        [:& ruler {:zoom zoom :ruler (:ruler local)}])

      [:& remote-user-cursors {:page page}]
      [:& selection-rect {:data (:selrect local)}]]]))


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

