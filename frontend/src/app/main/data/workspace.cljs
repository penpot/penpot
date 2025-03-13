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
   [app.common.logging :as log]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.schema :as sm]
   [app.common.text :as txt]
   [app.common.transit :as t]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dcmt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.plugins :as dp]
   [app.main.data.profile :as du]
   [app.main.data.project :as dpj]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.collapse :as dwco]
   [app.main.data.workspace.colors :as dwcl]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.edition :as dwe]
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
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.viewport :as dwv]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.errors]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [app.render-wasm :as wasm]
   [app.util.code-gen.style-css :as css]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :as storage]
   [app.util.text.content :as tc]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(def default-workspace-local {:zoom 1})
(log/set-level! :debug)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private workspace-initialized)
(declare ^:private fetch-libraries)
(declare ^:private libraries-fetched)

;; --- Initialize Workspace

(defn initialize-workspace-layout
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

(defn- datauri->blob-uri
  [uri]
  (->> (http/send! {:uri uri
                    :response-type :blob
                    :method :get})
       (rx/map :body)
       (rx/map (fn [blob] (wapi/create-uri blob)))))

(defn- get-file-object-thumbnails
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

(defn- resolve-file
  [file]
  (->> (fpmap/resolve-file file)
       (rx/map :data)
       (rx/mapcat
        (fn [{:keys [pages-index] :as data}]
          (->> (rx/from (seq pages-index))
               (rx/mapcat
                (fn [[id page]]
                  (let [page (update page :objects ctst/start-page-index)]
                    (->> (uw/ask! {:cmd :initialize-page-index :page page})
                         (rx/map (fn [_] [id page]))))))
               (rx/reduce conj {})
               (rx/map (fn [pages-index]
                         (let [data (assoc data :pages-index pages-index)]
                           (assoc file :data (d/removem (comp t/pointer? val) data))))))))))

(defn- libraries-fetched
  [file-id libraries]
  (ptk/reify ::libraries-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [libraries (->> libraries
                           (map (fn [l] (assoc l :library-of file-id)))
                           (d/index-by :id))]
        (update state :files merge libraries)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file         (dsh/lookup-file state)
            file-id      (get file :id)
            ignore-until (get file :ignore-sync-until)

            needs-check?
            (some #(and (> (:modified-at %) (:synced-at %))
                        (or (not ignore-until)
                            (> (:modified-at %) ignore-until)))
                  libraries)]

        (when needs-check?
          (->> (rx/of (dwl/notify-sync-file file-id))
               (rx/delay 1000)))))))

(defn- fetch-libraries
  [file-id]
  (ptk/reify ::fetch-libries
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (features/get-team-enabled-features state)]
        (->> (rp/cmd! :get-file-libraries {:file-id file-id})
             (rx/mapcat
              (fn [libraries]
                (rx/merge
                 (->> (rx/from libraries)
                      (rx/merge-map
                       (fn [{:keys [id synced-at]}]
                         (->> (rp/cmd! :get-file {:id id :features features})
                              (rx/map #(assoc % :synced-at synced-at)))))
                      (rx/merge-map resolve-file)
                      (rx/reduce conj [])
                      (rx/map (partial libraries-fetched file-id)))
                 (->> (rx/from libraries)
                      (rx/map :id)
                      (rx/mapcat (fn [file-id]
                                   (rp/cmd! :get-file-object-thumbnails {:file-id file-id :tag "component"})))
                      (rx/map dwl/library-thumbnails-fetched))))))))))

(defn- workspace-initialized
  [file-id]
  (ptk/reify ::workspace-initialized
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :workspace-undo {})
          (assoc :workspace-ready file-id)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dp/check-open-plugin)
             (fdf/fix-deleted-fonts)
             (fbs/fix-broken-shapes)))))

(defn- bundle-fetched
  [{:keys [features file thumbnails]}]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_]
      {:features features
       :file file
       :thumbnails thumbnails})

    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:id file)]
        (-> state
            (assoc :thumbnails thumbnails)
            (update :files assoc file-id file))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id    (:current-team-id state)
            file-id    (:id file)]

        (rx/of (dwn/initialize team-id file-id)
               (dwsl/initialize-shape-layout)
               (fetch-libraries file-id))))))

(defn zoom-to-frame
  []
  (ptk/reify ::zoom-to-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (rt/get-params state)
            board-id (get params :board-id)
            board-id (cond
                       (vector? board-id) board-id
                       (string? board-id) [board-id])
            frames-id (->> board-id
                           (map uuid/uuid)
                           (into (d/ordered-set)))]
        (rx/of (dws/select-shapes frames-id)
               dwz/zoom-to-selected-shape)))))

(defn- select-frame-tool
  [file-id page-id]
  (ptk/reify ::select-frame-tool
    ptk/WatchEvent
    (watch [_ state _]
      (let [page (dsh/lookup-page state file-id page-id)]
        (when (ctp/is-empty? page)
          (rx/of (dwd/select-for-drawing :frame)))))))

(defn- fetch-bundle
  "Multi-stage file bundle fetch coordinator"
  [file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (let [features     (features/get-team-enabled-features state)
            render-wasm? (contains? features "render-wasm/v1")
            stopper-s    (rx/filter (ptk/type? ::finalize-workspace) stream)
            team-id      (:current-team-id state)]

        (->> (rx/concat
              ;; Firstly load wasm module if it is enabled and fonts
              (rx/merge
               (if ^boolean render-wasm?
                 (->> (rx/from @wasm/module)
                      (rx/ignore))
                 (rx/empty))

               (->> stream
                    (rx/filter (ptk/type? ::df/fonts-loaded))
                    (rx/take 1)
                    (rx/ignore))
               (rx/of (df/fetch-fonts team-id)))

              ;; Then fetch file and thumbnails
              (->> (rx/zip (rp/cmd! :get-file {:id file-id :features features})
                           (get-file-object-thumbnails file-id))
                   (rx/take 1)
                   (rx/mapcat
                    (fn [[file thumbnails]]
                      (->> (resolve-file file)
                           (rx/map (fn [file]
                                     {:file file
                                      :features features
                                      :thumbnails thumbnails})))))
                   (rx/map bundle-fetched)))
             (rx/take-until stopper-s))))))

