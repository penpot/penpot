;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.features :as ffeat]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes :as ch]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

(defn- log-changes
  [changes file]
  (let [extract-change
        (fn [change]
          (let [shape (when (:id change)
                        (cond
                          (:page-id change)
                          (get-in file [:pages-index
                                        (:page-id change)
                                        :objects
                                        (:id change)])
                          (:component-id change)
                          (get-in file [:components
                                        (:component-id change)
                                        :objects
                                        (:id change)])
                          :else nil))

                prefix (if (:component-id change) "[C] " "[P] ")

                extract (cond-> {:type (:type change)
                                 :raw-change change}
                          shape
                          (assoc :shape (str prefix (:name shape)))
                          (:operations change)
                          (assoc :operations (:operations change)))]
            extract))]
    (map extract-change changes)))

(declare sync-file)

(defn extract-path-if-missing
  [item]
  (let [[path name] (cph/parse-path-name (:name item))]
    (if (and
         (= (:name item) name)
         (contains? item :path))
      item
      (assoc  item :path path :name name))))

(defn add-color
  [color]
  (let [id    (uuid/next)
        color (-> color
                  (assoc :id id)
                  (assoc :name (or (:color color)
                                   (uc/gradient-type->string (get-in color [:gradient :type])))))]
    (dm/assert! ::ctc/color color)
    (ptk/reify ::add-color
      IDeref
      (-deref [_] color)

      ptk/WatchEvent
      (watch [it _ _]
        (let [changes (-> (pcb/empty-changes it)
                          (pcb/add-color color))]
          (rx/of #(assoc-in % [:workspace-local :color-for-rename] id)
                 (dch/commit-changes changes)))))))

(defn add-recent-color
  [color]
  (dm/assert! (ctc/recent-color? color))
  (ptk/reify ::add-recent-color
    ptk/WatchEvent
    (watch [it _ _]
      (let [changes (-> (pcb/empty-changes it)
                        (pcb/add-recent-color color))]
        (rx/of (dch/commit-changes changes))))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn- do-update-color
  [it state color file-id]
  (let [data        (get state :workspace-data)
        [path name] (cph/parse-path-name (:name color))
        color       (assoc color :path path :name name)
        changes     (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/update-color color))
        undo-id (js/Symbol)]
    (rx/of (dwu/start-undo-transaction undo-id)
           (dch/commit-changes changes)
           (sync-file (:current-file-id state) file-id :colors (:id color))
           (dwu/commit-undo-transaction undo-id))))

(defn update-color
  [color file-id]
  (dm/assert! (ctc/color? color))
  (dm/assert! (uuid? file-id))

  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [it state _]
      (do-update-color it state color file-id))))

(defn rename-color
  [file-id id new-name]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? id))
  (dm/assert! (string? new-name))

  (ptk/reify ::rename-color
    ptk/WatchEvent
    (watch [it state _]
      (when (and (some? new-name) (not= "" new-name))
        (let [data        (get state :workspace-data)
              object      (get-in data [:colors id])
              new-object  (assoc object :name new-name)]
          (do-update-color it state new-object file-id))))))

(defn delete-color
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-color id))]
        (rx/of (dch/commit-changes changes))))))

(defn add-media
  [media]
  (dm/assert! (ctf/media-object? media))
  (ptk/reify ::add-media
    ptk/WatchEvent
    (watch [it _ _]
      (let [obj     (select-keys media [:id :name :width :height :mtype])
            changes (-> (pcb/empty-changes it)
                        (pcb/add-media obj))]
        (rx/of (dch/commit-changes changes))))))

(defn rename-media
  [id new-name]
  (dm/assert! (uuid? id))
  (dm/assert! (string? new-name))
  (ptk/reify ::rename-media
    ptk/WatchEvent
    (watch [it state _]
      (when (and (some? new-name) (not= "" new-name))
        (let [data        (get state :workspace-data)
              [path name] (cph/parse-path-name new-name)
              object      (get-in data [:media id])
              new-object  (assoc object :path path :name name)
              changes     (-> (pcb/empty-changes it)
                              (pcb/with-library-data data)
                              (pcb/update-media new-object))]
          (rx/of (dch/commit-changes changes)))))))


(defn delete-media
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-media
    ptk/WatchEvent
    (watch [it state _]
      (let [data        (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-media id))]
        (rx/of (dch/commit-changes changes))))))

