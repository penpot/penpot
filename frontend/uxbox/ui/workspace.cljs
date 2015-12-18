(ns uxbox.ui.workspace
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.ui.util :as util]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.lateralmenu :refer (lateralmenu)]
            [uxbox.ui.workspace.pagesmngr :refer (pagesmngr)]
            [uxbox.ui.workspace.header :refer (header)]
            [uxbox.ui.workspace.rules :refer (h-rule v-rule)]
            [uxbox.ui.workspace.workarea :refer (workarea)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workspace-render
  [own projectid]
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
      (workarea)
      ;; (working-area conn @open-toolboxes page project shapes (rum/react ws/zoom) (rum/react ws/grid?))
      ;;   ;; Aside
      ;;   (when-not (empty? @open-toolboxes)
      ;;     (aside conn open-toolboxes page shapes))
      ]]]))

(defn workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/props own)]
    (rs/emit! (dp/initialize-workspace projectid pageid))
    own))

(defn workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)]
    (rs/emit! (dp/initialize-workspace projectid pageid))))

(def ^:static workspace
  (util/component
   {:render workspace-render
    :will-mount workspace-will-mount
    :transfer-state workspace-transfer-state
    :name "workspace"
    :mixins [mx/static rum/reactive]}))

