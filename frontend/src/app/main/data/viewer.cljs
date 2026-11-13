;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.viewer
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.comments :as dcmt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.helpers :as dsh]
   [app.main.data.websocket :as dws]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.render-wasm.api :as wasm.api]
   [app.util.globals :as ug]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Local State Initialization

(def ^:private
  default-local-state
  {:zoom 1
   :fullscreen? false
   :interactions-mode :show-on-click
   :show-interactions false
   :comments-mode :all
   :comments-show :unresolved
   :selected #{}
   :collapsed #{}
   :hover nil
   :share-id ""})

(declare fetch-comment-threads)
(declare fetch-bundle)
(declare bundle-fetched)
(declare zoom-to-fill)
(declare zoom-to-fit)
(declare watch-file-changes)

(def ^:private
  schema:initialize
  [:map {:title "initialize"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:page-id {:optional true} [:maybe ::sm/uuid]]])

(defn initialize
  [{:keys [file-id share-id] :as params}]
  (dm/assert!
   "expected valid params"
   (sm/check schema:initialize params))

  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :current-file-id file-id)
          (assoc :current-share-id share-id)
          (update :viewer-local
                  (fn [lstate]
                    (if (nil? lstate)
                      default-local-state
                      lstate)))
          (assoc-in [:viewer-local :share-id] share-id)
          (update :comments-local dcmt/merge-persisted-filters)))

    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (fetch-bundle (d/without-nils params))
             ;; Only fetch threads for logged-in users
             (when (some? (:profile state))
               (fetch-comment-threads params))
             ;; Keep the viewer in sync with the edits done on the file
             ;; (only for logged-in users, the websocket requires it).
             (when (some? (:profile state))
               (watch-file-changes params))
             (when (:share-id params)
               (rx/of (ev/event {::ev/name "shared-prototipe-visited"})))))
    ptk/EffectEvent
    (effect [_ _ _]
      ;; Set the window name, the window name is used on inter-tab
      ;; navigation; in other words: when a user opens a tab with a
      ;; name, if there are already opened tab with that name, the
      ;; browser just focus the opened tab instead of creating new
      ;; tab.
      (let [name (str "viewer-" file-id)]
        (unchecked-set ug/global "name" name))
      ;; Make every `cf/resolve-file-media` call (inspector, code panel,
      ;; image previews, ...) share-link aware for the lifetime of this
      ;; viewer. Cleared by `finalize` below.
      (cf/set-current-share-id! share-id))))

(defn finalize
  [_]
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :viewer :viewer-sync-frame-id :viewer-missed-revn))
    ptk/EffectEvent
    (effect [_ _ _]
      (cf/set-current-share-id! nil))))

;; --- Data Fetching

(def ^:private
  schema:fetch-bundle
  [:map {:title "fetch-bundle"}
   [:page-id {:optional true} ::sm/uuid]
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]])

(defn resolve-viewer-fragment
  "Resolves one pointer-mapped entry of the view-only bundle into a
  [key data] pair. Thin wrapper over the shared `fpmap/resolve-pointer`
  with share-link scoping; see it for the failure contract."
  [file-id share-id entry]
  (fpmap/resolve-pointer file-id share-id entry))

(defn- fetch-bundle-data
  "Fetch the view-only bundle of the file and resolve all the lazy
  pointers. Returns an observable that emits the complete bundle."
  [{:keys [file-id share-id]}]
  (let [;; NOTE: in viewer we don't have access to the team when
        ;; user is not logged-in, so we can't know which features
        ;; are active from team, so in this case it is necesary
        ;; report the whole set of supported features instead of
        ;; the enabled ones.
        features cfeat/supported-features
        params'  (cond-> {:file-id file-id :features features}
                   (uuid? share-id)
                   (assoc :share-id share-id))

        resolve  (partial resolve-viewer-fragment file-id share-id)]

    (->> (rp/cmd! :get-view-only-bundle params')
         (rx/mapcat
          (fn [bundle]
            (->> (rx/from (-> bundle :file :data :pages-index seq))
                 (rx/merge-map
                  (fn [[_ page :as kp]]
                    (if (t/pointer? page)
                      (resolve kp)
                      (rx/of kp))))
                 (rx/reduce conj {})
                 (rx/map (fn [pages-index]
                           (update-in bundle [:file :data] assoc :pages-index pages-index))))))
         (rx/mapcat
          (fn [bundle]
            (->> (rx/from (-> bundle :file :data seq))
                 (rx/merge-map
                  (fn [[_ object :as kp]]
                    (if (t/pointer? object)
                      (resolve kp)
                      (rx/of kp))))
                 (rx/reduce conj {})
                 (rx/map (fn [data]
                           (update bundle :file assoc :data data)))))))))

