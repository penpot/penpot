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
            [uxbox.ui.workspace.toolbar :as wt]
            [uxbox.ui.workspace.leftsidebar :as wl]
            [uxbox.ui.workspace.header :as wh]
            [uxbox.ui.workspace.rules :as wr]
            [uxbox.ui.workspace.workarea :as wa]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workspace-render
  [own projectid]
  (html
   [:div
    (wh/header)
    [:main.main-content
     [:section.workspace-content
      ;; Toolbar
      (wt/toolbar)
      ;; Project bar
      (wl/left-sidebar)
      ;; Rules
      (wr/h-rule)
      (wr/v-rule)

      ;; Canvas
      (wa/workarea)
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

