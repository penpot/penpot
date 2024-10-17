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
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.logging :as log]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dc]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.notifications :as-alias dwn]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.specialized-panel :as dwsp]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.transforms :as dwtr]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.features.pointer-map :as fpmap]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.storage :as storage]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

(defn- pretty-file
  [file-id state]
  (if (= file-id (:current-file-id state))
    "<local>"
    (str "<" (get-in state [:workspace-libraries file-id :name]) ">")))

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
                          (assoc :shape (str prefix (:name shape))
                                 :shape-id (str (:id shape)))
                          (:obj change)
                          (assoc :obj (:name (:obj change))
                                 :obj-id (:id (:obj change)))
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
  ([color]
   (add-color color nil))

  ([color {:keys [rename?] :or {rename? true}}]
   (let [color (-> color
                   (update :id #(or % (uuid/next)))
                   (assoc :name (or (get-in color [:image :name])
                                    (:color color)
                                    (uc/gradient-type->string (get-in color [:gradient :type]))))
                   (d/without-nils))]

     (dm/assert!
      "expect valid color structure"
      (ctc/check-color! color))

     (ptk/reify ::add-color
       ev/Event
       (-data [_] color)

       ptk/WatchEvent
       (watch [it _ _]
         (let [changes (-> (pcb/empty-changes it)
                           (pcb/add-color color))]
           (rx/of
            (when rename?
              (fn [state] (assoc-in state [:workspace-local :color-for-rename] (:id color))))
            (dch/commit-changes changes))))))))

(defn add-recent-color
  [color]

  (dm/assert!
   "expected valid recent color structure"
   (ctc/check-recent-color! color))

  (ptk/reify ::add-recent-color
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)]
        (update state :recent-colors ctc/add-recent-color file-id color)))

    ptk/EffectEvent
    (effect [_ state _]
      (let [recent-colors (:recent-colors state)]
        (swap! storage/user assoc :recent-colors recent-colors)))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn- update-color*
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
  (let [color (d/without-nils color)]

    (dm/assert!
     "expected valid color data structure"
     (ctc/check-color! color))

    (dm/assert!
     "expected file-id"
     (uuid? file-id))

    (ptk/reify ::update-color
      ptk/WatchEvent
      (watch [it state _]
        (update-color* it state color file-id)))))

(defn rename-color
  [file-id id new-name]
  (dm/assert!
   "expected valid uuid for `id`"
   (uuid? id))

  (dm/assert!
   "expected valid uuid for `file-id`"
   (uuid? file-id))

  (dm/assert!
   "expected valid string for `new-name`"
   (string? new-name))

  (ptk/reify ::rename-color
    ptk/WatchEvent
    (watch [it state _]
      (let [new-name (str/trim new-name)]
        (if (str/empty? new-name)
          (rx/empty)
          (let [data   (get state :workspace-data)
                color  (get-in data [:colors id])
                color  (assoc color :name new-name)
                color  (d/without-nils color)]
            (update-color* it state color file-id)))))))

(defn delete-color
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-color
    ev/Event
    (-data [_] {:id id})

    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-color id))]
        (rx/of (dch/commit-changes changes))))))

(defn add-media
  [media]
  (dm/assert!
   "expected valid media object"
   (ctf/check-media-object! media))

  (ptk/reify ::add-media
    ev/Event
    (-data [_] media)

    ptk/WatchEvent
    (watch [it _ _]
      (let [obj     (select-keys media [:id :name :width :height :mtype])
            changes (-> (pcb/empty-changes it)
                        (pcb/add-media obj))]
        (rx/of (dch/commit-changes changes))))))

(defn rename-media
  [id new-name]

  (dm/assert!
   "expected valid uuid for `id`"
   (uuid? id))

  (dm/assert!
   "expected valid string for `new-name`"
   (string? new-name))

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
  [{:keys [id]}]
  (dm/assert!
   "expected valid uuid for `id`"
   (uuid? id))

  (ptk/reify ::delete-media
    ev/Event
    (-data [_] {:id id})

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
     (dm/assert!
      "expected valid typography"
      (ctt/check-typography! typography))

     (ptk/reify ::add-typography
       ev/Event
       (-data [_] typography)

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

  (dm/assert!
   "expected valid typography and file-id"
   (and (ctt/check-typography! typography)
        (uuid? file-id)))

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
    ev/Event
    (-data [_] {:id id :name new-name})

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
    ev/Event
    (-data [_] {:id id})

    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-typography id))]
        (rx/of (dch/commit-changes changes))))))