(defn add-typography
  ([typography] (add-typography typography true))
  ([typography edit?]
   (let [typography (update typography :id #(or % (uuid/next)))]
     (dm/assert! (ctt/typography? typography))
     (ptk/reify ::add-typography
       IDeref
       (-deref [_] typography)

       ptk/WatchEvent
       (watch [it _ _]
         (let [changes (-> (pcb/empty-changes it)
                           (pcb/add-typography typography))]
           (rx/of (dch/commit-changes changes)
                  #(cond-> %
                     edit?
                     (assoc-in [:workspace-global :rename-typography] (:id typography))))))))))

(defn- do-update-tipography
  [it state typography file-id]
  (let [data        (get state :workspace-data)
        typography  (extract-path-if-missing typography)
        changes     (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/update-typography typography))
        undo-id (js/Symbol)]
    (rx/of (dwu/start-undo-transaction undo-id)
           (dch/commit-changes changes)
           (sync-file (:current-file-id state) file-id :typographies (:id typography))
           (dwu/commit-undo-transaction undo-id))))

(defn update-typography
  [typography file-id]
  (dm/assert! (ctt/typography? typography))
  (dm/assert! (uuid? file-id))

  (ptk/reify ::update-typography
    ptk/WatchEvent
    (watch [it state _]
      (do-update-tipography it state typography file-id))))

(defn rename-typography
  [file-id id new-name]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? id))
  (dm/assert! (string? new-name))
  (ptk/reify ::rename-typography
    ptk/WatchEvent
    (watch [it state _]
      (when (and (some? new-name) (not= "" new-name))
        (let [data        (get state :workspace-data)
              [path name] (cph/parse-path-name new-name)
              object      (get-in data [:typographies id])
              new-object  (assoc object :path path :name name)]
          (do-update-tipography it state new-object file-id))))))

(defn delete-typography
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-typography
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-typography id))]
        (rx/of (dch/commit-changes changes))))))

(defn- add-component2
  "This is the second step of the component creation."
  [selected components-v2]
  (ptk/reify ::add-component2
    IDeref
    (-deref [_] {:num-shapes (count selected)})

    ptk/WatchEvent
    (watch [it state _]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            shapes   (dwg/shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [[root _ changes]
                (dwlh/generate-add-component it shapes objects page-id file-id components-v2
                                             dwg/prepare-create-group
                                             dwsh/prepare-create-artboard-from-selection)]
            (when-not (empty? (:redo-changes changes))
              (rx/of (dch/commit-changes changes)
                     (dws/select-shapes (d/ordered-set (:id root)))))))))))

(defn add-component
  "Add a new component to current file library, from the currently selected shapes.
  This operation is made in two steps, first one for calculate the
  shapes that will be part of the component and the second one with
  the component creation."
  []
  (ptk/reify ::add-component
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects       (wsh/lookup-page-objects state)
            selected      (->> (wsh/lookup-selected state)
                               (cph/clean-loops objects))
            components-v2 (features/active-feature? state :components-v2)]
        (rx/of (add-component2 selected components-v2))))))