(defn- fetch-bundle
  [params]

  (dm/assert!
   "expected valid params"
   (sm/check schema:fetch-bundle params))

  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (fetch-bundle-data params)
           (rx/mapcat
            (fn [{:keys [fonts team] :as bundle}]
              (rx/of (df/fonts-fetched fonts)
                     (features/initialize (:features team))
                     (bundle-fetched (merge bundle params)))))))))

(declare go-to-frame)
(declare go-to-frame-by-index)
(declare go-to-frame-auto)

;; --- Viewer pages

(defn- build-viewer-page
  [data]
  (assoc data
         :frames (ctt/get-viewer-frames (:objects data))
         :all-frames (ctt/get-viewer-frames (:objects data) {:all-frames? true})))

(defn- build-viewer-pages
  "Build the viewer pages index (page data plus the precalculated
  frames). When `prev-file` and `prev-pages` are provided, the pages
  that did not change between both file versions are reused instead of
  being calculated again."
  ([file]
   (build-viewer-pages file nil nil))
  ([file prev-file prev-pages]
   (let [index      (dm/get-in file [:data :pages-index])
         prev-index (dm/get-in prev-file [:data :pages-index])]
     (->> (dm/get-in file [:data :pages])
          (map (fn [page-id]
                 (let [data (get index page-id)]
                   [page-id (if (and (contains? prev-pages page-id)
                                     (identical? data (get prev-index page-id)))
                              (get prev-pages page-id)
                              (build-viewer-page data))])))
          (into {})))))

;; Applies to the viewer the changes passed as parameters
;; will not save the data but just modify the data localy
(defn- apply-changes-viewer
  [changes]
  (ptk/reify ::apply-changes-viewer
    ptk/UpdateEvent
    (update [_ state]
      (let [prev-file  (dm/get-in state [:viewer :file])
            prev-pages (dm/get-in state [:viewer :pages])
            file       (update prev-file :data cpc/process-changes changes false)]
        (-> state
            (assoc-in [:viewer :file] file)
            (assoc-in [:viewer :pages] (build-viewer-pages file prev-file prev-pages)))))))

(defn- generate-update-position-data-changes
  [shapes page-id]
  (reduce
   (fn [result shape]
     (conj result
           {:type :mod-obj
            :id (:id shape)
            :page-id page-id
            :operations
            [{:type :set
              :attr :position-data
              :val (wasm.api/calculate-position-data shape)
              :ignore-touched true
              :ignore-geometry true}]}))
   []
   shapes))

