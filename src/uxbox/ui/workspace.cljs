(ns uxbox.ui.workspace
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.options :refer (element-opts)]
            [uxbox.ui.workspace.shortcuts :as wshortcuts]
            [uxbox.ui.workspace.lateralmenu :refer (lateralmenu)]
            [uxbox.ui.workspace.pagesmngr :refer (pagesmngr)]
            [uxbox.ui.workspace.header :refer (header)]
            [uxbox.ui.workspace.rules :refer (h-rule v-rule)]
            [uxbox.ui.workspace.sidebar :refer (aside)]
            [uxbox.ui.workspace.colorpalette :refer (colorpalette)]
            [uxbox.ui.workspace.workarea :refer (viewport)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinates Debug
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coordenates-render
  [own]
  (let [[x y] (rum/react wb/mouse-position)]
    (html
     [:div {:style {:position "absolute" :left "80px" :top "20px"}}
      [:table
       [:tbody
        [:tr [:td "x="] [:td x]]
        [:tr [:td "y="] [:td y]]]]])))

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
    (rx/push! wb/scroll-b {:top top :left left})))

(defn- workspace-render
  [own projectid]
  (let [workspace (rum/react wb/workspace-l)
        no-toolbars? (empty? (:toolboxes workspace))]
    (html
     [:div
      (header)
      [:main.main-content
       [:section.workspace-content
        ;; Lateral Menu (left side)
        (lateralmenu)

        ;; Pages management lightbox
        (pagesmngr)

        ;; Rules
        (h-rule)
        (v-rule)

        ;; Canvas
        [:section.workspace-canvas {:class (when no-toolbars? "no-tool-bar")
                                    :on-scroll on-scroll}
         (when (and (:selected workspace)
                    (= (count (:selected workspace)) 1))
           (let [shape-id (first (:selected workspace))
                 shape (l/focus-atom (l/in [:shapes-by-id shape-id]) st/state)]
             (element-opts shape)))
         (coordinates)
         (viewport)]]

       (colorpalette)

       ;; Aside
       (when-not no-toolbars?
         (aside))]])))

(defn- workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/props own)]
    (rs/emit! (dw/initialize projectid pageid))
    own))

(defn- workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)]
   (rs/emit! (dw/initialize projectid pageid))
   state))

(def ^:static workspace
  (mx/component
   {:render workspace-render
    :will-mount workspace-will-mount
    :transfer-state workspace-transfer-state
    :name "workspace"
    :mixins [mx/static rum/reactive wshortcuts/mixin]}))

