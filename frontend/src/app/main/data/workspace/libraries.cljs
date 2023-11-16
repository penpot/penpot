;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as ch]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.libraries-helpers :as cflh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.main.data.workspace.notifications :as-alias dwn]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.specialized-panel :as dwsp]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cuerdas.core :as str]
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
  (let [[path name] (cfh/parse-path-name (:name item))]
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
                  (assoc :name (or (get-in color [:image :name])
                                   (:color color)
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
  (dm/assert! (ctc/valid-recent-color? color))
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
        [path name] (cfh/parse-path-name (:name color))
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
  (dm/assert! (ctc/valid-color? color))
  (dm/assert! (uuid? file-id))

  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [it state _]
      (do-update-color it state color file-id))))

(defn rename-color
  [file-id id new-name]
  (dm/verify! (uuid? file-id))
  (dm/verify! (uuid? id))
  (dm/verify! (string? new-name))

  (ptk/reify ::rename-color
    ptk/WatchEvent
    (watch [it state _]
      (let [new-name (str/trim new-name)]
        (if (str/empty? new-name)
          (rx/empty)
          (let [data   (get state :workspace-data)
                object (get-in data [:colors id])
                object (assoc object :name new-name)]
            (do-update-color it state object file-id)))))))

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
  (dm/assert! (ctf/valid-media-object? media))
  (ptk/reify ::add-media
    ptk/WatchEvent
    (watch [it _ _]
      (let [obj     (select-keys media [:id :name :width :height :mtype])
            changes (-> (pcb/empty-changes it)
                        (pcb/add-media obj))]
        (rx/of (dch/commit-changes changes))))))

(defn rename-media
  [id new-name]
  (dm/verify! (uuid? id))
  (dm/verify! (string? new-name))
  (ptk/reify ::rename-media
    ptk/WatchEvent
    (watch [it state _]
      (let [new-name (str/trim new-name)]
        (if (str/empty? new-name)
          (rx/empty)
          (let [[path name] (cfh/parse-path-name new-name)
                data        (get state :workspace-data)
                object      (get-in data [:media id])
                new-object  (assoc object :path path :name name)
                changes     (-> (pcb/empty-changes it)
                                (pcb/with-library-data data)
                                (pcb/update-media new-object))]
            (rx/of (dch/commit-changes changes))))))))

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
                     (assoc-in [:workspace-global :edit-typography] (:id typography))))))))))

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
              [path name] (cfh/parse-path-name new-name)
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
            shapes   (dwg/shapes-for-grouping objects selected)
            parents  (into #{} (map :parent-id) shapes)]
        (when-not (empty? shapes)
          (let [[root _ changes]
                (cflh/generate-add-component it shapes objects page-id file-id components-v2
                                             dwg/prepare-create-group
                                             cfsh/prepare-create-artboard-from-selection)]
            (when-not (empty? (:redo-changes changes))
              (rx/of (dch/commit-changes changes)
                     (dws/select-shapes (d/ordered-set (:id root)))
                     (ptk/data-event :layout/update parents)))))))))

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
                               (cfh/clean-loops objects)
                               (remove #(ctn/has-any-copy-parent? objects (get objects %)))) ;; We don't want to change the structure of component copies
            components-v2 (features/active-feature? state "components/v2")]
        (rx/of (add-component2 selected components-v2))))))

