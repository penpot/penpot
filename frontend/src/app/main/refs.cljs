;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.refs
  "A collection of derived refs."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.store :as st]
   [okulary.core :as l]))

;; ---- Global refs

(def route
  (l/derived :route st/state))

(def router
  (l/derived :router st/state))

(def message
  (l/derived :message st/state))

(def profile
  (l/derived :profile st/state))

(def teams
  (l/derived :teams st/state))

(def exception
  (l/derived :exception st/state))

(def threads-ref
  (l/derived :comment-threads st/state))

(def share-links
  (l/derived :share-links st/state))

(def export
  (l/derived :export st/state))

;; ---- Dashboard refs

(def dashboard-local
  (l/derived :dashboard-local st/state))

(def dashboard-fonts
  (l/derived :dashboard-fonts st/state))

(def dashboard-projects
  (l/derived :dashboard-projects st/state))

(def dashboard-files
  (l/derived :dashboard-files st/state))

(def dashboard-shared-files
  (l/derived :dashboard-shared-files st/state))

(def dashboard-search-result
  (l/derived :dashboard-search-result st/state))

(def dashboard-team-stats
  (l/derived :dashboard-team-stats st/state))

(def dashboard-team-members
  (l/derived :dashboard-team-members st/state))

(def dashboard-team-invitations
  (l/derived :dashboard-team-invitations st/state))

(def dashboard-team-webhooks
  (l/derived :dashboard-team-webhooks st/state))

(def dashboard-selected-project
  (l/derived (fn [state]
               (dm/get-in state [:dashboard-local :selected-project]))
             st/state))