(defn initialize-workspace
  [file-id]
  (assert (uuid? file-id) "expected valud uuid for `file-id`")
  (ptk/reify ::initialize-workspace
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :recent-colors (:recent-colors storage/user))
          (assoc :recent-fonts (:recent-fonts storage/user))
          (assoc :current-file-id file-id)
          (assoc :workspace-presence {})))

    ptk/WatchEvent
    (watch [_ state stream]
      (log/debug :hint "initialize-workspace" :file-id (dm/str file-id))
      (let [stoper-s (rx/filter (ptk/type? ::finalize-workspace) stream)
            rparams  (rt/get-params state)]

        (->> (rx/merge
              (rx/of (ntf/hide)
                     (dcmt/retrieve-comment-threads file-id)
                     (dcmt/fetch-profiles)
                     (fetch-bundle file-id))

              (->> stream
                   (rx/filter (ptk/type? ::bundle-fetched))
                   (rx/take 1)
                   (rx/map deref)
                   (rx/mapcat (fn [{:keys [file]}]
                                (rx/of (dpj/initialize-project (:project-id file))
                                       (-> (workspace-initialized file-id)
                                           (with-meta {:file-id file-id}))))))

              (when-let [component-id (some-> rparams :component-id parse-uuid)]
                (->> stream
                     (rx/filter (ptk/type? ::workspace-initialized))
                     (rx/observe-on :async)
                     (rx/take 1)
                     (rx/map #(dwl/go-to-local-component :id component-id))))

              (when (:board-id rparams)
                (->> stream
                     (rx/filter (ptk/type? ::dwv/initialize-viewport))
                     (rx/take 1)
                     (rx/map zoom-to-frame)))

              (when-let [comment-id (some-> rparams :comment-id parse-uuid)]
                (->> stream
                     (rx/filter (ptk/type? ::workspace-initialized))
                     (rx/observe-on :async)
                     (rx/take 1)
                     (rx/map #(dwcm/navigate-to-comment-id comment-id))))

              (->> stream
                   (rx/filter dch/commit?)
                   (rx/map deref)
                   (rx/mapcat (fn [{:keys [save-undo? undo-changes redo-changes undo-group tags stack-undo?]}]
                                (if (and save-undo? (seq undo-changes))
                                  (let [entry {:undo-changes undo-changes
                                               :redo-changes redo-changes
                                               :undo-group undo-group
                                               :tags tags}]
                                    (rx/of (dwu/append-undo entry stack-undo?)))
                                  (rx/empty))))))
             (rx/take-until stoper-s))))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [name (dm/str "workspace-" file-id)]
        (unchecked-set ug/global "name" name)))))

(defn finalize-workspace
  [file-id]
  (ptk/reify ::finalize-workspace
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          ;; FIXME: revisit
          (dissoc
           :current-file-id
           :workspace-editor-state
           :workspace-media-objects
           :workspace-persistence
           :workspace-presence
           :workspace-undo)
          (update :workspace-global dissoc :read-only?)
          (assoc-in [:workspace-global :options-mode] :design)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [project-id (:current-project-id state)]

        (rx/of (dwn/finalize file-id)
               (dpj/finalize-project project-id)
               (dwsl/finalize-shape-layout)
               (dwcl/stop-picker)
               (dwc/set-workspace-visited)
               (modal/hide)
               (ntf/hide))))))

(defn- reload-current-file
  []
  (ptk/reify ::reload-current-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (rx/of (initialize-workspace file-id))))))

;; Make this event callable through dynamic resolution
(defmethod ptk/resolve ::reload-current-file [_ _] (reload-current-file))



(def ^:private xf:collect-file-media
  "Resolve and collect all file media on page objects"
  (comp (map second)
        (keep (fn [{:keys [metadata fill-image]}]
                (cond
                  (some? metadata)   (cf/resolve-file-media metadata)
                  (some? fill-image) (cf/resolve-file-media fill-image))))))


