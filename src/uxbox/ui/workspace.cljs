(ns uxbox.ui.workspace
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.data.projects :as dp]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (classnames)]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.ui.workspace.shortcuts :as wshortcuts]
            [uxbox.ui.workspace.header :refer (header)]
            [uxbox.ui.workspace.rules :refer (horizontal-rule vertical-rule)]
            [uxbox.ui.workspace.sidebar :refer (left-sidebar right-sidebar)]
            [uxbox.ui.workspace.colorpalette :refer (colorpalette)]
            [uxbox.ui.workspace.canvas :refer (viewport)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (rx/push! uuwb/scroll-b (gpt/point left top))))

(defn- workspace-render
  [own projectid]
  (let [{:keys [flags] :as workspace} (rum/react uuwb/workspace-l)
        left-sidebar? (not (empty? (keep flags [:layers :sitemap :document-history])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        local (:rum/local own)
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?)
                 :scrolling (:scrolling @local false))]
    (html
     [:div
      (header)
      (colorpalette)
      [:main.main-content

       [:section.workspace-content {:class classes :on-scroll on-scroll}
        ;; Rules
        (horizontal-rule)
        (vertical-rule)

        ;; Canvas
        [:section.workspace-canvas {:ref "workspace-canvas"}
         (viewport)]]

       ;; Aside
       (when left-sidebar?
         (left-sidebar))
       (when right-sidebar?
         (right-sidebar))
       ]])))

(defn- workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/props own)]
    (rs/emit! (dw/initialize projectid pageid)
              (dp/load-projects)
              (dp/load-pages))
    own))

(defn- workspace-did-mount
  [own]
  (letfn [(handle-scroll-interaction []
            (let [stoper (->> uuc/actions-s
                              (rx/map :type)
                              (rx/filter #(not= % :scroll/viewport))
                              (rx/take 1))
                  local (:rum/local own)
                  initial @uuwb/mouse-viewport-a]
              (swap! local assoc :scrolling true)
              (as-> uuwb/mouse-viewport-s $
                (rx/take-until stoper $)
                (rx/subscribe $ #(on-scroll % initial) nil on-scroll-end))))

          (on-scroll-end []
            (let [local (:rum/local own)]
              (swap! local assoc :scrolling false)))

          (on-scroll [pt initial]
            (let [{:keys [x y]} (gpt/subtract pt initial)
                  el (mx/get-ref-dom own "workspace-canvas")
                  cx (.-scrollLeft el)
                  cy (.-scrollTop el)]
              (set! (.-scrollLeft el) (- cx x))
              (set! (.-scrollTop el) (- cy y))))]

  (let [el (mx/get-ref-dom own "workspace-canvas")
        sub (as-> uuc/actions-s $
              (rx/map :type $)
              (rx/dedupe $)
              (rx/filter #(= :scroll/viewport %) $)
              (rx/on-value $ handle-scroll-interaction))]
    (set! (.-scrollLeft el) uuwb/canvas-start-scroll-x)
    (set! (.-scrollTop el) uuwb/canvas-start-scroll-y)
    (assoc own ::sub sub))))

(defn- workspace-will-unmount
  [own]
  (let [unsub (::sub own)]
    (unsub)
    (dissoc own ::sub)))

(defn- workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)]
    (rs/emit! (dw/initialize projectid pageid))
    (assoc state ::sub (::sub old-state))))

(def ^:static workspace
  (mx/component
   {:render workspace-render
    :transfer-state workspace-transfer-state
    :will-mount workspace-will-mount
    :will-unmount workspace-will-unmount
    :did-mount workspace-did-mount
    :name "workspace"
    :mixins [mx/static rum/reactive wshortcuts/mixin
             (mx/local)]}))