(defn rename-component
  "Rename the component with the given id, in the current file library."
  [id new-name]
  (dm/assert! (uuid? id))
  (dm/assert! (string? new-name))
  (ptk/reify ::rename-component
    ptk/WatchEvent
    (watch [it state _]
      (when (and (some? new-name) (not= "" new-name))
        (let [data          (get state :workspace-data)
              [path name]   (cph/parse-path-name new-name)
              components-v2 (features/active-feature? state :components-v2)

              update-fn
              (fn [component]
                ;; NOTE: we need to ensure the component exists,
                ;; because there are small possibilities of race
                ;; conditions with component deletion.
                ;;
                ;; FIXME: this race conditon should be handled in pcb/update-component
                (when component
                  (cond-> component
                    :always
                    (assoc :path path
                           :name name)

                    (not components-v2)
                    (update :objects
                            ;; Give the same name to the root shape
                            #(assoc-in % [id :name] name)))))

              changes (-> (pcb/empty-changes it)
                          (pcb/with-library-data data)
                          (pcb/update-component id update-fn))]

          (rx/of (dch/commit-changes changes)))))))

(defn rename-component-and-main-instance
  [component-id name]
  (ptk/reify ::rename-component-and-main-instance
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [component (dm/get-in state [:workspace-data :components component-id])]
        (let [shape-id (:main-instance-id component)
              page-id  (:main-instance-page component)]
          (rx/concat
           (rx/of (rename-component component-id name))

           ;; NOTE: only when components-v2 is enabled
           (when (and shape-id page-id)
             (rx/of (dch/update-shapes [shape-id] #(assoc % :name name) {:page-id page-id :stack-undo? true})))))))))

(defn duplicate-component
  "Create a new component copied from the one with the given id."
  [library-id component-id]
  (ptk/reify ::duplicate-component
    ptk/WatchEvent
    (watch [it state _]
      (let [libraries      (wsh/get-libraries state)
            library        (get libraries library-id)
            component      (ctkl/get-component (:data library) component-id)
            new-name       (:name component)

            components-v2  (features/active-feature? state :components-v2)

            main-instance-page  (when components-v2
                                  (ctf/get-component-page (:data library) component))

            new-component  (assoc component :id (uuid/next))

            [new-component-shape new-component-shapes  ; <- null in components-v2
             new-main-instance-shape new-main-instance-shapes]
            (dwlh/duplicate-component new-component (:data library))

            changes (-> (pcb/empty-changes it nil)
                        (pcb/with-page main-instance-page)
                        (pcb/with-objects (:objects main-instance-page))
                        (pcb/add-objects new-main-instance-shapes {:ignore-touched true})
                        (pcb/add-component (if components-v2
                                             (:id new-component)
                                             (:id new-component-shape))
                                           (:path component)
                                           new-name
                                           new-component-shapes
                                           []
                                           (:id new-main-instance-shape)
                                           (:id main-instance-page)))]

        (rx/of (dch/commit-changes changes))))))

(defn delete-component
  "Delete the component with the given id, from the current file library."
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)]
        (if (features/active-feature? state :components-v2)
          (let [component (ctkl/get-component data id)
                page      (ctf/get-component-page data component)
                shape     (ctf/get-component-root data component)]
            (rx/of (dwsh/delete-shapes (:id page) #{(:id shape)}))) ;; Deleting main root triggers component delete
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/delete-component id))]
            (rx/of (dch/commit-changes changes))))))))

(defn restore-component
  "Restore a deleted component, with the given id, in the given file library."
  [library-id component-id]
  (dm/assert! (uuid? library-id))
  (dm/assert! (uuid? component-id))
  (ptk/reify ::restore-component
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id         (:current-page-id state)
            current-page    (dm/get-in state [:workspace-data :pages-index page-id])
            objects         (wsh/lookup-page-objects state page-id)
            library-data (wsh/get-file state library-id)
            {:keys [changes shape]} (dwlh/prepare-restore-component library-data component-id current-page it)
            parent-id (:parent-id shape)
            objects (cond-> (assoc objects (:id shape) shape)
                      (not (nil? parent-id))
                      (update-in [parent-id :shapes]
                                 #(conj % (:id shape))))


            ;; Adds a resize-parents operation so the groups are updated. We add all the new objects
            new-objects-ids (->> changes :redo-changes (filter #(= (:type %) :add-obj)) (mapv :id))
            changes (-> changes
                        (pcb/with-objects objects)
                        (pcb/resize-parents new-objects-ids))]
        (rx/of (dch/commit-changes changes))))))


(defn instantiate-component
  "Create a new shape in the current page, from the component with the given id
  in the given file library. Then selects the newly created instance."
  [file-id component-id position]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? component-id))
  (dm/assert! (gpt/point? position))

  (ptk/reify ::instantiate-component
    ptk/WatchEvent
    (watch [it state _]
      (let [page      (wsh/lookup-page state)
            libraries (wsh/get-libraries state)

            changes   (pcb/empty-changes it (:id page))

            [new-shape changes]
            (dwlh/generate-instantiate-component changes
                                                 file-id
                                                 component-id
                                                 position
                                                 page
                                                 libraries)
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (ptk/data-event :layout/update [(:id new-shape)])
               (dws/select-shapes (d/ordered-set (:id new-shape)))
               (dwu/commit-undo-transaction undo-id))))))

(defn detach-component
  "Remove all references to components in the shape with the given id,
  and all its children, at the current page."
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::detach-component
    ptk/WatchEvent
    (watch [it state _]
      (let [file      (wsh/get-local-file state)
            page-id   (get state :current-page-id)
            container (cph/get-container file :page page-id)

            changes   (-> (pcb/empty-changes it)
                          (pcb/with-container container)
                          (pcb/with-objects (:objects container))
                          (dwlh/generate-detach-instance container id))]

        (rx/of (dch/commit-changes changes))))))

(def detach-selected-components
  (ptk/reify ::detach-selected-components
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            file      (wsh/get-local-file state)
            container (cph/get-container file :page page-id)
            selected  (->> state
                           (wsh/lookup-selected)
                           (cph/clean-loops objects))

            changes (reduce
                     (fn [changes id]
                       (dwlh/generate-detach-instance changes container id))
                     (-> (pcb/empty-changes it)
                         (pcb/with-container container)
                         (pcb/with-objects objects))
                     selected)]

        (rx/of (dch/commit-changes changes))))))