(defn add-multiple-components
  "Add several new components to current file library, from the currently selected shapes."
  []
  (ptk/reify ::add-multiple-components
    ptk/WatchEvent
    (watch [_ state _]
      (let [components-v2    (features/active-feature? state "components/v2")
            objects       (wsh/lookup-page-objects state)
            selected      (->> (wsh/lookup-selected state)
                               (cfh/clean-loops objects)
                               (remove #(ctn/has-any-copy-parent? objects (get objects %)))) ;; We don't want to change the structure of component copies
            added-components (map
                              #(add-component2 [%] components-v2)
                              selected)
            undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (rx/from added-components)
         (rx/of (dwu/commit-undo-transaction undo-id)))))))

(defn rename-component
  "Rename the component with the given id, in the current file library."
  [id new-name]
  (dm/verify! (uuid? id))
  (dm/verify! (string? new-name))
  (ptk/reify ::rename-component
    ptk/WatchEvent
    (watch [it state _]
      (let [new-name (str/trim new-name)]
        (if (str/empty? new-name)
          (rx/empty)
          (let [data          (get state :workspace-data)
                [path name]   (cfh/parse-path-name new-name)
                components-v2 (features/active-feature? state "components/v2")

                update-fn
                (fn [component]
                  (cond-> component
                    :always
                    (assoc :path path
                           :name name)

                    (not components-v2)
                    (update :objects
                            ;; Give the same name to the root shape
                            #(assoc-in % [id :name] name))))

                changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/update-component id update-fn))]

            (rx/of (dch/commit-changes changes))))))))

(defn rename-component-and-main-instance
  [component-id name]
  (ptk/reify ::rename-component-and-main-instance
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [component (dm/get-in state [:workspace-data :components component-id])]
        (let [name     (cfh/clean-path name)
              shape-id (:main-instance-id component)
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
      (let [libraries          (wsh/get-libraries state)
            library            (get libraries library-id)
            component          (ctkl/get-component (:data library) component-id)
            new-name           (:name component)

            components-v2      (features/active-feature? state "components/v2")

            main-instance-page (when components-v2
                                 (ctf/get-component-page (:data library) component))

            new-component-id   (when components-v2
                                 (uuid/next))

            [new-component-shape new-component-shapes  ; <- null in components-v2
             new-main-instance-shape new-main-instance-shapes]
            (dwlh/duplicate-component component new-component-id (:data library))

            changes (-> (pcb/empty-changes it nil)
                        (pcb/with-page main-instance-page)
                        (pcb/with-objects (:objects main-instance-page))
                        (pcb/add-objects new-main-instance-shapes {:ignore-touched true})
                        (pcb/add-component (if components-v2
                                             new-component-id
                                             (:id new-component-shape))
                                           (:path component)
                                           new-name
                                           new-component-shapes
                                           []
                                           (:id new-main-instance-shape)
                                           (:id main-instance-page)
                                           (:annotation component)))]

        (rx/of (dch/commit-changes changes))))))

(defn delete-component
  "Delete the component with the given id, from the current file library."
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)]
        (if (features/active-feature? state "components/v2")
          (let [component (ctkl/get-component data id)
                page-id   (:main-instance-page component)
                root-id   (:main-instance-id component)]
            (rx/of
             (dwt/clear-thumbnail (:current-file-id state) page-id root-id "component")
             (dwsh/delete-shapes page-id #{root-id}))) ;; Deleting main root triggers component delete
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
      (let [page-id      (:current-page-id state)
            current-page (dm/get-in state [:workspace-data :pages-index page-id])
            objects      (wsh/lookup-page-objects state page-id)
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

        (rx/of (dch/commit-changes (assoc changes :file-id library-id)))))))


(defn restore-components
  "Restore multiple deleted component definded by a map with the component id as key and the component library as value"
  [components-data]
  (dm/assert! (map? components-data))
  (ptk/reify ::restore-components
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (rx/map #(restore-component (val %) (key %)) (rx/from components-data))
         (rx/of (dwu/commit-undo-transaction undo-id)))))))

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

            objects   (:objects page)
            changes   (-> (pcb/empty-changes it (:id page))
                          (pcb/with-objects objects))

            [new-shape changes]
            (dwlh/generate-instantiate-component changes
                                                 objects
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
            container (cfh/get-container file :page page-id)

            changes   (-> (pcb/empty-changes it)
                          (pcb/with-container container)
                          (pcb/with-objects (:objects container))
                          (dwlh/generate-detach-instance container id))]

        (rx/of (dch/commit-changes changes))))))

(defn detach-components
  "Remove all references to components in the shapes with the given ids"
  [ids]
  (dm/assert! (seq ids))
  (ptk/reify ::detach-components
    ptk/WatchEvent
    (watch [_ _ _]
           (let [undo-id (js/Symbol)]
             (rx/concat
              (rx/of (dwu/start-undo-transaction undo-id))
              (rx/map #(detach-component %) (rx/from ids))
              (rx/of (dwu/commit-undo-transaction undo-id)))))))

(def detach-selected-components
  (ptk/reify ::detach-selected-components
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            file      (wsh/get-local-file state)
            container (cfh/get-container file :page page-id)
            selected  (->> state
                           (wsh/lookup-selected)
                           (cfh/clean-loops objects))

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
  [library-id modified-at revn changes]
  (dm/assert! (uuid? library-id))
  (dm/assert! (ch/valid-changes? changes))
  (ptk/reify ::ext-library-changed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-libraries library-id]
                     assoc :modified-at modified-at :revn revn)
          (d/update-in-when [:workspace-libraries library-id :data]
                            ch/process-changes changes)))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-file-object-thumbnails {:file-id library-id :tag "component"})
           (rx/map (fn [thumbnails]
                     (fn [state]
                       (assoc-in state [:workspace-libraries library-id :thumbnails] thumbnails))))))))

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
            container (cfh/get-container file :page page-id)

            components-v2
            (features/active-feature? state "components/v2")

            changes
            (-> (pcb/empty-changes it)
                (pcb/with-container container)
                (pcb/with-objects (:objects container))
                (dwlh/generate-sync-shape-direct libraries container id true components-v2))]

        (log/debug :msg "RESET-COMPONENT finished" :js/rchanges (log-changes
                                                                 (:redo-changes changes)
                                                                 file))
        (rx/of (dch/commit-changes changes))))))

