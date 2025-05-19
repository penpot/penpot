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
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpp]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.logic.shapes :as cls]
   [app.common.transit :as t]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
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
   [app.main.data.persistence :as-alias dps]
   [app.main.data.plugins :as dp]
   [app.main.data.profile :as du]
   [app.main.data.project :as dpj]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.clipboard :as dwcp]
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
   [app.main.data.workspace.notifications :as dwn]
   [app.main.data.workspace.path :as dwdp]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwva]
   [app.main.data.workspace.viewport :as dwv]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.errors]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.worker :as mw]
   [app.render-wasm :as wasm]
   [app.render-wasm.api :as api]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :as storage]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

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
                    (->> (mw/ask! {:cmd :index/initialize-page-index :page page})
                         (rx/map (fn [_] [id page]))))))
               (rx/reduce conj {})
               (rx/map (fn [pages-index]
                         (let [data (assoc data :pages-index pages-index)]
                           (assoc file :data (d/removem (comp t/pointer? val) data))))))))))

(defn- check-libraries-synchronozation
  [file-id libraries]
  (ptk/reify ::check-libraries-synchronozation
    ptk/WatchEvent
    (watch [_ state _]
      (let [file         (dsh/lookup-file state file-id)
            ignore-until (get file :ignore-sync-until)

            needs-check?
            (some #(and (> (:modified-at %) (:synced-at %))
                        (or (not ignore-until)
                            (> (:modified-at %) ignore-until)))
                  libraries)]

        (when needs-check?
          (->> (rx/of (dwl/notify-sync-file file-id))
               (rx/delay 1000)))))))

(defn- library-resolved
  [library]
  (ptk/reify ::library-resolved
    ptk/UpdateEvent
    (update [_ state]
      (update state :files assoc (:id library) library))))

(defn- libraries-fetched
  [file-id libraries]
  (ptk/reify ::libraries-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update state :files merge
              (->> libraries
                   (map #(assoc % :library-of file-id))
                   (d/index-by :id))))))

(defn- fetch-libraries
  [file-id features]
  (ptk/reify ::fetch-libries
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-file-libraries {:file-id file-id})
           (rx/mapcat
            (fn [libraries]
              (rx/concat
               (rx/of (libraries-fetched file-id libraries))
               (rx/merge
                (->> (rx/from libraries)
                     (rx/merge-map
                      (fn [{:keys [id synced-at]}]
                        (->> (rp/cmd! :get-file {:id id :features features})
                             (rx/map #(assoc % :synced-at synced-at :library-of file-id)))))
                     (rx/mapcat resolve-file)
                     (rx/map library-resolved))
                (->> (rx/from libraries)
                     (rx/map :id)
                     (rx/mapcat (fn [file-id]
                                  (rp/cmd! :get-file-object-thumbnails {:file-id file-id :tag "component"})))
                     (rx/map dwl/library-thumbnails-fetched)))
               (rx/of (check-libraries-synchronozation file-id libraries)))))))))

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
  [{:keys [file file-id thumbnails] :as bundle}]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_] bundle)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :thumbnails thumbnails)
          (update :files assoc file-id file)))))

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
  [file-id features]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper-s (rx/filter (ptk/type? ::finalize-workspace) stream)]
        (->> (rx/zip (rp/cmd! :get-file {:id file-id :features features})
                     (get-file-object-thumbnails file-id))
             (rx/take 1)
             (rx/mapcat
              (fn [[file thumbnails]]
                (->> (resolve-file file)
                     (rx/map (fn [file]
                               {:file file
                                :file-id file-id
                                :features features
                                :thumbnails thumbnails})))))
             (rx/map bundle-fetched)
             (rx/take-until stopper-s))))))