(defn update-page-position-data
  [file-id page-id]
  (ptk/reify ::update-page-position-data
    ptk/WatchEvent
    (watch [_ state _]
      (if (and (features/active-feature? state "render-wasm/v1")
               (contains? cf/flags :available-viewer-wasm))
        ;; Fallback matches the viewer UI when the URL omits page-id.
        (let [page-id (or page-id
                          (-> (dsh/lookup-file-data state file-id)
                              :pages
                              first))
              objects (dsh/lookup-page-objects state file-id page-id)

              shapes
              (reduce-kv
               (fn [result _ shape]
                 (cond-> result
                   (and (cfh/text-shape? shape) (nil? (:position-data shape)))
                   (conj shape)))
               []
               objects)

              ;; Positive size required: OffscreenCanvas(0, 0) crashes
              ;; `_set_render_options` on some browsers.
              set-objects-stream
              (rx/create
               (fn [subs]
                 (wasm.api/init-canvas-context (js/OffscreenCanvas. 64 64))
                 (wasm.api/set-objects-callback shapes #(rx/push! subs :done))
                 nil))]

          (if (d/not-empty? shapes)
            (->> (rx/from @wasm.api/module)
                 (rx/mapcat (constantly set-objects-stream))
                 (rx/mapcat
                  (fn []
                    (let [changes (generate-update-position-data-changes shapes page-id)
                          _ (wasm.api/clear-canvas)]
                      (if (d/not-empty? changes)
                        (rx/of (apply-changes-viewer changes))
                        (rx/empty))))))
            (rx/empty)))

        ;; Render wasm disabled, we do nothing
        (rx/empty)))))

(defn bundle-fetched
  [{:keys [project file team share-links libraries users permissions thumbnails] :as bundle}]
  (let [pages (build-viewer-pages file)]

    (ptk/reify ::bundle-fetched
      ptk/UpdateEvent
      (update [_ state]
        (let [team-id (:id team)
              team    (assoc team :members users)]
          (-> state
              (assoc :share-links share-links)
              (assoc :current-team-id team-id)
              (assoc :teams {team-id team})
              (assoc :files (-> (d/index-by :id libraries)
                                (assoc (:id file) file)))
              (assoc :viewer {:libraries (d/index-by :id libraries)
                              :users (d/index-by :id users)
                              :permissions permissions
                              :project project
                              :pages pages
                              :thumbnails thumbnails
                              :file file}))))

      ptk/WatchEvent
      (watch [_ state _]
        (let [route    (:route state)
              qparams  (:query-params route)
              index    (some-> (rt/get-query-param qparams :index) parse-long)
              frame-id (some-> (:frame-id qparams) uuid/parse)
              page-id (some-> (rt/get-query-param qparams :page-id) uuid/parse)
              file-id (some-> (rt/get-query-param qparams :file-id) uuid/parse)
              first-page-id (-> file :data :pages first)]

          (if (and (nil? page-id) (some? first-page-id))
            ;; Every viewer event reads the page from the route, so a URL
            ;; without page-id names the first page; the viewer then
            ;; initializes again on the completed route.
            (rx/of (rt/nav :viewer
                           (assoc (rt/get-params state) :page-id first-page-id)
                           {::rt/replace true}))
            (rx/merge
             (rx/of (case (:zoom qparams)
                      "fit" zoom-to-fit
                      "fill" zoom-to-fill
                      nil))
             (rx/of
              (update-page-position-data file-id page-id)
              (cond
                (some? frame-id) (go-to-frame frame-id)
                (some? index) (go-to-frame-by-index index)
                :else (go-to-frame-auto))))))))))

;; --- Live updates
;;
;; While the viewer is open, the file is kept in sync with the edits done
;; in the workspace. The viewer subscribes to the file websocket topic and
;; applies the `file-change` messages locally. The `revn` of the viewer
;; file is tracked: if a message is missed (a gap in the revisions, the
;; socket reconnected, the file was restored...) the bundle is fetched
;; again silently, without altering the navigation state (current board,
;; zoom, fullscreen...).
;;
;; NOTE: the websocket only lets subscribe to files the user can read as
;; a team member, so this does not apply to anonymous share-links.

(def ^:private refresh-requested
  (ptk/reify ::refresh-requested))

(defn- current-page-id
  [state]
  (let [params (rt/get-params state)]
    (or (some-> (:page-id params) uuid/parse)
        (-> (dm/get-in state [:viewer :file :data :pages]) first))))

(defn- current-frame-id
  [state]
  (let [params  (rt/get-params state)
        index   (or (some-> (rt/get-query-param params :index) parse-long) 0)
        frames  (dm/get-in state [:viewer :pages (current-page-id state) :frames])]
    (:id (get frames index))))

(defn- newer-file?
  "Check if `file` is a newer version of the file loaded in the viewer."
  [state file]
  (let [prev (dm/get-in state [:viewer :file])]
    (and (some? prev)
         (= (:id file) (:id prev))
         (or (not= (:vern file) (:vern prev))
             (> (:revn file 0) (:revn prev 0))))))

(defn- assoc-viewer-file
  [state file]
  (let [prev-file  (dm/get-in state [:viewer :file])
        prev-pages (dm/get-in state [:viewer :pages])]
    (-> state
        ;; Remember the board we are looking at, to keep showing it even
        ;; if its position changes. Keep the oldest one if several updates
        ;; arrive before `after-file-updated` is processed.
        (update :viewer-sync-frame-id #(or % (current-frame-id state)))
        (assoc-in [:viewer :file] file)
        (assoc-in [:viewer :pages] (build-viewer-pages file prev-file prev-pages))
        (assoc-in [:files (:id file)] file))))

(def ^:private after-file-updated
  (ptk/reify ::after-file-updated
    ptk/WatchEvent
    (watch [_ state _]
      (let [params    (rt/get-params state)
            file-id   (dm/get-in state [:viewer :file :id])
            page-id   (current-page-id state)
            frames    (dm/get-in state [:viewer :pages page-id :frames])
            index     (or (some-> (rt/get-query-param params :index) parse-long) 0)
            frame-id  (:viewer-sync-frame-id state)
            zoom-type (dm/get-in state [:viewer-local :zoom-type])

            ;; The current board is tracked by id: when boards are added
            ;; or removed before it, keep showing the same one. If it was
            ;; removed, stay as close as possible to the previous position.
            new-index (when (seq frames)
                        (or (when (some? frame-id)
                              (d/index-of-pred frames #(= (:id %) frame-id)))
                            (min index (dec (count frames)))))
            moved?    (and (some? new-index) (not= new-index index))]

        (rx/concat
         (rx/of #(dissoc % :viewer-sync-frame-id))

         (cond
           ;; The zoom is recalculated when the index changes
           moved?
           (rx/of (rt/nav :viewer (assoc params :index new-index) {::rt/replace true}))

           ;; The board size may have changed
           (and (seq frames) (= :fit zoom-type))
           (rx/of zoom-to-fit)

           (and (seq frames) (= :fill zoom-type))
           (rx/of zoom-to-fill)

           :else
           (rx/empty))

         (rx/of (update-page-position-data file-id (some-> (:page-id params) uuid/parse))))))))

(defn- file-updated
  [file]
  (ptk/reify ::file-updated
    ptk/UpdateEvent
    (update [_ state]
      (if (newer-file? state file)
        (assoc-viewer-file state file)
        state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of after-file-updated))))

(defn- bundle-refreshed
  [{:keys [file libraries thumbnails]}]
  (ptk/reify ::bundle-refreshed
    ptk/UpdateEvent
    (update [_ state]
      (if (newer-file? state file)
        (let [libraries (d/index-by :id libraries)]
          (-> state
              (assoc-viewer-file file)
              (assoc-in [:viewer :libraries] libraries)
              (assoc-in [:viewer :thumbnails] thumbnails)
              (update :files merge libraries)))
        state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of after-file-updated))))

(defn- filter-page-changes
  "Remove the changes related to pages the viewer does not have (the
  viewer bundle may not contain all the pages of the file)."
  [file changes]
  (let [known (volatile! (into #{} (keys (dm/get-in file [:data :pages-index]))))]
    (filterv (fn [{:keys [type id page page-id]}]
               (when (= type :add-page)
                 (vswap! known conj (or id (:id page))))
               (or (nil? page-id) (contains? @known page-id)))
             changes)))

(defn- handle-file-change
  [{:keys [file-id revn vern changes]}]
  (ptk/reify ::handle-file-change
    ptk/UpdateEvent
    (update [_ state]
      ;; The bundle is still loading: it may have been built before this
      ;; change was saved. Remember the newest revn seen to compare it
      ;; with the bundle once it arrives (see `check-missed-changes`).
      (if (and (nil? (dm/get-in state [:viewer :file])) (int? revn))
        (update state :viewer-missed-revn (fnil max 0) revn)
        state))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file (dm/get-in state [:viewer :file])]
        (when (and (= file-id (:id file))
                   (int? revn))
          (let [file-revn (:revn file)]
            (cond
              ;; Already applied (or older than the loaded bundle)
              (and (int? file-revn) (<= revn file-revn))
              (rx/empty)

              ;; We missed something: other version of the file or a gap
              ;; in the revisions, fetch the file again.
              (or (not (int? file-revn))
                  (not= vern (:vern file))
                  (> revn (inc file-revn)))
              (rx/of refresh-requested)

              :else
              (let [file' (try
                            (-> file
                                (update :data cpc/process-changes
                                        (filter-page-changes file changes) false)
                                (assoc :revn revn))
                            (catch :default cause
                              (l/warn :hint "unable to apply file changes on viewer"
                                      :cause cause)
                              nil))]
                (if (some? file')
                  (rx/of (file-updated file'))
                  (rx/of refresh-requested))))))))))

(def ^:private check-missed-changes
  ;; Run after the bundle loads: the changes saved while it was loading
  ;; were dropped, so fetch the file again if one is newer than it.
  (ptk/reify ::check-missed-changes
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :viewer-missed-revn))

    ptk/WatchEvent
    (watch [_ state _]
      (let [missed (:viewer-missed-revn state)
            revn   (dm/get-in state [:viewer :file :revn])]
        (when (and (int? missed) (int? revn) (> missed revn))
          (rx/of refresh-requested))))))

(defn- handle-file-restored
  [{:keys [file-id vern]}]
  (ptk/reify ::handle-file-restored
    ptk/WatchEvent
    (watch [_ state _]
      (let [file (dm/get-in state [:viewer :file])]
        (when (and (= file-id (:id file))
                   (not= vern (:vern file)))
          (rx/of refresh-requested))))))

(def ^:private socket-reopened
  ;; The messages sent while the socket was closed are lost.
  (ptk/reify ::socket-reopened
    ptk/WatchEvent
    (watch [_ state _]
      (when (some? (dm/get-in state [:viewer :file]))
        (rx/of refresh-requested)))))

(defn- process-message
  [{:keys [type] :as message}]
  (case type
    :file-change   (handle-file-change message)
    :file-restored (handle-file-restored message)
    nil))

(defn- watch-file-changes
  [{:keys [file-id share-id]}]
  (ptk/reify ::watch-file-changes
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper   (rx/filter (ptk/type? ::finalize) stream)
            subscribe {:type :subscribe-file :file-id file-id}
            unsubscribe {:type :unsubscribe-file :file-id file-id}

            refetch
            (fn [_]
              (->> (fetch-bundle-data {:file-id file-id :share-id share-id})
                   (rx/map bundle-refreshed)
                   (rx/catch (fn [cause]
                               (l/warn :hint "unable to refresh viewer bundle"
                                       :cause cause)
                               (rx/empty)))))]

        (rx/concat
         (->> (rx/merge
               (rx/of (dws/send subscribe))

               ;; On reconnect, subscribe again and resync
               (->> stream
                    (rx/filter (ptk/type? ::dws/opened))
                    (rx/mapcat (fn [_]
                                 (rx/of (dws/send subscribe)
                                        socket-reopened))))

               (->> stream
                    (rx/filter (ptk/type? ::bundle-fetched))
                    (rx/map (constantly check-missed-changes)))

               (->> stream
                    (rx/filter (ptk/type? ::dws/message))
                    (rx/map deref)
                    (rx/filter #(= file-id (:topic %)))
                    (rx/map process-message)
                    (rx/filter some?))

               ;; Coalesce bursts and drop obsolete in-flight requests
               (->> stream
                    (rx/filter (ptk/type? ::refresh-requested))
                    (rx/debounce 300)
                    (rx/switch-map refetch)))
              (rx/take-until stopper))
         (rx/of (dws/send unsubscribe)))))))

(defn fetch-comment-threads
  [{:keys [file-id page-id share-id] :as params}]
  (letfn [(fetched [data state]
            (->> data
                 (filter #(= page-id (:page-id %)))
                 (d/index-by :id)
                 (assoc state :comment-threads)))
          (on-error [cause]
            (let [{:keys [type]} (ex-data cause)]
              (if (or (= :authentication type)
                      (= :not-found type))
                (rx/empty)
                (rx/throw cause))))]

    (ptk/reify ::fetch-comment-threads
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/cmd! :get-comment-threads {:file-id file-id :share-id share-id})
             (rx/map #(partial fetched %))
             (rx/catch on-error))))))

(defn refresh-comment-thread
  [{:keys [id file-id] :as thread}]
  (letfn [(fetched [thread state]
            (assoc-in state [:comment-threads id] thread))]
    (ptk/reify ::refresh-comment-thread
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/cmd! :get-comment-thread {:file-id file-id :id id})
             (rx/map #(partial fetched %)))))))

(defn fetch-comments
  [{:keys [thread-id]}]
  (assert (uuid? thread-id))
  (letfn [(fetched [comments state]
            (update state :comments assoc thread-id (d/index-by :id comments)))]
    (ptk/reify ::retrieve-comments
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/cmd! :get-comments {:thread-id thread-id})
             (rx/map #(partial fetched %)))))))

;; --- Zoom Management

(def update-zoom-querystring
  (ptk/reify ::update-zoom-querystring
    ptk/WatchEvent
    (watch [_ state _]
      (let [zoom-type (get-in state [:viewer-local :zoom-type])
            params    (rt/get-params state)
            current   (rt/get-query-param params :zoom)
            expected  (some-> zoom-type name)]
        ;; Zoom is view state: mirror it into the URL, replacing the history
        ;; entry, only when the query string does not already describe it.
        (when (not= current expected)
          (rx/of (rt/nav :viewer
                         (if (some? zoom-type)
                           (assoc params :zoom zoom-type)
                           (dissoc params :zoom))
                         {::rt/replace true})))))))

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(min (* % 1.3) 200)]
        (-> state
            (update-in  [:viewer-local :zoom] (fnil increase 1))
            (d/dissoc-in  [:viewer-local :zoom-type]))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(max (/ % 1.3) 0.01)]
        (-> state
            (update-in [:viewer-local :zoom] (fnil decrease 1))
            (d/dissoc-in  [:viewer-local :zoom-type]))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in  [:viewer-local :zoom] 1)
          (d/dissoc-in  [:viewer-local :zoom-type])))))

