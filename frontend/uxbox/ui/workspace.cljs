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
            [uxbox.data.workspace :as dw]
            [uxbox.ui.icons.dashboard :as icons]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.keyboard :as k]
            [uxbox.ui.users :as ui.u]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.dom :as dom]))

(def ^:static project-state
  (as-> (util/dep-in [:projects-by-id] [:workspace :project]) $
    (l/focus-atom $ s/state)))

(def ^:static page-state
  (as-> (util/dep-in [:pages-by-id] [:workspace :page]) $
    (l/focus-atom $ s/state)))

(def ^:static pages-state
  (as-> (util/getter #(let [pid (get-in % [:workspace :project])]
                        (dp/project-pages % pid))) $
    (l/focus-atom $ s/state)))

(def ^:static workspace-state
  (as-> (l/in [:workspace]) $
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
  (let [page (rum/react page-state)
        toggle #(rs/emit! (dw/toggle-pagesbar))]
    (html
     [:header#workspace-bar.workspace-bar
      [:div.main-icon
       (nav/link (r/route-for :dashboard/projects) i/logo-icon)]
      [:div.project-tree-btn
       {:on-click toggle}
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
    :name "workspace-header"
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
;; Project Bar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- project-sidebar-pageitem-render
  [own parent page numpages]
  (letfn [(on-edit [e]
            (let [data {:edit true :form page}]
              (reset! parent data)))]
    (let [curpage (rum/react page-state)
          active? (= (:id curpage) (:id page))
          deletable? (> numpages 1)
          navigate #(rs/emit! (dp/go-to-project (:project page) (:id page)))
          delete #(rs/emit! (dp/delete-page (:id page)))]
      (html
       [:li.single-page
        {:class (when active? "current")
         :on-click navigate}
        [:div.tree-icon i/page]
        [:span (:name page)]
        [:div.options
         [:div {:on-click on-edit} i/pencil]
         [:div {:class (when-not deletable? "hide")
                :on-click delete}
          i/trash]]]))))

(def project-sidebar-pageitem
  (util/component
   {:render project-sidebar-pageitem-render
    :name "project-sidebar-pageitem"
    :mixins [rum/reactive]}))

(defn- project-sidebar-pagelist-render
  [own parent]
  (let [project (rum/react project-state)
        pages (rum/react pages-state)
        name (:name project)]
    (html
     [:div.project-bar-inside
      [:span.project-name name]
      [:ul.tree-view
       (for [page pages]
         (let [component (project-sidebar-pageitem parent page (count pages))]
           (rum/with-key component (str (:id page)))))]
      [:button.btn-primary.btn-small
       {:on-click #(reset! parent {:edit true :form {}})}
       "+ Add new page"]])))

(def project-sidebar-pagelist
  (util/component
   {:render project-sidebar-pagelist-render
    :name "project-sidebar-pagelist"
    :mixins [rum/reactive]}))

(defn- project-sidebar-form-render
  [own parent]
  (let [form (:form @parent)
        project @project-state]
    (letfn [(on-change [e]
              (let [value (dom/event->value e)]
                (swap! parent assoc-in [:form :name] value)))
            (persist []
              (if (nil? (:id form))
                (let [data {:project (:id project)
                            :width (:width project)
                            :height (:height project)
                            :name (:name form)}]
                  (rs/emit! (dp/create-page data))
                  (reset! parent {:edit false}))
                (do
                  (rs/emit! (dp/update-page-name (:id form) (:name form)))
                  (reset! parent {:edit false}))))
            (on-save [e]
              (persist))
            (on-key-up [e]
              (when (k/enter? e)
                (persist)))
            (on-cancel [e]
              (reset! parent {:edit false}))]
      (html
       [:div.project-bar-inside
        [:input.input-text
         {:name "test"
          :auto-focus true
          :placeholder "Page title"
          :type "text"
          :value (get-in @parent [:form :name] "")
          :on-change on-change
          :on-key-up on-key-up}]
        [:button.btn-primary.btn-small
         {:disabled (str/empty? (str/trim (get-in @parent [:form :name] "")))
          :on-click on-save}
         "Save"]
        [:button.btn-primary.btn-small
         {:on-click on-cancel}
         "Cancel"]]))))

(def project-sidebar-form
  (util/component
   {:render project-sidebar-form-render
    :name "project-sidebar-form"
    :mixins [rum/reactive]}))

(defn project-sidebar-render
  [own]
  (let [local (:rum/local own)
        workspace (rum/react workspace-state)
        project (rum/react project-state)]
    (html
     [:div#project-bar.project-bar
      (when-not (:visible-pagebar workspace false)
        {:class "toggle"})
      (if (:edit @local)
        (project-sidebar-form local)
        (project-sidebar-pagelist local))])))

(def project-sidebar
  (util/component
   {:render project-sidebar-render
    :name "project-sidebar"
    :mixins [rum/reactive (rum/local {:edit false :form {}})]}))

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
      ;; Toolbar
      (toolbar)
      ;; Project bar
      (project-sidebar)
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

(defn workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)]
    (println "workspace-transfer-state" old-state)
    (println "workspace-transfer-state" state)
    (rs/emit! (dp/initialize-workspace projectid pageid))))

(def ^:static workspace
  (util/component
   {:render workspace-render
    :will-mount workspace-will-mount
    :transfer-state workspace-transfer-state
    :name "workspace"
    :mixins [rum/static]}))

