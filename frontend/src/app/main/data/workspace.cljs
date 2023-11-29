;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpp]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.grid-layout :as gslg]
   [app.common.schema :as sm]
   [app.common.text :as txt]
   [app.common.transit :as t]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.comments :as dcm]
   [app.main.data.events :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.messages :as msg]
   [app.main.data.users :as du]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwco]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.fix-bool-contents :as fbc]
   [app.main.data.workspace.fix-broken-shapes :as fbs]
   [app.main.data.workspace.fix-deleted-fonts :as fdf]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.guides :as dwgu]
   [app.main.data.workspace.highlight :as dwh]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.layers :as dwly]
   [app.main.data.workspace.layout :as layout]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.notifications :as dwn]
   [app.main.data.workspace.path :as dwdp]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.viewport :as dwv]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.repo :as rp]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(def default-workspace-local {:zoom 1})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private workspace-initialized)
(declare ^:private libraries-fetched)

;; --- Initialize Workspace

(defn initialize-layout
  [lname]
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-layout #(or % layout/default-layout))
          (update :workspace-global #(or % layout/default-global))))

    ptk/WatchEvent
    (watch [_ _ _]
      (if (and lname (contains? layout/presets lname))
        (rx/of (layout/ensure-layout lname))
        (rx/of (layout/ensure-layout :layers))))))

(defn- workspace-initialized
  []
  (ptk/reify ::workspace-initialized
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :workspace-undo {})
          (assoc :workspace-ready? true)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fbc/fix-bool-contents)
             (fdf/fix-deleted-fonts)
             (fbs/fix-broken-shapes)))))

(defn- workspace-data-loaded
  [data]
  (ptk/reify ::workspace-data-loaded
    ptk/UpdateEvent
    (update [_ state]
      (let [data (d/removem (comp t/pointer? val) data)]
        (assoc state :workspace-data data)))))

(defn- bundle-fetched
  [{:keys [features file thumbnails project team team-users comments-users]}]
  (ptk/reify ::bundle-fetched
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :users (d/index-by :id team-users))
          (assoc :workspace-thumbnails thumbnails)
          (assoc :workspace-file (dissoc file :data))
          (assoc :workspace-project project)
          (assoc :current-file-comments-users (d/index-by :id comments-users))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [team-id   (:id team)
            file-id   (:id file)
            stoper-s  (rx/filter (ptk/type? ::bundle-fetched) stream)]

        (->> (rx/concat
              ;; Initialize notifications
              (rx/of (dwn/initialize team-id file-id)
                     (dwsl/initialize))

              ;; Load team fonts. We must ensure custom fonts are
              ;; fully loadad before mark workspace as initialized
              (rx/merge
               (->> stream
                    (rx/filter (ptk/type? ::df/team-fonts-loaded))
                    (rx/take 1)
                    (rx/ignore))

               (rx/of (df/load-team-fonts team-id))

               ;; FIXME: move to bundle fetch stages

               ;; Load main file
               (->> (fpmap/resolve-file file)
                    (rx/map :data)
                    (rx/mapcat (fn [{:keys [pages-index] :as data}]
                                 (->> (rx/from (seq pages-index))
                                      (rx/mapcat
                                       (fn [[id page]]
                                         (let [page (update page :objects ctst/start-page-index)]
                                           (->> (uw/ask! {:cmd :initialize-page-index :page page})
                                                (rx/map (fn [_] [id page]))))))
                                      (rx/reduce conj {})
                                      (rx/map (fn [pages-index]
                                                (assoc data :pages-index pages-index))))))
                    (rx/map workspace-data-loaded))

               ;; Load libraries
               (->> (rp/cmd! :get-file-libraries {:file-id file-id})
                    (rx/mapcat identity)
                    (rx/merge-map
                     (fn [{:keys [id synced-at]}]
                       (->> (rp/cmd! :get-file {:id id :features features})
                            (rx/map #(assoc % :synced-at synced-at)))))
                    (rx/merge-map fpmap/resolve-file)
                    (rx/merge-map
                     (fn [{:keys [id] :as file}]
                       (->> (rp/cmd! :get-file-object-thumbnails {:file-id id :tag "component"})
                            (rx/map #(assoc file :thumbnails %)))))
                    (rx/reduce conj [])
                    (rx/map libraries-fetched)))

              (rx/of (with-meta (workspace-initialized)
                       {:file-id file-id})))
             (rx/take-until stoper-s))))))

(defn- libraries-fetched
  [libraries]
  (ptk/reify ::libraries-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-libraries (d/index-by :id libraries)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id       (dm/get-in state [:workspace-file :id])
            ignore-until  (dm/get-in state [:workspace-file :ignore-sync-until])
            needs-check?  (some #(and (> (:modified-at %) (:synced-at %))
                                      (or (not ignore-until)
                                          (> (:modified-at %) ignore-until)))
                                libraries)]
        (when needs-check?
          (rx/concat (rx/timer 1000)
                     (rx/of (dwl/notify-sync-file file-id))))))))

(defn- datauri->blob-uri
  [uri]
  (->> (http/send! {:uri uri
                    :response-type :blob
                    :method :get})
       (rx/map :body)
       (rx/map (fn [blob] (wapi/create-uri blob)))))

(defn- fetch-file-object-thumbnails
  [file-id]
  (->> (rp/cmd! :get-file-object-thumbnails {:file-id file-id})
       (rx/mapcat (fn [thumbnails]
                    (->> (rx/from thumbnails)
                         (rx/mapcat (fn [[k v]]
                                      ;; we only need to fetch the thumbnail if
                                      ;; it is a data:uri, otherwise we can just
                                      ;; use the value as is.
                                      (if (str/starts-with? v "data:")
                                        (->> (datauri->blob-uri v)
                                             (rx/map (fn [uri] [k uri])))
                                        (rx/of [k v])))))))
       (rx/reduce conj {})))

(defn- fetch-bundle-stage-1
  [project-id file-id]
  (ptk/reify ::fetch-bundle-stage-1
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> (rp/cmd! :get-project {:id project-id})
           (rx/mapcat (fn [project]
                        (->> (rp/cmd! :get-team {:id (:team-id project)})
                             (rx/mapcat (fn [team]
                                          (let [bundle {:team team
                                                        :project project
                                                        :file-id file-id
                                                        :project-id project-id}]
                                            (rx/of (du/set-current-team team)
                                                   (ptk/data-event ::bundle-stage-1 bundle))))))))
           (rx/take-until
            (rx/filter (ptk/type? ::fetch-bundle) stream))))))

(defn- fetch-bundle-stage-2
  [{:keys [file-id project-id] :as bundle}]
  (ptk/reify ::fetch-bundle-stage-2
    ptk/WatchEvent
    (watch [_ state stream]
      (let [features (features/get-team-enabled-features state)

            ;; WTF is this?
            share-id (-> state :viewer-local :share-id)]
        (->> (rx/zip (rp/cmd! :get-file {:id file-id :features features :project-id project-id})
                     (fetch-file-object-thumbnails file-id)
                     (rp/cmd! :get-team-users {:file-id file-id})
                     (rp/cmd! :get-profiles-for-file-comments {:file-id file-id :share-id share-id}))
             (rx/take 1)
             (rx/map (fn [[file thumbnails team-users comments-users]]
                       (let [bundle (-> bundle
                                        (assoc :file file)
                                        (assoc :features features)
                                        (assoc :thumbnails thumbnails)
                                        (assoc :team-users team-users)
                                        (assoc :comments-users comments-users))]
                         (ptk/data-event ::bundle-stage-2 bundle))))
             (rx/take-until
              (rx/filter (ptk/type? ::fetch-bundle) stream)))))))

(declare go-to-component)

(defn- fetch-bundle
  "Multi-stage file bundle fetch coordinator"
  [project-id file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/merge
            (rx/of (fetch-bundle-stage-1 project-id file-id))

            (->> stream
                 (rx/filter (ptk/type? ::bundle-stage-1))
                 (rx/observe-on :async)
                 (rx/map deref)
                 (rx/map fetch-bundle-stage-2))

            (->> stream
                 (rx/filter (ptk/type? ::bundle-stage-2))
                 (rx/observe-on :async)
                 (rx/map deref)
                 (rx/map bundle-fetched))

            (when-let [component-id (get-in state [:route :query-params :component-id])]
              (->> stream
                   (rx/filter (ptk/type? ::workspace-initialized))
                   (rx/observe-on :async)
                   (rx/take 1)
                   (rx/map #(go-to-component (uuid/uuid component-id))))))

                (rx/take-until
                 (rx/filter (ptk/type? ::fetch-bundle) stream))))))

(defn initialize-file
  [project-id file-id]
  (dm/assert! (uuid? project-id))
  (dm/assert! (uuid? file-id))

  (ptk/reify ::initialize-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc state
             :workspace-ready? false
             :current-file-id file-id
             :current-project-id project-id
             :workspace-presence {}))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of msg/hide
             (dcm/retrieve-comment-threads file-id)
             (dwp/initialize-file-persistence file-id)
             (fetch-bundle project-id file-id)))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [name (str "workspace-" file-id)]
        (unchecked-set ug/global "name" name)))))

