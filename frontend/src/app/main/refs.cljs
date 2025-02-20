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
   [app.common.files.helpers :as cph]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.tokens.selected-set :as dwts]
   [app.main.store :as st]
   [okulary.core :as l]))

;; ---- Global refs

(def route
  (l/derived (l/key :route) st/state))

(def router
  (l/derived (l/key :router) st/state))

(def profile
  (l/derived (l/key :profile) st/state))

(def team
  (l/derived (fn [state]
               (let [team-id (:current-team-id state)
                     teams   (:teams state)]
                 (get teams team-id)))
             st/state))

(def project
  (l/derived (fn [state]
               (let [project-id (:current-project-id state)
                     projects   (:projects state)]
                 (get projects project-id)))
             st/state))

(def permissions
  (l/derived (l/key :permissions) team))

(def teams
  (l/derived (l/key :teams) st/state))

(def exception
  (l/derived :exception st/state))

(def threads
  (l/derived :comment-threads st/state))

(def share-links
  (l/derived :share-links st/state))

(def export
  (l/derived :export st/state))

(def persistence
  (l/derived :persistence st/state))

(def projects
  (l/derived :projects st/state))

(def files
  (l/derived :files st/state))

(def file
  (l/derived (fn [state]
               (let [file-id (:current-file-id state)
                     files   (:files state)]
                 (get files file-id)))
             st/state))

(def shared-files
  "A derived state that points to the current list of shared
  files (without the content, only summary)"
  (l/derived :shared-files st/state))

(defn select-libraries
  [files file-id]
  (persistent!
   (reduce-kv (fn [result id file]
                (if (or (= id file-id)
                        (= (:library-of file) file-id))
                  (assoc! result id file)
                  result))
              (transient {})
              files)))

;; NOTE: for performance reasons, prefer derefing refs/files and then
;; use with-memo mechanism with `select-libraries` this will avoid
;; executing the select-libraries reduce-kv on each state change and
;; only execute it when files are changed. This ref exists for
;; backward compatibility with the code, but it is considered
;; DEPRECATED and all new code should not use it and old code should
;; be gradually migrated to more efficient approach
(def libraries
  "A derived state that contanins the currently loaded shared
  libraries with all its content; including the current file"
  (l/derived (fn [state]
               (let [files   (get state :files)
                     file-id (get state :current-file-id)]
                 (select-libraries files file-id)))
             st/state))

(defn extract-selected-files
  [files selected]
  (let [get-file #(get files %)
        sim-file #(select-keys % [:id :name :project-id :is-shared])
        xform    (comp (keep get-file)
                       (map sim-file))]
    (->> (sequence xform selected)
         (d/index-by :id))))

(def selected-files
  (l/derived (fn [state]
               (let [selected (get state :selected-files)
                     files    (get state :files)]
                 (extract-selected-files files selected)))
             st/state))

(def selected-project
  (l/derived :selected-project st/state))

(def dashboard-local
  (l/derived :dashboard-local st/state))

(def render-state
  (l/derived :render-state st/state))

(def render-context-lost?
  (l/derived :lost render-state))

(def workspace-local
  (l/derived :workspace-local st/state))

(def workspace-global
  (l/derived :workspace-global st/state))

(def workspace-drawing
  (l/derived :workspace-drawing st/state))

(def workspace-selrect-transform
  (l/derived :workspace-selrect-transform st/state))

(def workspace-tokens
  "All tokens related ephimeral state"
  (l/derived :workspace-tokens st/state))

;; TODO: rename to workspace-selected (?)
;; Don't use directly from components, this is a proxy to improve performance of selected-shapes
(def ^:private selected-shapes-data
  (l/derived
   (fn [state]
     (let [objects  (dsh/lookup-page-objects state)
           selected (dm/get-in state [:workspace-local :selected])]
       {:objects objects :selected selected}))
   st/state (fn [v1 v2]
              (and (identical? (:objects v1) (:objects v2))
                   (= (:selected v1) (:selected v2))))))

(def selected-shapes
  (l/derived
   (fn [{:keys [objects selected]}]
     (dsh/process-selected-shapes objects selected))
   selected-shapes-data =))

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

(def default-font
  (l/derived :default-font workspace-global))

(def inspect-expanded
  (l/derived :inspect-expanded workspace-local))

(def vbox
  (l/derived :vbox workspace-local))

(def current-hover
  (l/derived :hover workspace-local))

(def context-menu
  (l/derived :context-menu workspace-local))

(def token-context-menu
  (l/derived :token-context-menu workspace-local))

;; page item that it is being edited
(def editing-page-item
  (l/derived :page-item workspace-local))

(def current-hover-ids
  (l/derived :hover-ids context-menu))

(def workspace-layout
  (l/derived :workspace-layout st/state))

