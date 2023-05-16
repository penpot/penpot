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
   [app.common.files.features :as ffeat]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpp]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.common.transit :as t]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
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
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwco]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.fix-bool-contents :as fbc]
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
   [cuerdas.core :as str]
   [linked.core :as lks]
   [potok.core :as ptk]))

(def default-workspace-local {:zoom 1})

(s/def ::layout-name (s/nilable ::us/keyword))
(s/def ::coll-of-uuids (s/coll-of ::us/uuid))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private workspace-initialized)
(declare ^:private remove-graphics)
(declare ^:private libraries-fetched)

;; --- Initialize Workspace


(defn initialize-layout
  [lname]
  (us/assert! ::layout-name lname)
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
    (watch [_ state _]
      (let [file           (:workspace-data state)
            has-graphics?  (-> file :media seq)
            components-v2  (features/active-feature? state :components-v2)]
        (rx/merge
         (rx/of (fbc/fix-bool-contents))
         (if (and has-graphics? components-v2)
           (rx/of (remove-graphics (:id file) (:name file)))
           (rx/empty)))))))

(defn- workspace-data-loaded
  [data]
  (ptk/reify ::workspace-data-loaded
    ptk/UpdateEvent
    (update [_ state]
      (let [data (d/removem (comp t/pointer? val) data)]
        (assoc state :workspace-data data)))))

(defn- workspace-data-pointers-loaded
  [pdata]
  (ptk/reify ::workspace-data-pointers-loaded
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-data merge pdata))))

(defn- bundle-fetched
  [features [{:keys [id data] :as file} thumbnails project users comments-users]]
  (letfn [(resolve-pointer [file-id [key pointer]]
            (->> (rp/cmd! :get-file-fragment {:file-id file-id :fragment-id @pointer})
                 (rx/map :content)
                 (rx/map #(vector key %))))

          (resolve-pointers [file-id coll]
            (->> (rx/from (seq coll))
                 (rx/merge-map (partial resolve-pointer file-id))
                 (rx/reduce conj {})))]

    (ptk/reify ::bundle-fetched
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc :workspace-thumbnails thumbnails)
            (assoc :workspace-file (dissoc file :data))
            (assoc :workspace-project project)
            (assoc :current-team-id (:team-id project))
            (assoc :users (d/index-by :id users))
            (assoc :current-file-comments-users (d/index-by :id comments-users))))

      ptk/WatchEvent
      (watch [_ _ stream]
        (let [team-id  (:team-id project)
              stoper   (rx/filter (ptk/type? ::bundle-fetched) stream)]
          (->> (rx/concat
                ;; Initialize notifications
                (rx/of (dwn/initialize team-id id)
                       (dwsl/initialize))

                ;; Load team fonts. We must ensure custom fonts are fully loadad before starting the workspace load
                (rx/merge
                 (->> stream
                      (rx/filter (ptk/type? :app.main.data.fonts/team-fonts-loaded))
                      (rx/take 1)
                      (rx/ignore))
                 (rx/of (df/load-team-fonts team-id)))

                (rx/merge
                 ;; Load all pages, independently if they are pointers or already
                 ;; resolved values.
                 (->> (rx/from (seq (:pages-index data)))
                      (rx/merge-map
                       (fn [[_ page :as kp]]
                         (if (t/pointer? page)
                           (resolve-pointer id kp)
                           (rx/of kp))))
                      (rx/merge-map
                       (fn [[id page]]
                         (let [page (update page :objects ctst/start-page-index)]
                           (->> (uw/ask! {:cmd :initialize-page-index :page page})
                                (rx/map (constantly [id page]))))))
                      (rx/reduce conj {})
                      (rx/map (fn [pages-index]
                                (-> data
                                    (assoc :pages-index pages-index)
                                    (workspace-data-loaded)))))

                 ;; Once workspace data is loaded, proceed asynchronously load
                 ;; the local library and all referenced libraries, without
                 ;; blocking the main workspace load process.
                 (->> stream
                      (rx/filter (ptk/type? ::workspace-data-loaded))
                      (rx/take 1)
                      (rx/merge-map
                       (fn [_]
                         (rx/merge
                          (rx/of (workspace-initialized))

                          (->> data
                               (filter (comp t/pointer? val))
                               (resolve-pointers id)
                               (rx/map workspace-data-pointers-loaded))

                          (->> (rp/cmd! :get-file-libraries {:file-id id :features features})
                               (rx/mapcat identity)
                               (rx/mapcat
                                (fn [{:keys [id data] :as file}]
                                  (->> (filter (comp t/pointer? val) data)
                                       (resolve-pointers id)
                                       (rx/map #(update file :data merge %)))))
                               (rx/mapcat
                                (fn [{:keys [id data] :as file}]
                                  ;; Resolve all pages of each library, if needed
                                  (->> (rx/from (seq (:pages-index data)))
                                       (rx/merge-map
                                        (fn [[_ page :as kp]]
                                          (if (t/pointer? page)
                                            (resolve-pointer id kp)
                                            (rx/of kp))))
                                       (rx/reduce conj {})
                                       (rx/map
                                        (fn [pages-index]
                                          (assoc-in file [:data :pages-index] pages-index))))))
                               (rx/reduce conj [])
                               (rx/map libraries-fetched))))))))

               (rx/take-until stoper)))))))

(defn- libraries-fetched
  [libraries]
  (ptk/reify ::libraries-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-libraries (d/index-by :id libraries)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [ignore-until  (-> state :workspace-file :ignore-sync-until)
            file-id       (-> state :workspace-file :id)
            needs-update? (some #(and (> (:modified-at %) (:synced-at %))
                                      (or (not ignore-until)
                                          (> (:modified-at %) ignore-until)))
                                libraries)]
        (when needs-update?
          (rx/of (dwl/notify-sync-file file-id)))))))

(defn- fetch-bundle
  [project-id file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (let [features (cond-> ffeat/enabled
                       (features/active-feature? state :components-v2)
                       (conj "components/v2")

                       ;; We still put the feature here and not in the
                       ;; ffeat/enabled var because the pointers map is only
                       ;; supported on workspace bundle fetching mechanism.
                       :always
                       (conj "storage/pointer-map"))

            ;; WTF is this?
            share-id (-> state :viewer-local :share-id)
            stoper   (rx/filter (ptk/type? ::fetch-bundle) stream)]

        (->> (rx/zip (rp/cmd! :get-file {:id file-id :features features})
                     (rp/cmd! :get-file-object-thumbnails {:file-id file-id})
                     (rp/cmd! :get-project {:id project-id})
                     (rp/cmd! :get-team-users {:file-id file-id})
                     (rp/cmd! :get-profiles-for-file-comments {:file-id file-id :share-id share-id}))
             (rx/take 1)
             (rx/map (partial bundle-fetched features))
             (rx/take-until stoper))))))