(def zoom-to-fit
  (ptk/reify ::zoom-to-fit
    ptk/UpdateEvent
    (update [_ state]
      (let [params (rt/get-params state)
            page-id (some-> (:page-id params) uuid/parse)
            index   (some-> (rt/get-query-param params :index) parse-long)

            frames  (dm/get-in state [:viewer :pages page-id :frames])
            index   (min (or index 0) (max 0 (dec (count frames))))
            srect   (-> (nth frames index)
                        (get :selrect))
            osize   (dm/get-in state [:viewer-local :viewport-size])
            wdiff   (/ (:width osize) (:width srect))
            hdiff   (/ (:height osize) (:height srect))
            minzoom (min wdiff hdiff)]
        (-> state
            (assoc-in  [:viewer-local :zoom] minzoom)
            (assoc-in  [:viewer-local :zoom-type] :fit))))

    ptk/WatchEvent
    (watch [_ _ _] (rx/of update-zoom-querystring))))

(def zoom-to-fill
  (ptk/reify ::zoom-to-fill
    ptk/UpdateEvent
    (update [_ state]

      (let [params (rt/get-params state)
            page-id (some-> (:page-id params) uuid/parse)
            index   (some-> (rt/get-query-param params :index) parse-long)

            frames  (dm/get-in state [:viewer :pages page-id :frames])
            index   (min (or index 0) (max 0 (dec (count frames))))
            srect   (-> (nth frames index)
                        (get :selrect))

            osize   (dm/get-in state [:viewer-local :viewport-size])

            wdiff   (/ (:width osize) (:width srect))
            hdiff   (/ (:height osize) (:height srect))

            maxzoom (max wdiff hdiff)]
        (-> state
            (assoc-in  [:viewer-local :zoom] maxzoom)
            (assoc-in  [:viewer-local :zoom-type] :fill))))

    ptk/WatchEvent
    (watch [_ _ _] (rx/of update-zoom-querystring))))