(def snap-pixel?
  (l/derived #(contains? % :snap-pixel-grid) workspace-layout))

(def rulers?
  (l/derived #(contains? % :rulers) workspace-layout))

;; FIXME: rename to current-file-data
(def workspace-data
  "Currently working file data on workspace"
  (l/derived dsh/lookup-file-data st/state))

(def workspace-file-colors
  (l/derived (fn [{:keys [id] :as data}]
               (some-> (:colors data) (update-vals #(assoc % :file-id id))))
             workspace-data
             =))

(def recent-colors
  "Recent colors for the currently selected file"
  (l/derived (fn [state]
               (when-let [file-id (:current-file-id state)]
                 (dm/get-in state [:recent-colors file-id])))
             st/state))

(def recent-fonts
  "Recent fonts for the currently selected file"
  (l/derived (fn [state]
               (when-let [file-id (:current-file-id state)]
                 (dm/get-in state [:recent-fonts file-id])))
             st/state))

(def workspace-file-typography
  (l/derived :typographies workspace-data))

(def workspace-presence
  (l/derived :workspace-presence st/state))

(def workspace-page
  "Ref to currently active page on workspace"
  (l/derived dsh/lookup-page st/state))

(def workspace-page-flows
  (l/derived #(-> % :flows not-empty) workspace-page))

(defn workspace-page-object-by-id
  [page-id shape-id]
  (l/derived #(dsh/lookup-shape % page-id shape-id) st/state =))

;; TODO: Looks like using the `=` comparator can be pretty expensive
;; on large pages, we are using this for some reason?
(def workspace-page-objects
  (l/derived dsh/lookup-page-objects st/state =))

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

(def workspace-frames
  (l/derived ctt/get-frames workspace-page-objects =))

(def workspace-editor
  (l/derived :workspace-editor st/state))

(def workspace-editor-state
  (l/derived :workspace-editor-state st/state))

(def workspace-v2-editor-state
  (l/derived :workspace-v2-editor-state st/state))

(def workspace-modifiers
  (l/derived :workspace-modifiers st/state =))

(def workspace-modifiers-with-objects
  (l/derived
   (fn [state]
     {:modifiers (:workspace-modifiers state)
      :objects   (dsh/lookup-page-objects state)})
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
  (l/derived #(dsh/select-bool-children % id) st/state =))

(def selected-data
  (l/derived #(let [selected (dsh/lookup-selected %)
                    objects (dsh/lookup-page-objects %)]
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
     (let [objects  (dsh/lookup-page-objects state)]
       (into []
             (comp (map (d/getf objects))
                   (filter (partial ctl/flex-layout-immediate-child? objects)))
             ids)))
   st/state =))

;; ---- Token refs

(def tokens-lib
  (l/derived :tokens-lib workspace-data))

(def workspace-token-theme-groups
  (l/derived (d/nilf ctob/get-theme-groups) tokens-lib))

(defn workspace-token-theme
  [group name]
  (l/derived
   (fn [lib]
     (when lib
       (ctob/get-theme lib group name)))
   tokens-lib))

(def workspace-token-theme-tree-no-hidden
  (l/derived (fn [lib]
               (or
                (some-> lib
                        (ctob/delete-theme ctob/hidden-token-theme-group ctob/hidden-token-theme-name)
                        (ctob/get-theme-tree))
                []))
             tokens-lib))

(def workspace-token-themes
  (l/derived #(or (some-> % ctob/get-themes) []) tokens-lib))

(def workspace-token-themes-no-hidden
  (l/derived #(remove ctob/hidden-temporary-theme? %) workspace-token-themes))

(def selected-token-set-name
  (l/derived (l/key :selected-token-set-name) workspace-tokens))

(def workspace-ordered-token-sets
  (l/derived #(or (some-> % ctob/get-sets) []) tokens-lib))

(def workspace-token-sets-tree
  (l/derived (d/nilf ctob/get-set-tree) tokens-lib))

(def workspace-active-theme-paths
  (l/derived (d/nilf ctob/get-active-theme-paths) tokens-lib))

(defn token-sets-at-path-all-active
  [group-path]
  (l/derived
   (fn [lib]
     (when lib
       (ctob/sets-at-path-all-active? lib group-path)))
   tokens-lib))

(def workspace-active-theme-paths-no-hidden
  (l/derived #(disj % ctob/hidden-token-theme-path) workspace-active-theme-paths))

;; FIXME: deprecated, it should not be implemented with ref (still used in form)
(def workspace-active-theme-sets-tokens
  (l/derived #(or (some-> % ctob/get-active-themes-set-tokens) {}) tokens-lib))

(def workspace-selected-token-set-token
  (fn [token-name]
    (l/derived
     #(dwts/get-selected-token-set-token % token-name)
     st/state)))

(def workspace-selected-token-set-tokens
  (l/derived #(or (dwts/get-selected-token-set-tokens %) {}) st/state))

(def plugins-permissions-peek
  (l/derived (fn [state]
               (dm/get-in state [:plugins-permissions-peek :data]))
             st/state))

;; ---- Viewer refs

(defn get-viewer-objects
  [state page-id]
  (dm/get-in state [:viewer :pages page-id :objects]))

(defn lookup-viewer-objects-by-id
  [page-id]
  (l/derived #(get-viewer-objects % page-id) st/state =))

(def viewer-data
  (l/derived (l/key :viewer) st/state))

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

(def profiles
  (l/derived :profiles st/state))

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
     (some-> (dm/get-in state [:thumbnails object-id])
             (cf/resolve-media)))
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

(def colorpicker
  (l/derived :colorpicker st/state))

(def workspace-grid-edition
  (l/derived :workspace-grid-edition st/state))

(defn workspace-grid-edition-id
  [id]
  (l/derived #(get % id) workspace-grid-edition))

(def workspace-preview-blend
  (l/derived :workspace-preview-blend st/state))

(defn workspace-preview-blend-by-id [id]
  (l/derived (l/key id) workspace-preview-blend =))

(def specialized-panel
  (l/derived :specialized-panel st/state))

(def updating-library
  (l/derived :updating-library st/state))

(def persistence-state
  (l/derived (comp :status :persistence) st/state))