(defn reset-components
  "Cancels all modifications in the shapes with the given ids"
  [ids]
  (dm/assert! (seq ids))
  (ptk/reify ::reset-components
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (rx/map #(reset-component %) (rx/from ids))
         (rx/of (dwu/commit-undo-transaction undo-id)))))))


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
       (let [page-id       (get state :current-page-id)
             local-file    (wsh/get-local-file state)
             container     (cfh/get-container local-file :page page-id)
             shape         (ctn/get-shape container id)
             components-v2 (features/active-feature? state "components/v2")]

         (when (ctk/instance-head? shape)
           (let [libraries (wsh/get-libraries state)

                 changes
                 (-> (pcb/empty-changes it)
                     (pcb/set-undo-group undo-group)
                     (pcb/with-container container)
                     (dwlh/generate-sync-shape-inverse libraries container id components-v2))

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

(defn launch-component-sync
  "Launch a sync of the current file and of the library file of the given component."
  ([component-id file-id] (launch-component-sync component-id file-id nil))
  ([component-id file-id undo-group]
   (ptk/reify ::launch-component-sync
     ptk/WatchEvent
     (watch [_ state _]
       (let [current-file-id (:current-file-id state)
             undo-id         (js/Symbol)]
          (rx/of
           (dwu/start-undo-transaction undo-id)
           (sync-file current-file-id file-id :components component-id undo-group)
           (when (not= current-file-id file-id)
             (sync-file file-id file-id :components component-id undo-group))
           (dwu/commit-undo-transaction undo-id)))))))

(defn update-component-thumbnail
  "Update the thumbnail of the component with the given id, in the
   current file and in the imported libraries."
  [component-id file-id]
  (ptk/reify ::update-component-thumbnail
    ptk/WatchEvent
    (watch [_ state _]
      (let [data            (get state :workspace-data)
            component       (ctkl/get-component data component-id)
            page-id         (:main-instance-page component)
            root-id         (:main-instance-id component)]
           (rx/of (dwt/request-thumbnail file-id page-id root-id "component"))))))

(defn- find-shape-index
  [objects id shape-id]
  (let [object (get objects id)]
    (when object
      (let [shapes (:shapes object)]
        (or (->> shapes
                 (map-indexed (fn [index shape] [shape index]))
                 (filter #(= shape-id (first %)))
                 first
                 second)
            0)))))

(defn- component-swap
  "Swaps a component with another one"
  [shape file-id id-new-component]
  (dm/assert! (uuid? id-new-component))
  (dm/assert! (uuid? file-id))
  (ptk/reify ::component-swap
    ptk/WatchEvent
    (watch [it state _]
      (let [page      (wsh/lookup-page state)
            libraries (wsh/get-libraries state)

            objects   (:objects page)
            index     (find-shape-index objects (:parent-id shape) (:id shape))
            position  (gpt/point (:x shape) (:y shape))
            changes   (-> (pcb/empty-changes it (:id page))
                          (pcb/with-objects objects))

            [new-shape changes]
            (dwlh/generate-instantiate-component changes
                                                 objects
                                                 file-id
                                                 id-new-component
                                                 position
                                                 page
                                                 libraries)
            changes (pcb/change-parent changes (:parent-id shape) [new-shape] index {:component-swap true})]
        (rx/of (dch/commit-changes changes)
               (ptk/data-event :layout/update [(:id new-shape)])
               (dws/select-shape (:id new-shape) true)
               (dwsh/delete-shapes nil (d/ordered-set (:id shape)) {:component-swap true}))))))



(defn component-multi-swap
  "Swaps several components with another one"
  [shapes file-id id-new-component]
  (dm/assert! (seq shapes))
  (dm/assert! (uuid? id-new-component))
  (dm/assert! (uuid? file-id))
  (ptk/reify ::component-multi-swap
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (rx/map #(component-swap % file-id id-new-component) (rx/from shapes))
         (rx/of (dwu/commit-undo-transaction undo-id))
         (rx/of (dwsp/open-specialized-panel :component-swap)))))))


(def valid-asset-types
  #{:colors :components :typographies})

(defn set-updating-library
  [updating?]
  (ptk/reify ::set-updating-library
    ptk/UpdateEvent
    (update [_ state]
      (if updating?
        (assoc state :updating-library true)
        (dissoc state :updating-library)))))

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
            (rx/of (set-updating-library false)
                   (msg/hide-tag :sync-dialog))
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
                                   :library-id library-id}))))))))))

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
  overriden by providing a ignore-until parameter."
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
            ignore-until (dm/get-in state [:workspace-file :ignore-sync-until])
            libraries-need-sync (filter #(seq (assets-need-sync % file-data ignore-until))
                                        (vals (get state :workspace-libraries)))
            do-more-info #(do (modal/show! :libraries-dialog {:starting-tab :updates})
                              (st/emit! msg/hide))
            do-update #(do (apply st/emit! (map (fn [library]
                                                  (sync-file (:current-file-id state)
                                                             (:id library)))
                                                libraries-need-sync))
                           (st/emit! msg/hide))
            do-dismiss #(do (st/emit! ignore-sync)
                            (st/emit! msg/hide))]

        (when (seq libraries-need-sync)
          (rx/of (msg/info-dialog
                  :content (tr "workspace.updates.there-are-updates")
                  :controls :inline-actions
                  :links   [{:label (tr "workspace.updates.more-info")
                             :callback do-more-info}]
                  :actions [{:label (tr "workspace.updates.update")
                             :callback do-update}
                            {:label (tr "workspace.updates.dismiss")
                             :callback do-dismiss}]
                  :tag :sync-dialog)))))))