(def toggle-zoom-style
  (ptk/reify ::toggle-zoom-style
    ptk/WatchEvent
    (watch [_ state _]
      (let [zoom-type (get-in state [:viewer-local :zoom-type])]
        (if (= zoom-type :fit)
          (rx/of zoom-to-fill)
          (rx/of zoom-to-fit))))))

(def toggle-fullscreen
  (ptk/reify ::toggle-fullscreen
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:viewer-local :fullscreen?] not))))

(defn exit-fullscreen
  []
  (ptk/reify ::exit-fullscreen
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :fullscreen?] false))))

(defn set-viewport-size
  [{:keys [size]}]
  (ptk/reify ::set-viewport-size
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :viewport-size] size))))

;; --- Local State Management

(def toggle-thumbnails-panel
  (ptk/reify ::toggle-thumbnails-panel
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:viewer-local :show-thumbnails] not))))

(def close-thumbnails-panel
  (ptk/reify ::close-thumbnails-panel
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-thumbnails] false))))

(def select-prev-frame
  (ptk/reify ::select-prev-frame
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :viewer-animations)
          (assoc  :viewer-overlays [])))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)
            index   (some-> (rt/get-query-param params :index) parse-long)]
        (when (pos? index)
          (rx/of
           (dcmt/close-thread)
           (rt/nav :viewer (assoc params :index (dec index)))))))))