(defn- initialize-page*
  "Second phase of page initialization, once we know the page is
  available on the sate"
  [file-id page-id page]
  (ptk/reify ::initialize-page*
    ptk/UpdateEvent
    (update [_ state]
      ;; selection; when user abandon the current page, the selection is lost
      (let [local (dm/get-in state [:workspace-cache [file-id page-id]] default-workspace-local)]
        (-> state
            (assoc :current-page-id page-id)
            (assoc :workspace-local (assoc local :selected (d/ordered-set)))
            (assoc :workspace-trimmed-page (dm/select-keys page [:id :name]))

            ;; FIXME: this should be done on `initialize-layout` (?)
            (update :workspace-layout layout/load-layout-flags)
            (update :workspace-global layout/load-layout-state))))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [uris  (into #{} xf:collect-file-media (:objects page))]
        (->> (rx/from uris)
             (rx/subs! #(http/fetch-data-uri % false)))))))

(defn initialize-page
  [file-id page-id]
  (assert (uuid? file-id) "expected valid uuid for `file-id`")

  (ptk/reify ::initialize-page
    ptk/WatchEvent
    (watch [_ state _]
      (if-let [page (dsh/lookup-page state file-id page-id)]
        (rx/of (initialize-page* file-id page-id page)
               (dwth/watch-state-changes file-id page-id)
               (dwl/watch-component-changes)
               (select-frame-tool file-id page-id))
        (rx/of (dcm/go-to-workspace :file-id file-id ::rt/replace true))))))

(defn finalize-page
  [file-id page-id]
  (assert (uuid? file-id) "expected valid uuid for `file-id`")
  (assert (uuid? page-id) "expected valid uuid for `page-id`")

  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (-> (:workspace-local state)
                      (dissoc :edition :edit-path :selected))
            exit? (not= :workspace (rt/lookup-name state))
            state (-> state
                      (update :workspace-cache assoc [file-id page-id] local)
                      (dissoc :current-page-id
                              :workspace-local
                              :workspace-trimmed-page
                              :workspace-focus-selected))]

        (cond-> state
          exit? (dissoc :workspace-drawing))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Page CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page
  [{:keys [page-id file-id]}]
  (let [id (or page-id (uuid/next))]
    (ptk/reify ::create-page
      ev/Event
      (-data [_]
        {:id id
         :file-id file-id})

      ptk/WatchEvent
      (watch [it state _]
        (let [pages   (-> (dsh/lookup-file-data state)
                          (get :pages-index))

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
            fdata              (dsh/lookup-file-data state)
            pages              (get fdata :pages-index)
            page               (get pages page-id)

            unames             (cfh/get-used-names pages)
            name               (cfh/generate-unique-name unames (:name page))
            objects            (update-vals (:objects page) #(dissoc % :use-for-thumbnail))

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
                                                 true
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
      (let [page    (dsh/lookup-page state id)
            changes (-> (pcb/empty-changes it)
                        (pcb/mod-page page {:name name}))]
        (rx/of (dch/commit-changes changes))))))

(defn set-plugin-data
  ([file-id type namespace key value]
   (set-plugin-data file-id type nil nil namespace key value))
  ([file-id type id namespace key value]
   (set-plugin-data file-id type id nil namespace key value))
  ([file-id type id page-id namespace key value]
   (dm/assert! (contains? #{:file :page :shape :color :typography :component} type))
   (dm/assert! (or (nil? id) (uuid? id)))
   (dm/assert! (or (nil? page-id) (uuid? page-id)))
   (dm/assert! (uuid? file-id))
   (dm/assert! (keyword? namespace))
   (dm/assert! (string? key))
   (dm/assert! (or (nil? value) (string? value)))

   (ptk/reify ::set-file-plugin-data
     ptk/WatchEvent
     (watch [it state _]
       (let [file-data (dm/get-in state [:files file-id :data])
             changes   (-> (pcb/empty-changes it)
                           (pcb/with-file-data file-data)
                           (assoc :file-id file-id)
                           (pcb/set-plugin-data type id page-id namespace key value))]
         (rx/of (dch/commit-changes changes)))))))

(declare purge-page)

(defn- delete-page-components
  [changes page]
  (let [components-to-delete (->> page
                                  :objects
                                  vals
                                  (filter #(true? (:main-instance %)))
                                  (map :component-id))

        changes (reduce (fn [changes component-id]
                          (pcb/delete-component changes component-id (:id page)))
                        changes
                        components-to-delete)]
    changes))

(defn delete-page
  [id]
  (ptk/reify ::delete-page
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id (:current-file-id state)
            fdata   (dsh/lookup-file-data state file-id)
            pindex  (:pages-index fdata)
            pages   (:pages fdata)

            index   (d/index-of pages id)
            page    (get pindex id)
            page    (assoc page :index index)
            pages   (filter #(not= % id) pages)

            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data fdata)
                        (delete-page-components page)
                        (pcb/del-page page))]

        (rx/of (dch/commit-changes changes)
               (when (= id (:current-page-id state))
                 (dcm/go-to-workspace {:page-id (first pages)})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WORKSPACE File Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME: move to common
(defn rename-file
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (let [name (str/prune name 200)]
    (ptk/reify ::rename-file
      IDeref
      (-deref [_]
        {::ev/origin "workspace" :id id :name name})

      ptk/UpdateEvent
      (update [_ state]
        (let [file-id (:current-file-id state)]
          (assoc-in state [:files file-id :name] name)))

      ptk/WatchEvent
      (watch [_ _ _]
        (let [params {:id id :name name}]
          (->> (rp/cmd! :rename-file params)
               (rx/ignore)))))))

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
      (rx/of (dwsh/update-shapes [id] #(merge % attrs))))))

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
  ([] (end-rename-shape nil nil))
  ([shape-id name]
   (ptk/reify ::end-rename-shape
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [shape-id (d/nilv shape-id (dm/get-in state [:workspace-local :shape-for-rename]))]
         (let [shape (dsh/lookup-shape state shape-id)
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
      (let [selected (dsh/lookup-selected state)]
        (rx/from (map #(update-shape % attrs) selected))))))

;; --- Delete Selected

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected     (dsh/lookup-selected state)
            hover-guides (get-in state [:workspace-guides :hover])]
        (cond
          (d/not-empty? selected)
          (rx/of (dwsh/delete-shapes selected)
                 (dws/deselect-all))

          (d/not-empty? hover-guides)
          (rx/of (dwgu/remove-guides hover-guides)))))))


;; --- Start renaming selected shape

(defn start-rename-selected
  "Rename selected shape."
  []
  (ptk/reify ::start-rename-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)
            id       (first selected)]
        (when (= (count selected) 1)
          (rx/of (dcm/go-to-workspace :layout :layers)
                 (start-rename-shape id)))))))

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
            objects         (dsh/lookup-page-objects state page-id)
            selected-ids    (dsh/lookup-selected state)
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
               (ptk/data-event :layout/update {:ids selected-ids})
               (dwu/commit-undo-transaction undo-id))))))

;; --- Change Shape Order (D&D Ordering)

(defn relocate-shapes
  [ids parent-id to-index & [ignore-parents?]]
  (dm/assert! (every? uuid? ids))
  (dm/assert! (set? ids))
  (dm/assert! (uuid? parent-id))
  (dm/assert! (number? to-index))

  (ptk/reify ::relocate-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (dsh/lookup-page-objects state page-id)

            ;; Ignore any shape whose parent is also intended to be moved
            ids      (cfh/clean-loops objects ids)

            ;; If we try to move a parent into a child we remove it
            ids      (filter #(not (cfh/is-parent? objects parent-id %)) ids)

            all-parents (into #{parent-id} (map #(cfh/get-parent-id objects %)) ids)

            changes (cls/generate-relocate (pcb/empty-changes it)
                                           objects
                                           parent-id
                                           page-id
                                           to-index
                                           ids
                                           :ignore-parents? ignore-parents?)
            undo-id (js/Symbol)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwco/expand-collapse parent-id)
               (ptk/data-event :layout/update {:ids (concat all-parents ids)})
               (dwu/commit-undo-transaction undo-id))))))

(defn relocate-selected-shapes
  [parent-id to-index]
  (ptk/reify ::relocate-selected-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (relocate-shapes selected parent-id to-index))))))

(defn start-editing-selected
  []
  (ptk/reify ::start-editing-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)
            objects (dsh/lookup-page-objects state)]

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

          (when (d/not-empty? selected)
            (let [{:keys [id type shapes]} (get objects (first selected))]
              (case type
                :text
                (rx/of (dwe/start-edition-mode id))

                (:group :bool :frame)
                (let [shapes-ids (into (d/ordered-set) shapes)]
                  (rx/of (dws/select-shapes shapes-ids)))

                :svg-raw
                nil

                (rx/of (dwe/start-edition-mode id)
                       (dwdp/start-path-edit id))))))))))