(defn nav-to-component-file
  [file-id]
  (dm/assert! (uuid? file-id))
  (ptk/reify ::nav-to-component-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [file         (get-in state [:workspace-libraries file-id])
            path-params  {:project-id (:project-id file)
                          :file-id (:id file)}
            query-params {:page-id (first (get-in file [:data :pages]))
                          :layout :assets}]
        (rx/of (rt/nav-new-window* {:rname :workspace
                                    :path-params path-params
                                    :query-params query-params}))))))

(defn ext-library-changed
  [file-id modified-at revn changes]
  (dm/assert! (uuid? file-id))
  (dm/assert! (ch/changes? changes))
  (ptk/reify ::ext-library-changed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-libraries file-id]
                     assoc :modified-at modified-at :revn revn)
          (d/update-in-when [:workspace-libraries file-id :data]
                            cp/process-changes changes)))))

(defn reset-component
  "Cancels all modifications in the shape with the given id, and all its children, in
  the current page. Set all attributes equal to the ones in the linked component,
  and untouched."
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::reset-component
    ptk/WatchEvent
    (watch [it state _]
      (log/info :msg "RESET-COMPONENT of shape" :id (str id))
      (let [file      (wsh/get-local-file state)
            libraries (wsh/get-libraries state)

            page-id   (:current-page-id state)
            container (cph/get-container file :page page-id)

            components-v2
            (features/active-feature? state :components-v2)

            changes
            (-> (pcb/empty-changes it)
                (pcb/with-container container)
                (pcb/with-objects (:objects container))
                (dwlh/generate-sync-shape-direct libraries container id true components-v2))]

        (log/debug :msg "RESET-COMPONENT finished" :js/rchanges (log-changes
                                                                 (:redo-changes changes)
                                                                 file))
        (rx/of (dch/commit-changes changes))))))