(def select-next-frame
  (ptk/reify ::select-next-frame
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :viewer-animations)
          (assoc  :viewer-overlays [])))
    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)
            index   (some-> (rt/get-query-param params :index) parse-long)
            page-id (some-> params :page-id uuid/parse)

            total   (count (get-in state [:viewer :pages page-id :frames]))]

        (when (< index (dec total))
          (rx/of
           (dcmt/close-thread)
           (rt/nav :viewer (assoc params :index (inc index)))))))))

(def select-first-frame
  (ptk/reify ::select-first-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (rt/get-params state)]
        (rx/of
         (dcmt/close-thread)
         (rt/nav :viewer (assoc params :index 0)))))))

(def valid-interaction-modes
  #{:hide :show :show-on-click})

(defn set-interactions-mode
  [mode]
  (dm/assert!
   "expected valid interaction mode"
   (contains? valid-interaction-modes mode))
  (ptk/reify ::set-interactions-mode
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:viewer-local :interactions-mode] mode)
          (assoc-in [:viewer-local :show-interactions] (case mode
                                                         :hide false
                                                         :show true
                                                         :show-on-click false))))
    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)]
        (rx/of (rt/nav :viewer (assoc params :interactions-mode mode)))))))

(declare flash-done)

(defn flash-interactions
  []
  (ptk/reify ::flash-interactions
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-interactions] true))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::flash-interactions) stream)]
        (->> (rx/of flash-done)
             (rx/delay 500)
             (rx/take-until stopper))))))

(def flash-done
  (ptk/reify ::flash-done
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-interactions] false))))

(defn set-nav-scroll
  [scroll]
  (ptk/reify ::set-nav-scroll
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :nav-scroll] scroll))))

(defn reset-nav-scroll
  []
  (ptk/reify ::reset-nav-scroll
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:viewer-local :nav-scroll]))))