(defn select-parent-layer
  []
  (ptk/reify ::select-parent-layer
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)
            objects (dsh/lookup-page-objects state)
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
      (let [prev-index (-> (dsh/lookup-file-data state)
                           (get :pages)
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
    [(gal/align-to-parent object parent axis)]))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (gsh/shapes->rect selected-objs)]
    (map #(gal/align-to-rect % rect axis) selected-objs)))

(defn align-objects
  ([axis]
   (align-objects axis nil))
  ([axis selected]
   (dm/assert!
    "expected valid align axis value"
    (contains? gal/valid-align-axis axis))

   (ptk/reify ::align-objects
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects  (dsh/lookup-page-objects state)
             selected (or selected (dsh/lookup-selected state))
             moved    (if (= 1 (count selected))
                        (align-object-to-parent objects (first selected) axis)
                        (align-objects-list objects selected axis))
             undo-id (js/Symbol)]
         (when (can-align? selected objects)
           (rx/of (dwu/start-undo-transaction undo-id)
                  (dwt/position-shapes moved)
                  (ptk/data-event :layout/update {:ids selected})
                  (dwu/commit-undo-transaction undo-id))))))))

(defn can-distribute? [selected]
  (cond
    (empty? selected) false
    (< (count selected) 3) false
    :else true))

(defn distribute-objects
  ([axis]
   (distribute-objects axis nil))
  ([axis ids]
   (dm/assert!
    "expected valid distribute axis value"
    (contains? gal/valid-dist-axis axis))

   (ptk/reify ::distribute-objects
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id   (:current-page-id state)
             objects   (dsh/lookup-page-objects state page-id)
             selected  (or ids (dsh/lookup-selected state))
             moved     (-> (map #(get objects %) selected)
                           (gal/distribute-space axis))
             undo-id  (js/Symbol)]
         (when (can-distribute? selected)
           (rx/of (dwu/start-undo-transaction undo-id)
                  (dwt/position-shapes moved)
                  (ptk/data-event :layout/update {:ids selected})
                  (dwu/commit-undo-transaction undo-id))))))))

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
        (rx/of (dwsh/update-shapes [id] assign-proportions))))))