(defn update-component
  "Modify the component linked to the shape with the given id, in the
  current page, so that all attributes of its shapes are equal to the
  shape and its children. Also set all attributes of the shape
  untouched.

  NOTE: It's possible that the component to update is defined in an
  external library file, so this function may cause to modify a file
  different of that the one we are currently editing."
  ([id] (update-component id nil))
  ([id undo-group]
   (dm/assert! (uuid? id))
   (ptk/reify ::update-component
     ptk/WatchEvent
     (watch [it state _]
       (log/info :msg "UPDATE-COMPONENT of shape" :id (str id) :undo-group undo-group)
       (let [page-id     (get state :current-page-id)
             local-file  (wsh/get-local-file state)
             container   (cph/get-container local-file :page page-id)
             shape       (ctn/get-shape container id)]

         (when (ctk/instance-head? shape)
           (let [libraries (wsh/get-libraries state)

                 changes
                 (-> (pcb/empty-changes it)
                     (pcb/set-undo-group undo-group)
                     (pcb/with-container container)
                     (dwlh/generate-sync-shape-inverse libraries container id))

                 file-id   (:component-file shape)
                 file      (wsh/get-file state file-id)

                 xf-filter (comp
                            (filter :local-change?)
                            (map #(dissoc % :local-change?)))

                 local-changes (-> changes
                                   (update :redo-changes #(into [] xf-filter %))
                                   (update :undo-changes #(into [] xf-filter %)))

                 xf-remove (comp
                            (remove :local-change?)
                            (map #(dissoc % :local-change?)))

                 nonlocal-changes (-> changes
                                      (update :redo-changes #(into [] xf-remove %))
                                      (update :undo-changes #(into [] xf-remove %)))]

             (log/debug :msg "UPDATE-COMPONENT finished"
                        :js/local-changes (log-changes
                                           (:redo-changes local-changes)
                                           file)
                        :js/nonlocal-changes (log-changes
                                              (:redo-changes nonlocal-changes)
                                              file))

             (rx/of
              (when (seq (:redo-changes local-changes))
                (dch/commit-changes (assoc local-changes
                                           :file-id (:id local-file))))
              (when (seq (:redo-changes nonlocal-changes))
                (dch/commit-changes (assoc nonlocal-changes
                                           :file-id file-id)))))))))))

(defn update-component-sync
  ([shape-id file-id] (update-component-sync shape-id file-id nil))
  ([shape-id file-id undo-group]
   (ptk/reify ::update-component-sync
     ptk/WatchEvent
     (watch [_ state _]
       (let [current-file-id (:current-file-id state)
             page            (wsh/lookup-page state)
             shape           (ctn/get-shape page shape-id)
             undo-id         (js/Symbol)]
         (rx/of
          (dwu/start-undo-transaction undo-id)
          (update-component shape-id undo-group)
          (sync-file current-file-id file-id :components (:component-id shape) undo-group)
          (when (not= current-file-id file-id)
            (sync-file file-id file-id :components (:component-id shape) undo-group))
          (dwu/commit-undo-transaction undo-id)))))))

(defn update-component-in-bulk
  [shapes file-id]
  (ptk/reify ::update-component-in-bulk
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
       (rx/concat
       (rx/of (dwu/start-undo-transaction undo-id))
       (rx/map #(update-component-sync (:id %) file-id (uuid/next)) (rx/from shapes))
       (rx/of (dwu/commit-undo-transaction undo-id)))))))

(declare sync-file-2nd-stage)

(def valid-asset-types
  #{:colors :components :typographies})

(defn sync-file
  "Synchronize the given file from the given library. Walk through all
  shapes in all pages in the file that use some color, typography or
  component of the library, and copy the new values to the shapes. Do
  it also for shapes inside components of the local file library.

  If it's known that only one asset has changed, you can give its
  type and id, and only shapes that use it will be synced, thus avoiding
  a lot of unneeded checks."
  ([file-id library-id]
   (sync-file file-id library-id nil nil))
  ([file-id library-id asset-type asset-id]
   (sync-file file-id library-id asset-type asset-id nil))
  ([file-id library-id asset-type asset-id undo-group]
   (dm/assert! (uuid? file-id))
   (dm/assert! (uuid? library-id))
   (dm/assert! (or (nil? asset-type)
                   (contains? valid-asset-types asset-type)))
   (dm/assert! (or (nil? asset-id)
                   (uuid? asset-id)))
   (ptk/reify ::sync-file
     ptk/UpdateEvent
     (update [_ state]
       (if (and (not= library-id (:current-file-id state))
                (nil? asset-id))
         (d/assoc-in-when state [:workspace-libraries library-id :synced-at] (dt/now))
         state))

     ptk/WatchEvent
     (watch [it state _]
       (when (and (some? file-id) (some? library-id)) ; Prevent race conditions while navigating out of the file
         (log/info :msg "SYNC-FILE"
                   :file (dwlh/pretty-file file-id state)
                   :library (dwlh/pretty-file library-id state)
                   :asset-type asset-type
                   :asset-id asset-id
                   :undo-group undo-group)
         (let [file            (wsh/get-file state file-id)

               sync-components?   (or (nil? asset-type) (= asset-type :components))
               sync-colors?       (or (nil? asset-type) (= asset-type :colors))
               sync-typographies? (or (nil? asset-type) (= asset-type :typographies))

               library-changes (reduce
                                pcb/concat-changes
                                (-> (pcb/empty-changes it)
                                    (pcb/set-undo-group undo-group))
                                [(when sync-components?
                                   (dwlh/generate-sync-library it file-id :components asset-id library-id state))
                                 (when sync-colors?
                                   (dwlh/generate-sync-library it file-id :colors asset-id library-id state))
                                 (when sync-typographies?
                                   (dwlh/generate-sync-library it file-id :typographies asset-id library-id state))])
               file-changes    (reduce
                                pcb/concat-changes
                                (-> (pcb/empty-changes it)
                                    (pcb/set-undo-group undo-group))
                                [(when sync-components?
                                   (dwlh/generate-sync-file it file-id :components asset-id library-id state))
                                 (when sync-colors?
                                   (dwlh/generate-sync-file it file-id :colors asset-id library-id state))
                                 (when sync-typographies?
                                   (dwlh/generate-sync-file it file-id :typographies asset-id library-id state))])

               changes         (pcb/concat-changes library-changes file-changes)]

           (log/debug :msg "SYNC-FILE finished" :js/rchanges (log-changes
                                                              (:redo-changes changes)
                                                              file))
           (rx/concat
            (rx/of (msg/hide-tag :sync-dialog))
            (when (seq (:redo-changes changes))
              (rx/of (dch/commit-changes (assoc changes ;; TODO a ver qué pasa con esto
                                                :file-id file-id))))
            (when (not= file-id library-id)
              ;; When we have just updated the library file, give some time for the
              ;; update to finish, before marking this file as synced.
              ;; TODO: look for a more precise way of syncing this.
              ;; Maybe by using the stream (second argument passed to watch)
              ;; to wait for the corresponding changes-committed and then proceed
              ;; with the :update-file-library-sync-status mutation.
              (rx/concat (rx/timer 3000)
                         (rp/cmd! :update-file-library-sync-status
                                  {:file-id file-id
                                   :library-id library-id})))
            (when (and (seq (:redo-changes library-changes))
                       sync-components?)
              (rx/of (sync-file-2nd-stage file-id library-id asset-id undo-group))))))))))

(defn- sync-file-2nd-stage
  "If some components have been modified, we need to launch another synchronization
  to update the instances of the changed components."
  ;; TODO: this does not work if there are multiple nested components. Only the
  ;;       first level will be updated.
  ;;       To solve this properly, it would be better to launch another sync-file
  ;;       recursively. But for this not to cause an infinite loop, we need to
  ;;       implement updated-at at component level, to detect what components have
  ;;       not changed, and then not to apply sync and terminate the loop.
  [file-id library-id asset-id undo-group]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? library-id))
  (dm/assert! (or (nil? asset-id)
                  (uuid? asset-id)))
  (ptk/reify ::sync-file-2nd-stage
    ptk/WatchEvent
    (watch [it state _]
      (log/info :msg "SYNC-FILE (2nd stage)"
                :file (dwlh/pretty-file file-id state)
                :library (dwlh/pretty-file library-id state))
      (let [file    (wsh/get-file state file-id)
            changes (reduce
                     pcb/concat-changes
                     (-> (pcb/empty-changes it)
                         (pcb/set-undo-group undo-group))
                     [(dwlh/generate-sync-file it file-id :components asset-id library-id state)
                      (dwlh/generate-sync-library it file-id :components asset-id library-id state)])]

        (log/debug :msg "SYNC-FILE (2nd stage) finished" :js/rchanges (log-changes
                                                                       (:redo-changes changes)
                                                                       file))
        (when (seq (:redo-changes changes))
          (rx/of (dch/commit-changes (assoc changes :file-id file-id))))))))

(def ignore-sync
  "Mark the file as ignore syncs. All library changes before this moment will not
   ber notified to sync."
  (ptk/reify ::ignore-sync
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :ignore-sync-until] (dt/now)))

    ptk/WatchEvent
    (watch [_ state _]
      (rp/cmd! :ignore-file-library-sync-status
               {:file-id (get-in state [:workspace-file :id])
                :date (dt/now)}))))

(defn assets-need-sync
  "Get a lazy sequence of all the assets of each type in the library that have
  been modified after the last sync of the library. The sync date may be
  overriden by providing a ignore-until parameter.

  The sequence items are tuples of (page-id shape-id asset-id asset-type)."
  ([library file-data] (assets-need-sync library file-data nil))
  ([library file-data ignore-until]
    (let [sync-date (max (:synced-at library) (or ignore-until 0))]
      (when (> (:modified-at library) sync-date)
        (ctf/used-assets-changed-since file-data library sync-date)))))

(defn notify-sync-file
  [file-id]
  (dm/assert! (uuid? file-id))
  (ptk/reify ::notify-sync-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-data (:workspace-data state)
            libraries-need-sync (filter #(seq (assets-need-sync % file-data))
                                        (vals (get state :workspace-libraries)))
            do-update #(do (apply st/emit! (map (fn [library]
                                                  (sync-file (:current-file-id state)
                                                             (:id library)))
                                                libraries-need-sync))
                           (st/emit! msg/hide))
            do-dismiss #(do (st/emit! ignore-sync)
                            (st/emit! msg/hide))]

        (when (seq libraries-need-sync)
          (rx/of (msg/info-dialog
                  (tr "workspace.updates.there-are-updates")
                  :inline-actions
                  [{:label (tr "workspace.updates.update")
                    :callback do-update}
                   {:label (tr "workspace.updates.dismiss")
                    :callback do-dismiss}]
                  :sync-dialog)))))))

