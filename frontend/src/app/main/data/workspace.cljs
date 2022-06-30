;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.common.transit :as t]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.users :as du]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwco]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.fix-bool-contents :as fbc]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.guides :as dwgu]
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
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.repo :as rp]
   [app.main.streams :as ms]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.http :as http]
   [app.util.i18n :as i18n]
   [app.util.names :as un]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(s/def ::shape-attrs ::cts/shape-attrs)
(s/def ::set-of-string
  (s/every string? :kind set?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare file-initialized)

;; --- Initialize Workspace

(def default-workspace-local
  {:zoom 1})

(defn initialize
  [lname]
  (us/verify (s/nilable ::us/keyword) lname)
  (ptk/reify ::initialize
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

(defn initialize-file
  [project-id file-id]
  (us/verify ::us/uuid project-id)
  (us/verify ::us/uuid file-id)

  (ptk/reify ::initialize-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc state
             :current-file-id file-id
             :current-project-id project-id
             :workspace-presence {}))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (dwp/fetch-bundle project-id file-id))

       ;; Initialize notifications (websocket connection) and the file persistence
       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/take 1)
            (rx/map deref)
            (rx/mapcat
             (fn [bundle]
               (rx/merge
                (rx/of (dwc/initialize-indices bundle))

                (->> (rx/of bundle)
                     (rx/mapcat
                      (fn [bundle]
                        (let [bundle (assoc bundle :file (t/decode-str (:file-raw bundle)))
                              team-id (dm/get-in bundle [:project :team-id])]
                          (rx/merge
                           (rx/of (dwn/initialize team-id file-id)
                                  (dwp/initialize-file-persistence file-id))
                           (->> stream
                                (rx/filter #(= ::dwc/index-initialized %))
                                (rx/take 1)
                                (rx/map #(file-initialized bundle))))))))))))))

    ptk/EffectEvent
    (effect [_ _ _]
      (let [name (str "workspace-" file-id)]
        (unchecked-set ug/global "name" name)))))

(defn- file-initialized
  [{:keys [file users project libraries file-comments-users] :as bundle}]
  (ptk/reify ::file-initialized
    ptk/UpdateEvent
    (update [_ state]
      (assoc state
             :current-team-id (:team-id project)
             :users (d/index-by :id users)
             :workspace-undo {}
             :workspace-project project
             :workspace-file (assoc file :initialized true)
             :workspace-data (-> (:data file)
                                 (cph/start-object-indices)
                                 ;; DEBUG: Uncomment this to try out migrations in local without changing
                                 ;; the version number
                                 #_(assoc :version 17)
                                 #_(app.common.pages.migrations/migrate-data 19))
             :workspace-libraries (d/index-by :id libraries)
             :current-file-comments-users (d/index-by :id file-comments-users)))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [file-id       (:id file)
            ignore-until  (:ignore-sync-until file)
            needs-update? (some #(and (> (:modified-at %) (:synced-at %))
                                      (or (not ignore-until)
                                          (> (:modified-at %) ignore-until)))
                                libraries)]
        (rx/merge
         (rx/of (fbc/fix-bool-contents))
         (if needs-update?
           (rx/of (dwl/notify-sync-file file-id))
           (rx/empty)))))))

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
              :workspace-media-objects
              :workspace-persistence
              :workspace-presence
              :workspace-project
              :workspace-project
              :workspace-undo))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/merge
       (rx/of (dwn/finalize file-id))
       (->> (rx/of ::dwp/finalize)
            (rx/observe-on :async))))))

(declare go-to-page)

(defn initialize-page
  [page-id]
  (us/assert ::us/uuid page-id)
  (ptk/reify ::initialize-page
    ptk/WatchEvent
    (watch [_ state _]
      (if (contains? (get-in state [:workspace-data :pages-index]) page-id)
        (rx/of (dwp/preload-data-uris)
               (dwth/watch-state-changes))
        (let [default-page-id (get-in state [:workspace-data :pages 0])]
          (rx/of (go-to-page default-page-id)))))

    ptk/UpdateEvent
    (update [_ state]
      (if-let [{:keys [id] :as page} (get-in state [:workspace-data :pages-index page-id])]
        ;; we maintain a cache of page state for user convenience with
        ;; the exception of the selection; when user abandon the
        ;; current page, the selection is lost
        (let [local (-> state
                        (get-in [:workspace-cache id] default-workspace-local)
                        (assoc :selected (d/ordered-set)))]
          (-> state
              (assoc :current-page-id id)
              (assoc :trimmed-page (dm/select-keys page [:id :name]))
              (assoc :workspace-local local)
              (update :workspace-layout layout/load-layout-flags)
              (update :workspace-global layout/load-layout-state)
              (update :workspace-global assoc :background-color (-> page :options :background))
              (update-in [:route :params :query] assoc :page-id (dm/str id))))
        state))))

(defn finalize-page
  [page-id]
  (us/assert ::us/uuid page-id)
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (-> (:workspace-local state)
                      (dissoc :edition
                              :edit-path
                              :selected))
            exit-workspace? (not= :workspace (get-in state [:route :data :name]))]
        (cond-> (assoc-in state [:workspace-cache page-id] local)
          :always
          (dissoc :current-page-id :workspace-local :trimmed-page :workspace-focus-selected)
          exit-workspace?
          (dissoc :workspace-drawing))))))

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
              unames  (un/retrieve-used-names pages)
              name    (un/generate-unique-name unames "Page-1")

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
            unames  (un/retrieve-used-names pages)
            page    (get-in state [:workspace-data :pages-index page-id])
            name    (un/generate-unique-name unames (:name page))

            no_thumbnails_objects (->> (:objects page)
                                      (d/mapm (fn [_ val] (dissoc val :use-for-thumbnail?))))

            page (-> page (assoc :name name :id id :objects no_thumbnails_objects))

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
      (let [page (get-in state [:workspace-data :pages-index id])

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
        (->> (rp/mutation :rename-file params)
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

;; --- Viewport Sizing

(defn initialize-viewport
  [{:keys [width height] :as size}]
  (letfn [(update* [{:keys [vport] :as local}]
            (let [wprop (/ (:width vport) width)
                  hprop (/ (:height vport) height)]
              (-> local
                  (assoc :vport size)
                  (update :vbox (fn [vbox]
                                  (-> vbox
                                      (update :width #(/ % wprop))
                                      (update :height #(/ % hprop))))))))

          (initialize [state local]
            (let [page-id (:current-page-id state)
                  objects (wsh/lookup-page-objects state page-id)
                  shapes  (cph/get-immediate-children objects)
                  srect   (gsh/selection-rect shapes)
                  local   (assoc local :vport size :zoom 1)]
              (cond
                (or (not (d/num? (:width srect)))
                    (not (d/num? (:height srect))))
                (assoc local :vbox (assoc size :x 0 :y 0))

                (or (> (:width srect) width)
                    (> (:height srect) height))
                (let [srect (gal/adjust-to-viewport size srect {:padding 40})
                      zoom  (/ (:width size) (:width srect))]
                  (-> local
                      (assoc :zoom zoom)
                      (update :vbox merge srect)))

                :else
                (assoc local :vbox (assoc size
                                          :x (+ (:x srect) (/ (- (:width srect) width) 2))
                                          :y (+ (:y srect) (/ (- (:height srect) height) 2)))))))

          (setup [state local]
            (if (and (:vbox local) (:vport local))
              (update* local)
              (initialize state local)))]

    (ptk/reify ::initialize-viewport
      ptk/UpdateEvent
      (update [_ state]
        (update state :workspace-local
                (fn [local]
                  (setup state local)))))))

(defn update-viewport-position
  [{:keys [x y] :or {x identity y identity}}]
  (us/assert fn? x)
  (us/assert fn? y)
  (ptk/reify ::update-viewport-position
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :vbox]
                 (fn [vbox]
                   (-> vbox
                       (update :x x)
                       (update :y y)))))))

(defn update-viewport-size
  [resize-type {:keys [width height] :as size}]
  (ptk/reify ::update-viewport-size
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vport] :as local}]
                (if (or (nil? vport)
                        (mth/almost-zero? width)
                        (mth/almost-zero? height))
                  ;; If we have a resize to zero just keep the old value
                  local
                  (let [wprop (/ (:width vport) width)
                        hprop (/ (:height vport) height)

                        vbox (:vbox local)
                        vbox-x (:x vbox)
                        vbox-y (:y vbox)
                        vbox-width (:width vbox)
                        vbox-height (:height vbox)

                        vbox-width' (/ vbox-width wprop)
                        vbox-height' (/ vbox-height hprop)

                        vbox-x'
                        (case resize-type
                          :left  (+ vbox-x (- vbox-width vbox-width'))
                          :right vbox-x
                          (+ vbox-x (/ (- vbox-width vbox-width') 2)))

                        vbox-y'
                        (case resize-type
                          :top  (+ vbox-y (- vbox-height vbox-height'))
                          :bottom vbox-y
                          (+ vbox-y (/ (- vbox-height vbox-height') 2)))]
                    (-> local
                        (assoc :vport size)
                        (assoc-in [:vbox :x] vbox-x')
                        (assoc-in [:vbox :y] vbox-y')
                        (assoc-in [:vbox :width] vbox-width')
                        (assoc-in [:vbox :height] vbox-height')))))))))

(defn start-panning []
  (ptk/reify ::start-panning
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter (ptk/type? ::finish-panning)))
            zoom (-> (get-in state [:workspace-local :zoom]) gpt/point)]
        (when-not (get-in state [:workspace-local :panning])
          (rx/concat
           (rx/of #(-> % (assoc-in [:workspace-local :panning] true)))
           (->> stream
                (rx/filter ms/pointer-event?)
                (rx/filter #(= :delta (:source %)))
                (rx/map :pt)
                (rx/take-until stopper)
                (rx/map (fn [delta]
                          (let [delta (gpt/divide delta zoom)]
                            (update-viewport-position {:x #(- % (:x delta))
                                                       :y #(- % (:y delta))})))))))))))

(defn finish-panning []
  (ptk/reify ::finish-panning
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :panning)))))




;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
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
  (us/verify ::shape-attrs attrs)
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

        (rx/of (dch/commit-changes changes))))))


;; --- Change Shape Order (D&D Ordering)

(defn relocate-shapes-changes [it objects parents parent-id page-id to-index ids
                               groups-to-delete groups-to-unmask shapes-to-detach
                               shapes-to-reroot shapes-to-deroot shapes-to-unconstraint]
  (let [shapes (map (d/getf objects) ids)]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)

        ; Move the shapes
        (pcb/change-parent parent-id
                           (reverse shapes)
                           to-index)

        ; Remove empty groups
        (pcb/remove-objects groups-to-delete)

        ; Unmask groups whose mask have moved outside
        (pcb/update-shapes groups-to-unmask
                           (fn [shape]
                             (assoc shape :masked-group? false)))

        ; Detach shapes moved out of their component
        (pcb/update-shapes shapes-to-detach
                           (fn [shape]
                             (assoc shape :component-id nil
                                    :component-file nil
                                    :component-root? nil
                                    :remote-synced? nil
                                    :shape-ref nil
                                    :touched nil)))

        ; Make non root a component moved inside another one
        (pcb/update-shapes shapes-to-deroot
                           (fn [shape]
                             (assoc shape :component-root? nil)))

        ; Make root a subcomponent moved outside its parent component
        (pcb/update-shapes shapes-to-reroot
                           (fn [shape]
                             (assoc shape :component-root? true)))

        ; Reset constraints depending on the new parent
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

        ; Resize parent containers that need to
        (pcb/resize-parents parents))))

(defn relocate-shapes
  [ids parent-id to-index]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify ::us/uuid parent-id)
  (us/verify number? to-index)

  (ptk/reify ::relocate-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)

            ;; Ignore any shape whose parent is also intented to be moved
            ids      (cph/clean-loops objects ids)

            ;; If we try to move a parent into a child we remove it
            ids      (filter #(not (cph/is-parent? objects parent-id %)) ids)
            parents  (into #{parent-id} (map #(cph/get-parent-id objects %)) ids)

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
            ;; be more efficient than using reduce and continuos data
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
                            component-shape        (cph/get-component-shape objects shape)
                            component-shape-parent (cph/get-component-shape objects parent)

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
                                             ids)]

        (rx/of (dch/commit-changes changes)
               (dwco/expand-collapse parent-id))))))

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
      (let [selected (wsh/lookup-selected state)]
        (if-not (= 1 (count selected))
          (rx/empty)

          (let [objects (wsh/lookup-page-objects state)
                {:keys [id type shapes]} (get objects (first selected))]

            (case type
              :text
              (rx/of (dwe/start-edition-mode id))

              (:group :bool)
              (rx/of (dws/select-shapes (into (d/ordered-set) [(last shapes)])))

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
            update-fn (fn [shape] (first (get moved-objects (:id shape))))]
        (when (can-align? selected objects)
          (rx/of (dch/update-shapes ids update-fn {:reg-objects? true})))))))

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
                      (gpr/assign-proportions))))]
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

;; --- Update Shape Flags

(defn update-shape-flags
  [ids {:keys [blocked hidden] :as flags}]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/assert ::shape-attrs flags)
  (ptk/reify ::update-shape-flags
    ptk/WatchEvent
    (watch [_ state _]
      (let [update-fn
            (fn [obj]
              (cond-> obj
                (boolean? blocked) (assoc :blocked blocked)
                (boolean? hidden) (assoc :hidden hidden)))
            objects (wsh/lookup-page-objects state)
            ids     (into ids (->> ids (mapcat #(cph/get-children-ids objects %))))]
        (rx/of (dch/update-shapes ids update-fn))))))

(defn toggle-visibility-selected
  []
  (ptk/reify ::toggle-visibility-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (dch/update-shapes selected #(update % :hidden not)))))))

(defn toggle-lock-selected
  []
  (ptk/reify ::toggle-lock-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (dch/update-shapes selected #(update % :blocked not)))))))

(defn toggle-file-thumbnail-selected
  []
  (ptk/reify ::toggle-file-thumbnail-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected   (wsh/lookup-selected state)
            pages      (-> state :workspace-data :pages-index vals)
            get-frames (fn [{:keys [objects id] :as page}]
                         (->> (cph/get-frames objects)
                              (sequence
                               (comp (filter :use-for-thumbnail?)
                                     (map :id)
                                     (remove selected)
                                     (map (partial vector id))))))]

        (rx/concat
         (rx/from
          (->> (mapcat get-frames pages)
               (d/group-by first second)
               (map (fn [[page-id frame-ids]]
                      (dch/update-shapes frame-ids #(dissoc % :use-for-thumbnail?) {:page-id page-id})))))
         (rx/of (dch/update-shapes selected #(update % :use-for-thumbnail? not))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
   (us/verify ::us/uuid page-id)
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

(defn go-to-component
  [component-id]
  (ptk/reify ::go-to-component
    IDeref
    (-deref [_] {:layout :assets})

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
      (let [wrapper-id    (str "component-shape-id-" component-id)]
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
                      :disable-booleans? (or no-bool-shapes? not-group-like?)
                      :disable-flatten? no-bool-shapes?
                      :selected (conj selected (:id shape)))))))))))

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
                  objects (wsh/lookup-page-objects state)

                  ;; Narrow the objects map so it contains only relevant data for
                  ;; selected and its parents
                  objects (cph/selected-subtree objects selected)

                  selected (->> (cph/sort-z-index objects selected)
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
          (prepare-object [objects selected+children {:keys [type] :as obj}]
            (let [obj (maybe-translate obj objects selected+children)]
              (if (= type :image)
                (let [url (cfg/resolve-file-media (:metadata obj))]
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

          (maybe-translate [shape objects selected+children]
            (if (and (not= (:type shape) :frame)
                     (not (contains? selected+children (:frame-id shape))))
              ;; When the parent frame is not selected we change to relative
              ;; coordinates
              (let [frame (get objects (:frame-id shape))]
                (gsh/translate-to-frame shape frame))
              shape))

          (on-copy-error [error]
            (js/console.error "Clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state _]
        (let [objects  (wsh/lookup-page-objects state)
              selected (->> (wsh/lookup-selected state)
                            (cph/clean-loops objects))

              selected+children (cph/selected-with-children objects selected)
              pdata    (reduce (partial collect-object-ids objects) {} selected)
              initial  {:type :copied-shapes
                        :file-id (:current-file-id state)
                        :selected selected
                        :objects {}
                        :images #{}}]


          (->> (rx/from (seq (vals pdata)))
               (rx/merge-map (partial prepare-object objects selected+children))
               (rx/reduce collect-data initial)
               (rx/map (partial sort-selected state))
               (rx/map t/encode-str)
               (rx/map wapi/write-to-clipboard)
               (rx/catch on-copy-error)
               (rx/ignore)))))))

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
              (rx/of (msg/warn (i18n/tr "errors.clipboard-not-implemented")))
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
                 (rx/mapcat #(rp/mutation! :upload-file-media-object %))
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
            (let [page-objects  (wsh/lookup-page-objects state)
                  selected-objs (map #(get paste-objects %) selected)
                  has-frame?    (d/seek #(= (:type %) :frame) selected-objs)
                  page-selected (wsh/lookup-selected state)
                  wrapper       (gsh/selection-rect selected-objs)
                  orig-pos      (gpt/point (:x1 wrapper) (:y1 wrapper))]
              (cond
                has-frame?
                (let [index     (cph/get-position-on-parent page-objects uuid/zero)
                      delta     (gpt/subtract mouse-pos orig-pos)]
                  [uuid/zero uuid/zero delta index])

                (selected-frame? state)
                (let [frame-id (first page-selected)
                      frame-object (get page-objects frame-id)

                      origin-frame-id (:frame-id (first selected-objs))
                      origin-frame-object (get page-objects origin-frame-id)

                      ;; - The pasted object position must be limited to container boundaries. If the pasted object doesn't fit we try to:
                      ;;    - Align it to the limits on the x and y axis
                      ;;    - Respect the distance of the object to the right and bottom in the original frame
                      margin-x (if origin-frame-object
                                 (- (:width origin-frame-object) (+ (:x wrapper) (:width wrapper)))
                                 0)
                      margin-x (min margin-x (- (:width frame-object) (:width wrapper)))

                      margin-y  (if origin-frame-object
                                  (- (:height origin-frame-object) (+ (:y wrapper) (:height wrapper)))
                                  0)
                      margin-y (min margin-y (- (:height frame-object) (:height wrapper)))

                      ;; Pasted objects mustn't exceed the selected frame x limit
                      paste-x (if (> (+ (:width wrapper) (:x1 wrapper)) (:width frame-object))
                                (+ (- (:x frame-object) (:x orig-pos)) (- (:width frame-object) (:width wrapper) margin-x))
                                (:x frame-object))

                      ;; Pasted objects mustn't exceed the selected frame y limit
                      paste-y (if (> (+ (:height wrapper) (:y1 wrapper)) (:height frame-object))
                                (+ (- (:y frame-object) (:y orig-pos)) (- (:height frame-object) (:height wrapper) margin-y))
                                (:y frame-object))

                      delta    (gpt/point paste-x paste-y)]

                  [frame-id frame-id delta])

                (empty? page-selected)
                (let [frame-id (cph/frame-id-by-position page-objects mouse-pos)
                      delta    (gpt/subtract mouse-pos orig-pos)]
                  [frame-id frame-id delta])

                :else
                (let [base      (cph/get-base-shape page-objects page-selected)
                      index     (cph/get-position-on-parent page-objects (:id base))
                      frame-id  (:frame-id base)
                      parent-id (:parent-id base)
                      delta     (if in-viewport?
                                  (gpt/subtract mouse-pos orig-pos)
                                  (gpt/subtract (gpt/point (:selrect base)) orig-pos))]
                  [frame-id parent-id delta index]))))

          ;; Change the indexes if the paste is done with an element selected
          (change-add-obj-index [paste-objects selected index change]
            (let [set-index (fn [[result index] id]
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
            (let [page         (wsh/lookup-page state)
                  media-idx    (d/index-by :prev-id media)

                  ;; Calculate position for the pasted elements
                  [frame-id parent-id delta index] (calculate-paste-position state mouse-pos in-viewport?)

                  process-shape
                  (fn [_ shape]
                    (-> shape
                        (assoc :frame-id frame-id)
                        (assoc :parent-id parent-id)

                        ;; if foreign instance, detach the shape
                        (cond-> (foreign-instance? shape paste-objects state)
                          (dissoc :component-id :component-file :component-root?
                                  :remote-synced? :shape-ref :touched))))

                  paste-objects (->> paste-objects (d/mapm process-shape))

                  all-objects (merge (:objects page) paste-objects)

                  changes  (-> (dws/prepare-duplicate-changes all-objects page selected delta it)
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
                                 (into (d/ordered-set)))]

              (rx/of (dch/commit-changes changes)
                     (dws/select-shapes selected))))]

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
    {:type "root"
     :children [{:type "paragraph-set" :children paragraphs}]}))

(defn paste-text
  [text]
  (us/assert string? text)
  (ptk/reify ::paste-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (uuid/next)
            {:keys [x y]} @ms/mouse-position
            width (max 8 (min (* 7 (count text)) 700))
            height 16
            page-id (:current-page-id state)
            frame-id (-> (wsh/lookup-page-objects state page-id)
                         (cph/frame-id-by-position @ms/mouse-position))
            shape (cp/setup-rect-selrect
                   {:id id
                    :type :text
                    :name "Text"
                    :x x
                    :y y
                    :frame-id frame-id
                    :width width
                    :height height
                    :grow-type (if (> (count text) 100) :auto-height :auto-width)
                    :content (as-content text)})]
        (rx/of (dwu/start-undo-transaction)
               (dws/deselect-all)
               (dwsh/add-shape shape)
               (dwu/commit-undo-transaction))))))

;; TODO: why not implement it in terms of upload-media-workspace?
(defn- paste-svg
  [text]
  (us/assert string? text)
  (ptk/reify ::paste-svg
    ptk/WatchEvent
    (watch [_ state _]
      (let [position (deref ms/mouse-position)
            file-id  (:current-file-id state)]
        (->> (dwm/svg->clj ["svg" text])
             (rx/map #(dwm/svg-uploaded % file-id position)))))))

(defn- paste-image
  [image]
  (ptk/reify ::paste-bin-impl
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (get-in state [:workspace-file :id])
            params  {:file-id file-id
                     :blobs [image]
                     :position @ms/mouse-position}]
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
;; Artboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-artboard-from-selection
  []
  (ptk/reify ::create-artboard-from-selection
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id       (:current-page-id state)
            objects       (wsh/lookup-page-objects state page-id)
            selected      (wsh/lookup-selected state)
            selected-objs (map #(get objects %) selected)]
        (when (d/not-empty? selected)
          (let [srect    (gsh/selection-rect selected-objs)
                frame-id (get-in objects [(first selected) :frame-id])
                parent-id (get-in objects [(first selected) :parent-id])
                shape    (-> (cp/make-minimal-shape :frame)
                             (merge {:x (:x srect) :y (:y srect) :width (:width srect) :height (:height srect)})
                             (assoc :frame-id frame-id :parent-id parent-id)
                             (cond-> (not= frame-id uuid/zero)
                               (assoc :fills [] :hide-in-viewer true))
                             (cp/setup-rect-selrect))]
            (rx/of
             (dwu/start-undo-transaction)
             (dwsh/add-shape shape)
             (dwsh/move-shapes-into-frame (:id shape) selected)

             (dwu/commit-undo-transaction))))))))


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
(dm/export dws/shift-select-shapes)

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