(defn update-exports-cache
  [shapes-key exports]
  (ptk/reify ::update-exports-cache
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:inspect-exports-cache shapes-key] exports))))

(defn complete-animation
  []
  (ptk/reify ::complete-animation
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :viewer-animations))))

;; --- Navigation inside page

(defn go-to-frame-by-index
  [index]
  (ptk/reify ::go-to-frame-by-index
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :viewer-overlays []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)]
        (rx/of (rt/nav :viewer (assoc params :index index)))))))

(defn go-to-frame
  ([frame-id]
   (go-to-frame frame-id nil))

  ([frame-id animation]
   (dm/assert!
    "expected valid parameters"
    (and (uuid? frame-id)
         (or (nil? animation)
             (ctsi/check-animation! animation))))

   (ptk/reify ::go-to-frame
     ptk/UpdateEvent
     (update [_ state]
       (let [route   (:route state)
             qparams (:query-params route)
             page-id (some-> (:page-id qparams) uuid/parse)
             index   (some-> (rt/get-query-param qparams :index) parse-long)
             frames  (get-in state [:viewer :pages page-id :frames])
             frame   (get frames index)]
         (cond-> state
           :always
           (assoc :viewer-overlays [])

           (some? animation)
           (assoc-in [:viewer-animations (:id frame)]
                     {:kind :go-to-frame
                      :orig-frame-id (:id frame)
                      :animation animation}))))

     ptk/WatchEvent
     (watch [_ state _]
       (let [route   (:route state)
             qparams (:query-params route)
             page-id (some-> (:page-id qparams) uuid/parse)
             frames  (get-in state [:viewer :pages page-id :frames])
             index   (d/index-of-pred frames #(= (:id %) frame-id))]
         (rx/of (go-to-frame-by-index (or index 0))))))))

(defn go-to-frame-auto
  []
  (ptk/reify ::go-to-frame-auto
    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            qparams (:query-params route)
            page-id (some-> (:page-id qparams) uuid/parse)
            flows   (get-in state [:viewer :pages page-id :options :flows])]
        (if (seq flows)
          (let [frame-id (:starting-frame (first flows))]
            (rx/of (go-to-frame frame-id)))
          (rx/of (go-to-frame-by-index 0)))))))

(defn go-to-section
  [section]
  (ptk/reify ::go-to-section
    ev/Event
    (-data [_]
      {::ev/origin "viewer"
       :section (name section)})

    ptk/UpdateEvent
    (update [_ state]
      (assoc state :viewer-overlays []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params (rt/get-params state)]
        (rx/of (rt/nav :viewer (assoc params :section section)))))))

;; --- Overlays

(defn- open-overlay*
  [state frame position snap-to close-click-outside background-overlay animation fixed-source?]
  (cond-> state
    :always
    (update :viewer-overlays conj
            {:frame frame
             :id (:id frame)
             :position position
             :snap-to snap-to
             :close-click-outside close-click-outside
             :background-overlay background-overlay
             :animation animation
             :fixed-source? fixed-source?})

    (some? animation)
    (assoc-in [:viewer-animations (:id frame)]
              {:kind :open-overlay
               :overlay-id (:id frame)
               :animation animation})))