(defn component-changed
  "Notify that the component with the given id has changed, so it needs to be updated
   in the current file and in the copies. And also update its thumbnails."
  [component-id file-id undo-group]
  (ptk/reify ::component-changed
    cljs.core/IDeref
    (-deref [_] [component-id file-id])

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (launch-component-sync component-id file-id undo-group)))))

(defn watch-component-changes
  "Watch the state for changes that affect to any main instance. If a change is detected will throw
  an update-component-sync, so changes are immediately propagated to the component and copies."
  []
  (ptk/reify ::watch-component-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [components-v2? (features/active-feature? state "components/v2")

            stopper-s
            (->> stream
                 (rx/filter #(or (= ::dw/finalize-page (ptk/type %))
                                 (= ::watch-component-changes (ptk/type %)))))

            workspace-data-s
            (->> (rx/concat
                  (rx/of nil)
                  (rx/from-atom refs/workspace-data {:emit-current-value? true}))
                 ;; Need to get the file data before the change, so deleted shapes
                 ;; still exist, for example
                 (rx/buffer 3 1)
                 (rx/filter (fn [[old-data]] (some? old-data))))

            changes-s
            (->> stream
                 (rx/filter #(or (dch/commit-changes? %)
                                 (ptk/type? % ::dwn/handle-file-change)))
                 (rx/observe-on :async))

            check-changes
            (fn [[event [old-data _mid_data _new-data]]]
              (when old-data
                (let [{:keys [file-id changes save-undo? undo-group]} (deref event)

                      changed-components
                      (when (or (nil? file-id) (= file-id (:id old-data)))
                        (->> changes
                             (map (partial ch/components-changed old-data))
                             (reduce into #{})))]

                  (if (and (d/not-empty? changed-components) save-undo?)
                    (do (log/info :msg "DETECTED COMPONENTS CHANGED"
                                  :ids (map str changed-components)
                                  :undo-group undo-group)

                        (->> (rx/from changed-components)
                             (rx/map #(component-changed % (:id old-data) undo-group))))
                    (rx/empty)))))

            changes-s
            (->> changes-s
                 (rx/with-latest-from workspace-data-s)
                 (rx/mapcat check-changes)
                 (rx/share))

            notifier-s
            (->> changes-s
                 (rx/debounce 5000)
                 (rx/tap #(log/trc :hint "buffer initialized")))]

        (when components-v2?
          (->> (rx/merge
                changes-s

                (->> changes-s
                     (rx/map deref)
                     (rx/buffer-until notifier-s)
                     (rx/mapcat #(into #{} %))
                     (rx/map (fn [[component-id file-id]]
                               (update-component-thumbnail component-id file-id)))))

               (rx/take-until stopper-s)))))))

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
      (let [features (features/get-team-enabled-features state)]
        (rx/merge
         (->> (rp/cmd! :link-file-to-library {:file-id file-id :library-id library-id})
              (rx/ignore))
         (->> (rp/cmd! :get-file {:id library-id :features features})
              (rx/merge-map fpmap/resolve-file)
              (rx/map (fn [file]
                        (fn [state]
                          (assoc-in state [:workspace-libraries library-id] file)))))
         (->> (rp/cmd! :get-file-object-thumbnails {:file-id library-id :tag "component"})
              (rx/map (fn [thumbnails]
                        (fn [state]
                          (assoc-in state [:workspace-libraries library-id :thumbnails] thumbnails))))))))))

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