(def dashboard-selected-files
  (l/derived (fn [state]
               (let [get-file #(dm/get-in state [:dashboard-files %])
                     sim-file #(select-keys % [:id :name :project-id :is-shared])
                     selected (get-in state [:dashboard-local :selected-files])
                     xform    (comp (map get-file)
                                    (map sim-file))]
                 (->> (into #{} xform selected)
                      (d/index-by :id))))
             st/state =))

;; ---- Workspace refs

(def workspace-local
  (l/derived :workspace-local st/state))

(def workspace-global
  (l/derived :workspace-global st/state))

(def workspace-drawing
  (l/derived :workspace-drawing st/state))

;; TODO: rename to workspace-selected (?)
;; Don't use directly from components, this is a proxy to improve performance of selected-shapes
(def ^:private selected-shapes-data
  (l/derived
   (fn [state]
     (let [objects  (wsh/lookup-page-objects state)
           selected (dm/get-in state [:workspace-local :selected])]
       {:objects objects :selected selected}))
   st/state (fn [v1 v2]
              (and (identical? (:objects v1) (:objects v2))
                   (= (:selected v1) (:selected v2))))))

(def selected-shapes
  (l/derived
   (fn [{:keys [objects selected]}]
     (wsh/process-selected-shapes objects selected))
   selected-shapes-data))

(defn make-selected-ref
  [id]
  (l/derived #(contains? % id) selected-shapes))

(def highlighted-shapes
  (l/derived :highlighted workspace-local))

(def export-in-progress?
  (l/derived :export-in-progress? export))

(def export-error?
  (l/derived :export-error? export))

(def export-progress
  (l/derived :export-progress export))

(def exports
  (l/derived :exports export))

(def export-detail-visibililty
  (l/derived :export-detail-visibililty export))

(def export-widget-visibililty
  (l/derived :export-widget-visibililty export))

(def export-health
  (l/derived :export-health export))

(def selected-zoom
  (l/derived :zoom workspace-local))

(def selected-drawing-tool
  (l/derived :tool workspace-drawing))

(def current-drawing-shape
  (l/derived :object workspace-drawing))

(def selected-edition
  (l/derived :edition workspace-local))

(def current-transform
  (l/derived :transform workspace-local))

(def options-mode
  (l/derived :options-mode workspace-local))

(def options-mode-global
  (l/derived :options-mode workspace-global))

(def inspect-expanded
  (l/derived :inspect-expanded workspace-local))

(def vbox
  (l/derived :vbox workspace-local))

(def current-hover
  (l/derived :hover workspace-local))

(def context-menu
  (l/derived :context-menu workspace-local))

(def toolbar-visibility
  (l/derived :hide-toolbar workspace-local))

;; page item that it is being edited
(def editing-page-item
  (l/derived :page-item workspace-local))

(def current-hover-ids
  (l/derived :hover-ids context-menu))

(def workspace-layout
  (l/derived :workspace-layout st/state))

(def snap-pixel?
  (l/derived #(contains? % :snap-pixel-grid) workspace-layout))

(def rules?
  (l/derived #(contains? % :rules) workspace-layout))

(def workspace-file
  "A ref to a striped vision of file (without data)."
  (l/derived (fn [state]
               (let [file (:workspace-file state)
                     data (:workspace-data state)]
                 (-> file
                     (dissoc :data)
                     ;; FIXME: still used in sitemaps but sitemaps
                     ;; should declare its own lense for it
                     (assoc :pages (:pages data)))))
             st/state =))

(def workspace-data
  (l/derived :workspace-data st/state))

(def workspace-file-colors
  (l/derived (fn [data]
               (when data
                 (->> (:colors data)
                      (d/mapm #(assoc %2 :file-id (:id data))))))
             workspace-data
             =))

(def workspace-recent-colors
  (l/derived (fn [data]
               (get data :recent-colors []))
             workspace-data))

(def workspace-recent-fonts
  (l/derived (fn [data]
               (get data :recent-fonts []))
             workspace-data))

(def workspace-file-typography
  (l/derived :typographies workspace-data))

(def workspace-project
  (l/derived :workspace-project st/state))

(def workspace-shared-files
  (l/derived :workspace-shared-files st/state))

(def workspace-local-library
  (l/derived (fn [state]
               (select-keys (:workspace-data state)
                            [:id
                             :colors
                             :media
                             :typographies
                             :components]))
             st/state =))

(def workspace-libraries
  (l/derived :workspace-libraries st/state))

(def workspace-presence
  (l/derived :workspace-presence st/state))

(def workspace-snap-data
  (l/derived :workspace-snap-data st/state))

(def workspace-page
  (l/derived (fn [state]
               (let [page-id (:current-page-id state)
                     data    (:workspace-data state)]
                 (dm/get-in data [:pages-index page-id])))
             st/state))

(defn workspace-page-objects-by-id
  [page-id]
  (l/derived #(wsh/lookup-page-objects % page-id) st/state =))

;; TODO: Looks like using the `=` comparator can be pretty expensive
;; on large pages, we are using this for some reason?
(def workspace-page-objects
  (l/derived wsh/lookup-page-objects st/state =))

(def workspace-read-only?
  (l/derived :read-only? workspace-global))

(def workspace-paddings-selected
  (l/derived :paddings-selected workspace-global))

(def workspace-gap-selected
  (l/derived :gap-selected workspace-global))

(def workspace-margins-selected
  (l/derived :margins-selected workspace-global))

(defn object-by-id
  [id]
  (l/derived #(get % id) workspace-page-objects))

(defn objects-by-id
  [ids]
  (l/derived #(into [] (keep (d/getf %)) ids) workspace-page-objects =))

(defn parents-by-ids
  [ids]
  (l/derived
   (fn [objects]
     (let [parent-ids (into #{} (keep #(get-in objects [% :parent-id])) ids)]
       (into [] (keep #(get objects %)) parent-ids)))
   workspace-page-objects =))

(defn shape-parents
  [id]
  (l/derived
   (fn [objects]
     (into []
           (keep (d/getf objects))
           (cph/get-parent-ids objects id)))
   workspace-page-objects =))

(defn children-objects
  [id]
  (l/derived
   (fn [objects]
     (->> (dm/get-in objects [id :shapes])
          (into [] (keep (d/getf objects)))))
   workspace-page-objects =))

(defn all-children-objects
  [id]
  (l/derived
   (fn [objects]
     (let [children-ids (cph/get-children-ids objects id)]
       (into [] (keep (d/getf objects)) children-ids)))
   workspace-page-objects =))

(def workspace-page-options
  (l/derived :options workspace-page))

(def workspace-frames
  (l/derived ctt/get-frames workspace-page-objects =))

(def workspace-editor
  (l/derived :workspace-editor st/state))

(def workspace-editor-state
  (l/derived :workspace-editor-state st/state))

(def workspace-modifiers
  (l/derived :workspace-modifiers st/state =))

(def workspace-modifiers-with-objects
  (l/derived
   (fn [state]
     {:modifiers (:workspace-modifiers state)
      :objects   (wsh/lookup-page-objects state)})
   st/state
   (fn [a b]
     (and (= (:modifiers a) (:modifiers b))
          (identical? (:objects a) (:objects b))))))

(def workspace-frame-modifiers
  (l/derived
   (fn [{:keys [modifiers objects]}]
     (->> modifiers
          (reduce
           (fn [result [id modifiers]]
             (let [shape (get objects id)
                   frame-id (:frame-id shape)]
               (cond
                 (cph/frame-shape? shape)
                 (assoc-in result [id id] modifiers)

                 (some? frame-id)
                 (assoc-in result [frame-id id] modifiers)

                 :else
                 result)))
           {})))
   workspace-modifiers-with-objects))

(defn workspace-modifiers-by-frame-id
  [frame-id]
  (l/derived #(get % frame-id) workspace-frame-modifiers =))

(defn select-bool-children [id]
  (l/derived (partial wsh/select-bool-children id) st/state =))

(def selected-data
  (l/derived #(let [selected (wsh/lookup-selected %)
                    objects (wsh/lookup-page-objects %)]
                (hash-map :selected selected
                          :objects objects))
             st/state =))

(defn is-child-selected?
  [id]
  (letfn [(selector [{:keys [selected objects]}]
            (let [children (cph/get-children-ids objects id)]
              (some #(contains? selected %) children)))]
    (l/derived selector selected-data =)))

(def selected-objects
  (letfn [(selector [{:keys [selected objects]}]
            (into [] (keep (d/getf objects)) selected))]
    (l/derived selector selected-data =)))

(def selected-shapes-with-children
  (letfn [(selector [{:keys [selected objects]}]
            (let [xform (comp (remove nil?)
                              (mapcat #(cph/get-children-ids objects %)))
                  shapes (into selected xform selected)]
              (mapv (d/getf objects) shapes)))]
    (l/derived selector selected-data =)))

(def workspace-focus-selected
  (l/derived :workspace-focus-selected st/state))

(defn workspace-get-flex-child
  [ids]
  (l/derived
   (fn [state]
     (let [objects  (wsh/lookup-page-objects state)]
       (into []
             (comp (map (d/getf objects))
                   (filter (partial ctl/flex-layout-immediate-child? objects)))
             ids)))
   st/state =))

;; ---- Viewer refs

(defn lookup-viewer-objects-by-id
  [page-id]
  (l/derived #(wsh/lookup-viewer-objects % page-id) st/state =))

(def viewer-data
  (l/derived :viewer st/state))

(def viewer-file
  (l/derived :file viewer-data))

(def viewer-thumbnails
  (l/derived :thumbnails viewer-file))

(def viewer-project
  (l/derived :project viewer-data))

(def viewer-state
  (l/derived :viewer st/state))

(def viewer-local
  (l/derived :viewer-local st/state))

(def viewer-overlays
  (l/derived :viewer-overlays st/state))

(def comment-threads
  (l/derived :comment-threads st/state))

(def comments-local
  (l/derived :comments-local st/state))

(def users
  (l/derived :users st/state))

(def current-file-comments-users
  (l/derived :current-file-comments-users st/state))

(def current-team-comments-users
  (l/derived :current-team-comments-users st/state))

(def viewer-fullscreen?
  (l/derived (fn [state]
               (dm/get-in state [:viewer-local :fullscreen?]))
             st/state))

(def viewer-zoom-type
  (l/derived (fn [state]
               (dm/get-in state [:viewer-local :zoom-type]))
             st/state))

(defn workspace-thumbnail-by-id
  [object-id]
  (l/derived
   (fn [state]
     (dm/get-in state [:workspace-thumbnails object-id]))
   st/state))

(def workspace-text-modifier
  (l/derived :workspace-text-modifier st/state))

(defn workspace-text-modifier-by-id [id]
  (l/derived #(get % id) workspace-text-modifier =))

(defn is-layout-child?
  [ids]
  (l/derived
   (fn [objects]
     (->> ids
          (map (d/getf objects))
          (some (partial ctl/any-layout-immediate-child? objects))))
   workspace-page-objects))

(defn all-layout-child?
  [ids]
  (l/derived
   (fn [objects]
     (->> ids
          (map (d/getf objects))
          (every? (partial ctl/any-layout-immediate-child? objects))))
   workspace-page-objects =))

(defn flex-layout-child?
  [ids]
  (l/derived
   (fn [objects]
     (->> ids
          (map (d/getf objects))
          (every? (partial ctl/flex-layout-immediate-child? objects))))
   workspace-page-objects =))

(defn grid-layout-child?
  [ids]
  (l/derived
   (fn [objects]
     (->> ids
          (map (d/getf objects))
          (every? (partial ctl/grid-layout-immediate-child? objects))))
   workspace-page-objects =))

(defn get-flex-child-viewer
  [ids page-id]
  (l/derived
   (fn [state]
     (let [objects (wsh/lookup-viewer-objects state page-id)]
       (into []
             (comp (map (d/getf objects))
                   (filter (partial ctl/flex-layout-immediate-child? objects)))
             ids)))
   st/state =))


(defn get-viewer-objects
  ([]
   (let [route      (deref route)
         page-id    (:page-id (:query-params route))]
     (get-viewer-objects page-id)))
  ([page-id]
   (l/derived
    (fn [state]
      (let [objects (wsh/lookup-viewer-objects state page-id)]
        objects))
    st/state =)))

(def colorpicker
  (l/derived :colorpicker st/state))


(def workspace-grid-edition
  (l/derived :workspace-grid-edition st/state))

(defn workspace-grid-edition-id
  [id]
  (l/derived #(get % id) workspace-grid-edition))

(def workspace-annotations
  (l/derived #(get % :workspace-annotations {}) st/state))

(def current-file-id
  (l/derived :current-file-id st/state))

(def workspace-preview-blend
  (l/derived :workspace-preview-blend st/state))

(defn workspace-preview-blend-by-id [id]
  (l/derived (l/key id) workspace-preview-blend =))

(def specialized-panel
  (l/derived :specialized-panel st/state))

(def updating-library
  (l/derived :updating-library st/state))