(defn finalize-file
  [_project-id file-id]
  (ptk/reify ::finalize-file
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state
              :current-file-id
              :current-project-id
              :workspace-data
              :workspace-editor-state
              :workspace-file
              :workspace-libraries
              :workspace-ready?
              :workspace-media-objects
              :workspace-persistence
              :workspace-presence
              :workspace-project
              :workspace-project
              :workspace-undo))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwn/finalize file-id)
             (dwsl/finalize)))))

(declare go-to-page)
(declare ^:private preload-data-uris)

(defn initialize-page
  [page-id]
  (dm/assert! (uuid? page-id))
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (if-let [{:keys [id] :as page} (dm/get-in state [:workspace-data :pages-index page-id])]
        ;; we maintain a cache of page state for user convenience with the exception of the
        ;; selection; when user abandon the current page, the selection is lost
        (let [local (dm/get-in state [:workspace-cache id] default-workspace-local)]
          (-> state
              (assoc :current-page-id id)
              (assoc :workspace-local (assoc local :selected (d/ordered-set)))
              (assoc :workspace-trimmed-page (dm/select-keys page [:id :name]))

              ;; FIXME: this should be done on `initialize-layout` (?)
              (update :workspace-layout layout/load-layout-flags)
              (update :workspace-global layout/load-layout-state)

              (update :workspace-global assoc :background-color (-> page :options :background))
              (update-in [:route :params :query] assoc :page-id (dm/str id))))

        state))

    ptk/WatchEvent
    (watch [_ state _]
      ;; NOTE: there are cases between files navigation when this
      ;; event is emmited but the page-index is still not loaded, so
      ;; we only need to proceed when page-index is properly loaded
      (when-let [pindex (-> state :workspace-data :pages-index)]
        (if (contains? pindex page-id)
          (let [file-id (:current-file-id state)]
            (rx/of (preload-data-uris page-id)
                   (dwth/watch-state-changes file-id page-id)
                   (dwl/watch-component-changes)))
          (let [page-id (dm/get-in state [:workspace-data :pages 0])]
            (rx/of (go-to-page page-id))))))))

(defn finalize-page
  [page-id]
  (dm/assert! (uuid? page-id))
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (-> (:workspace-local state)
                      (dissoc :edition :edit-path :selected))
            exit? (not= :workspace (dm/get-in state [:route :data :name]))
            state (-> state
                      (update :workspace-cache assoc page-id local)
                      (dissoc :current-page-id
                              :workspace-local
                              :workspace-trimmed-page
                              :workspace-focus-selected))]

        (cond-> state
          exit? (dissoc :workspace-drawing))))))

(defn- preload-data-uris
  "Preloads the image data so it's ready when necessary"
  [page-id]
  (ptk/reify ::preload-data-uris
    ptk/EffectEvent
    (effect [_ state _]
      (let [xform (comp (map second)
                        (keep (fn [{:keys [metadata fill-image]}]
                                (cond
                                  (some? metadata)   (cf/resolve-file-media metadata)
                                  (some? fill-image) (cf/resolve-file-media fill-image)))))
            uris  (into #{} xform (wsh/lookup-page-objects state page-id))]

        (->> (rx/from uris)
             (rx/subs #(http/fetch-data-uri % false)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Page CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page
  [{:keys [file-id]}]
  (let [id (uuid/next)]
    (ptk/reify ::create-page
      IDeref
      (-deref [_]
        {:id id :file-id file-id})

      ptk/WatchEvent
      (watch [it state _]
        (let [pages   (get-in state [:workspace-data :pages-index])
              unames  (cfh/get-used-names pages)
              name    (cfh/generate-unique-name unames "Page 1")

              changes (-> (pcb/empty-changes it)
                          (pcb/add-empty-page id name))]

          (rx/of (dch/commit-changes changes)))))))

(defn duplicate-page
  [page-id]
  (ptk/reify ::duplicate-page
    ptk/WatchEvent
    (watch [it state _]
      (let [id                 (uuid/next)
            pages              (get-in state [:workspace-data :pages-index])
            unames             (cfh/get-used-names pages)
            page               (get-in state [:workspace-data :pages-index page-id])
            name               (cfh/generate-unique-name unames (:name page))
            fdata              (:workspace-data state)
            components-v2      (dm/get-in fdata [:options :components-v2])
            objects            (->> (:objects page)
                                    (d/mapm (fn [_ val] (dissoc val :use-for-thumbnail))))
            main-instances-ids (set (keep #(when (ctk/main-instance? (val %)) (key %)) objects))
            ids-to-remove      (set (apply concat (map #(cfh/get-children-ids objects %) main-instances-ids)))

            add-component-copy
            (fn [objs id shape]
              (let [component (ctkl/get-component fdata (:component-id shape))
                    [new-shape new-shapes]
                    (ctn/make-component-instance page
                                                 component
                                                 fdata
                                                 (gpt/point (:x shape) (:y shape))
                                                 components-v2
                                                 {:keep-ids? true})
                    children (into {} (map (fn [shape] [(:id shape) shape]) new-shapes))
                    objs (assoc objs id new-shape)]
                (merge objs children)))

            objects
            (reduce
             (fn [objs [id shape]]
               (cond (contains? main-instances-ids id)
                     (add-component-copy objs id shape)
                     (contains? ids-to-remove id)
                     objs
                     :else
                     (assoc objs id shape)))
             {}
             objects)

            page    (-> page
                        (assoc :name name)
                        (assoc :id id)
                        (assoc :objects
                               objects))

            changes (-> (pcb/empty-changes it)
                        (pcb/add-page id page))]

        (rx/of (dch/commit-changes changes))))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (dm/assert! (uuid? id))
  (dm/assert! (string? name))
  (ptk/reify ::rename-page
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (get-in state [:workspace-data :pages-index id])
            changes (-> (pcb/empty-changes it)
                        (pcb/mod-page page name))]

        (rx/of (dch/commit-changes changes))))))

(declare purge-page)
(declare go-to-file)

(defn- delete-page-components
  [changes page]
  (let [components-to-delete (->> page
                                  :objects
                                  vals
                                  (filter #(true? (:main-instance %)))
                                  (map :component-id))

        changes (reduce (fn [changes component-id]
                          (pcb/delete-component changes component-id))
                        changes
                        components-to-delete)]
    changes))

(defn delete-page
  [id]
  (ptk/reify ::delete-page
    ptk/WatchEvent
    (watch [it state _]
      (let [components-v2 (features/active-feature? state "components/v2")
            file-id       (:current-file-id state)
            file          (wsh/get-file state file-id)
            pages (get-in state [:workspace-data :pages])
            index (d/index-of pages id)
            page (get-in state [:workspace-data :pages-index id])
            page (assoc page :index index)

            changes (cond-> (pcb/empty-changes it)
                      components-v2
                      (pcb/with-library-data file)
                      components-v2
                      (delete-page-components page)
                      :always
                      (pcb/del-page page))]

        (rx/of (dch/commit-changes changes)
               (when (= id (:current-page-id state))
                 go-to-file))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WORKSPACE File Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rename-file
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-file
    IDeref
    (-deref [_]
      {::ev/origin "workspace" :id id :name name})

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :name] name))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:id id :name name}]
        (->> (rp/cmd! :rename-file params)
             (rx/ignore))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace State Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Layout Flags

(dm/export layout/toggle-layout-flag)
(dm/export layout/remove-layout-flag)

;; --- Profile

(defn update-nudge
  [{:keys [big small] :as params}]
  (ptk/reify ::update-nudge
    IDeref
    (-deref [_] (d/without-nils params))

    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props :nudge]
                 (fn [nudge]
                   (cond-> nudge
                     (number? big) (assoc :big big)
                     (number? small) (assoc :small small)))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [nudge (get-in state [:profile :props :nudge])]
        (rx/of (du/update-profile-props {:nudge nudge}))))))

(defn toggle-theme
  []
  (ptk/reify ::toggle-theme
    ptk/UpdateEvent
    (update [_ state]
      (update-in
       state
       [:profile :theme]
       (fn [theme]
         (cond
           (= theme "default")
           "light"

           :else
           "default"))))

    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (du/update-profile (:profile state))))))

;; --- Set element options mode

(dm/export layout/set-options-mode)

;; --- Tooltip

(defn assign-cursor-tooltip
  [content]
  (ptk/reify ::assign-cursor-tooltip
    ptk/UpdateEvent
    (update [_ state]
      (if (string? content)
        (assoc-in state [:workspace-global :tooltip] content)
        (assoc-in state [:workspace-global :tooltip] nil)))))

;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (dm/assert!
   "expected valid parameters"
   (and (cts/check-shape-attrs! attrs)
        (uuid? id)))

  (ptk/reify ::update-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [id] #(merge % attrs))))))

(defn start-rename-shape
  "Start shape renaming process"
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::start-rename-shape
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :shape-for-rename] id))))

(defn end-rename-shape
  "End the ongoing shape rename process"
  ([] (end-rename-shape nil))
  ([name]
   (ptk/reify ::end-rename-shape
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [shape-id (dm/get-in state [:workspace-local :shape-for-rename])]
         (let [shape (wsh/lookup-shape state shape-id)
               name        (str/trim name)
               clean-name  (cfh/clean-path name)
               valid?      (and (not (str/ends-with? name "/"))
                                (string? clean-name)
                                (not (str/blank? clean-name)))]
           (rx/concat
            ;; Remove rename state from workspace local state
            (rx/of #(update % :workspace-local dissoc :shape-for-rename))

            ;; Rename the shape if string is not empty/blank
            (when valid?
              (rx/of (update-shape shape-id {:name clean-name})))

            ;; Update the component in case if shape is a main instance
            (when (and valid? (:main-instance shape))
              (when-let [component-id (:component-id shape)]
                (rx/of (dwl/rename-component component-id clean-name)))))))))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
  (dm/assert!
   "expected valid shape attrs"
   (cts/check-shape-attrs! attrs))

  (ptk/reify ::update-selected-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/from (map #(update-shape % attrs) selected))))))

;; --- Delete Selected

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected     (wsh/lookup-selected state)
            hover-guides (get-in state [:workspace-guides :hover])]
        (cond
          (d/not-empty? selected)
          (rx/of (dwsh/delete-shapes selected)
                 (dws/deselect-all))

          (d/not-empty? hover-guides)
          (rx/of (dwgu/remove-guides hover-guides)))))))