(defn initialize-file
  [project-id file-id]
  (us/assert! ::us/uuid project-id)
  (us/assert! ::us/uuid file-id)

  (ptk/reify ::initialize-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc state
             :current-file-id file-id
             :current-project-id project-id
             :workspace-presence {}))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dcm/retrieve-comment-threads file-id)
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
  (us/assert! ::us/uuid page-id)
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
      (let [pindex (-> state :workspace-data :pages-index)]
        (if (contains? pindex page-id)
          (rx/of (preload-data-uris page-id)
                 (dwth/watch-state-changes)
                 (dwl/watch-component-changes))
          (let [page-id (dm/get-in state [:workspace-data :pages 0])]
            (rx/of (go-to-page page-id))))))))

(defn finalize-page
  [page-id]
  (us/assert! ::us/uuid page-id)
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (-> (:workspace-local state)
                      (dissoc :edition :edit-path :selected))
            exit? (not= :workspace (dm/get-in state [:route :data :name]))
            state (-> state
                      (update :workspace-cache assoc page-id local)
                      (dissoc :current-page-id :workspace-local :workspace-trimmed-page :workspace-focus-selected))]

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
              unames  (cp/retrieve-used-names pages)
              name    (cp/generate-unique-name unames "Page 1")

              changes (-> (pcb/empty-changes it)
                          (pcb/add-empty-page id name))]

          (rx/of (dch/commit-changes changes)))))))

(defn duplicate-page
  [page-id]
  (ptk/reify ::duplicate-page
    ptk/WatchEvent
    (watch [it state _]
      (let [id      (uuid/next)
            pages   (get-in state [:workspace-data :pages-index])
            unames  (cp/retrieve-used-names pages)
            page    (get-in state [:workspace-data :pages-index page-id])
            name    (cp/generate-unique-name unames (:name page))

            page    (-> page
                        (assoc :name name)
                        (assoc :id id)
                        (assoc :objects
                               (->> (:objects page)
                                    (d/mapm (fn [_ val] (dissoc val :use-for-thumbnail?))))))

            changes (-> (pcb/empty-changes it)
                        (pcb/add-page id page))]

        (rx/of (dch/commit-changes changes))))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-page
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (get-in state [:workspace-data :pages-index id])
            changes (-> (pcb/empty-changes it)
                        (pcb/mod-page page name))]

        (rx/of (dch/commit-changes changes))))))

(declare purge-page)
(declare go-to-file)