(defn toggle-proportion-lock
  []
  (ptk/reify ::toggle-proportion-lock
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id       (:current-page-id state)
            objects       (dsh/lookup-page-objects state page-id)
            selected      (dsh/lookup-selected state)
            selected-obj  (-> (map #(get objects %) selected))
            multi         (attrs/get-attrs-multi selected-obj [:proportion-lock])
            multi?        (= :multiple (:proportion-lock multi))]
        (if multi?
          (rx/of (dwsh/update-shapes selected #(assoc % :proportion-lock true)))
          (rx/of (dwsh/update-shapes selected #(update % :proportion-lock not))))))))

(defn workspace-focus-lost
  []
  (ptk/reify ::workspace-focus-lost
    ptk/UpdateEvent
    (update [_ state]
      ;; FIXME: remove the `?` from show-distances?
      (assoc-in state [:workspace-global :show-distances?] false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-assets-section-open
  [file-id section open?]
  (ptk/reify ::set-assets-section-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-assets :open-status file-id section] open?))))

(defn clear-assets-section-open
  []
  (ptk/reify ::clear-assets-section-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-assets :open-status] {}))))


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

(defn show-component-in-assets
  [component-id]

  (ptk/reify ::show-component-in-assets
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            fdata   (dsh/lookup-file-data state file-id)
            cpath   (dm/get-in fdata [:components component-id :path])
            cpath   (cfh/split-path cpath)
            paths   (map (fn [i] (cfh/join-path (take (inc i) cpath)))
                         (range (count cpath)))]
        (rx/concat
         (rx/from (map #(set-assets-group-open file-id :components % true) paths))
         (rx/of (dcm/go-to-workspace :layout :assets)
                (set-assets-section-open file-id :library true)
                (set-assets-section-open file-id :components true)
                (select-single-asset file-id component-id :components)))))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [wrapper-id (str "component-shape-id-" component-id)]
        (tm/schedule-on-idle #(dom/scroll-into-view-if-needed! (dom/get-element wrapper-id)))))))

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
      (let [selected        (dsh/lookup-selected state)
            objects         (dsh/lookup-page-objects state)
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

(defn show-track-context-menu
  [{:keys [grid-id type index] :as params}]
  (ptk/reify ::show-track-context-menu
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (show-context-menu
              (-> params (assoc :kind :grid-track
                                :grid-id grid-id
                                :type type
                                :index index)))))))

(defn show-grid-cell-context-menu
  [{:keys [grid-id] :as params}]
  (ptk/reify ::show-grid-cell-context-menu
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            grid (get objects grid-id)
            cells (->> (get-in state [:workspace-grid-edition grid-id :selected])
                       (map #(get-in grid [:layout-grid-cells %])))]
        (rx/of (show-context-menu
                (-> params (assoc :kind :grid-cells
                                  :grid grid
                                  :cells cells))))))))
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
            (let [selected (dsh/lookup-selected state)
                  objects  (dsh/lookup-page-objects state)

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
                             [(:metadata obj)])
                           (when (:fill-image obj)
                             [(:fill-image obj)]))]

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

          ;; When copying an instance that is nested inside another one, we need to
          ;; advance the shape refs to one or more levels of remote mains.
          (advance-copies [state selected data]
            (let [file      (dsh/lookup-file state)
                  libraries (:files state)
                  ;; FIXME
                  page      (dsh/lookup-page state)
                  heads     (mapcat #(ctn/get-child-heads (:objects data) %) selected)]
              (update data :objects
                      #(reduce (partial advance-copy file libraries page)
                               %
                               heads))))

          (advance-copy [file libraries page objects shape]
            (if (and (ctk/instance-head? shape) (not (ctk/main-instance? shape)))
              (let [level-delta (ctn/get-nesting-level-delta (:objects page) shape uuid/zero)]
                (if (pos? level-delta)
                  (reduce (partial advance-shape file libraries page level-delta)
                          objects
                          (cfh/get-children-with-self objects (:id shape)))
                  objects))
              objects))

          (advance-shape [file libraries page level-delta objects shape]
            (let [new-shape-ref (ctf/advance-shape-ref file page libraries shape level-delta {:include-deleted? true})]
              (cond-> objects
                (and (some? new-shape-ref) (not= new-shape-ref (:shape-ref shape)))
                (assoc-in [(:id shape) :shape-ref] new-shape-ref))))

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

            (let [objects  (dsh/lookup-page-objects state)
                  selected (->> (dsh/lookup-selected state)
                                (cfh/clean-loops objects))
                  features (-> (features/get-team-enabled-features state)
                               (set/difference cfeat/frontend-only-features))

                  file-id  (:current-file-id state)
                  frame-id (cfh/common-parent-frame objects selected)
                  file     (dsh/lookup-file state file-id)
                  version  (get file :version)

                  initial  {:type :copied-shapes
                            :features features
                            :version version
                            :file-id file-id
                            :selected selected
                            :objects {}
                            :images #{}}

                  shapes   (->> (cfh/selected-with-children objects selected)
                                (keep (d/getf objects)))]

              ;; The clipboard API doesn't handle well asynchronous calls because it expects to use
              ;; the clipboard in an user interaction. If you do an async call the callback is outside
              ;; the thread of the UI and so Safari blocks the copying event.
              ;; We use the API `ClipboardItem` that allows promises to be passed and so the event
              ;; will wait for the promise to resolve and everything should work as expected.
              ;; This only works in the current versions of the browsers.
              (if (some? (unchecked-get ug/global "ClipboardItem"))
                (let [resolve-data-promise
                      (p/create
                       (fn [resolve reject]
                         (->> (rx/from shapes)
                              (rx/merge-map (partial prepare-object objects frame-id))
                              (rx/reduce collect-data initial)
                              (rx/map (partial sort-selected state))
                              (rx/map (partial advance-copies state selected))
                              (rx/map #(t/encode-str % {:type :json-verbose}))
                              (rx/map #(wapi/create-blob % "text/plain"))
                              (rx/subs! resolve reject))))]
                  (->> (rx/from (wapi/write-to-clipboard-promise "text/plain" resolve-data-promise))
                       (rx/catch on-copy-error)
                       (rx/ignore)))

                ;; FIXME: this is to support Firefox versions below 116 that don't support
                ;; `ClipboardItem` after the version 116 is less common we could remove this.
                ;; https://caniuse.com/?search=ClipboardItem
                (->> (rx/from shapes)
                     (rx/merge-map (partial prepare-object objects frame-id))
                     (rx/reduce collect-data initial)
                     (rx/map (partial sort-selected state))
                     (rx/map (partial advance-copies state selected))
                     (rx/map #(t/encode-str % {:type :json-verbose}))
                     (rx/map wapi/write-to-clipboard)
                     (rx/catch on-copy-error)
                     (rx/ignore))))))))))

(declare ^:private paste-transit-shapes)
(declare ^:private paste-transit-props)
(declare ^:private paste-html-text)
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
              (cond
                (str/empty? data)
                (rx/empty)

                (re-find #"<svg\s" data)
                (rx/of (paste-svg-text data))

                :else
                (rx/of (paste-text data)))

              :transit
              (rx/of (paste-transit-shapes data))))

          (on-error [cause]
            (let [data (ex-data cause)]
              (if (:not-implemented data)
                (rx/of (ntf/warn (tr "errors.clipboard-not-implemented")))
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
      (let [objects     (dsh/lookup-page-objects state)
            edit-id     (dm/get-in state [:workspace-local :edition])
            is-editing? (and edit-id (= :text (get-in objects [edit-id :type])))]

        ;; Some paste events can be fired while we're editing a text
        ;; we forbid that scenario so the default behaviour is executed
        (if is-editing?
          (rx/empty)
          (let [pdata        (wapi/read-from-paste-event event)
                image-data   (some-> pdata wapi/extract-images)
                text-data    (some-> pdata wapi/extract-text)
                html-data    (some-> pdata wapi/extract-html-text)
                transit-data (ex/ignoring (some-> text-data t/decode-str))]
            (cond
              (and (string? text-data) (re-find #"<svg\s" text-data))
              (rx/of (paste-svg-text text-data))

              (seq image-data)
              (->> (rx/from image-data)
                   (rx/map paste-image))

              (coll? transit-data)
              (rx/of (paste-transit-shapes (assoc transit-data :in-viewport in-viewport?)))

              (and (string? html-data) (d/not-empty? html-data))
              (rx/of (paste-html-text html-data text-data))

              (and (string? text-data) (d/not-empty? text-data))
              (rx/of (paste-text text-data))

              :else
              (rx/empty))))))))

(defn copy-selected-css
  []
  (ptk/reify ::copy-selected-css
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            selected (->> (dsh/lookup-selected state) (mapv (d/getf objects)))
            css (css/generate-style objects selected selected {:with-prelude? false})]
        (wapi/write-to-clipboard css)))))

(defn copy-selected-css-nested
  []
  (ptk/reify ::copy-selected-css-nested
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            selected (->> (dsh/lookup-selected state)
                          (cfh/selected-with-children objects)
                          (mapv (d/getf objects)))
            css (css/generate-style objects selected selected {:with-prelude? false})]
        (wapi/write-to-clipboard css)))))

