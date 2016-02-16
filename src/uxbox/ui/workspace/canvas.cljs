(ns uxbox.ui.workspace.canvas
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [goog.events :as events]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.xforms :as xf]
            [uxbox.shapes :as sh]
            [uxbox.util.lens :as ul]
            [uxbox.library.icons :as _icons]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.canvas.movement]
            [uxbox.ui.workspace.canvas.draw :refer (draw-area)]
            [uxbox.ui.workspace.canvas.ruler :refer (ruler)]
            [uxbox.ui.workspace.canvas.selection :refer (shapes-selection)]
            [uxbox.ui.workspace.canvas.selrect :refer (selrect)]
            [uxbox.ui.workspace.grid :refer (grid)]
            [uxbox.ui.workspace.options :refer (element-opts)])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Background
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn background-render
  []
  (html
   [:rect
    {:x 0 :y 0 :width "100%" :height "100%" :fill "white"}]))

(def background
  (mx/component
   {:render background-render
    :name "background"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare shape)

(defn shape-render
  [own item selected]
  (let [{:keys [id x y width height group] :as item} item
        selected? (contains? selected id)
        local (:rum/local own)]
    (letfn [(on-mouse-down [event]
              (when-not (:blocked item)
                (cond
                  (and group (:locked (sh/resolve-parent item)))
                  nil

                  (and (not selected?) (empty? selected))
                  (do
                    (dom/stop-propagation event)
                    (swap! local assoc :init-coords [x y])
                    (wb/emit-interaction! :shape/movement)
                    (rs/emit! (dw/select-shape id)))

                  (and (not selected?) (not (empty? selected)))
                  (do
                    (dom/stop-propagation event)
                    (swap! local assoc :init-coords [x y])
                    (if (kbd/shift? event)
                      (rs/emit! (dw/select-shape id))
                      (rs/emit! (dw/deselect-all)
                                (dw/select-shape id))))

                  :else
                  (do
                    (dom/stop-propagation event)
                    (swap! local assoc :init-coords [x y])
                    (wb/emit-interaction! :shape/movement)))))

            (on-mouse-up [event]
              (cond
                (and group (:locked (sh/resolve-parent item)))
                nil

                :else
                (do
                  (dom/stop-propagation event)
                  (wb/emit-interaction! :nothing)
                  )))]
      (html
       [:g.shape {:class (when selected? "selected")
                  :on-mouse-down on-mouse-down
                  :on-mouse-up on-mouse-up}
        (sh/-render item #(shape % selected))]))))

(def ^:static shape
  (mx/component
   {:render shape-render
    :name "shape"
    :mixins [(mx/local {}) rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn- canvas-did-mount
;;   [own]
;;   (letfn [(translate-point [pt page]
;;             (let [canvas (mx/get-ref-dom own (str "canvas" (:id page)))
;;                   brect (.getBoundingClientRect canvas)
;;                   brect (gpt/point (.-left brect) (.-top brect))]
;;                   ;; brect (gpt/add brect @wb/scroll-a)]
;;               (gpt/subtract pt brect)))
;;           (on-mousemove [event page]
;;             (let [wpt (gpt/point (.-clientX event)
;;                                  (.-clientY event))
;;                   cpt (translate-point wpt page)
;;                   event {:id (:id page)
;;                          :ctrl (kbd/ctrl? event)
;;                          :shift (kbd/shift? event)
;;                          :window-coords wpt
;;                          :canvas-coords cpt}]
;;               (println "on-mousemove" cpt)
;;               (rx/push! wb/mouse-b event)))]
;;     (let [[page] (:rum/props own)
;;           key (events/listen js/document EventType.MOUSEMOVE
;;                              #(on-mousemove % page))]
;;       (assoc own ::eventkey key))))

;; (defn- canvas-will-unmount
;;   [own]
;;   (let [key (::eventkey own)
;;         [page] (:rum/props own)]
;;     (events/unlistenByKey key)
;;     (dissoc own ::eventkey)))

;; (defn- canvas-transfer-state
;;   [old-own own]
;;   (let [key (::eventkey old-own)]
;;     (assoc own ::eventkey key)))

(defn- canvas-render
  [own {:keys [width height id] :as page}]
  (let [workspace (rum/react wb/workspace-l)
        shapes-by-id (rum/react wb/shapes-by-id-l)
        workspace-selected (:selected workspace)
        xf (comp
            (map #(get shapes-by-id %))
            (remove :hidden))
        shapes (->> (vals shapes-by-id)
                    (filter #(= (:page %) id)))
        shapes-selected (filter (comp workspace-selected :id) shapes)
        shapes-notselected (filter (comp not workspace-selected :id) shapes)]
    (html
     [:svg.page-canvas {:x wb/document-start-x
                        :y wb/document-start-y
                        :ref (str "canvas" id)
                        :width width
                        :height height}
      (background)
      (grid 1)
      [:svg.page-layout {}
       (shapes-selection shapes-selected)
       [:g.main {}
        (for [item (reverse (sequence xf (:shapes page)))]
          (-> (shape item workspace-selected)
              (rum/with-key (str (:id item)))))
        (draw-area)]]])))

(def canvas
  (mx/component
   {:render canvas-render
    ;; :did-mount canvas-did-mount
    ;; :will-unmount canvas-will-unmount
    ;; :transfer-state canvas-transfer-state
    :name "canvas"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Viewport Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn viewport-render
  [own]
  (let [workspace (rum/react wb/workspace-l)
        page (rum/react wb/page-l)
        drawing? (:drawing workspace)
        zoom 1]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (when-not (empty? (:selected workspace))
                (rs/emit! (dw/deselect-all)))
              (if-let [shape (:drawing workspace)]
                (wb/emit-interaction! :draw/shape)
                (wb/emit-interaction! :draw/selrect)))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (wb/emit-interaction! :nothing))]
      (html
       [:svg.viewport {:width wb/viewport-width
                       :height wb/viewport-height
                       :ref "viewport"
                       :class (when drawing? "drawing")
                       :on-mouse-down on-mouse-down
                       :on-mouse-up on-mouse-up}
        [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
         (if page
           (canvas page))
         (ruler)
         (selrect)]]))))

(defn- viewport-did-mount
  [own]
  (letfn [(translate-point-to-viewport [pt]
            (let [viewport (mx/get-ref-dom own "viewport")
                  brect (.getBoundingClientRect viewport)
                  brect (gpt/point (.-left brect) (.-top brect))]
              (gpt/subtract pt brect)))

          (translate-point-to-canvas [pt]
            (let [viewport (mx/get-ref-dom own "viewport")
                  canvas (dom/get-element-by-class "page-canvas" viewport)
                  brect (.getBoundingClientRect canvas)
                  brect (gpt/point (.-left brect) (.-top brect))]
              (gpt/subtract pt brect)))

          (on-mousemove [event page]
            (let [wpt (gpt/point (.-clientX event)
                                 (.-clientY event))
                  vppt (translate-point-to-viewport wpt)
                  cvpt (translate-point-to-canvas wpt)
                  event {:ctrl (kbd/ctrl? event)
                         :shift (kbd/shift? event)
                         :window-coords wpt
                         :viewport-coords vppt
                         :canvas-coords cvpt}]
              (rx/push! wb/mouse-b event)))]
    (let [[page] (:rum/props own)
          key (events/listen js/document EventType.MOUSEMOVE
                             #(on-mousemove % page))]
      (assoc own ::eventkey key))))

(defn- viewport-will-unmount
  [own]
  (let [key (::eventkey own)
        [page] (:rum/props own)]
    (events/unlistenByKey key)
    (dissoc own ::eventkey)))

(defn- viewport-transfer-state
  [old-own own]
  (let [key (::eventkey old-own)]
    (assoc own ::eventkey key)))


(def viewport
  (mx/component
   {:render viewport-render
    :name "viewport"
    :did-mount viewport-did-mount
    :will-unmount viewport-will-unmount
    :transfer-state viewport-transfer-state
    :mixins [rum/reactive]}))