(defn- add-component2
  "This is the second step of the component creation."
  ([selected components-v2]
   (add-component2 nil selected components-v2))
  ([id-ref selected components-v2]
   (ptk/reify ::add-component2
     ev/Event
     (-data [_]
       {::ev/name "add-component"
        :shapes (count selected)})

     ptk/WatchEvent
     (watch [it state _]
       (let [file-id  (:current-file-id state)
             page-id  (:current-page-id state)
             objects  (wsh/lookup-page-objects state page-id)
             shapes   (dwg/shapes-for-grouping objects selected)
             parents  (into #{} (map :parent-id) shapes)]
         (when-not (empty? shapes)
           (let [[root component-id changes]
                 (cll/generate-add-component (pcb/empty-changes it) shapes objects page-id file-id components-v2
                                             dwg/prepare-create-group
                                             cfsh/prepare-create-artboard-from-selection)]
             (when id-ref
               (reset! id-ref component-id))
             (when-not (empty? (:redo-changes changes))
               (rx/of (dch/commit-changes changes)
                      (dws/select-shapes (d/ordered-set (:id root)))
                      (ptk/data-event :layout/update {:ids parents}))))))))))

(defn add-component
  "Add a new component to current file library, from the currently selected shapes.
  This operation is made in two steps, first one for calculate the
  shapes that will be part of the component and the second one with
  the component creation."
  ([]
   (add-component nil nil))

  ([id-ref ids]
   (ptk/reify ::add-component
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects            (wsh/lookup-page-objects state)
             selected           (->> (d/nilv ids (wsh/lookup-selected state))
                                     (cfh/clean-loops objects))
             selected-objects   (map #(get objects %) selected)
             components-v2      (features/active-feature? state "components/v2")
             ;; We don't want to change the structure of component copies
             can-make-component (every? true? (map #(ctn/valid-shape-for-component? objects %) selected-objects))]

         (when can-make-component
           (rx/of (add-component2 id-ref selected components-v2))))))))

(defn add-multiple-components
  "Add several new components to current file library, from the currently selected shapes."
  []
  (ptk/reify ::add-multiple-components
    ptk/WatchEvent
    (watch [_ state _]
      (let [components-v2      (features/active-feature? state "components/v2")
            objects            (wsh/lookup-page-objects state)
            selected           (->> (wsh/lookup-selected state)
                                    (cfh/clean-loops objects))
            selected-objects   (map #(get objects %) selected)
            ;; We don't want to change the structure of component copies
            can-make-component (every? true? (map #(ctn/valid-shape-for-component? objects %) selected-objects))
            added-components   (map (fn [id]
                                      (with-meta (add-component2 [id] components-v2)
                                        {:multiple true}))
                                    selected)
            undo-id (js/Symbol)]
        (when can-make-component
          (rx/concat
           (rx/of (dwu/start-undo-transaction undo-id))
           (rx/from added-components)
           (rx/of (dwu/commit-undo-transaction undo-id))))))))

(defn rename-component
  "Rename the component with the given id, in the current file library."
  [id new-name]
  (dm/assert!
   "expected an uuid instance"
   (uuid? id))

  (dm/assert!
   "expected string for new-name"
   (string? new-name))

  (ptk/reify ::rename-component
    ptk/WatchEvent
    (watch [it state _]
      (let [new-name (str/trim new-name)]
        (if (str/empty? new-name)
          (rx/empty)
          (let [library-data  (get state :workspace-data)
                components-v2 (features/active-feature? state "components/v2")
                changes       (-> (pcb/empty-changes it)
                                  (cll/generate-rename-component id new-name library-data components-v2))]

            (rx/of (dch/commit-changes changes))))))))

(defn rename-component-and-main-instance
  [component-id name]
  (ptk/reify ::rename-component-and-main-instance
    ptk/WatchEvent
    (watch [_ state _]
      (let [name        (str/trim name)
            clean-name  (cfh/clean-path name)
            valid?      (and (not (str/ends-with? name "/"))
                             (string? clean-name)
                             (not (str/blank? clean-name)))
            component (dm/get-in state [:workspace-data :components component-id])]
        (when (and valid? component)
          (let [shape-id (:main-instance-id component)
                page-id  (:main-instance-page component)]
            (rx/concat
             (rx/of (rename-component component-id clean-name))

           ;; NOTE: only when components-v2 is enabled
             (when (and shape-id page-id)
               (rx/of (dwsh/update-shapes [shape-id] #(assoc % :name clean-name) {:page-id page-id :stack-undo? true}))))))))))

(defn duplicate-component
  "Create a new component copied from the one with the given id."
  [library-id component-id]
  (ptk/reify ::duplicate-component
    ptk/WatchEvent
    (watch [it state _]
      (let [libraries          (wsh/get-libraries state)
            library            (get libraries library-id)
            components-v2      (features/active-feature? state "components/v2")
            changes (-> (pcb/empty-changes it nil)
                        (cll/generate-duplicate-component library component-id components-v2))]

        (rx/of (dch/commit-changes changes))))))

(defn delete-component
  "Delete the component with the given id, from the current file library."
  [{:keys [id]}]
  (dm/assert!
   "expected valid uuid for `id`"
   (uuid? id))

  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)]
        (if (features/active-feature? state "components/v2")
          (let [component     (ctkl/get-component data id)
                page-id       (:main-instance-page component)
                root-id       (:main-instance-id component)
                file-id       (:current-file-id state)
                file          (wsh/get-file state file-id)
                page          (wsh/lookup-page state page-id)
                objects       (wsh/lookup-page-objects state page-id)
                components-v2 (features/active-feature? state "components/v2")
                undo-group    (uuid/next)
                undo-id       (js/Symbol)
                [all-parents changes]
                (-> (pcb/empty-changes it page-id)
                    ;; Deleting main root triggers component delete
                    (cls/generate-delete-shapes file page objects #{root-id} {:components-v2 components-v2
                                                                              :undo-group undo-group
                                                                              :undo-id undo-id}))]
            (rx/of
             (dwu/start-undo-transaction undo-id)
             (dwt/clear-thumbnail (:current-file-id state) page-id root-id "component")
             (dc/detach-comment-thread #{root-id})
             (dch/commit-changes changes)
             (ptk/data-event :layout/update {:ids all-parents :undo-group undo-group})
             (dwu/commit-undo-transaction undo-id)))
          (let [page-id (:current-page-id state)
                changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/delete-component id page-id))]
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
            library-data (wsh/get-file state library-id)
            objects      (wsh/lookup-page-objects state page-id)
            changes      (-> (pcb/empty-changes it)
                             (cll/generate-restore-component library-data component-id library-id current-page objects))]
        (rx/of (dch/commit-changes changes))))))


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
  ([file-id component-id position]
   (instantiate-component file-id component-id position nil))
  ([file-id component-id position {:keys [start-move? initial-point id-ref]}]
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
             (cll/generate-instantiate-component changes
                                                 objects
                                                 file-id
                                                 component-id
                                                 position
                                                 page
                                                 libraries)
             undo-id (js/Symbol)]

         (when id-ref
           (reset! id-ref (:id new-shape)))

         (rx/of (dwu/start-undo-transaction undo-id)
                (dch/commit-changes changes)
                (ptk/data-event :layout/update {:ids [(:id new-shape)]})
                (dws/select-shapes (d/ordered-set (:id new-shape)))
                (when start-move?
                  (dwtr/start-move initial-point #{(:id new-shape)}))
                (dwu/commit-undo-transaction undo-id)))))))

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
            libraries (wsh/get-libraries state)

            changes   (-> (pcb/empty-changes it)
                          (cll/generate-detach-component id file page-id libraries))]

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
      (let [page-id          (:current-page-id state)
            objects          (wsh/lookup-page-objects state page-id)
            file             (wsh/get-local-file state)
            libraries        (wsh/get-libraries state)
            selected         (->> state
                                  (wsh/lookup-selected)
                                  (cfh/clean-loops objects))
            selected-objects (map #(get objects %) selected)
            copies           (filter ctk/in-component-copy? selected-objects)
            can-detach?      (and (seq copies)
                                  (every? #(not (ctn/has-any-copy-parent? objects %)) selected-objects))
            changes (when can-detach?
                      (reduce
                       (fn [changes id]
                         (cll/generate-detach-component changes id file page-id libraries))
                       (pcb/empty-changes it)
                       selected))]

        (rx/of (when can-detach?
                 (dch/commit-changes changes)))))))

(defn nav-to-component-file
  [file-id component]
  (dm/assert! (uuid? file-id))
  (dm/assert! (some? component))
  (ptk/reify ::nav-to-component-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [project-id   (get-in state [:workspace-libraries file-id :project-id])
            path-params  {:project-id project-id
                          :file-id file-id}
            query-params {:page-id (:main-instance-page component)
                          :component-id (:id component)}]
        (rx/of (rt/nav-new-window* {:rname :workspace
                                    :path-params path-params
                                    :query-params query-params}))))))

(defn library-thumbnails-fetched
  [thumbnails]
  (ptk/reify ::library-thumbnails-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-thumbnails merge thumbnails))))

(defn fetch-library-thumbnails
  [library-id]
  (ptk/reify ::fetch-library-thumbnails
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-file-object-thumbnails {:file-id library-id :tag "component"})
           (rx/map library-thumbnails-fetched)))))