(defn- close-overlay*
  [state frame-id animation]
  (if (nil? animation)
    (update state :viewer-overlays
            (fn [overlays]
              (d/removev #(= (:id (:frame %)) frame-id) overlays)))
    (assoc-in state [:viewer-animations frame-id]
              {:kind :close-overlay
               :overlay-id frame-id
               :animation animation})))

(defn open-overlay
  [frame-id position snap-to close-click-outside background-overlay animation fixed-source?]
  (dm/assert! (uuid? frame-id))
  (dm/assert! (gpt/point? position))
  (dm/assert! (or (nil? close-click-outside)
                  (boolean? close-click-outside)))
  (dm/assert! (or (nil? background-overlay)
                  (boolean? background-overlay)))
  (dm/assert! (or (nil? animation)
                  (ctsi/check-animation! animation)))
  (ptk/reify ::open-overlay
    ptk/UpdateEvent
    (update [_ state]
      (let [route    (:route state)
            qparams  (:query-params route)
            page-id  (some-> (:page-id qparams) uuid/parse)
            frames   (dm/get-in state [:viewer :pages page-id :all-frames])
            frame    (d/seek #(= (:id %) frame-id) frames)
            overlays (:viewer-overlays state)]
        (if-not (some #(= (:frame %) frame) overlays)
          (open-overlay* state
                         frame
                         position
                         snap-to
                         close-click-outside
                         background-overlay
                         animation
                         fixed-source?)
          state)))))


(defn toggle-overlay
  [frame-id position snap-to close-click-outside background-overlay animation fixed-source?]
  (dm/assert! (uuid? frame-id))
  (dm/assert! (gpt/point? position))
  (dm/assert! (or (nil? close-click-outside)
                  (boolean? close-click-outside)))
  (dm/assert! (or (nil? background-overlay)
                  (boolean? background-overlay)))
  (dm/assert! (or (nil? animation)
                  (ctsi/check-animation! animation)))

  (ptk/reify ::toggle-overlay
    ptk/UpdateEvent
    (update [_ state]
      (let [route    (:route state)
            qparams  (:query-params route)
            page-id  (some-> (:page-id qparams) uuid/parse)
            frames   (get-in state [:viewer :pages page-id :all-frames])
            frame    (d/seek #(= (:id %) frame-id) frames)
            overlays (:viewer-overlays state)]
        (if-not (some #(= (:frame %) frame) overlays)
          (open-overlay* state
                         frame
                         position
                         snap-to
                         close-click-outside
                         background-overlay
                         animation
                         fixed-source?)
          (close-overlay* state
                          (:id frame)
                          (ctsi/invert-direction animation)))))))

(defn close-overlay
  ([frame-id] (close-overlay frame-id nil))
  ([frame-id animation]
   (dm/assert! (uuid? frame-id))
   (dm/assert! (or (nil? animation)
                   (ctsi/check-animation! animation)))

   (ptk/reify ::close-overlay
     ptk/UpdateEvent
     (update [_ state]
       (close-overlay* state
                       frame-id
                       animation)))))

;; --- Objects selection

(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :selected] #{}))))

(defn select-shape
  ([id]
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (assoc-in [:viewer-local :selected] #{id}))))))

(defn toggle-selection
  [id]
  (ptk/reify ::toggle-selection
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:viewer-local :selected])]
        (cond-> state
          (not (selected id)) (update-in [:viewer-local :selected] conj id)
          (selected id)       (update-in [:viewer-local :selected] disj id))))))

(defn shift-select-to
  [id]
  (ptk/reify ::shift-select-to
    ptk/UpdateEvent
    (update [_ state]
      (let [route     (:route state)
            qparams   (:query-params route)
            page-id   (some-> (:page-id qparams) uuid/parse)
            objects   (get-in state [:viewer :pages page-id :objects])
            selection (-> state
                          (get-in [:viewer-local :selected] #{})
                          (conj id))]
        (-> state
            (assoc-in [:viewer-local :selected]
                      (cfh/expand-region-selection objects selection)))))))

(defn select-all
  []
  (ptk/reify ::select-all
    ptk/UpdateEvent
    (update [_ state]
      (let [route     (:route state)
            qparams   (:query-params route)
            page-id   (some-> (:page-id qparams) uuid/parse)
            index     (some-> (rt/get-query-param qparams :index) parse-long)
            objects   (get-in state [:viewer :pages page-id :objects])
            frame-id  (get-in state [:viewer :pages page-id :frames index :id])

            selection (->> objects
                           (filter #(= (:frame-id (second %)) frame-id))
                           (map first)
                           (into #{frame-id}))]
        (-> state
            (assoc-in [:viewer-local :selected] selection))))))

(defn toggle-collapse [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (let [toggled? (contains? (get-in state [:viewer-local :collapsed]) id)]
        (update-in state [:viewer-local :collapsed] (if toggled? disj conj) id)))))

(defn hover-shape
  [id hover?]
  (ptk/reify ::hover-shape
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :hover] (when hover? id)))))

;; --- Navigation outside page

(defn go-to-dashboard
  []
  (ptk/reify ::go-to-dashboard
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (get-in state [:viewer :project :team-id])]
        (rx/of (dcm/go-to-dashboard-recent :team-id team-id))))))

(defn go-to-page
  [page-id]
  (ptk/reify ::go-to-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :overlays] []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :index 0)
                       (assoc :page-id page-id))]
        (rx/of (rt/nav :viewer params))))))

(defn go-to-workspace
  ([] (go-to-workspace nil))
  ([page-id]
   (ptk/reify ::go-to-workspace
     ptk/WatchEvent
     (watch [_ state _]
       (let [params  (rt/get-params state)
             file-id (get-in state [:viewer :file :id])
             team-id (get-in state [:viewer :project :team-id])
             page-id (or page-id
                         (some-> (:page-id params) uuid/parse))
             params {:page-id page-id
                     :file-id file-id
                     :team-id team-id}
             name   (dm/str "workspace-" file-id)]

         (rx/of (rt/nav :workspace params
                        ::rt/new-window true
                        ::rt/window-name name)))))))
