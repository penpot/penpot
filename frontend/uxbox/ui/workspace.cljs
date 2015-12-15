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
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.header :as ui.h]
            [uxbox.ui.users :as ui.u]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.dom :as dom]))

(def ^:static project-state
  (as-> (util/dep-in [:projects-by-id] [:workspace :project]) $
    (l/focus-atom $ s/state)))

(def ^:static page-state
  (as-> (util/dep-in [:pages-by-id] [:workspace :page]) $
    (l/focus-atom $ s/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Header
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-download-clicked
  [event page]
  (let [content (.-innerHTML (.getElementById js/document "page-layout"))
        width (:width page)
        height (:height page)
        html (str "<svg width='" width  "' height='" height  "'>" content "</svg>")
        data (js/Blob. #js [html] #js {:type "application/octet-stream"})
        url (.createObjectURL (.-URL js/window) data)]
    (set! (.-href (.-currentTarget event)) url)))

(defn header-render
  [own]
  (let [page (rum/react page-state)]
    (html
     [:header#workspace-bar.workspace-bar
      [:div.main-icon
       (nav/link (r/route-for :dashboard) i/logo-icon)]
      [:div.project-tree-btn
       {:on-click (constantly nil)}
       i/project-tree
       [:span (:name page)]]
      [:div.workspace-options
       [:ul.options-btn
        [:li.tooltip.tooltip-bottom {:alt "Undo (Ctrl + Z)"}
         i/undo]
        [:li.tooltip.tooltip-bottom {:alt "Redo (Ctrl + Shift + Z)"}
         i/redo]]
       [:ul.options-btn
        ;; TODO: refactor
        [:li.tooltip.tooltip-bottom
         {:alt "Export (Ctrl + E)"}
         ;; page-title
         [:a {:download (str (:name page) ".svg")
              :href "#" :on-click on-download-clicked}
          i/export]]
        [:li.tooltip.tooltip-bottom
         {:alt "Image (Ctrl + I)"}
         i/image]]
       [:ul.options-btn
        [:li.tooltip.tooltip-bottom
         {:alt "Ruler (Ctrl + R)"}
         i/ruler]
        [:li.tooltip.tooltip-bottom
         {:alt "Grid (Ctrl + G)"
          :class (when false "selected")
          :on-click (constantly nil)}
         i/grid]
        [:li.tooltip.tooltip-bottom
         {:alt "Align (Ctrl + A)"}
         i/alignment]
        [:li.tooltip.tooltip-bottom
         {:alt "Organize (Ctrl + O)"}
         i/organize]]]
      (ui.u/user)])))

(def header
  (util/component
   {:render header-render
    :name "header"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Toolbar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static toolbar-state
  (as-> (l/in [:workspace :toolbars]) $
    (l/focus-atom $ s/state)))

(defn- toggle-toolbox
  [state item]
  (update state item (fnil not false)))

(defn toolbar-render
  [own]
  (let [state (rum/react toolbar-state)]
    (html
     [:div#tool-bar.tool-bar
      [:div.tool-bar-inside
       [:ul.main-tools
        [:li.tooltip
         {:alt "Shapes (Ctrl + Shift + F)"
          :class (when (:tools state) "current")
          :on-click #(swap! toolbar-state toggle-toolbox :tools)}
         i/shapes]
        [:li.tooltip
         {:alt "Icons (Ctrl + Shift + I)"
          :class (when (:icons state) "current")
          :on-click #(swap! toolbar-state toggle-toolbox :icons)}
         i/icon-set]
        [:li.tooltip
         {:alt "Elements (Ctrl + Shift + L)"
          :class (when (:layers state)
                   "current")
          :on-click #(swap! toolbar-state toggle-toolbox :layers)}
         i/layers]
        [:li.tooltip
         {:alt "Feedback (Ctrl + Shift + M)"}
         i/chat]]]])))

(def ^:static toolbar
  (util/component
   {:render toolbar-render
    :name "toolbar"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Header
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workspace-render
  [own projectid]
  (html
   [:div
    (header)
    [:main.main-content
     [:section.workspace-content
      ;; Toolbar
      (toolbar)
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
      ]]]))

(defn workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/props own)]
    (println "workspace-will-mount" projectid pageid)
    (rs/emit! (dp/initialize-workspace projectid pageid))
    own))

(def ^:static workspace
  (util/component
   {:render workspace-render
    :will-mount workspace-will-mount
    :name "workspace"
    :mixins [rum/static]}))