(defn ext-library-changed
  [library-id modified-at revn changes]

  (dm/assert!
   "expected valid uuid for library-id"
   (uuid? library-id))

  (dm/assert!
   "expected valid changes vector"
   (ch/check-changes! changes))

  (ptk/reify ::ext-library-changed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-libraries library-id]
                     assoc :modified-at modified-at :revn revn)
          (d/update-in-when [:workspace-libraries library-id :data]
                            ch/process-changes changes)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper-s (rx/filter (ptk/type? ::ext-library-changed) stream)]
        (->>
         (rx/merge
          (->> (rx/of library-id)
               (rx/delay 5000)
               (rx/map fetch-library-thumbnails)))

         (rx/take-until stopper-s))))))

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
      (let [file       (wsh/get-local-file state)
            file-full  (wsh/get-local-file-full state)
            libraries  (wsh/get-libraries state)

            page-id    (:current-page-id state)
            container  (cfh/get-container file :page page-id)

            components-v2
            (features/active-feature? state "components/v2")

            undo-id    (js/Symbol)

            changes
            (-> (pcb/empty-changes it)
                (cll/generate-reset-component file-full libraries container id components-v2))]

        (log/debug :msg "RESET-COMPONENT finished" :js/rchanges (log-changes
                                                                 (:redo-changes changes)
                                                                 file))

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id))))))

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
             full-file     (wsh/get-local-file-full state)
             container     (cfh/get-container local-file :page page-id)
             shape         (ctn/get-shape container id)
             components-v2 (features/active-feature? state "components/v2")]

         (when (ctk/instance-head? shape)
           (let [libraries (wsh/get-libraries state)

                 changes
                 (-> (pcb/empty-changes it)
                     (pcb/set-undo-group undo-group)
                     (pcb/with-container container)
                     (cll/generate-sync-shape-inverse full-file libraries container id components-v2))

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

(defn- update-component-thumbnail-sync
  [state component-id file-id tag]
  (let [current-file-id (:current-file-id state)
        current-file?   (= current-file-id file-id)
        data            (if current-file?
                          (get state :workspace-data)
                          (get-in state [:workspace-libraries file-id :data]))
        component       (ctkl/get-component data component-id)
        page-id         (:main-instance-page component)
        root-id         (:main-instance-id component)]
    (dwt/update-thumbnail file-id page-id root-id tag "update-component-thumbnail-sync")))

(defn update-component-sync
  ([shape-id file-id] (update-component-sync shape-id file-id nil))
  ([shape-id file-id undo-group]
   (ptk/reify ::update-component-sync
     ptk/WatchEvent
     (watch [_ state _]
       (let [current-file-id (:current-file-id state)
             current-file?   (= current-file-id file-id)
             page            (wsh/lookup-page state)
             shape           (ctn/get-shape page shape-id)
             component-id    (:component-id shape)
             undo-id         (js/Symbol)]
         (rx/of
          (dwu/start-undo-transaction undo-id)
          (update-component shape-id undo-group)
          (sync-file current-file-id file-id :components (:component-id shape) undo-group)
          (update-component-thumbnail-sync state component-id file-id "frame")
          (update-component-thumbnail-sync state component-id file-id "component")
          (when (not current-file?)
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
      (rx/of (update-component-thumbnail-sync state component-id file-id "component")))))

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
      ;; First delete shapes so we have space in the layout otherwise we can have problems
      ;; in the grid creating new rows/columns to make space
      (let [file      (wsh/get-file state file-id)
            libraries (wsh/get-libraries state)
            page    (wsh/lookup-page state)
            objects (wsh/lookup-page-objects state)
            parent (get objects (:parent-id shape))

            ;; If the target parent is a grid layout we need to pass the target cell
            target-cell (when (ctl/grid-layout? parent)
                          (ctl/get-cell-by-shape-id parent (:id shape)))

            index (find-shape-index objects (:parent-id shape) (:id shape))

            ;; Store the properties that need to be maintained when the component is swapped
            keep-props-values (select-keys shape ctk/swap-keep-attrs)

            undo-id (js/Symbol)
            undo-group (uuid/next)

            [new-shape all-parents changes]
            (-> (pcb/empty-changes it (:id page))
                (pcb/set-undo-group undo-group)
                (cll/generate-component-swap objects shape file page libraries id-new-component index target-cell keep-props-values))]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dws/select-shape (:id new-shape) true)
         (ptk/data-event :layout/update {:ids all-parents :undo-group undo-group})
         (dwu/commit-undo-transaction undo-id))))))

(defn component-multi-swap
  "Swaps several components with another one"
  [shapes file-id id-new-component]
  (dm/assert! (seq shapes))
  (dm/assert! (uuid? id-new-component))
  (dm/assert! (uuid? file-id))
  (ptk/reify ::component-multi-swap
    ev/Event
    (-data [_]
      {::ev/name "component-swap"})

    ptk/WatchEvent
    (watch [_ state _]
      (let [undo-id (js/Symbol)]
        (log/info :msg "COMPONENT-SWAP"
                  :file (pretty-file file-id state)
                  :id-new-component id-new-component
                  :undo-id undo-id)
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
                   :file (pretty-file file-id state)
                   :library (pretty-file library-id state)
                   :asset-type asset-type
                   :asset-id asset-id
                   :undo-group undo-group)
         (let [file            (wsh/get-file state file-id)
               libraries       (wsh/get-libraries state)
               current-file-id (:current-file-id state)

               changes         (cll/generate-sync-file-changes
                                (pcb/empty-changes it)
                                undo-group
                                asset-type
                                file-id
                                asset-id
                                library-id
                                libraries
                                current-file-id)

               find-frames     (fn [change]
                                 (->> (ch/frames-changed file change)
                                      (map #(assoc %1 :page-id (:page-id change)))))

               updated-frames  (->> changes
                                    :redo-changes
                                    (mapcat find-frames)
                                    distinct)]

           (log/debug :msg "SYNC-FILE finished" :js/rchanges (log-changes
                                                              (:redo-changes changes)
                                                              file))
           (rx/concat
            (rx/of (set-updating-library false)
                   (ntf/hide {:tag :sync-dialog}))
            (when (seq (:redo-changes changes))
              (rx/of (dch/commit-changes changes)))
            (when-not (empty? updated-frames)
              (rx/merge
               (rx/of (ptk/data-event :layout/update {:ids (map :id updated-frames) :undo-group undo-group}))
               (->> (rx/from updated-frames)
                    (rx/mapcat
                     (fn [shape]
                       (rx/of
                        (dwt/clear-thumbnail file-id (:page-id shape) (:id shape) "frame")
                        (when-not (= (:frame-id shape) uuid/zero)
                          (dwt/clear-thumbnail file-id (:page-id shape) (:frame-id shape) "frame"))))))))

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


;; FIXME: the data should be set on the backend for clock consistency

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
            do-more-info #(modal/show! :libraries-dialog {:starting-tab :updates})
            do-update #(do (apply st/emit! (map (fn [library]
                                                  (sync-file (:current-file-id state)
                                                             (:id library)))
                                                libraries-need-sync))
                           (st/emit! (ntf/hide)))
            do-dismiss #(do (st/emit! ignore-sync)
                            (st/emit! (ntf/hide)))]

        (when (seq libraries-need-sync)
          (rx/of (ntf/dialog
                  :content (tr "workspace.updates.there-are-updates")
                  :controls :inline-actions
                  :links   [{:label (tr "workspace.updates.more-info")
                             :callback do-more-info}]
                  :actions [{:label (tr "workspace.updates.dismiss")
                             :type :secondary
                             :callback do-dismiss}
                            {:label (tr "workspace.updates.update")
                             :type :primary
                             :callback do-update}]
                  :tag :sync-dialog)))))))