(defn watch-component-changes
  "Watch the state for changes that affect to any main instance. If a change is detected will throw
  an update-component-sync, so changes are immediately propagated to the component and copies."
  []
  (ptk/reify ::watch-component-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [components-v2 (features/active-feature? state :components-v2)

            stopper
            (->> stream
                 (rx/filter #(or (= :app.main.data.workspace/finalize-page (ptk/type %))
                                 (= ::watch-component-changes (ptk/type %)))))

            workspace-data-s
            (->> (rx/concat
                  (rx/of nil)
                  (rx/from-atom refs/workspace-data {:emit-current-value? true}))
                 ;; Need to get the file data before the change, so deleted shapes
                 ;; still exist, for example
                 (rx/buffer 3 1))

            change-s
            (->> stream
                 (rx/filter #(or (dch/commit-changes? %)
                                 (= (ptk/type %) :app.main.data.workspace.notifications/handle-file-change)))
                 (rx/observe-on :async))

            check-changes
            (fn [[event [old-data _mid_data _new-data]]]
              (let [{:keys [changes save-undo? undo-group]} (deref event)
                    components-changed (reduce #(into %1 (ch/components-changed old-data %2))
                                               #{}
                                               changes)]
                (when (and (d/not-empty? components-changed) save-undo?)
                  (log/info :msg "DETECTED COMPONENTS CHANGED"
                            :ids (map str components-changed)
                            :undo-group undo-group)
                  (run! st/emit!
                        (map #(update-component-sync % (:id old-data) undo-group)
                             components-changed)))))]

        (when components-v2
          (->> change-s
               (rx/with-latest-from workspace-data-s)
               (rx/map check-changes)
               (rx/take-until stopper)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Backend interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-file-shared
  [id is-shared]
  {:pre [(uuid? id) (boolean? is-shared)]}
  (ptk/reify ::set-file-shared
    IDeref
    (-deref [_]
      {::ev/origin "workspace" :id id :shared is-shared})

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :is-shared] is-shared))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:id id :is-shared is-shared}]
        (->> (rp/cmd! :set-file-shared params)
             (rx/ignore))))))