;; --- Shape Vertical Ordering

(def valid-vertical-locations
  #{:up :down :bottom :top})

(defn vertical-order-selected
  [loc]
  (dm/assert!
   "expected valid location"
   (contains? valid-vertical-locations loc))
  (ptk/reify ::vertical-order-selected
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id         (:current-page-id state)
            objects         (wsh/lookup-page-objects state page-id)
            selected-ids    (wsh/lookup-selected state)
            selected-shapes (map (d/getf objects) selected-ids)
            undo-id (js/Symbol)

            move-shape
            (fn [changes shape]
              (let [parent        (get objects (:parent-id shape))
                    sibling-ids   (:shapes parent)
                    current-index (d/index-of sibling-ids (:id shape))
                    index-in-selection (d/index-of selected-ids (:id shape))
                    new-index     (case loc
                                    :top (count sibling-ids)
                                    :down (max 0 (- current-index 1))
                                    :up (min (count sibling-ids) (+ (inc current-index) 1))
                                    :bottom index-in-selection)]
                (pcb/change-parent changes
                                   (:id parent)
                                   [shape]
                                   new-index)))

            changes (reduce move-shape
                            (-> (pcb/empty-changes it page-id)
                                (pcb/with-objects objects))
                            selected-shapes)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (ptk/data-event :layout/update selected-ids)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Change Shape Order (D&D Ordering)

(defn relocate-shapes-changes [it objects parents parent-id page-id to-index ids
                               groups-to-delete groups-to-unmask shapes-to-detach
                               shapes-to-reroot shapes-to-deroot shapes-to-unconstraint]
  (let [ordered-indexes (cfh/order-by-indexed-shapes objects ids)
        shapes (map (d/getf objects) ordered-indexes)
        parent (get objects parent-id)]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)

        ;; Remove layout-item properties when moving a shape outside a layout
        (cond-> (not (ctl/any-layout? parent))
          (pcb/update-shapes ordered-indexes ctl/remove-layout-item-data))

        ;; Remove the hide in viewer flag
        (cond-> (and (not= uuid/zero parent-id) (cfh/frame-shape? parent))
          (pcb/update-shapes ordered-indexes #(cond-> % (cfh/frame-shape? %) (assoc :hide-in-viewer true))))

        ;; Move the shapes
        (pcb/change-parent parent-id
                           shapes
                           to-index)

        ;; Remove empty groups
        (pcb/remove-objects groups-to-delete)

        ;; Unmask groups whose mask have moved outside
        (pcb/update-shapes groups-to-unmask
                           (fn [shape]
                             (assoc shape :masked-group false)))

        ;; Detach shapes moved out of their component
        (pcb/update-shapes shapes-to-detach ctk/detach-shape)

        ;; Make non root a component moved inside another one
        (pcb/update-shapes shapes-to-deroot
                           (fn [shape]
                             (assoc shape :component-root nil)))

        ;; Make root a subcomponent moved outside its parent component
        (pcb/update-shapes shapes-to-reroot
                           (fn [shape]
                             (assoc shape :component-root true)))

        ;; Reset constraints depending on the new parent
        (pcb/update-shapes shapes-to-unconstraint
                           (fn [shape]
                             (let [frame-id    (if (= (:type parent) :frame)
                                                 (:id parent)
                                                 (:frame-id parent))
                                   moved-shape (assoc shape
                                                      :parent-id parent-id
                                                      :frame-id frame-id)]
                               (assoc shape
                                      :constraints-h (gsh/default-constraints-h moved-shape)
                                      :constraints-v (gsh/default-constraints-v moved-shape))))
                           {:ignore-touched true})

        ;; Fix the sizing when moving a shape
        (pcb/update-shapes parents
                           (fn [parent]
                             (if (ctl/flex-layout? parent)
                               (cond-> parent
                                 (ctl/change-h-sizing? (:id parent) objects (:shapes parent))
                                 (assoc :layout-item-h-sizing :fix)

                                 (ctl/change-v-sizing? (:id parent) objects (:shapes parent))
                                 (assoc :layout-item-v-sizing :fix))
                               parent)))

        ;; Update grid layout
        (cond-> (ctl/grid-layout? objects parent-id)
          (pcb/update-shapes [parent-id] #(ctl/add-children-to-index % ids objects to-index)))

        (pcb/update-shapes parents
                           (fn [parent]
                             (cond-> parent
                               (ctl/grid-layout? parent)
                               (ctl/assign-cells))))

        (pcb/reorder-grid-children parents)

        ;; If parent locked, lock the added shapes
        (cond-> (:blocked parent)
          (pcb/update-shapes ordered-indexes #(assoc % :blocked true)))

        ;; Resize parent containers that need to
        (pcb/resize-parents parents))))

(defn relocate-shapes
  [ids parent-id to-index & [ignore-parents?]]
  (dm/assert! (every? uuid? ids))
  (dm/assert! (uuid? parent-id))
  (dm/assert! (number? to-index))

  (ptk/reify ::relocate-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)

            ;; Ignore any shape whose parent is also intended to be moved
            ids      (cfh/clean-loops objects ids)

            ;; If we try to move a parent into a child we remove it
            ids      (filter #(not (cfh/is-parent? objects parent-id %)) ids)

            all-parents (into #{parent-id} (map #(cfh/get-parent-id objects %)) ids)
            parents  (if ignore-parents? #{parent-id} all-parents)

            groups-to-delete
            (loop [current-id  (first parents)
                   to-check    (rest parents)
                   removed-id? (set ids)
                   result #{}]

              (if-not current-id
                ;; Base case, no next element
                result

                (let [group (get objects current-id)]
                  (if (and (not= :frame (:type group))
                           (not= current-id parent-id)
                           (empty? (remove removed-id? (:shapes group))))

                    ;; Adds group to the remove and check its parent
                    (let [to-check (concat to-check [(cfh/get-parent-id objects current-id)])]
                      (recur (first to-check)
                             (rest to-check)
                             (conj removed-id? current-id)
                             (conj result current-id)))

                    ;; otherwise recur
                    (recur (first to-check)
                           (rest to-check)
                           removed-id?
                           result)))))

            groups-to-unmask
            (reduce (fn [group-ids id]
                      ;; When a masked group loses its mask shape, because it's
                      ;; moved outside the group, the mask condition must be
                      ;; removed, and it must be converted to a normal group.
                      (let [obj (get objects id)
                            parent (get objects (:parent-id obj))]
                        (if (and (:masked-group parent)
                                 (= id (first (:shapes parent)))
                                 (not= (:id parent) parent-id))
                          (conj group-ids (:id parent))
                          group-ids)))
                    #{}
                    ids)

            ;; TODO: Probably implementing this using loop/recur will
            ;; be more efficient than using reduce and continuous data
            ;; desturcturing.

            ;; Sets the correct components metadata for the moved shapes
            ;; `shapes-to-detach` Detach from a component instance a shape that was inside a component and is moved outside
            ;; `shapes-to-deroot` Removes the root flag from a component instance moved inside another component
            ;; `shapes-to-reroot` Adds a root flag when a nested component instance is moved outside
            [shapes-to-detach shapes-to-deroot shapes-to-reroot]
            (reduce (fn [[shapes-to-detach shapes-to-deroot shapes-to-reroot] id]
                      (let [shape                  (get objects id)
                            parent                 (get objects parent-id)
                            component-shape        (ctn/get-component-shape objects shape)
                            component-shape-parent (ctn/get-component-shape objects parent)
                            root-parent            (ctn/get-instance-root objects parent)

                            detach? (and (ctk/in-component-copy-not-head? shape)
                                         (not= (:id component-shape)
                                               (:id component-shape-parent)))
                            deroot? (and (ctk/instance-root? shape)
                                         root-parent)
                            reroot? (and (ctk/subinstance-head? shape)
                                         (not component-shape-parent))

                            ids-to-detach (when detach?
                                            (cons id (cfh/get-children-ids objects id)))]

                        [(cond-> shapes-to-detach detach? (into ids-to-detach))
                         (cond-> shapes-to-deroot deroot? (conj id))
                         (cond-> shapes-to-reroot reroot? (conj id))]))
                    [[] [] []]
                    ids)

            changes (relocate-shapes-changes it
                                             objects
                                             parents
                                             parent-id
                                             page-id
                                             to-index
                                             ids
                                             groups-to-delete
                                             groups-to-unmask
                                             shapes-to-detach
                                             shapes-to-reroot
                                             shapes-to-deroot
                                             ids)
            undo-id (js/Symbol)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwco/expand-collapse parent-id)
               (ptk/data-event :layout/update (concat all-parents ids))
               (dwu/commit-undo-transaction undo-id))))))

(defn relocate-selected-shapes
  [parent-id to-index]
  (ptk/reify ::relocate-selected-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (relocate-shapes selected parent-id to-index))))))

(defn start-editing-selected
  []
  (ptk/reify ::start-editing-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)
            objects (wsh/lookup-page-objects state)]

        (if (> (count selected) 1)
          (let [shapes-to-select
                (->> selected
                     (reduce
                      (fn [result shape-id]
                        (let [children (dm/get-in objects [shape-id :shapes])]
                          (if (empty? children)
                            (conj result shape-id)
                            (into result children))))
                      (d/ordered-set)))]
            (rx/of (dws/select-shapes shapes-to-select)))

          (let [{:keys [id type shapes]} (get objects (first selected))]
            (case type
              :text
              (rx/of (dwe/start-edition-mode id))

              (:group :bool :frame)
              (let [shapes-ids (into (d/ordered-set)
                                     (remove #(dm/get-in objects [% :hidden]))
                                     shapes)]
                (rx/of (dws/select-shapes shapes-ids)))

              :svg-raw
              nil

              (rx/of (dwe/start-edition-mode id)
                     (dwdp/start-path-edit id)))))))))

