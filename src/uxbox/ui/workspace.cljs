(ns uxbox.ui.workspace
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (classnames)]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.shortcuts :as wshortcuts]
            [uxbox.ui.workspace.pagesmngr :refer (pagesmngr)]
            [uxbox.ui.workspace.header :refer (header)]
            [uxbox.ui.workspace.rules :refer (h-rule v-rule)]
            [uxbox.ui.workspace.sidebar :refer (left-sidebar right-sidebar)]
            [uxbox.ui.workspace.colorpalette :refer (colorpalette)]
            [uxbox.ui.workspace.canvas :refer (viewport)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinates Debug
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coordenates-render
  [own]
  (let [{:keys [x y]} (rum/react wb/mouse-canvas-a)]
    (html
     [:div {:style {:position "absolute" :left "50px" :top "25px"}}
      [:span (str "| x=" x " | y=" y " |")]])))

(def coordinates
  (mx/component
   {:render coordenates-render
    :name "coordenates"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (rx/push! wb/scroll-b (gpt/point left top))))

(defn- on-key-down
  [event]
  (js/console.log event))

(defn- on-key-up
  [event]
  (js/console.log event))

(defn- workspace-render
  [own projectid]
  (let [{:keys [flags] :as workspace} (rum/react wb/workspace-l)
        left-sidebar? (not (empty? (keep flags [:layers :sitemap])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?))]
    (html
     [:div
      (header)
      [:main.main-content
       (when left-sidebar?
         (left-sidebar))

       [:section.workspace-content {:class classes
                                    :on-scroll on-scroll
                                    :on-key-up on-key-up
                                    :on-key-down on-key-down}
        ;; Pages management lightbox
        ;; (pagesmngr)

        ;; Rules
        (h-rule left-sidebar?)
        (v-rule left-sidebar?)

        (coordinates)

        ;; Canvas
        [:section.workspace-canvas
         {:class classes
          :ref "workspace-canvas"}
         [:section.viewport-container
          {:ref "viewport-container"
           :width wb/viewport-width
           :height wb/viewport-height}
          (viewport)]]]

       (colorpalette)

       ;; Aside
       (when right-sidebar?
         (right-sidebar))
       ]])))

(defn- workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/props own)]
    (rs/emit! (dw/initialize projectid pageid))
    own))

(defn- workspace-handle-scroll
  [el1 el2]
  (letfn [(on-value [pt1 pt2]
            (let [{:keys [x y]} (gpt/subtract pt1 pt2)
                  cx (.-scrollLeft el2)
                  cy (.-scrollTop el2)]
              (set! (.-scrollLeft el2) (- cx x))
              (set! (.-scrollTop el2) (- cy y))))]
    (let [stoper (->> wb/interactions-b
                      (rx/filter #(not= % :scroll/viewport))
                      (rx/take 1))
          initial @wb/mouse-viewport-a]
      (as-> wb/mouse-viewport-s $
        (rx/take-until stoper $)
        (rx/subscribe $ #(on-value % initial))))))

(defn- workspace-did-mount
  [own]
  ;; FIXME: this is a hack. I don't know why I need setup
  ;; scroll to both elements, but it does not works without
  ;; that horrible hack.
  (let [el1 (mx/get-ref-dom own "viewport-container")
        el2 (mx/get-ref-dom own "workspace-canvas")
        sub (as-> wb/interactions-b $
              (rx/dedupe $)
              (rx/filter #(= :scroll/viewport %) $)
              (rx/on-value $ #(workspace-handle-scroll el1 el2)))]
    (set! (.-scrollLeft el1) wb/canvas-start-scroll-x)
    (set! (.-scrollLeft el2) wb/canvas-start-scroll-x)
    (assoc own ::sub sub)))

(defn- workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)
        sub (::sub old-state)]
   (rs/emit! (dw/initialize projectid pageid))
   (assoc state ::sub sub)))

(defn- workspace-will-unmount
  [own]
  (let [unsub (::sub own)]
    (unsub)
    (dissoc own ::sub)))

(def ^:static workspace
  (mx/component
   {:render workspace-render
    :will-mount workspace-will-mount
    :did-mount workspace-did-mount
    :will-unmount workspace-will-unmount
    :transfer-state workspace-transfer-state
    :name "workspace"
    :mixins [mx/static rum/reactive wshortcuts/mixin]}))