(defn- shared-files-fetched
  [files]
  (ptk/reify ::shared-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [state (dissoc state :files)]
        (assoc state :workspace-shared-files files)))))

(defn fetch-shared-files
  [{:keys [team-id] :as params}]
  (dm/assert! (uuid? team-id))
  (ptk/reify ::fetch-shared-files
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-team-shared-files {:team-id team-id})
           (rx/map shared-files-fetched)))))

;; --- Link and unlink Files

(defn link-file-to-library
  [file-id library-id]
  (ptk/reify ::attach-library
    ;; NOTE: this event implements UpdateEvent protocol for perform an
    ;; optimistic update state for make the UI feel more responsive.
    ptk/UpdateEvent
    (update [_ state]
      (let [libraries (:workspace-shared-files state)
            library   (d/seek #(= (:id %) library-id) libraries)]
        (if library
          (update state :workspace-libraries assoc library-id (dissoc library :library-summary))
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [features (cond-> ffeat/enabled
                       (features/active-feature? state :components-v2)
                       (conj "components/v2"))]
        (rx/merge
         (->> (rp/cmd! :link-file-to-library {:file-id file-id :library-id library-id})
              (rx/ignore))
         (->> (rp/cmd! :get-file {:id library-id :features features})
              (rx/map (fn [file]
                        (fn [state]
                          (assoc-in state [:workspace-libraries library-id] file))))))))))

(defn unlink-file-from-library
  [file-id library-id]
  (ptk/reify ::detach-library
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-libraries library-id]))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:file-id file-id
                    :library-id library-id}]
        (->> (rp/cmd! :unlink-file-from-library params)
             (rx/ignore))))))