(defn select-parent-layer
  []
  (ptk/reify ::select-parent-layer
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)
            objects (wsh/lookup-page-objects state)
            shapes-to-select
            (->> selected
                 (reduce
                   (fn [result shape-id]
                     (let [parent-id (dm/get-in objects [shape-id :parent-id])]
                       (if (and (some? parent-id)  (not= parent-id uuid/zero))
                         (conj result parent-id)
                         (conj result shape-id))))
                   (d/ordered-set)))]
        (rx/of (dws/select-shapes shapes-to-select))))))

;; --- Change Page Order (D&D Ordering)

(defn relocate-page
  [id index]
  (ptk/reify ::relocate-page
    ptk/WatchEvent
    (watch [it state _]
      (let [prev-index (-> (get-in state [:workspace-data :pages])
                           (d/index-of id))
            changes    (-> (pcb/empty-changes it)
                           (pcb/move-page id index prev-index))]
        (rx/of (dch/commit-changes changes))))))

;; --- Shape / Selection Alignment and Distribution

(defn can-align? [selected objects]
  (cond
    (empty? selected) false
    (> (count selected) 1) true
    :else
    (not= uuid/zero (:parent-id (get objects (first selected))))))

(defn align-object-to-parent
  [objects object-id axis]
  (let [object     (get objects object-id)
        parent-id  (:parent-id (get objects object-id))
        parent     (get objects parent-id)]
    [(gal/align-to-rect object parent axis)]))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (gsh/shapes->rect selected-objs)]
    (map #(gal/align-to-rect % rect axis) selected-objs)))

(defn align-objects
  [axis]
  (dm/assert!
   "expected valid align axis value"
   (contains? gal/valid-align-axis axis))

  (ptk/reify ::align-objects
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects  (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state)
            moved    (if (= 1 (count selected))
                       (align-object-to-parent objects (first selected) axis)
                       (align-objects-list objects selected axis))
            undo-id (js/Symbol)]
        (when (can-align? selected objects)
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dwt/position-shapes moved)
                 (ptk/data-event :layout/update selected)
                 (dwu/commit-undo-transaction undo-id)))))))

(defn can-distribute? [selected]
  (cond
    (empty? selected) false
    (< (count selected) 3) false
    :else true))

(defn distribute-objects
  [axis]
  (dm/assert!
   "expected valid distribute axis value"
   (contains? gal/valid-dist-axis axis))

  (ptk/reify ::distribute-objects
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            selected  (wsh/lookup-selected state)
            moved     (-> (map #(get objects %) selected)
                          (gal/distribute-space axis))
            undo-id  (js/Symbol)]
        (when (can-distribute? selected)
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dwt/position-shapes moved)
                 (ptk/data-event :layout/update selected)
                 (dwu/commit-undo-transaction undo-id)))))))

;; --- Shape Proportions

(defn set-shape-proportion-lock
  [id lock]
  (ptk/reify ::set-shape-proportion-lock
    ptk/WatchEvent
    (watch [_ _ _]
      (letfn [(assign-proportions [shape]
                (if-not lock
                  (assoc shape :proportion-lock false)
                  (-> (assoc shape :proportion-lock true)
                      (gpp/assign-proportions))))]
        (rx/of (dch/update-shapes [id] assign-proportions))))))

(defn toggle-proportion-lock
  []
  (ptk/reify ::toggle-proportion-lock
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id       (:current-page-id state)
            objects       (wsh/lookup-page-objects state page-id)
            selected      (wsh/lookup-selected state)
            selected-obj  (-> (map #(get objects %) selected))
            multi         (attrs/get-attrs-multi selected-obj [:proportion-lock])
            multi?        (= :multiple (:proportion-lock multi))]
        (if multi?
          (rx/of (dch/update-shapes selected #(assoc % :proportion-lock true)))
          (rx/of (dch/update-shapes selected #(update % :proportion-lock not))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workspace-focus-lost
  []
  (ptk/reify ::workspace-focus-lost
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :show-distances?] false))))

(defn navigate-to-project
  [project-id]
  (ptk/reify ::navigate-to-project
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-ids (get-in state [:projects project-id :pages])
            params {:project project-id :page (first page-ids)}]
        (rx/of (rt/nav :workspace/page params))))))

(defn go-to-page
  ([]
   (ptk/reify ::go-to-page
     ptk/WatchEvent
     (watch [_ state _]
       (let [project-id (:current-project-id state)
             file-id    (:current-file-id state)
             page-id    (get-in state [:workspace-data :pages 0])

             pparams    {:file-id file-id :project-id project-id}
             qparams    {:page-id page-id}]
         (rx/of (rt/nav' :workspace pparams qparams))))))
  ([page-id]
   (dm/assert! (uuid? page-id))
   (ptk/reify ::go-to-page-2
     ptk/WatchEvent
     (watch [_ state _]
       (let [project-id (:current-project-id state)
             file-id    (:current-file-id state)
             pparams    {:file-id file-id :project-id project-id}
             qparams    {:page-id page-id}]
         (rx/of (rt/nav :workspace pparams qparams)))))))

(defn go-to-layout
  [layout]
  (ptk/reify ::go-to-layout
    IDeref
    (-deref [_] {:layout layout})

    ptk/WatchEvent
    (watch [_ state _]
      (let [project-id (get-in state [:workspace-project :id])
            file-id    (get-in state [:workspace-file :id])
            page-id    (get state :current-page-id)
            pparams    {:file-id file-id :project-id project-id}
            qparams    {:page-id page-id :layout (name layout)}]
        (rx/of (rt/nav :workspace pparams qparams))))))

(defn navigate-to-library
  "Open a new tab, and navigate to the workspace with the provided file"
  [library-id]
  (ptk/reify ::navigate-to-file
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file (dm/get-in state [:workspace-libraries library-id])]
        (let [params {:rname :workspace
                      :path-params {:project-id (:project-id file)
                                    :file-id (:id file)}
                      :query-params {:page-id (dm/get-in file [:data :pages 0])}}]
          (rx/of (rt/nav-new-window* params)))))))

(defn set-assets-section-open
  [file-id section open?]
  (ptk/reify ::set-assets-section-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-assets :open-status file-id section] open?))))

(defn set-assets-group-open
  [file-id section path open?]
  (ptk/reify ::set-assets-group-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-assets :open-status file-id :groups section path] open?))))

(defn- check-in-asset
  [items element]
  (let [items (or items #{})]
    (if (contains? items element)
      (disj items element)
      (conj items element))))

(defn toggle-selected-assets
  [file-id asset-id type]
  (ptk/reify ::toggle-selected-assets
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-assets :selected file-id type] check-in-asset asset-id))))

