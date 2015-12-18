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
            [uxbox.ui.workspace.workarea :refer (workarea aside)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workspace-render
  [own projectid]
  (let [workspace (rum/react wb/workspace-state )]
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
        (workarea)]

       ;; Aside
       (when-not (empty? (:toolboxes workspace))
         (aside))]])))


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