(defn delete-page
  [id]
  (ptk/reify ::delete-page
    ptk/WatchEvent
    (watch [it state _]
      (let [pages (get-in state [:workspace-data :pages])
            index (d/index-of pages id)
            page (get-in state [:workspace-data :pages-index id])
            page (assoc page :index index)

            changes (-> (pcb/empty-changes it)
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

;; --- Nudge

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
  (us/verify ::us/uuid id)
  (us/verify ::cts/shape-attrs attrs)
  (ptk/reify ::update-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [id] #(merge % attrs))))))


(defn start-rename-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::start-rename-shape
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :shape-for-rename] id))))

(defn end-rename-shape
  []
  (ptk/reify ::end-rename-shape
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :shape-for-rename))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
  (us/verify ::cts/shape-attrs attrs)
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

(s/def ::loc  #{:up :down :bottom :top})

(defn vertical-order-selected
  [loc]
  (us/verify ::loc loc)
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
  (let [ordered-indexes (cph/order-by-indexed-shapes objects ids)
        shapes (map (d/getf objects) ordered-indexes)]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)

        ;; Remove layout-item properties when moving a shape outside a layout
        (cond-> (not (ctl/any-layout? objects parent-id))
          (pcb/update-shapes ordered-indexes ctl/remove-layout-item-data))

        ;; Remove the hide in viewer flag
        (cond-> (and (not= uuid/zero parent-id) (cph/frame-shape? objects parent-id))
          (pcb/update-shapes ordered-indexes #(cond-> % (cph/frame-shape? %) (assoc :hide-in-viewer true))))

        ;; Move the shapes
        (pcb/change-parent parent-id
                           shapes
                           to-index)

        ;; Remove empty groups
        (pcb/remove-objects groups-to-delete)

        ;; Unmask groups whose mask have moved outside
        (pcb/update-shapes groups-to-unmask
                           (fn [shape]
                             (assoc shape :masked-group? false)))

        ;; Detach shapes moved out of their component
        (pcb/update-shapes shapes-to-detach
                           (fn [shape]
                             (assoc shape :component-id nil
                                    :component-file nil
                                    :component-root? nil
                                    :remote-synced? nil
                                    :shape-ref nil
                                    :touched nil)))

        ;; Make non root a component moved inside another one
        (pcb/update-shapes shapes-to-deroot
                           (fn [shape]
                             (assoc shape :component-root? nil)))

        ;; Make root a subcomponent moved outside its parent component
        (pcb/update-shapes shapes-to-reroot
                           (fn [shape]
                             (assoc shape :component-root? true)))

        ;; Reset constraints depending on the new parent
        (pcb/update-shapes shapes-to-unconstraint
                           (fn [shape]
                             (let [parent      (get objects parent-id)
                                   frame-id    (if (= (:type parent) :frame)
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

        ;; Resize parent containers that need to
        (pcb/resize-parents parents))))

(defn relocate-shapes
  [ids parent-id to-index & [ignore-parents?]]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify ::us/uuid parent-id)
  (us/verify number? to-index)

  (ptk/reify ::relocate-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)

            ;; Ignore any shape whose parent is also intended to be moved
            ids      (cph/clean-loops objects ids)

            ;; If we try to move a parent into a child we remove it
            ids      (filter #(not (cph/is-parent? objects parent-id %)) ids)

            all-parents (into #{parent-id} (map #(cph/get-parent-id objects %)) ids)
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
                    (let [to-check (concat to-check [(cph/get-parent-id objects current-id)])]
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
                        (if (and (:masked-group? parent)
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
                      (let [shape          (get objects id)
                            instance-part? (and (:shape-ref shape)
                                                (not (:component-id shape)))
                            instance-root? (:component-root? shape)
                            sub-instance?  (and (:component-id shape)
                                                (not (:component-root? shape)))

                            parent                 (get objects parent-id)
                            component-shape        (ctn/get-component-shape objects shape)
                            component-shape-parent (ctn/get-component-shape objects parent)

                            detach? (and instance-part? (not= (:id component-shape)
                                                              (:id component-shape-parent)))
                            deroot? (and instance-root? component-shape-parent)
                            reroot? (and sub-instance? (not component-shape-parent))

                            ids-to-detach (when detach?
                                            (cons id (cph/get-children-ids objects id)))]

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
              (rx/of (dws/select-shapes (into (d/ordered-set) shapes)))

              :svg-raw
              nil

              (rx/of (dwe/start-edition-mode id)
                     (dwdp/start-path-edit id)))))))))


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

(declare align-object-to-parent)
(declare align-objects-list)

(defn can-align? [selected objects]
  (cond
    (empty? selected) false
    (> (count selected) 1) true
    :else
    (not= uuid/zero (:parent-id (get objects (first selected))))))

(defn align-objects
  [axis]
  (us/verify ::gal/align-axis axis)
  (ptk/reify ::align-objects
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            moved    (if (= 1 (count selected))
                       (align-object-to-parent objects (first selected) axis)
                       (align-objects-list objects selected axis))
            moved-objects (->> moved (group-by :id))
            ids (keys moved-objects)
            update-fn (fn [shape] (first (get moved-objects (:id shape))))
            undo-id (js/Symbol)]
        (when (can-align? selected objects)
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/update-shapes ids update-fn {:reg-objects? true})
                 (ptk/data-event :layout/update ids)
                 (dwu/commit-undo-transaction undo-id)))))))

(defn align-object-to-parent
  [objects object-id axis]
  (let [object (get objects object-id)
        parent (:parent-id (get objects object-id))
        parent-obj (get objects parent)]
    (gal/align-to-rect object parent-obj axis objects)))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (gsh/selection-rect selected-objs)]
    (mapcat #(gal/align-to-rect % rect axis objects) selected-objs)))

(defn can-distribute? [selected]
  (cond
    (empty? selected) false
    (< (count selected) 2) false
    :else true))

(defn distribute-objects
  [axis]
  (us/verify ::gal/dist-axis axis)
  (ptk/reify ::distribute-objects
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            selected  (wsh/lookup-selected state)
            moved     (-> (map #(get objects %) selected)
                          (gal/distribute-space axis objects))

            moved     (d/index-by :id moved)
            ids       (keys moved)

            update-fn #(get moved (:id %))]
        (when (can-distribute? selected)
          (rx/of (dch/update-shapes ids update-fn {:reg-objects? true})))))))

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
  (ptk/reify ::toggle-propotion-lock
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
   (us/assert! ::us/uuid page-id)
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
  (us/verify ::layout/flag layout)
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

(defn check-in-asset
  [items element]
  (let [items (or items #{})]
    (if (contains? items element)
      (disj items element)
      (conj items element))))

(defn toggle-selected-assets
  [asset type]
  (ptk/reify ::toggle-selected-assets
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-global :selected-assets type] #(check-in-asset % asset)))))

(defn select-single-asset
  [asset type]
  (ptk/reify ::select-single-asset
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :selected-assets type] #{asset}))))

(defn select-assets
  [assets type]
  (ptk/reify ::select-assets
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :selected-assets type] (into #{} assets)))))

(defn unselect-all-assets
  []
  (ptk/reify ::unselect-all-assets
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :selected-assets] {:components #{}
                                                            :graphics #{}
                                                            :colors #{}
                                                            :typographies #{}}))))
(defn go-to-main-instance
  [page-id shape-id]
  (us/verify ::us/uuid page-id)
  (us/verify ::us/uuid shape-id)
  (ptk/reify ::go-to-main-instance
    ptk/WatchEvent
    (watch [_ state stream]
      (let [current-page-id (:current-page-id state)]
        (if (= page-id current-page-id)
          (rx/of (dws/select-shapes (lks/set shape-id))
                 dwz/zoom-to-selected-shape)
          (let [project-id      (:current-project-id state)
                file-id         (:current-file-id state)
                pparams         {:file-id file-id :project-id project-id}
                qparams         {:page-id page-id}]
            (rx/merge
             (rx/of (rt/nav :workspace pparams qparams))
             (->> stream
                  (rx/filter (ptk/type? ::dwv/page-loaded))
                  (rx/take 1)
                  (rx/mapcat #(rx/of (dws/select-shapes (lks/set shape-id))
                                     dwz/zoom-to-selected-shape))))))))))

(defn go-to-component
  [component-id]
  (ptk/reify ::go-to-component
    IDeref
    (-deref [_] {:layout :assets})

    ptk/WatchEvent
    (watch [_ state _]
      (let [components-v2 (features/active-feature? state :components-v2)]
        (if components-v2
          (let [file-data          (wsh/get-local-file state)
                component          (ctkl/get-component file-data component-id)
                main-instance-id   (:main-instance-id component)
                main-instance-page (:main-instance-page component)]
            (rx/of (go-to-main-instance main-instance-page main-instance-id)))
          (let [project-id    (get-in state [:workspace-project :id])
                file-id       (get-in state [:workspace-file :id])
                page-id       (get state :current-page-id)
                pparams       {:file-id file-id :project-id project-id}
                qparams       {:page-id page-id :layout :assets}]
            (rx/of (rt/nav :workspace pparams qparams)
                   (dwl/set-assets-box-open file-id :library true)
                   (dwl/set-assets-box-open file-id :components true)
                   (select-single-asset component-id :components))))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [components-v2 (features/active-feature? state :components-v2)
            wrapper-id    (str "component-shape-id-" component-id)]
        (when-not components-v2
          (tm/schedule-on-idle #(dom/scroll-into-view-if-needed! (dom/get-element wrapper-id))))))))

(defn show-component-in-assets
  [component-id]
  (ptk/reify ::show-component-in-assets
    ptk/WatchEvent
    (watch [_ state _]
      (let [project-id    (get-in state [:workspace-project :id])
            file-id       (get-in state [:workspace-file :id])
            page-id       (get state :current-page-id)
            pparams       {:file-id file-id :project-id project-id}
            qparams       {:page-id page-id :layout :assets}]
        (rx/of (rt/nav :workspace pparams qparams)
               (dwl/set-assets-box-open file-id :library true)
               (dwl/set-assets-box-open file-id :components true)
               (select-single-asset component-id :components))))

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
  ([{:keys [file-id page-id section]}]
   (ptk/reify ::go-to-viewer
     ptk/WatchEvent
     (watch [_ state _]
       (let [{:keys [current-file-id current-page-id]} state
             pparams {:file-id (or file-id current-file-id)}
             qparams (cond-> {:page-id (or page-id current-page-id)}
                       (some? section)
                       (assoc :section section))]
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

(s/def ::point gpt/point?)


(defn show-context-menu
  [{:keys [position] :as params}]
  (us/verify ::point position)
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
            all-selected    (into [] (mapcat #(cph/get-children-with-self objects %)) selected)
            head            (get objects (first selected))

            not-group-like? (and (= (count selected) 1)
                                 (not (contains? #{:group :bool} (:type head))))

            no-bool-shapes? (->> all-selected (some (comp #{:frame :text} :type)))]

        (rx/concat
         (when (and (some? shape) (not (contains? selected (:id shape))))
           (rx/of (dws/select-shape (:id shape))))
         (rx/of (show-context-menu
                 (-> params
                     (assoc
                      :kind :shape
                      :disable-booleans? (or no-bool-shapes? not-group-like?)
                      :disable-flatten? no-bool-shapes?
                      :selected (conj selected (:id shape)))))))))))

(defn show-page-item-context-menu
  [{:keys [position page] :as params}]
  (us/verify ::point position)
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
                  objects  (cph/selected-subtree objects selected)
                  selected (->> (ctst/sort-z-index objects selected)
                                (reverse)
                                (into (d/ordered-set)))]

              (assoc data :selected selected)))

          ;; Retrieve all ids of selected shapes with corresponding
          ;; children; this is needed because each shape should be
          ;; processed one by one because of async events (data url
          ;; fetching).
          (collect-object-ids [objects res id]
            (let [obj (get objects id)]
              (reduce (partial collect-object-ids objects)
                      (assoc res id obj)
                      (:shapes obj))))

          ;; Prepare the shape object. Mainly needed for image shapes
          ;; for retrieve the image data and convert it to the
          ;; data-url.
          (prepare-object [objects parent-frame-id {:keys [type] :as obj}]
            (let [obj (maybe-translate obj objects parent-frame-id)]
              (if (= type :image)
                (let [url (cf/resolve-file-media (:metadata obj))]
                  (->> (http/send! {:method :get
                                    :uri url
                                    :response-type :blob})
                       (rx/map :body)
                       (rx/mapcat wapi/read-file-as-data-url)
                       (rx/map #(assoc obj ::data %))
                       (rx/take 1)))
                (rx/of obj))))

          ;; Collects all the items together and split images into a
          ;; separated data structure for a more easy paste process.
          (collect-data [res {:keys [id metadata] :as item}]
            (let [res (update res :objects assoc id (dissoc item ::data))]
              (if (= :image (:type item))
                (let [img-part {:id   (:id metadata)
                                :name (:name item)
                                :file-data (::data item)}]
                  (update res :images conj img-part))
                res)))

          (maybe-translate [shape objects parent-frame-id]
            (if (= parent-frame-id uuid/zero)
              shape
              (let [frame (get objects parent-frame-id)]
                (gsh/translate-to-frame shape frame))))

          (on-copy-error [error]
            (js/console.error "Clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state _]
        (let [objects  (wsh/lookup-page-objects state)
              selected (->> (wsh/lookup-selected state)
                            (cph/clean-loops objects))

              parent-frame-id (cph/common-parent-frame objects selected)
              pdata    (reduce (partial collect-object-ids objects) {} selected)
              initial  {:type :copied-shapes
                        :file-id (:current-file-id state)
                        :selected selected
                        :objects {}
                        :images #{}}
              selected_text (.. js/window getSelection toString)]

          (if (not-empty selected_text)
            (try
              (wapi/write-to-clipboard selected_text)
              (catch :default e
                (on-copy-error e)))
            (->> (rx/from (seq (vals pdata)))
                 (rx/merge-map (partial prepare-object objects parent-frame-id))
                 (rx/reduce collect-data initial)
                 (rx/map (partial sort-selected state))
                 (rx/map t/encode-str)
                 (rx/map wapi/write-to-clipboard)
                 (rx/catch on-copy-error)
                 (rx/ignore))))))))

(declare paste-shape)
(declare paste-text)
(declare paste-image)
(declare paste-svg)

(def paste
  (ptk/reify ::paste
    ptk/WatchEvent
    (watch [_ _ _]
      (try
        (let [clipboard-str (wapi/read-from-clipboard)
              paste-transit-str
              (->> clipboard-str
                   (rx/filter t/transit?)
                   (rx/map t/decode-str)
                   (rx/filter #(= :copied-shapes (:type %)))
                   (rx/map #(select-keys % [:selected :objects]))
                   (rx/map paste-shape))

              paste-plain-text-str
              (->> clipboard-str
                   (rx/filter (comp not empty?))
                   (rx/map paste-text))

              paste-image-str
              (->> (wapi/read-image-from-clipboard)
                   (rx/map paste-image))]

          (->> (rx/concat paste-transit-str
                          paste-plain-text-str
                          paste-image-str)
               (rx/take 1)
               (rx/catch
                (fn [err]
                  (js/console.error "Clipboard error:" err)
                  (rx/empty)))))
        (catch :default e
          (let [data (ex-data e)]
            (if (:not-implemented data)
              (rx/of (msg/warn (tr "errors.clipboard-not-implemented")))
              (js/console.error "ERROR" e))))))))

(defn paste-from-event
  [event in-viewport?]
  (ptk/reify ::paste-from-event
    ptk/WatchEvent
    (watch [_ state _]
      (try
        (let [objects (wsh/lookup-page-objects state)
              paste-data    (wapi/read-from-paste-event event)
              image-data    (wapi/extract-images paste-data)
              text-data     (wapi/extract-text paste-data)
              decoded-data  (and (t/transit? text-data)
                                 (t/decode-str text-data))

              edit-id (get-in state [:workspace-local :edition])
              is-editing-text? (and edit-id (= :text (get-in objects [edit-id :type])))]

          ;; Some paste events can be fired while we're editing a text
          ;; we forbid that scenario so the default behaviour is executed
          (when-not is-editing-text?
            (cond
              (and (string? text-data)
                   (str/includes? text-data "<svg"))
              (rx/of (paste-svg text-data))

              (seq image-data)
              (rx/from (map paste-image image-data))

              (coll? decoded-data)
              (->> (rx/of decoded-data)
                   (rx/filter #(= :copied-shapes (:type %)))
                   (rx/map #(paste-shape % in-viewport?)))

              (string? text-data)
              (rx/of (paste-text text-data))

              :else
              (rx/empty))))

        (catch :default err
          (js/console.error "Clipboard error:" err))))))

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


(defn- paste-shape
  [{selected :selected
    paste-objects :objects ;; rename this because here comes only the clipboard shapes,
    images :images         ;; not the whole page tree of shapes.
    :as data}
   in-viewport?]
  (letfn [;; Given a file-id and img (part generated by the
          ;; copy-selected event), uploads the new media.
          (upload-media [file-id imgpart]
            (->> (http/send! {:uri (:file-data imgpart)
                              :response-type :blob
                              :method :get})
                 (rx/map :body)
                 (rx/map
                  (fn [blob]
                    {:name (:name imgpart)
                     :file-id file-id
                     :content blob
                     :is-local true}))
                 (rx/mapcat #(rp/cmd! :upload-file-media-object %))
                 (rx/map (fn [media]
                           (assoc media :prev-id (:id imgpart))))))

          ;; Analyze the rchange and replace staled media and
          ;; references to the new uploaded media-objects.
          (process-rchange [media-idx item]
            (if (and (= (:type item) :add-obj)
                     (= :image (get-in item [:obj :type])))
              (update-in item [:obj :metadata]
                         (fn [{:keys [id] :as mdata}]
                           (if-let [mobj (get media-idx id)]
                             (assoc mdata
                                    :id (:id mobj)
                                    :path (:path mobj))
                             mdata)))
              item))

          (calculate-paste-position [state mouse-pos in-viewport?]
            (let [page-objects         (wsh/lookup-page-objects state)
                  selected-objs        (map #(get paste-objects %) selected)
                  first-selected-obj   (first selected-objs)
                  page-selected        (wsh/lookup-selected state)
                  wrapper              (gsh/selection-rect selected-objs)
                  orig-pos             (gpt/point (:x1 wrapper) (:y1 wrapper))
                  frame-id             (first page-selected)
                  frame-object         (get page-objects frame-id)
                  base                 (cph/get-base-shape page-objects page-selected)
                  index                (cph/get-position-on-parent page-objects (:id base))
                  tree-root            (get-tree-root-shapes paste-objects)
                  only-one-root-shape? (and
                                        (< 1 (count paste-objects))
                                        (= 1 (count tree-root)))]

              (cond
                (selected-frame? state)

                (if (or (any-same-frame-from-selected? state (keys paste-objects))
                        (and only-one-root-shape?
                             (frame-same-size? paste-objects (first tree-root))))
                  ;; Paste next to selected frame, if selected is itself or of the same size as the copied
                  (let [selected-frame-obj (get page-objects (first page-selected))
                        parent-id          (:parent-id base)
                        paste-x            (+ (:width selected-frame-obj) (:x selected-frame-obj) 50)
                        paste-y            (:y selected-frame-obj)
                        delta              (gpt/subtract (gpt/point paste-x paste-y) orig-pos)]

                    [(:frame-id base) parent-id delta index])

                  ;; Paste inside selected frame otherwise
                  (let [origin-frame-id (:frame-id first-selected-obj)
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
                                (gpt/subtract (gsh/center-shape frame-object) (gsh/center-selrect wrapper))
                              ;; When pasting from one frame to another frame the object position must be limited to container boundaries. If the pasted object doesn't fit we try to:
                              ;;    - Align it to the limits on the x and y axis
                              ;;    - Respect the distance of the object to the right and bottom in the original frame
                                (gpt/point paste-x paste-y))]
                    [frame-id frame-id delta]))

                (empty? page-selected)
                (let [frame-id (ctst/top-nested-frame page-objects mouse-pos)
                      delta    (gpt/subtract mouse-pos orig-pos)]
                  [frame-id frame-id delta])

                :else
                (let [frame-id  (:frame-id base)
                      parent-id (:parent-id base)
                      delta     (if in-viewport?
                                  (gpt/subtract mouse-pos orig-pos)
                                  (gpt/subtract (gpt/point (:selrect base)) orig-pos))]
                  [frame-id parent-id delta index]))))

          ;; Change the indexes of the pasted shapes
          (change-add-obj-index [paste-objects selected index change]
            (let [index (or index -1) ;; if there is no current element selected, we want the first (inc index) to be 0
                  set-index (fn [[result index] id]
                              [(assoc result id index) (inc index)])

                  map-ids
                  (->> selected
                       (map #(get-in paste-objects [% :id]))
                       (reduce set-index [{} (inc index)])
                       first)]
              (if (and (= :add-obj (:type change))
                       (contains? map-ids (:old-id change)))
                (assoc change :index (get map-ids (:old-id change)))
                change)))

          ;; Check if the shape is an instance whose master is defined in a
          ;; library that is not linked to the current file
          (foreign-instance? [shape paste-objects state]
            (let [root         (cph/get-root-shape paste-objects shape)
                  root-file-id (:component-file root)]
              (and (some? root)
                   (not= root-file-id (:current-file-id state))
                   (nil? (get-in state [:workspace-libraries root-file-id])))))

          ;; Proceed with the standard shape paste process.
          (do-paste [it state mouse-pos media]
            (let [libraries    (wsh/get-libraries state)
                  file-id      (:current-file-id state)
                  page         (wsh/lookup-page state)
                  media-idx    (d/index-by :prev-id media)

                  ;; Calculate position for the pasted elements
                  [frame-id parent-id delta index] (calculate-paste-position state mouse-pos in-viewport?)

                  process-shape
                  (fn [_ shape]
                    (-> shape
                        (assoc :frame-id frame-id :parent-id parent-id)

                        ;; if foreign instance, detach the shape
                        (cond-> (foreign-instance? shape paste-objects state)
                          (->
                             (assoc :saved-component-root? (:component-root? shape)) ;; this is used later, if the paste needs to create a new component from the detached shape
                             (dissoc :component-id :component-file :component-root?
                                  :remote-synced? :shape-ref :touched)))
                        ;; if is a text, remove references to external typographies
                        (cond-> (= (:type shape) :text)
                          (ctt/remove-external-typographies file-id))))

                  paste-objects (->> paste-objects (d/mapm process-shape))

                  all-objects (merge (:objects page) paste-objects)

                  library-data (wsh/get-file state file-id)

                  changes  (-> (dws/prepare-duplicate-changes all-objects page selected delta it libraries library-data file-id)
                               (pcb/amend-changes (partial process-rchange media-idx))
                               (pcb/amend-changes (partial change-add-obj-index paste-objects selected index)))

                  ;; Adds a resize-parents operation so the groups are updated. We add all the new objects
                  new-objects-ids (->> changes :redo-changes (filter #(= (:type %) :add-obj)) (mapv :id))
                  changes (pcb/resize-parents changes new-objects-ids)

                  selected  (->> changes
                                 :redo-changes
                                 (filter #(= (:type %) :add-obj))
                                 (filter #(selected (:old-id %)))
                                 (map #(get-in % [:obj :id]))
                                 (into (d/ordered-set)))
                  undo-id (js/Symbol)]

              (rx/of (dwu/start-undo-transaction undo-id)
                     (dch/commit-changes changes)
                     (dws/select-shapes selected)
                     (ptk/data-event :layout/update [frame-id])
                     (dwu/commit-undo-transaction undo-id))))]

    (ptk/reify ::paste-shape
      ptk/WatchEvent
      (watch [it state _]
        (let [file-id   (:current-file-id state)
              mouse-pos (deref ms/mouse-position)]
          (if (= file-id (:file-id data))
            (do-paste it state mouse-pos [])
            (->> (rx/from images)
                 (rx/merge-map (partial upload-media file-id))
                 (rx/reduce conj [])
                 (rx/mapcat (partial do-paste it state mouse-pos)))))))))


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
      (gsh/center-shape frame-object))

    :else
    (deref ms/mouse-position)))

(defn paste-text
  [text]
  (us/assert! (string? text) "expected string as first argument")
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
(defn- paste-svg
  [text]
  (us/assert! (string? text) "expected string as first argument")
  (ptk/reify ::paste-svg
    ptk/WatchEvent
    (watch [_ state _]
      (let [position (calculate-paste-position state)
            file-id  (:current-file-id state)]
        (->> (dwm/svg->clj ["svg" text])
             (rx/map #(dwm/svg-uploaded % file-id position)))))))

(defn- paste-image
  [image]
  (ptk/reify ::paste-bin-impl
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (get-in state [:workspace-file :id])
            position (calculate-paste-position state)
            params  {:file-id file-id
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Remove graphics
;; TODO: this should be deprecated and removed together with components-v2
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- initialize-remove-graphics
  [total]
  (ptk/reify ::initialize-remove-graphics
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :remove-graphics {:total total
                                     :current nil
                                     :error false
                                     :completed false}))))

(defn- update-remove-graphics
  [current]
  (ptk/reify ::update-remove-graphics
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:remove-graphics :current] current))))

(defn- error-in-remove-graphics
  []
  (ptk/reify ::error-in-remove-graphics
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:remove-graphics :error] true))))

(defn clear-remove-graphics
  []
  (ptk/reify ::clear-remove-graphics
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :remove-graphics))))

(defn- complete-remove-graphics
  []
  (ptk/reify ::complete-remove-graphics
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:remove-graphics :completed] true))

    ptk/WatchEvent
    (watch [_ state _]
      (when-not (get-in state [:remove-graphics :error])
        (rx/of (modal/hide))))))

(defn- remove-graphic
  [it file-data page [index [media-obj pos]]]
  (let [process-shapes
        (fn [[shape children]]
          (let [page'  (reduce #(ctst/add-shape (:id %2) %2 %1 uuid/zero (:parent-id %2) nil false)
                               page
                               (cons shape children))

                shape' (ctn/get-shape page' (:id shape))

                path   (cph/merge-path-item (tr "workspace.assets.graphics") (:path media-obj))

                [component-shape component-shapes updated-shapes]
                (ctn/make-component-shape shape' (:objects page') (:id file-data) true)

                changes (-> (pcb/empty-changes it)
                            (pcb/set-save-undo? false)
                            (pcb/with-page page')
                            (pcb/with-objects (:objects page'))
                            (pcb/with-library-data file-data)
                            (pcb/delete-media (:id media-obj))
                            (pcb/add-objects (cons shape children))
                            (pcb/add-component (:id component-shape)
                                               path
                                               (:name media-obj)
                                               component-shapes
                                               updated-shapes
                                               (:id shape)
                                               (:id page)))]

            (dch/commit-changes changes)))

        shapes (if (= (:mtype media-obj) "image/svg+xml")
                 (->> (dwm/load-and-parse-svg media-obj)
                      (rx/mapcat (partial dwm/create-shapes-svg (:id file-data) (:objects page) pos)))
                 (dwm/create-shapes-img pos media-obj))]

    (->> (rx/concat
          (rx/of (update-remove-graphics index))
          (rx/map process-shapes shapes))
         (rx/catch #(do
                      (log/error :msg (str "Error removing " (:name media-obj))
                                 :hint (ex-message %)
                                 :error %)
                      (rx/of (error-in-remove-graphics)))))))

(defn- remove-graphics
  [file-id file-name]
  (ptk/reify ::remove-graphics
    ptk/WatchEvent
    (watch [it state stream]
      (let [file-data (wsh/get-file state file-id)

            grid-gap 50

            [file-data' page-id start-pos]
            (ctf/get-or-add-library-page file-data grid-gap)

            new-page? (nil? (ctpl/get-page file-data page-id))
            page      (ctpl/get-page file-data' page-id)
            media     (vals (:media file-data'))

            media-points
            (map #(assoc % :points (gsh/rect->points {:x 0
                                                      :y 0
                                                      :width (:width %)
                                                      :height (:height %)}))
                 media)

            shape-grid
            (ctst/generate-shape-grid media-points start-pos grid-gap)

            stoper (rx/filter (ptk/type? ::finalize-file) stream)]

        (rx/concat
         (rx/of (modal/show {:type :remove-graphics-dialog :file-name file-name})
                (initialize-remove-graphics (count media)))
         (when new-page?
           (rx/of (dch/commit-changes (-> (pcb/empty-changes it)
                                          (pcb/set-save-undo? false)
                                          (pcb/add-page (:id page) page)))))
         (->> (rx/mapcat (partial remove-graphic it file-data' page)
                         (rx/from (d/enumerate (d/zip media shape-grid))))
              (rx/take-until stoper))
         (rx/of (complete-remove-graphics)))))))

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
;; Inspect
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn set-inspect-expanded
  [expanded?]
  (ptk/reify ::set-inspect-expanded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :inspect-expanded] expanded?))))

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
;; File Library persistent settings
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn set-file-library-listing-thumbs
  [listing-thumbs?]
  (ptk/reify ::set-file-library-listing-thumbs
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :file-library-listing-thumbs] listing-thumbs?))))

(defn set-file-library-reverse-sort
  [reverse-sort?]
  (ptk/reify ::set-file-library-reverse-sort
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-global :file-library-reverse-sort] reverse-sort?))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Components annotations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-component-annotation
  "Update the component with the given annotation"
  [id annotation]
  (us/assert ::us/uuid id)
  (us/assert ::us/string annotation)
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
(dm/export dwv/page-loaded)

;; Undo
(dm/export dwu/reinitialize-undo)