(defn touch-component
  "Update the modified-at attribute of the component to now"
  [id]
  (dm/assert!
   "expected valid uuid for `id`"
   (uuid? id))
  (ptk/reify ::touch-component
    cljs.core/IDeref
    (-deref [_] [id])

    ptk/WatchEvent
    (watch [it state _]
      (let [data          (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/update-component id #(assoc % :modified-at (dt/now))))]
        (rx/of (dch/commit-changes {:origin it
                                    :redo-changes (:redo-changes changes)
                                    :undo-changes []
                                    :save-undo? false}))))))

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
       (touch-component component-id)
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
            (->> (rx/from-atom refs/workspace-data {:emit-current-value? true})
                 (rx/share))

            workspace-buffer-s
            (->> (rx/concat
                  (rx/take 1 workspace-data-s)
                  (rx/take 1 workspace-data-s)
                  workspace-data-s)
                  ;; Need to get the file data before the change, so deleted shapes
                  ;; still exist, for example. We initialize the buffer with three
                  ;; copies of the initial state
                 (rx/buffer 3 1))

            changes-s
            (->> stream
                 (rx/filter dch/commit?)
                 (rx/map deref)
                 (rx/filter #(= :local (:source %)))
                 (rx/observe-on :async))

            check-changes
            (fn [[event [old-data _mid_data _new-data]]]
              (when old-data
                (let [{:keys [file-id changes save-undo? undo-group]} event

                      changed-components
                      (when (or (nil? file-id) (= file-id (:id old-data)))
                        (->> changes
                             (map (partial ch/components-changed old-data))
                             (reduce into #{})))]

                  (if (d/not-empty? changed-components)
                    (if save-undo?
                      (do (log/info :hint "detected component changes"
                                    :ids (map str changed-components)
                                    :undo-group undo-group)

                          (->> (rx/from changed-components)
                               (rx/map #(component-changed % (:id old-data) undo-group))))
                      ;; even if save-undo? is false, we need to update the :modified-date of the component
                      ;; (for example, for undos)
                      (->> (rx/from changed-components)
                           (rx/map touch-component)))

                    (rx/empty)))))

            changes-s
            (->> changes-s
                 (rx/with-latest-from workspace-buffer-s)
                 (rx/mapcat check-changes)
                 (rx/share))

            notifier-s
            (->> changes-s
                 (rx/debounce 5000)
                 (rx/tap #(log/trc :hint "buffer initialized")))]

        (when (and components-v2? (contains? cf/flags :component-thumbnails))
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
    ev/Event
    (-data [_]
      {::ev/origin "workspace"
       :id id
       :shared is-shared})

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
    ev/Event
    (-data [_]
      {::ev/name "attach-library"
       :file-id file-id
       :library-id library-id})

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
        (rx/concat
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
                           (update state :workspace-thumbnails merge thumbnails))))))
         (rx/of (ptk/reify ::attach-library-finished)))))))

(defn unlink-file-from-library
  [file-id library-id]
  (ptk/reify ::detach-library
    ev/Event
    (-data [_]
      {::ev/name "detach-library"
       :file-id file-id
       :library-id library-id})

    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-libraries library-id]))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:file-id file-id
                    :library-id library-id}]
        (->> (rp/cmd! :unlink-file-from-library params)
             (rx/ignore))))))