(defn select-single-asset
  [file-id asset-id type]
  (ptk/reify ::select-single-asset
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-assets :selected file-id type] #{asset-id}))))

(defn select-assets
  [file-id assets-ids type]
  (ptk/reify ::select-assets
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-assets :selected file-id type] (into #{} assets-ids)))))

(defn unselect-all-assets
  ([] (unselect-all-assets nil))
  ([file-id]
   (ptk/reify ::unselect-all-assets
     ptk/UpdateEvent
     (update [_ state]
       (if file-id
         (update-in state [:workspace-assets :selected] dissoc file-id)
         (update state :workspace-assets dissoc :selected))))))

(defn go-to-main-instance
  [file-id component-id]
  (dm/assert!
   "expected uuid type for `file-id` parameter (nilable)"
   (or (nil? file-id)
       (uuid? file-id)))

  (dm/assert!
   "expected uuid type for `component-id` parameter"
   (uuid? component-id))

  (ptk/reify ::go-to-main-instance
    ptk/WatchEvent
    (watch [_ state stream]
      (let [current-file-id    (:current-file-id state)
            current-page-id    (:current-page-id state)
            current-project-id (:current-project-id state)
            file-id            (or file-id current-file-id)

            select-and-zoom
            (fn [shape-id]
              (rx/of (dws/select-shapes (d/ordered-set shape-id))
                     dwz/zoom-to-selected-shape))

            redirect-to-page
            (fn [page-id shape-id]
              (rx/concat
               (rx/of (go-to-page page-id))
               (->> stream
                    (rx/filter (ptk/type? ::initialize-page))
                    (rx/take 1)
                    (rx/observe-on :async))
               (select-and-zoom shape-id)))

            redirect-to-file
            (fn [file-id page-id]
              (let [pparams {:file-id file-id :project-id current-project-id}
                    qparams {:page-id page-id}]
                (rx/merge
                 (rx/of (rt/nav :workspace pparams qparams))
                 (->> stream
                      (rx/filter (ptk/type? ::workspace-initialized))
                      (rx/map meta)
                      (rx/filter #(= file-id (:file-id %)))
                      (rx/take 1)
                      (rx/observe-on :async)
                      (rx/map #(go-to-main-instance file-id component-id))))))]

        (if (= file-id current-file-id)
          (let [component (dm/get-in state [:workspace-data :components component-id])
                page-id   (:main-instance-page component)
                shape-id (:main-instance-id component)]
            (when (some? page-id)
              (if (= page-id current-page-id)
                (select-and-zoom shape-id)
                (redirect-to-page page-id shape-id))))

          (let [component (dm/get-in state [:workspace-libraries file-id :data :components component-id])]
            (some->> (:main-instance-page component)
                     (redirect-to-file file-id))))))))

(defn go-to-component
  [component-id]
  (ptk/reify ::go-to-component
    IDeref
    (-deref [_] {:layout :assets})

    ptk/WatchEvent
    (watch [_ state _]
      (let [components-v2 (features/active-feature? state "components/v2")]
        (if components-v2
          (rx/of (go-to-main-instance nil component-id))
          (let [project-id    (get-in state [:workspace-project :id])
                file-id       (get-in state [:workspace-file :id])
                page-id       (get state :current-page-id)
                pparams       {:file-id file-id :project-id project-id}
                qparams       {:page-id page-id :layout :assets}]
            (rx/of (rt/nav :workspace pparams qparams)
                   (set-assets-section-open file-id :library true)
                   (set-assets-section-open file-id :components true)
                   (select-single-asset file-id component-id :components))))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [components-v2 (features/active-feature? state "components/v2")
            wrapper-id    (str "component-shape-id-" component-id)]
        (when-not components-v2
          (tm/schedule-on-idle #(dom/scroll-into-view-if-needed! (dom/get-element wrapper-id))))))))

(defn show-component-in-assets
  [component-id]
  (ptk/reify ::show-component-in-assets
    ptk/WatchEvent
    (watch [_ state _]
      (let [project-id     (get-in state [:workspace-project :id])
            file-id        (get-in state [:workspace-file :id])
            page-id        (get state :current-page-id)
            pparams        {:file-id file-id :project-id project-id}
            qparams        {:page-id page-id :layout :assets}
            component-path (cfh/split-path (get-in state [:workspace-data :components component-id :path]))
            paths          (map (fn [i] (cfh/join-path (take (inc i) component-path))) (range (count component-path)))]
        (rx/concat
         (rx/from (map #(set-assets-group-open file-id :components % true) paths))
         (rx/of (rt/nav :workspace pparams qparams)
                (set-assets-section-open file-id :library true)
                (set-assets-section-open file-id :components true)
                (select-single-asset file-id component-id :components)))))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [wrapper-id (str "component-shape-id-" component-id)]
        (tm/schedule-on-idle #(dom/scroll-into-view-if-needed! (dom/get-element wrapper-id)))))))

(def go-to-file
  (ptk/reify ::go-to-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [id project-id data] :as file} (:workspace-file state)
            page-id (get-in data [:pages 0])
            pparams {:project-id project-id :file-id id}
            qparams {:page-id page-id}]
        (rx/of (rt/nav :workspace pparams qparams))))))

(defn go-to-viewer
  ([] (go-to-viewer {}))
  ([{:keys [file-id page-id section frame-id]}]
   (ptk/reify ::go-to-viewer
     ptk/WatchEvent
     (watch [_ state _]
       (let [{:keys [current-file-id current-page-id]} state
             pparams {:file-id (or file-id current-file-id)}
             qparams (cond-> {:page-id (or page-id current-page-id)}
                       (some? section)
                       (assoc :section section)
                       (some? frame-id)
                       (assoc :frame-id frame-id))]
         (rx/of ::dwp/force-persist
                (rt/nav-new-window* {:rname :viewer
                                     :path-params pparams
                                     :query-params qparams
                                     :name (str "viewer-" (:file-id pparams))})))))))

(defn go-to-dashboard
  ([] (go-to-dashboard nil))
  ([{:keys [team-id]}]
   (ptk/reify ::go-to-dashboard
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [team-id (or team-id (:current-team-id state))]
         (rx/of ::dwp/force-persist
                (rt/nav :dashboard-projects {:team-id team-id})))))))

(defn go-to-dashboard-fonts
  []
  (ptk/reify ::go-to-dashboard-fonts
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of ::dwp/force-persist
               (rt/nav :dashboard-fonts {:team-id team-id}))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-context-menu
  [{:keys [position] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] params))))

(defn show-shape-context-menu
  [{:keys [shape] :as params}]
  (ptk/reify ::show-shape-context-menu
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected        (wsh/lookup-selected state)
            objects         (wsh/lookup-page-objects state)
            all-selected    (into [] (mapcat #(cfh/get-children-with-self objects %)) selected)
            head            (get objects (first selected))

            not-group-like? (and (= (count selected) 1)
                                 (not (contains? #{:group :bool} (:type head))))

            no-bool-shapes? (->> all-selected (some (comp #{:frame :text} :type)))]

          (if (and (some? shape) (not (contains? selected (:id shape))))
            (rx/concat
              (rx/of (dws/select-shape (:id shape)))
              (rx/of (show-shape-context-menu params)))
            (rx/of (show-context-menu
                     (-> params
                         (assoc
                           :kind :shape
                           :disable-booleans? (or no-bool-shapes? not-group-like?)
                           :disable-flatten? no-bool-shapes?
                           :selected (conj selected (:id shape)))))))))))

(defn show-page-item-context-menu
  [{:keys [position page] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-page-item-context-menu
    ptk/WatchEvent
    (watch [_ _ _]
           (rx/of (show-context-menu
                   (-> params (assoc :kind :page :selected (:id page))))))))

(def hide-context-menu
  (ptk/reify ::hide-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] nil))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clipboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn copy-selected
  []
  (letfn [(sort-selected [state data]
            (let [selected (wsh/lookup-selected state)
                  objects  (wsh/lookup-page-objects state)

                  ;; Narrow the objects map so it contains only relevant data for
                  ;; selected and its parents
                  objects  (cfh/selected-subtree objects selected)
                  selected (->> (ctst/sort-z-index objects selected)
                                (reverse)
                                (into (d/ordered-set)))]

              (assoc data :selected selected)))

          (fetch-image [entry]
            (let [url (cf/resolve-file-media entry)]
              (->> (http/send! {:method :get
                                :uri url
                                :response-type :blob})
                   (rx/map :body)
                   (rx/mapcat wapi/read-file-as-data-url)
                   (rx/map #(assoc entry :data %)))))

          ;; Prepare the shape object. Mainly needed for image shapes
          ;; for retrieve the image data and convert it to the
          ;; data-url.
          (prepare-object [objects parent-frame-id obj]
            (let [obj     (maybe-translate obj objects parent-frame-id)
                  ;; Texts can have different fills for pieces of the text
                  imgdata (concat
                           (->> (or (:position-data obj) [obj])
                                (mapcat :fills)
                                (keep :fill-image))
                           (->> (:strokes obj)
                                (keep :stroke-image))
                           (when (cfh/image-shape? obj)
                             [(:metadata obj)]))]

              (if (seq imgdata)
                (->> (rx/from imgdata)
                     (rx/mapcat fetch-image)
                     (rx/reduce conj [])
                     (rx/map (fn [images]
                               (assoc obj ::images images))))
                (rx/of obj))))

          ;; Collects all the items together and split images into a
          ;; separated data structure for a more easy paste process.
          (collect-data [result {:keys [id ::images] :as item}]
            (cond-> result
              :always
              (update :objects assoc id (dissoc item ::images))

              (some? images)
              (update :images into images)))

          (maybe-translate [shape objects parent-frame-id]
            (if (= parent-frame-id uuid/zero)
              shape
              (let [frame (get objects parent-frame-id)]
                (gsh/translate-to-frame shape frame))))

          (on-copy-error [error]
            (js/console.error "clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state _]
        (let [text (wapi/get-current-selected-text)]
          (if-not (str/empty? text)
            (try
              (wapi/write-to-clipboard text)
              (catch :default e
                (on-copy-error e)))

            (let [objects  (wsh/lookup-page-objects state)
                  selected (->> (wsh/lookup-selected state)
                                (cfh/clean-loops objects))
                  features (features/get-team-enabled-features state)

                  file-id  (:current-file-id state)
                  frame-id (cfh/common-parent-frame objects selected)
                  version  (dm/get-in state [:workspace-data :version])

                  initial  {:type :copied-shapes
                            :features features
                            :version version
                            :file-id file-id
                            :selected selected
                            :objects {}
                            :images #{}}

                  shapes   (->> (cfh/selected-with-children objects selected)
                                (keep (d/getf objects)))]

              (->> (rx/from shapes)
                   (rx/merge-map (partial prepare-object objects frame-id))
                   (rx/reduce collect-data initial)
                   (rx/map (partial sort-selected state))
                   (rx/map #(t/encode-str % {:type :json-verbose}))
                   (rx/map wapi/write-to-clipboard)
                   (rx/catch on-copy-error)
                   (rx/ignore)))))))))

(declare ^:private paste-transit)
(declare ^:private paste-text)
(declare ^:private paste-image)
(declare ^:private paste-svg-text)
(declare ^:private paste-shapes)

(defn paste-from-clipboard
  "Perform a `paste` operation using the Clipboard API."
  []
  (letfn [(decode-entry [entry]
            (try
              [:transit (t/decode-str entry)]
              (catch :default _cause
                [:text entry])))

          (process-entry [[type data]]
            (case type
              :text
              (if (str/empty? data)
                (rx/empty)
                (rx/of (paste-text data)))

              :transit
              (rx/of (paste-transit data))))

          (on-error [cause]
            (let [data (ex-data cause)]
              (if (:not-implemented data)
                (rx/of (msg/warn (tr "errors.clipboard-not-implemented")))
                (js/console.error "Clipboard error:" cause))
              (rx/empty)))]

    (ptk/reify ::paste-from-clipboard
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rx/concat
              (->> (wapi/read-from-clipboard)
                   (rx/map decode-entry)
                   (rx/mapcat process-entry))
              (->> (wapi/read-image-from-clipboard)
                   (rx/map paste-image)))
             (rx/take 1)
             (rx/catch on-error))))))


(defn paste-from-event
  "Perform a `paste` operation from user emmited event."
  [event in-viewport?]
  (ptk/reify ::paste-from-event
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects     (wsh/lookup-page-objects state)
            edit-id     (dm/get-in state [:workspace-local :edition])
            is-editing? (and edit-id (= :text (get-in objects [edit-id :type])))]

        ;; Some paste events can be fired while we're editing a text
        ;; we forbid that scenario so the default behaviour is executed
        (if is-editing?
          (rx/empty)
          (let [pdata        (wapi/read-from-paste-event event)
                image-data   (some-> pdata wapi/extract-images)
                text-data    (some-> pdata wapi/extract-text)
                transit-data (ex/ignoring (some-> text-data t/decode-str))]
            (cond
              (and (string? text-data)
                   (str/includes? text-data "<svg "))
              (rx/of (paste-svg-text text-data))

              (seq image-data)
              (->> (rx/from image-data)
                   (rx/map paste-image))

              (coll? transit-data)
              (rx/of (paste-transit (assoc transit-data :in-viewport in-viewport?)))

              (string? text-data)
              (rx/of (paste-text text-data))

              :else
              (rx/empty))))))))

(defn selected-frame? [state]
  (let [selected (wsh/lookup-selected state)
        objects  (wsh/lookup-page-objects state)]

    (and (= 1 (count selected))
         (= :frame (get-in objects [(first selected) :type])))))

(defn get-tree-root-shapes [tree]
  ;; This fn gets a map of shapes and finds what shapes are parent of the rest
  (let [shapes-in-tree (vals tree)
        shape-ids (keys tree)
        parent-ids (set (map #(:parent-id %) shapes-in-tree))]
    (->> shape-ids
         (filter #(contains? parent-ids %)))))

(defn any-same-frame-from-selected? [state frame-ids]
  (let [selected (first (wsh/lookup-selected state))]
    (< 0 (count (filter #(= % selected) frame-ids)))))

(defn frame-same-size?
  [paste-obj frame-obj]
  (and
   (= (:heigth (:selrect (first (vals paste-obj))))
      (:heigth (:selrect frame-obj)))
   (= (:width (:selrect (first (vals paste-obj))))
      (:width (:selrect frame-obj)))))

(defn- check-paste-features!
  "Function used for check feature compability between currently
  enabled features set on the application with the provided featured
  set by the paste data."
  [enabled-features paste-features]
  (let [not-supported (-> enabled-features
                          (set/difference paste-features)
                          ;; NOTE: we don't want to raise a feature-mismatch
                          ;; exception for features which don't require an
                          ;; explicit file migration process or has no real
                          ;; effect on file data structure
                          (set/difference cfeat/no-migration-features))]

    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :missing-features-in-paste-content
                :feature (first not-supported)
                :hint (str/ffmt "expected features '%' not present in pasted content"
                                (str/join "," not-supported)))))

  (let [not-supported (set/difference enabled-features cfeat/supported-features)]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :paste-feature-not-supported
                :feature (first not-supported)
                :hint (str/ffmt "features '%' not supported in the application"
                                (str/join "," not-supported)))))

  (let [not-supported (-> paste-features
                          (set/difference enabled-features)
                          (set/difference cfeat/backend-only-features)
                          (set/difference cfeat/frontend-only-features))]

    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :paste-feature-not-enabled
                :feature (first not-supported)
                :hint (str/ffmt "paste features '%' not enabled on the application"
                                (str/join "," not-supported))))))

(def ^:private schema:paste-data
  (sm/define
    [:map {:title "paste-data"}
     [:type [:= :copied-shapes]]
     [:features ::sm/set-of-strings]
     [:version :int]
     [:file-id ::sm/uuid]
     [:selected ::sm/set-of-uuid]
     [:objects
      [:map-of ::sm/uuid :map]]
     [:images [:set :map]]
     [:position {:optional true} ::gpt/point]]))

(defn- paste-transit
  [{:keys [images] :as pdata}]

  (letfn [(upload-media [file-id imgpart]
            (->> (http/send! {:uri (:data imgpart)
                              :response-type :blob
                              :method :get})
                 (rx/map :body)
                 (rx/map
                  (fn [blob]
                    {:name (:name imgpart)
                     :file-id file-id
                     :content blob
                     :is-local true}))
                 (rx/mapcat (partial rp/cmd! :upload-file-media-object))
                 (rx/map #(assoc % :prev-id (:id imgpart)))))]

    (ptk/reify ::paste-transit
      ptk/WatchEvent
      (watch [_ state _]
        (let [file-id (:current-file-id state)
              features (features/get-team-enabled-features state)]

          (sm/validate! schema:paste-data pdata
                        {:hint "invalid paste data"
                         :code :invalid-paste-data})

          (check-paste-features! features (:features pdata))
          (if (= file-id (:file-id pdata))
            (let [pdata (assoc pdata :images [])]
              (rx/of (paste-shapes pdata)))
            (->> (rx/from images)
                 (rx/merge-map (partial upload-media file-id))
                 (rx/reduce conj [])
                 (rx/map #(assoc pdata :images %))
                 (rx/map paste-shapes))))))))

(defn paste-shapes
  [{in-viewport? :in-viewport :as pdata}]
  (letfn [(translate-media [mdata media-idx attr-path]
            (let [id   (get-in mdata attr-path)
                  mobj (get media-idx id)]
              (if mobj
                (update-in mdata attr-path (fn [value]
                                             (-> value
                                                 (assoc :id (:id mobj))
                                                 (assoc :path (:path mobj)))))
                mdata)))

          (add-obj? [chg]
            (= (:type chg) :add-obj))

          ;; Analyze the rchange and replace staled media and
          ;; references to the new uploaded media-objects.
          (process-rchange [media-idx change]
            (let [;; Texts can have different fills for pieces of the text
                  tr-fill-xf    (map #(translate-media % media-idx [:fill-image :id]))
                  tr-stroke-xf  (map #(translate-media % media-idx [:stroke-image :id]))]

              (if (add-obj? change)
                (update change :obj (fn [obj]
                                      (-> obj
                                          (update :fills #(into [] tr-fill-xf %))
                                          (update :strokes #(into [] tr-stroke-xf %))
                                          (d/update-when :metadata translate-media media-idx [:id])
                                          (d/update-when :content
                                                         (fn [content]
                                                           (txt/xform-nodes tr-fill-xf content)))
                                          (d/update-when :position-data
                                                         (fn [position-data]
                                                           (mapv (fn [pos-data]
                                                                   (update pos-data :fills #(into [] tr-fill-xf %)))
                                                                 position-data))))))
                change)))

          (calculate-paste-position [state pobjects selected position]
            (let [page-objects         (wsh/lookup-page-objects state)
                  selected-objs        (map (d/getf pobjects) selected)
                  first-selected-obj   (first selected-objs)
                  page-selected        (wsh/lookup-selected state)
                  wrapper              (gsh/shapes->rect selected-objs)
                  orig-pos             (gpt/point (:x1 wrapper) (:y1 wrapper))
                  frame-id             (first page-selected)
                  frame-object         (get page-objects frame-id)
                  base                 (cfh/get-base-shape page-objects page-selected)
                  index                (cfh/get-position-on-parent page-objects (:id base))
                  tree-root            (get-tree-root-shapes pobjects)
                  only-one-root-shape? (and
                                        (< 1 (count pobjects))
                                        (= 1 (count tree-root)))
                  all-objects           (merge page-objects pobjects)
                  comps-nesting-loop?   (not (->> (keys pobjects)
                                                  (map #(cfh/components-nesting-loop? all-objects % (:id base)))
                                                  (every? nil?)))]

              (cond
                comps-nesting-loop?
                ;; Avoid placing a shape as a direct or indirect child of itself,
                ;; or inside its main component if it's in a copy.
                [uuid/zero uuid/zero (gpt/subtract position orig-pos)]

                (selected-frame? state)

                (if (or (any-same-frame-from-selected? state (keys pobjects))
                        (and only-one-root-shape?
                             (frame-same-size? pobjects (first tree-root))))
                  ;; Paste next to selected frame, if selected is itself or of the same size as the copied
                  (let [selected-frame-obj (get page-objects (first page-selected))
                        parent-id          (:parent-id base)
                        paste-x            (+ (:width selected-frame-obj) (:x selected-frame-obj) 50)
                        paste-y            (:y selected-frame-obj)
                        delta              (gpt/subtract (gpt/point paste-x paste-y) orig-pos)]

                    [(:frame-id base) parent-id delta index])

                  ;; Paste inside selected frame otherwise
                  (let [selected-frame-obj (get page-objects (first page-selected))
                        origin-frame-id (:frame-id first-selected-obj)
                        origin-frame-object (get page-objects origin-frame-id)

                        margin-x (-> (- (:width origin-frame-object) (+ (:x wrapper) (:width wrapper)))
                                     (min (- (:width frame-object) (:width wrapper))))

                        margin-y  (-> (- (:height origin-frame-object) (+ (:y wrapper) (:height wrapper)))
                                      (min (- (:height frame-object) (:height wrapper))))

                        ;; Pasted objects mustn't exceed the selected frame x limit
                        paste-x (if (> (+ (:width wrapper) (:x1 wrapper)) (:width frame-object))
                                  (+ (- (:x frame-object) (:x orig-pos)) (- (:width frame-object) (:width wrapper) margin-x))
                                  (:x frame-object))

                        ;; Pasted objects mustn't exceed the selected frame y limit
                        paste-y (if (> (+ (:height wrapper) (:y1 wrapper)) (:height frame-object))
                                  (+ (- (:y frame-object) (:y orig-pos)) (- (:height frame-object) (:height wrapper) margin-y))
                                  (:y frame-object))

                        delta (if (= origin-frame-id uuid/zero)
                                ;; When the origin isn't in a frame the result is pasted in the center.
                                (gpt/subtract (gsh/shape->center frame-object) (grc/rect->center wrapper))
                                ;; When pasting from one frame to another frame the object
                                ;; position must be limited to container boundaries. If
                                ;; the pasted object doesn't fit we try to:
                                ;;
                                ;; - Align it to the limits on the x and y axis
                                ;; - Respect the distance of the object to the right and bottom in the original frame
                                (gpt/point paste-x paste-y))]
                    [frame-id frame-id delta (dec (count (:shapes selected-frame-obj )))]))

                (empty? page-selected)
                (let [frame-id (ctst/top-nested-frame page-objects position)
                      delta    (gpt/subtract position orig-pos)]
                  [frame-id frame-id delta])

                :else
                (let [frame-id  (:frame-id base)
                      parent-id (:parent-id base)
                      delta     (if in-viewport?
                                  (gpt/subtract position orig-pos)
                                  (gpt/subtract (gpt/point (:selrect base)) orig-pos))]
                  [frame-id parent-id delta index]))))

          ;; Change the indexes of the pasted shapes
          (change-add-obj-index [objects selected index change]
            (let [;; if there is no current element selected, we want
                  ;; the first (inc index) to be 0
                  index (d/nilv index -1)
                  set-index (fn [[result index] id]
                              [(assoc result id index) (inc index)])

                  ;; FIXME: optimize ???
                  map-ids
                  (->> selected
                       (map #(get-in objects [% :id]))
                       (reduce set-index [{} (inc index)])
                       first)]

              (if (and (add-obj? change)
                       (contains? map-ids (:old-id change)))
                (assoc change :index (get map-ids (:old-id change)))
                change)))

          (process-shape [file-id frame-id parent-id shape]
            (cond-> shape
              :always
              (assoc :frame-id frame-id :parent-id parent-id)

              (and (or (cfh/group-shape? shape)
                       (cfh/bool-shape? shape))
                   (nil? (:shapes shape)))
              (assoc :shapes [])

              (cfh/text-shape? shape)
              (ctt/remove-external-typographies file-id)))]

    (ptk/reify ::paste-shapes
      ptk/WatchEvent
      (watch [it state _]
        (let [
              file-id     (:current-file-id state)
              page        (wsh/lookup-page state)

              media-idx   (->> (:media pdata)
                               (d/index-by :prev-id))

              selected    (:selected pdata)
              objects     (:objects pdata)

              position    (deref ms/mouse-position)

              ;; Calculate position for the pasted elements
              [frame-id
               parent-id
               delta
               index]     (calculate-paste-position state objects selected position)

              ;; We don't want to change the structure of component
              ;; copies If the parent-id or the frame-id are
              ;; component-copies, we need to get the first not copy
              ;; parent
              parent-id   (:id (ctn/get-first-not-copy-parent (:objects page) parent-id))
              frame-id    (:id (ctn/get-first-not-copy-parent (:objects page) frame-id))

              objects     (update-vals objects (partial process-shape file-id frame-id parent-id))
              all-objects (merge (:objects page) objects)

              libraries   (wsh/get-libraries state)
              ldata       (wsh/get-file state file-id)

              drop-cell   (when (ctl/grid-layout? all-objects parent-id)
                            (gslg/get-drop-cell frame-id all-objects position))

              changes     (-> (dws/prepare-duplicate-changes all-objects page selected delta it libraries ldata file-id)
                              (pcb/amend-changes (partial process-rchange media-idx))
                              (pcb/amend-changes (partial change-add-obj-index objects selected index)))

              ;; Adds a resize-parents operation so the groups are
              ;; updated. We add all the new objects
              changes     (->> (:redo-changes changes)
                               (filter add-obj?)
                               (map :id)
                               (pcb/resize-parents changes))

              selected    (into (d/ordered-set)
                                (comp
                                 (filter add-obj?)
                                 (filter #(contains? selected (:old-id %)))
                                 (map :obj)
                                 (map :id))
                                (:redo-changes changes))

              changes     (cond-> changes
                            (some? drop-cell)
                            (pcb/update-shapes [parent-id]
                                               #(ctl/add-children-to-cell % selected all-objects drop-cell)))

              undo-id     (js/Symbol)]

          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/commit-changes changes)
                 (dws/select-shapes selected)
                 (ptk/data-event :layout/update [frame-id])
                 (dwu/commit-undo-transaction undo-id)))))))

(defn as-content [text]
  (let [paragraphs (->> (str/lines text)
                        (map str/trim)
                        (mapv #(hash-map :type "paragraph"
                                         :children [(merge txt/default-text-attrs {:text %})])))]
    ;; if text is composed only by line breaks paragraphs is an empty list and should be nil
    (when (d/not-empty? paragraphs)
      {:type "root"
       :children [{:type "paragraph-set" :children paragraphs}]})))

(defn calculate-paste-position [state]
  (cond
    ;; Pasting inside a frame
    (selected-frame? state)
    (let [page-selected (wsh/lookup-selected state)
          page-objects  (wsh/lookup-page-objects state)
          frame-id (first page-selected)
          frame-object (get page-objects frame-id)]
      (gsh/shape->center frame-object))

    :else
    (deref ms/mouse-position)))

(defn- paste-text
  [text]
  (dm/assert! (string? text))
  (ptk/reify ::paste-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (uuid/next)
            width (max 8 (min (* 7 (count text)) 700))
            height 16
            {:keys [x y]} (calculate-paste-position state)

            shape {:id id
                   :type :text
                   :name (txt/generate-shape-name text)
                   :x x
                   :y y
                   :width width
                   :height height
                   :grow-type (if (> (count text) 100) :auto-height :auto-width)
                   :content (as-content text)}
            undo-id (js/Symbol)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dwsh/create-and-add-shape :text x y shape)
               (dwu/commit-undo-transaction undo-id))))))

;; TODO: why not implement it in terms of upload-media-workspace?
(defn- paste-svg-text
  [text]
  (dm/assert! (string? text))
  (ptk/reify ::paste-svg-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [position (calculate-paste-position state)
            file-id  (:current-file-id state)]
        (->> (dwm/svg->clj ["svg" text])
             (rx/map #(dwm/svg-uploaded % file-id position)))))))

(defn- paste-image
  [image]
  (ptk/reify ::paste-image
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (dm/get-in state [:workspace-file :id])
            position (calculate-paste-position state)
            params   {:file-id file-id
                      :blobs [image]
                      :position position}]
        (rx/of (dwm/upload-media-workspace params))))))

(defn toggle-distances-display [value]
  (ptk/reify ::toggle-distances-display

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :show-distances?] value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(dm/export dwi/start-edit-interaction)
(dm/export dwi/move-edit-interaction)
(dm/export dwi/finish-edit-interaction)
(dm/export dwi/start-move-overlay-pos)
(dm/export dwi/move-overlay-pos)
(dm/export dwi/finish-move-overlay-pos)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CANVAS OPTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn change-canvas-color
  [color]
  (ptk/reify ::change-canvas-color
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (wsh/lookup-page state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-page page)
                        (pcb/set-page-option :background (:color color)))]

        (rx/of (dch/commit-changes changes))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read only
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-workspace-read-only
  [read-only?]
  (ptk/reify ::set-workspace-read-only
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :read-only?] read-only?))

    ptk/WatchEvent
    (watch [_ _ _]
      (if read-only?
        (rx/of :interrupt
               (dwdc/clear-drawing)
               (remove-layout-flag :colorpalette)
               (remove-layout-flag :textpalette))
        (rx/empty)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Measurements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-paddings-selected
  [paddings-selected]
  (ptk/reify ::set-paddings-selected
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :paddings-selected] paddings-selected))))

(defn set-gap-selected
  [gap-selected]
  (ptk/reify ::set-gap-selected
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :gap-selected] gap-selected))))

(defn set-margins-selected
  [margins-selected]
  (ptk/reify ::set-margins-selected
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :margins-selected] margins-selected))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orphan Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fix-orphan-shapes
  []
  (ptk/reify ::fix-orphan-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [orphans (set (into [] (keys (wsh/find-orphan-shapes state))))]
        (rx/of (relocate-shapes orphans uuid/zero 0 true))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sitemap
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-rename-page-item
  [id]
  (ptk/reify ::start-rename-page-item
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :page-item] id))))

(defn stop-rename-page-item
  []
  (ptk/reify ::stop-rename-page-item
    ptk/UpdateEvent
    (update [_ state]
      (let [local (-> (:workspace-local state)
                      (dissoc :page-item))]
        (assoc state :workspace-local local)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Components annotations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-component-annotation
  "Update the component with the given annotation"
  [id annotation]
  (dm/assert! (uuid? id))
  (dm/assert! (or (nil? annotation) (string? annotation)))
  (ptk/reify ::update-component-annotation
    ptk/WatchEvent
    (watch [it state _]

        (let [data (get state :workspace-data)

              update-fn
              (fn [component]
                ;; NOTE: we need to ensure the component exists,
                ;; because there are small possibilities of race
                ;; conditions with component deletion.
                (when component
                  (if (nil? annotation)
                    (dissoc component :annotation)
                    (assoc component :annotation annotation))))

              changes (-> (pcb/empty-changes it)
                          (pcb/with-library-data data)
                          (pcb/update-component id update-fn))]

          (rx/of (dch/commit-changes changes))))))

(defn set-annotations-expanded
  [expanded?]
  (ptk/reify ::set-annotations-expanded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-annotations :expanded?] expanded?))))

(defn set-annotations-id-for-create
  [id]
  (ptk/reify ::set-annotations-id-for-create
    ptk/UpdateEvent
    (update [_ state]
      (if id
        (-> (assoc-in state [:workspace-annotations :id-for-create] id)
            (assoc-in [:workspace-annotations :expanded?] true))
        (d/dissoc-in state [:workspace-annotations :id-for-create])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Preview blend modes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-preview-blend-mode
  [ids blend-mode]
  (ptk/reify ::set-preview-blend-mode
    ptk/UpdateEvent
    (update [_ state]
      (reduce #(assoc-in %1 [:workspace-preview-blend %2] blend-mode) state ids))))

(defn unset-preview-blend-mode
  [ids]
  (ptk/reify ::unset-preview-blend-mode
    ptk/UpdateEvent
    (update [_ state]
      (reduce #(update %1 :workspace-preview-blend dissoc %2) state ids))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-components-norefs
  []
  (ptk/reify ::find-components-norefs
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            copies  (->> objects
                         vals
                         (filter #(and (ctk/instance-head? %) (not (ctk/main-instance? %)))))

            copies-no-ref (filter #(not (:shape-ref %)) copies)
            find-childs-no-ref (fn [acc-map item]
                                 (let [id (:id item)
                                       childs (->> (cfh/get-children objects id)
                                                   (filter #(not (:shape-ref %))))]
                                   (if (seq childs)
                                     (assoc acc-map id childs)
                                     acc-map)))
            childs-no-ref (reduce
                           find-childs-no-ref
                           {}
                           copies)]
        (js/console.log "Copies no ref" (count copies-no-ref) (clj->js copies-no-ref))
        (js/console.log "Childs no ref" (count childs-no-ref) (clj->js childs-no-ref))))))

(defn set-shape-ref
  [id shape-ref]
  (ptk/reify ::set-shape-ref
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (update-shape (uuid/uuid id) {:shape-ref (uuid/uuid shape-ref)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Transform

(dm/export dwt/start-resize)
(dm/export dwt/update-dimensions)
(dm/export dwt/change-orientation)
(dm/export dwt/start-rotate)
(dm/export dwt/increase-rotation)
(dm/export dwt/start-move-selected)
(dm/export dwt/move-selected)
(dm/export dwt/update-position)
(dm/export dwt/flip-horizontal-selected)
(dm/export dwt/flip-vertical-selected)
(dm/export dwly/set-opacity)

;; Common
(dm/export dwsh/add-shape)
(dm/export dwe/clear-edition-mode)
(dm/export dws/select-shapes)
(dm/export dwe/start-edition-mode)

;; Drawing
(dm/export dwd/select-for-drawing)

;; Selection
(dm/export dws/toggle-focus-mode)
(dm/export dws/deselect-all)
(dm/export dws/deselect-shape)
(dm/export dws/duplicate-selected)
(dm/export dws/handle-area-selection)
(dm/export dws/select-all)
(dm/export dws/select-inside-group)
(dm/export dws/select-shape)
(dm/export dws/select-prev-shape)
(dm/export dws/select-next-shape)
(dm/export dws/shift-select-shapes)

;; Highlight
(dm/export dwh/highlight-shape)
(dm/export dwh/dehighlight-shape)

;; Shape flags
(dm/export dwsh/update-shape-flags)
(dm/export dwsh/toggle-visibility-selected)
(dm/export dwsh/toggle-lock-selected)
(dm/export dwsh/toggle-file-thumbnail-selected)

;; Groups
(dm/export dwg/mask-group)
(dm/export dwg/unmask-group)
(dm/export dwg/group-selected)
(dm/export dwg/ungroup-selected)

;; Boolean
(dm/export dwb/create-bool)
(dm/export dwb/group-to-bool)
(dm/export dwb/bool-to-group)
(dm/export dwb/change-bool-type)

;; Shapes to path
(dm/export dwps/convert-selected-to-path)

;; Guides
(dm/export dwgu/update-guides)
(dm/export dwgu/remove-guide)
(dm/export dwgu/set-hover-guide)

;; Zoom
(dm/export dwz/reset-zoom)
(dm/export dwz/zoom-to-selected-shape)
(dm/export dwz/start-zooming)
(dm/export dwz/finish-zooming)
(dm/export dwz/zoom-to-fit-all)
(dm/export dwz/decrease-zoom)
(dm/export dwz/increase-zoom)
(dm/export dwz/set-zoom)

;; Thumbnails
(dm/export dwth/update-thumbnail)

;; Viewport
(dm/export dwv/initialize-viewport)
(dm/export dwv/update-viewport-position)
(dm/export dwv/update-viewport-size)
(dm/export dwv/start-panning)
(dm/export dwv/finish-panning)

;; Undo
(dm/export dwu/reinitialize-undo)