(defn copy-selected-text
  []
  (ptk/reify ::copy-selected-text
    ptk/EffectEvent
    (effect [_ state _]
      (let [selected (dsh/lookup-selected state)
            objects  (dsh/lookup-page-objects state)

            text-shapes
            (->> (cfh/selected-with-children objects selected)
                 (keep (d/getf objects))
                 (filter cfh/text-shape?))

            selected (into (d/ordered-set) (map :id) text-shapes)

            ;; Narrow the objects map so it contains only relevant data for
            ;; selected and its parents
            objects  (cfh/selected-subtree objects selected)
            selected (->> (ctst/sort-z-index objects selected)
                          (into (d/ordered-set)))

            text
            (->> selected
                 (map
                  (fn [id]
                    (let [shape (get objects id)]
                      (-> shape :content txt/content->text))))
                 (str/join "\n"))]

        (wapi/write-to-clipboard text)))))

(defn copy-selected-props
  []
  (ptk/reify ::copy-selected-props
    ptk/WatchEvent
    (watch [_ state _]
      (letfn [(fetch-image [entry]
                (let [url (cf/resolve-file-media entry)]
                  (->> (http/send! {:method :get
                                    :uri url
                                    :response-type :blob})
                       (rx/map :body)
                       (rx/mapcat wapi/read-file-as-data-url)
                       (rx/map #(assoc entry :data %)))))

              (resolve-images [data]
                (let [images
                      (concat
                       (->> data :props :fills (keep :fill-image))
                       (->> data :props :strokes (keep :stroke-image)))]

                  (if (seq images)
                    (->> (rx/from images)
                         (rx/mapcat fetch-image)
                         (rx/reduce conj #{})
                         (rx/map #(assoc data :images %)))
                    (rx/of data))))

              (on-copy-error [error]
                (js/console.error "clipboard blocked:" error)
                (rx/empty))]

        (let [selected (dsh/lookup-selected state)]
          (if (> (count selected) 1)
            ;; If multiple items are selected don't do anything
            (rx/empty)

            (let [selected (->> (dsh/lookup-selected state) first)
                  objects  (dsh/lookup-page-objects state)]
              (when-let [shape (get objects selected)]
                (let [props (cts/extract-props shape)
                      features (-> (features/get-team-enabled-features state)
                                   (set/difference cfeat/frontend-only-features))
                      version  (-> (dsh/lookup-file state) :version)

                      copy-data {:type :copied-props
                                 :features features
                                 :version version
                                 :props props
                                 :images #{}}]

                  ;; The clipboard API doesn't handle well asynchronous calls because it expects to use
                  ;; the clipboard in an user interaction. If you do an async call the callback is outside
                  ;; the thread of the UI and so Safari blocks the copying event.
                  ;; We use the API `ClipboardItem` that allows promises to be passed and so the event
                  ;; will wait for the promise to resolve and everything should work as expected.
                  ;; This only works in the current versions of the browsers.
                  (if (some? (unchecked-get ug/global "ClipboardItem"))
                    (let [resolve-data-promise
                          (p/create
                           (fn [resolve reject]
                             (->> (rx/of copy-data)
                                  (rx/mapcat resolve-images)
                                  (rx/map #(t/encode-str % {:type :json-verbose}))
                                  (rx/map #(wapi/create-blob % "text/plain"))
                                  (rx/subs! resolve reject))))]

                      (->> (rx/from (wapi/write-to-clipboard-promise "text/plain" resolve-data-promise))
                           (rx/catch on-copy-error)
                           (rx/ignore)))
                    ;; FIXME: this is to support Firefox versions below 116 that don't support
                    ;; `ClipboardItem` after the version 116 is less common we could remove this.
                    ;; https://caniuse.com/?search=ClipboardItem
                    (->> (rx/of copy-data)
                         (rx/mapcat resolve-images)
                         (rx/map #(wapi/write-to-clipboard (t/encode-str % {:type :json-verbose})))
                         (rx/catch on-copy-error)
                         (rx/ignore))))))))))))

(defn paste-selected-props
  []
  (ptk/reify ::paste-selected-props
    ptk/WatchEvent
    (watch [_ state _]
      (when-not (-> state :workspace-global :read-only?)
        (letfn [(decode-entry [entry]
                  (-> entry t/decode-str paste-transit-props))

                (on-error [cause]
                  (let [data (ex-data cause)]
                    (if (:not-implemented data)
                      (rx/of (ntf/warn (tr "errors.clipboard-not-implemented")))
                      (js/console.error "Clipboard error:" cause))
                    (rx/empty)))]

          (->> (wapi/read-from-clipboard)
               (rx/map decode-entry)
               (rx/take 1)
               (rx/catch on-error)))))))

(defn selected-frame? [state]
  (let [selected (dsh/lookup-selected state)
        objects  (dsh/lookup-page-objects state)]

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
  (let [selected (first (dsh/lookup-selected state))]
    (< 0 (count (filter #(= % selected) frame-ids)))))

(defn frame-same-size?
  [paste-obj frame-obj]
  (and
   (= (:heigth (:selrect (first (vals paste-obj))))
      (:heigth (:selrect frame-obj)))
   (= (:width (:selrect (first (vals paste-obj))))
      (:width (:selrect frame-obj)))))

(def ^:private
  schema:paste-data-shapes
  [:map {:title "paste-data-shapes"}
   [:type [:= :copied-shapes]]
   [:features ::sm/set-of-strings]
   [:version :int]
   [:file-id ::sm/uuid]
   [:selected ::sm/set-of-uuid]
   [:objects
    [:map-of ::sm/uuid :map]]
   [:images [:set :map]]
   [:position {:optional true} ::gpt/point]])

(def ^:private
  schema:paste-data-props
  [:map {:title "paste-data-props"}
   [:type [:= :copied-props]]
   [:features ::sm/set-of-strings]
   [:version :int]
   [:props
    ;; todo type the properties
    [:map-of :keyword :any]]])

(def schema:paste-data
  [:multi {:title "paste-data" :dispatch :type}
   [:copied-shapes schema:paste-data-shapes]
   [:copied-props schema:paste-data-props]])

(def paste-data-valid?
  (sm/lazy-validator schema:paste-data))

(defn- paste-transit-shapes
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

    (ptk/reify ::paste-transit-shapes
      ptk/WatchEvent
      (watch [_ state _]
        (let [file-id (:current-file-id state)
              features (features/get-team-enabled-features state)]

          (when-not (paste-data-valid? pdata)
            (ex/raise :type :validation
                      :code :invalid-paste-data
                      :hibt "invalid paste data found"))

          (cfeat/check-paste-features! features (:features pdata))

          (case (:type pdata)
            :copied-shapes
            (if (= file-id (:file-id pdata))
              (let [pdata (assoc pdata :images [])]
                (rx/of (paste-shapes pdata)))
              (->> (rx/from images)
                   (rx/merge-map (partial upload-media file-id))
                   (rx/reduce conj [])
                   (rx/map #(assoc pdata :images %))
                   (rx/map paste-shapes)))
            nil))))))

(defn- paste-transit-props
  [pdata]

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
                 (rx/map #(vector (:id imgpart) %))))

          (update-image-data
            [pdata media-map]
            (update
             pdata :props
             (fn [props]
               (-> props
                   (d/update-when
                    :fills
                    (fn [fills]
                      (mapv (fn [fill]
                              (cond-> fill
                                (some? (:fill-image fill))
                                (update-in [:fill-image :id] #(get media-map % %))))
                            fills)))
                   (d/update-when
                    :strokes
                    (fn [strokes]
                      (mapv (fn [stroke]
                              (cond-> stroke
                                (some? (:stroke-image stroke))
                                (update-in [:stroke-image :id] #(get media-map % %))))
                            strokes)))))))

          (upload-images
            [file-id pdata]
            (->> (rx/from (:images pdata))
                 (rx/merge-map (partial upload-media file-id))
                 (rx/reduce conj {})
                 (rx/map (partial update-image-data pdata))))]

    (ptk/reify ::paste-transit-props
      ptk/WatchEvent
      (watch [_ state _]
        (let [features (features/get-team-enabled-features state)
              selected (dsh/lookup-selected state)]

          (when (paste-data-valid? pdata)
            (cfeat/check-paste-features! features (:features pdata))
            (case (:type pdata)
              :copied-props

              (rx/concat
               (->> (rx/of pdata)
                    (rx/mapcat (partial upload-images (:current-file-id state)))
                    (rx/map
                     #(dwsh/update-shapes
                       selected
                       (fn [shape objects] (cts/patch-props shape (:props pdata) objects))
                       {:with-objects? true})))
               (rx/of (ptk/data-event :layout/update {:ids selected})))
              ;;
              (rx/empty))))))))

(defn paste-shapes
  [{in-viewport? :in-viewport :as pdata}]
  (letfn [(translate-media [mdata media-idx attr-path]
            (let [id   (-> (get-in mdata attr-path)
                           (:id))
                  mobj (get media-idx id)]
              (if mobj
                (if (empty? attr-path)
                  (-> mdata
                      (assoc :id (:id mobj))
                      (assoc :path (:path mobj)))
                  (update-in mdata attr-path (fn [value]
                                               (-> value
                                                   (assoc :id (:id mobj))
                                                   (assoc :path (:path mobj))))))

                mdata)))

          (add-obj? [chg]
            (= (:type chg) :add-obj))

          ;; Analyze the rchange and replace staled media and
          ;; references to the new uploaded media-objects.
          (process-rchange [media-idx change]
            (let [;; Texts can have different fills for pieces of the text
                  tr-fill-xf    (map #(translate-media % media-idx [:fill-image]))
                  tr-stroke-xf  (map #(translate-media % media-idx [:stroke-image]))]
              (if (add-obj? change)
                (update change :obj (fn [obj]
                                      (-> obj
                                          (update :fills #(into [] tr-fill-xf %))
                                          (update :strokes #(into [] tr-stroke-xf %))
                                          (d/update-when :metadata translate-media media-idx [])
                                          (d/update-when :fill-image translate-media media-idx [])
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
            (let [page-objects         (dsh/lookup-page-objects state)
                  selected-objs        (map (d/getf pobjects) selected)
                  first-selected-obj   (first selected-objs)
                  page-selected        (dsh/lookup-selected state)
                  wrapper              (gsh/shapes->rect selected-objs)
                  orig-pos             (gpt/point (:x1 wrapper) (:y1 wrapper))
                  frame-id             (first page-selected)
                  frame-object         (get page-objects frame-id)
                  base                 (cfh/get-base-shape page-objects page-selected)
                  index                (cfh/get-position-on-parent page-objects (:id base))
                  tree-root            (get-tree-root-shapes pobjects)
                  only-one-root-shape? (and
                                        (< 1 (count pobjects))
                                        (= 1 (count tree-root)))]

              (cond
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

                    [parent-id delta index])

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
                                ;; - Respect the distance of the object to the right
                                ;;   and bottom in the original frame
                                (gpt/point paste-x paste-y))]
                    [frame-id delta (dec (count (:shapes selected-frame-obj)))]))

                (empty? page-selected)
                (let [frame-id (ctst/top-nested-frame page-objects position)
                      delta    (gpt/subtract position orig-pos)]
                  [frame-id delta])

                :else
                (let [parent-id (:parent-id base)
                      delta     (if in-viewport?
                                  (gpt/subtract position orig-pos)
                                  (gpt/subtract (gpt/point (:selrect base)) orig-pos))]
                  [parent-id delta index]))))

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
        (let [file-id      (:current-file-id state)
              page         (dsh/lookup-page state)

              media-idx    (->> (:images pdata)
                                (d/index-by :prev-id))

              selected     (:selected pdata)

              objects      (:objects pdata)

              position     (deref ms/mouse-position)

              ;; Calculate position for the pasted elements
              [candidate-parent-id
               delta
               index]      (calculate-paste-position state objects selected position)

              page-objects (:objects page)

              libraries    (dsh/lookup-libraries state)
              ldata        (dsh/lookup-file-data state file-id)

              ;; full-libs    (assoc-in libraries [(:id ldata) :data] ldata)

              full-libs    libraries

              [parent-id
               frame-id]   (ctn/find-valid-parent-and-frame-ids candidate-parent-id page-objects (vals objects) true full-libs)

              index        (if (= candidate-parent-id parent-id)
                             index
                             0)

              selected     (if (and (ctl/flex-layout? page-objects parent-id) (not (ctl/reverse? page-objects parent-id)))
                             (into (d/ordered-set) (reverse selected))
                             selected)

              objects      (update-vals objects (partial process-shape file-id frame-id parent-id))

              all-objects  (merge page-objects objects)


              drop-cell    (when (ctl/grid-layout? all-objects parent-id)
                             (gslg/get-drop-cell frame-id all-objects position))

              changes      (-> (pcb/empty-changes it)
                               (cll/generate-duplicate-changes all-objects page selected delta libraries ldata file-id)
                               (pcb/amend-changes (partial process-rchange media-idx))
                               (pcb/amend-changes (partial change-add-obj-index objects selected index)))

              ;; Adds a resize-parents operation so the groups are
              ;; updated. We add all the new objects
              changes      (->> (:redo-changes changes)
                                (filter add-obj?)
                                (map :id)
                                (pcb/resize-parents changes))

              orig-shapes  (map (d/getf all-objects) selected)

              selected     (into (d/ordered-set)
                                 (comp
                                  (filter add-obj?)
                                  (filter #(contains? selected (:old-id %)))
                                  (map :obj)
                                  (map :id))
                                 (:redo-changes changes))

              changes      (cond-> changes
                             (some? drop-cell)
                             (pcb/update-shapes [parent-id]
                                                #(ctl/add-children-to-cell % selected all-objects drop-cell)))

              undo-id      (js/Symbol)]

          (rx/concat
           (->> (filter ctk/instance-head? orig-shapes)
                (map (fn [{:keys [component-file]}]
                       (ptk/event ::ev/event
                                  {::ev/name "use-library-component"
                                   ::ev/origin "paste"
                                   :external-library (not= file-id component-file)})))
                (rx/from))
           (rx/of (dwu/start-undo-transaction undo-id)
                  (dch/commit-changes changes)
                  (dws/select-shapes selected)
                  (ptk/data-event :layout/update {:ids [frame-id]})
                  (dwu/commit-undo-transaction undo-id))))))))

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
    (let [page-selected (dsh/lookup-selected state)
          page-objects  (dsh/lookup-page-objects state)
          frame-id (first page-selected)
          frame-object (get page-objects frame-id)]
      (gsh/shape->center frame-object))

    :else
    (deref ms/mouse-position)))

(defn- paste-html-text
  [html text]
  (dm/assert! (string? html))
  (ptk/reify ::paste-html-text
    ptk/WatchEvent
    (watch [_ state  _]
      (let [root (dwtxt/create-root-from-html html)
            content (tc/dom->cljs root)

            id (uuid/next)
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
                   :content content}
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwsh/create-and-add-shape :text x y shape)
               (dwu/commit-undo-transaction undo-id))))))

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
      (let [file-id  (:current-file-id state)
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

(defn copy-link-to-clipboard
  []
  (ptk/reify ::copy-link-to-clipboard
    ptk/WatchEvent
    (watch [_ _ _]
      (wapi/write-to-clipboard (rt/get-current-href)))))

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
  ([color]
   (change-canvas-color nil color))
  ([page-id color]
   (ptk/reify ::change-canvas-color
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id (or page-id (:current-page-id state))
             page    (dsh/lookup-page state page-id)
             changes (-> (pcb/empty-changes it)
                         (pcb/with-page page)
                         (pcb/mod-page {:background (:color color)}))]
         (rx/of (dch/commit-changes changes)))))))



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

(defn- find-orphan-shapes
  ([state]
   (find-orphan-shapes state (:current-page-id state)))
  ([state page-id]
   (let [objects (dsh/lookup-page-objects state page-id)
         objects (filter (fn [item]
                           (and
                            (not= (key item) uuid/zero)
                            (not (contains? objects (:parent-id (val item))))))
                         objects)]
     objects)))

(defn fix-orphan-shapes
  []
  (ptk/reify ::fix-orphan-shapes
    ptk/WatchEvent
    (watch [_ state _]
      (let [orphans (set (into [] (keys (find-orphan-shapes state))))]
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
      (let [data
            (dsh/lookup-file-data state)

            update-fn
            (fn [component]
              ;; NOTE: we need to ensure the component exists,
              ;; because there are small possibilities of race
              ;; conditions with component deletion.
              (when component
                (if (nil? annotation)
                  (dissoc component :annotation)
                  (assoc component :annotation annotation))))

            changes
            (-> (pcb/empty-changes it)
                (pcb/with-library-data data)
                (pcb/update-component id update-fn))]

        (rx/concat
         (rx/of (dch/commit-changes changes))
         (when (nil? annotation)
           (rx/of (ptk/data-event ::ev/event {::ev/name "delete-component-annotation"}))))))))

(defn set-annotations-expanded
  [expanded]
  (ptk/reify ::set-annotations-expanded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-annotations :expanded] expanded))))

(defn set-annotations-id-for-create
  [id]
  (ptk/reify ::set-annotations-id-for-create
    ptk/UpdateEvent
    (update [_ state]
      (if id
        (-> (assoc-in state [:workspace-annotations :id-for-create] id)
            (assoc-in [:workspace-annotations :expanded] true))
        (d/dissoc-in state [:workspace-annotations :id-for-create])))

    ptk/WatchEvent
    (watch [_ _ _]
      (when (some? id)
        (rx/of (ptk/data-event ::ev/event {::ev/name "create-component-annotation"}))))))

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
      (let [objects (dsh/lookup-page-objects state)
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

(dm/export dwt/trigger-bounding-box-cloaking)
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