(defn initialize-workspace
  [team-id file-id]
  (assert (uuid? team-id) "expected valud uuid for `team-id`")
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
      (let [stoper-s     (rx/filter (ptk/type? ::finalize-workspace) stream)
            rparams      (rt/get-params state)
            features     (features/get-enabled-features state team-id)
            render-wasm? (contains? features "render-wasm/v1")]

        (log/debug :hint "initialize-workspace"
                   :team-id (dm/str team-id)
                   :file-id (dm/str file-id))

        (->> (rx/merge
              (rx/concat
               ;; Fetch all essential data that should be loaded before the file
               (rx/merge
                (if ^boolean render-wasm?
                  (->> (rx/from @wasm/module)
                       (rx/ignore))
                  (rx/empty))

                (->> stream
                     (rx/filter (ptk/type? ::df/fonts-loaded))
                     (rx/take 1)
                     (rx/ignore))

                (rx/of (ntf/hide)
                       (dcmt/retrieve-comment-threads file-id)
                       (dcmt/fetch-profiles)
                       (df/fetch-fonts team-id)))

               ;; Once the essential data is fetched, lets proceed to
               ;; fetch teh file bunldle
               (rx/of (fetch-bundle file-id features)))

              (->> stream
                   (rx/filter (ptk/type? ::bundle-fetched))
                   (rx/take 1)
                   (rx/map deref)
                   (rx/mapcat
                    (fn [{:keys [file]}]
                      (rx/of (dpj/initialize-project (:project-id file))
                             (dwn/initialize team-id file-id)
                             (dwsl/initialize-shape-layout)
                             (fetch-libraries file-id features)
                             (-> (workspace-initialized file-id)
                                 (with-meta {:team-id team-id
                                             :file-id file-id}))))))

              (->> stream
                   (rx/filter (ptk/type? ::dps/persistence-notification))
                   (rx/take 1)
                   (rx/map dwc/set-workspace-visited))

              (when-let [component-id (some-> rparams :component-id uuid/parse)]
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

              (when-let [comment-id (some-> rparams :comment-id uuid/parse)]
                (->> stream
                     (rx/filter (ptk/type? ::workspace-initialized))
                     (rx/observe-on :async)
                     (rx/take 1)
                     (rx/map #(dwcm/navigate-to-comment-id comment-id))))

              (->> stream
                   (rx/filter dch/commit?)
                   (rx/map deref)
                   (rx/mapcat (fn [{:keys [save-undo? undo-changes redo-changes undo-group tags stack-undo?]}]
                                (when render-wasm?
                                  (let [added (->> redo-changes
                                                   (filter #(= (:type %) :add-obj))
                                                   (map :obj))]
                                    (doseq [shape added]
                                      (api/process-object shape))))

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
  [_team-id file-id]
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
           :workspace-tokens
           :workspace-undo)
          (update :workspace-global dissoc :read-only?)
          (assoc-in [:workspace-global :options-mode] :design)
          (update :files d/update-vals #(dissoc % :data))))

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
      (let [file-id (:current-file-id state)
            team-id (:current-team-id state)]
        (rx/of (initialize-workspace team-id file-id))))))

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
  (assert (uuid? page-id) "expected valid uuid for `page-id`")

  (ptk/reify ::initialize-page
    ptk/WatchEvent
    (watch [_ state _]
      (if-let [page (dsh/lookup-page state file-id page-id)]
        (rx/concat
         (rx/of (initialize-page* file-id page-id page)
                (dwth/watch-state-changes file-id page-id)
                (dwl/watch-component-changes))
         (let [profile (:profile state)
               props   (get profile :props)]
           (when (not (:workspace-visited props))
             (rx/of (select-frame-tool file-id page-id)))))

        ;; NOTE: this redirect is necessary for cases where user
        ;; explicitly passes an non-existing page-id on the url
        ;; params, so on check it we can detect that there are no data
        ;; for the page and redirect user to an existing page
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
              name    (cfh/generate-unique-name "Page" unames :immediate-suffix? true)
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
            suffix-fn          (fn [copy-count]
                                 (str/concat " "
                                             (tr "dashboard.copy-suffix")
                                             (when (> copy-count 1)
                                               (str " " copy-count))))
            base-name          (:name page)
            name               (cfh/generate-unique-name base-name unames :suffix-fn suffix-fn)
            objects            (update-vals (:objects page) #(dissoc % :use-for-thumbnail))

            main-instances-ids (set (keep #(when (ctc/main-instance? (val %)) (key %)) objects))
            ids-to-remove      (set (apply concat (map #(cfh/get-children-ids objects %) main-instances-ids)))

            add-component-copy
            (fn [objs id shape]
              (let [component (ctkl/get-component fdata (:component-id shape))
                    [new-shape new-shapes]
                    (ctn/make-component-instance page
                                                 component
                                                 fdata
                                                 (gpt/point (:x shape) (:y shape))
                                                 {:keep-ids? true :force-frame-id (:frame-id shape)})
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
  (let [name (dm/truncate name 200)]
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

;; FIXME: rename to update-shape-generic-attrs because on the end we
;; only allow here to update generic attrs
(defn update-shape
  [id attrs]
  (assert (uuid? id) "expected valid uuid for `id`")
  (let [attrs (cts/check-shape-generic-attrs attrs)]
    (ptk/reify ::update-shape
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/of (dwsh/update-shapes [id] #(merge % attrs)))))))

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
     ptk/UpdateEvent
     (update [_ state]
       ;; Remove rename state from workspace local state
       (update state :workspace-local dissoc :shape-for-rename))
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [shape-id (d/nilv shape-id (dm/get-in state [:workspace-local :shape-for-rename]))]
         (let [shape        (dsh/lookup-shape state shape-id)
               name         (str/trim name)
               clean-name   (cfh/clean-path name)
               valid?       (and (not (str/ends-with? name "/"))
                                 (string? clean-name)
                                 (not (str/blank? clean-name)))
               component-id (:component-id shape)
               undo-id (js/Symbol)]


           (when valid?
             (if (ctc/is-variant-container? shape)
               ;; Rename the full variant when it is a variant container
               (rx/of (dwva/rename-variant shape-id clean-name))
               (rx/of
                (dwu/start-undo-transaction undo-id)
                ;; Rename the shape if string is not empty/blank
                (update-shape shape-id {:name clean-name})

                ;; Update the component in case shape is a main instance
                (when (and (some? component-id) (ctc/main-instance? shape))
                  (dwl/rename-component component-id clean-name))
                (dwu/commit-undo-transaction undo-id))))))))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
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
            data     (dsh/lookup-file-data state)

            ;; Ignore any shape whose parent is also intended to be moved
            ids      (cfh/clean-loops objects ids)

            ;; If we try to move a parent into a child we remove it
            ids      (filter #(not (cfh/is-parent? objects parent-id %)) ids)

            all-parents (into #{parent-id} (map #(cfh/get-parent-id objects %)) ids)

            changes (-> (pcb/empty-changes it)
                        (pcb/with-page-id page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data)
                        (cls/generate-relocate
                         parent-id
                         to-index
                         ids
                         :ignore-parents? ignore-parents?))
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
            objects  (dsh/lookup-page-objects state)]

        (condp = (count selected)
          0 (rx/empty)
          1 (let [{:keys [id type] :as shape} (get objects (first selected))]
              (case type
                :text
                (rx/of (dwe/start-edition-mode id))

                (:group :bool :frame)
                (let [shapes-ids (into (d/ordered-set) (get shape :shapes))]
                  (rx/of (dws/select-shapes shapes-ids)))

                :svg-raw
                nil

                (rx/of (dwe/start-edition-mode id)
                       (dwdp/start-path-edit id))))

          ;; When we have multiple shapes selected, instead of enter
          ;; on the edition mode, we proceed to select all children of
          ;; the selected shapes. Because we can't enter on edition
          ;; mode on multiple shapes and this is a fallback operation.
          (let [shapes-to-select
                (->> selected
                     (reduce
                      (fn [result shape-id]
                        (let [children (dm/get-in objects [shape-id :shapes])]
                          (if (empty? children)
                            (conj result shape-id)
                            (into result children))))
                      (d/ordered-set)))]
            (rx/of (dws/select-shapes shapes-to-select))))))))

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
      (let [file-id   (:current-file-id state)
            fdata     (dsh/lookup-file-data state file-id)
            component (cfv/get-primary-component fdata component-id)
            cpath     (:path component)
            cpath     (cfh/split-path cpath)
            paths     (map (fn [i] (cfh/join-path (take (inc i) cpath)))
                           (range (count cpath)))]
        (rx/concat
         (rx/from (map #(set-assets-group-open file-id :components % true) paths))
         (rx/of (dcm/go-to-workspace :layout :assets)
                (set-assets-section-open file-id :library true)
                (set-assets-section-open file-id :components true)
                (select-single-asset file-id (:id component) :components)))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [file-id   (:current-file-id state)
            fdata     (dsh/lookup-file-data state file-id)
            component (cfv/get-primary-component fdata component-id)
            wrapper-id (str "component-shape-id-" (:id component))]
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
                         (filter #(and (ctc/instance-head? %) (not (ctc/main-instance? %)))))

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

;; Clipboard
(dm/export dwcp/copy-selected)
(dm/export dwcp/paste-from-clipboard)
(dm/export dwcp/paste-from-event)
(dm/export dwcp/copy-selected-css)
(dm/export dwcp/copy-selected-css-nested)
(dm/export dwcp/copy-selected-text)
(dm/export dwcp/copy-selected-props)
(dm/export dwcp/paste-selected-props)
(dm/export dwcp/paste-shapes)
(dm/export dwcp/paste-data-valid?)
(dm/export dwcp/copy-link-to-clipboard)

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
