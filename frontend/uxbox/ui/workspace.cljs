(ns uxbox.ui.workspace
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [cuerdas.core :as str]
            [uxbox.util :as util]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.ui.icons.dashboard :as icons]
            [uxbox.ui.icons :as i]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.header :as ui.h]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.time :refer [ago]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Header
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn header-render
;;   [conn page grid? project-bar-visible?]
;;   (let [{page-title :page/title
;;          page-width :page/width
;;          page-height :page/height} page]
;;     [:header#workspace-bar.workspace-bar
;;      [:div.main-icon
;;       (nav/link (nav/route-for :dashboard) icons/logo-icon)]
;;      (project-tree page-title project-bar-visible?)
;;      [:div.workspace-options
;;       [:ul.options-btn
;;        [:li.tooltip.tooltip-bottom {:alt "Undo (Ctrl + Z)"}
;;         icons/undo]
;;        [:li.tooltip.tooltip-bottom {:alt "Redo (Ctrl + Shift + Z)"}
;;         icons/redo]]
;;       [:ul.options-btn
;;        ;; TODO: refactor
;;        [:li.tooltip.tooltip-bottom
;;         {:alt "Export (Ctrl + E)"}
;;         ;; page-title
;;         [:a {:download (str page-title ".svg")
;;              :href "#"
;;              :on-click (fn [e]
;;                          (let [innerHTML (.-innerHTML (.getElementById js/document "page-layout"))
;;                                width page-width
;;                                height page-height
;;                                html (str "<svg width='" width  "' height='" height  "'>" innerHTML "</svg>")
;;                                data (js/Blob. #js [html] #js {:type "application/octet-stream"})
;;                                url (.createObjectURL (.-URL js/window) data)]
;;                            (set! (.-href (.-currentTarget e)) url)))}
;;          icons/export]]
;;        [:li.tooltip.tooltip-bottom
;;         {:alt "Image (Ctrl + I)"}
;;         icons/image]]
;;       [:ul.options-btn
;;        [:li.tooltip.tooltip-bottom
;;         {:alt "Ruler (Ctrl + R)"}
;;         icons/ruler]
;;        [:li.tooltip.tooltip-bottom
;;         {:alt "Grid (Ctrl + G)"
;;          :class (when (rum/react ws/grid?) "selected")
;;          :on-click ws/toggle-grid!}
;;         icons/grid]
;;        [:li.tooltip.tooltip-bottom
;;         {:alt "Align (Ctrl + A)"}
;;         icons/alignment]
;;        [:li.tooltip.tooltip-bottom
;;         {:alt "Organize (Ctrl + O)"}
;;         icons/organize]]]
;;      (user conn)]))

;; (def header
;;   (util/component
;;    {:render header-render
;;     :name "header"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Header
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def project-state
  (as-> (util/dep-in [:projects-by-id] [:workspace :project]) $
    (l/focus-atom $ s/state)))

(def page-state
  (as-> (util/dep-in [:pages-by-id] [:workspace :page]) $
    (l/focus-atom $ s/state)))

(defn workspace-render
  [own projectid]
  (println "workspace-render" @s/state)
  (println 22222 @page-state)
  (html
   [:div "hello world"
    ;; (header conn page ws/grid? project-bar-visible?)
    ;; [:main.main-content
    ;;  [:section.workspace-content
    ;;   ;; Toolbar
    ;;   (toolbar open-toolboxes)
    ;;   ;; Project bar
    ;;   (project-bar conn project page pages @project-bar-visible?)
    ;;   ;; Rules
    ;;   (horizontal-rule (rum/react ws/zoom))
    ;;   (vertical-rule (rum/react ws/zoom))
    ;;   ;; Working area
    ;;   (working-area conn @open-toolboxes page project shapes (rum/react ws/zoom) (rum/react ws/grid?))
    ;;   ;; Aside
    ;;   (when-not (empty? @open-toolboxes)
    ;;     (aside conn open-toolboxes page shapes))
    ]))

(def ^:static workspace
  (util/component
   {:render workspace-render
    :name "workspace"
    :mixins [rum/static]}))

