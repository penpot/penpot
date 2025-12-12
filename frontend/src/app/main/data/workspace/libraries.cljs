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
   [app.common.logic.variants :as clv]
   [app.common.path-names :as cpn]
   [app.common.time :as ct]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.library :as ctl]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dc]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.notifications :as-alias dwn]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.specialized-panel :as dwsp]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.transforms :as dwtr]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.features.pointer-map :as fpmap]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

(defn- debug-pretty-file
  [file-id state]
  (if (= file-id (:current-file-id state))
    "<local>"
    (str "<" (get-in state [:files file-id :name]) ">")))

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
  (let [[path name] (cpn/split-group-name (:name item))]
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
                   (d/without-nils)
                   (ctc/check-library-color))]

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

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn- update-color*
  [it state color file-id]
  (let [data        (dsh/lookup-file-data state)
        [path name] (cpn/split-group-name (:name color))
        color       (assoc color :path path :name name)
        changes     (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/update-color color))
        undo-id     (js/Symbol)]
    (rx/of (dwu/start-undo-transaction undo-id)
           (dch/commit-changes changes)
           (sync-file (:id data) file-id :colors (:id color))
           (dwu/commit-undo-transaction undo-id))))

(defn update-color
  [color file-id]
  (assert (uuid? file-id) "expected a uuid instance for `file-id`")

  (let [color (-> (d/without-nils color)
                  (ctc/check-library-color))]
    (ptk/reify ::update-color
      ptk/WatchEvent
      (watch [it state _]
        (update-color* it state color file-id)))))

(defn update-color-data
  "Update color data without affecting the path location"
  [color file-id]
  (assert (uuid? file-id) "expected a uuid instance for `file-id`")

  (let [color (-> (d/without-nils color)
                  (ctc/check-library-color))]

    (ptk/reify ::update-color-data
      ptk/WatchEvent
      (watch [it state _]
        (let [color (assoc color :name (dm/str (:path color) "/" (:name color)))]
          (update-color* it state color file-id))))))

;; FIXME: revisit why file-id is passed on the event
(defn rename-color
  [file-id id new-name]

  (assert (uuid? id) "expected valid uuid instance for `id`")
  (assert (uuid? file-id) "expected a uuid instance for `file-id`")
  (assert (string? new-name) "expected a string instance for `new-name`")

  (ptk/reify ::rename-color
    ptk/WatchEvent
    (watch [it state _]
      (let [new-name (str/trim new-name)]
        (if (str/empty? new-name)
          (rx/empty)
          (let [data   (dsh/lookup-file-data state)
                color  (-> (ctl/get-color data id)
                           (assoc :name new-name)
                           (d/without-nils)
                           (ctc/check-library-color))]
            (update-color* it state color file-id)))))))

(defn delete-color
  [{:keys [id] :as params}]
  (assert (uuid? id) "expected valid uuid instance for `id`")

  (ptk/reify ::delete-color
    ev/Event
    (-data [_] {:id id})

    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-color id))]
        (rx/of (dch/commit-changes changes))))))

;; FIXME: this should be deleted
(defn add-media
  [media]
  (let [media (ctf/check-file-media media)]
    (ptk/reify ::add-media
      ev/Event
      (-data [_] media)

      ptk/WatchEvent
      (watch [it _ _]
        (let [obj     (select-keys media [:id :name :width :height :mtype])
              changes (-> (pcb/empty-changes it)
                          (pcb/add-media obj))]
          (rx/of (dch/commit-changes changes)))))))

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
          (let [[path name] (cpn/split-group-name new-name)
                data        (dsh/lookup-file-data state)
                object      (get-in data [:media id])
                new-object  (assoc object :path path :name name)
                changes     (-> (pcb/empty-changes it)
                                (pcb/with-library-data data)
                                (pcb/update-media new-object))]
            (rx/of (dch/commit-changes changes))))))))

(defn delete-media
  [{:keys [id]}]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::delete-media
    ev/Event
    (-data [_] {:id id})

    ptk/WatchEvent
    (watch [it state _]
      (let [data        (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-media id))]
        (rx/of (dch/commit-changes changes))))))

(defn add-typography
  ([typography] (add-typography typography true))
  ([typography edit?]
   (let [typography (-> (update typography :id #(or % (uuid/next)))
                        (ctt/check-typography))]
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
  (let [data        (dsh/lookup-file-data state)
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
  (assert (uuid? file-id) "expected valid uuid for `file-id`")
  (let [typography (ctt/check-typography typography)]
    (ptk/reify ::update-typography
      ptk/WatchEvent
      (watch [it state _]
        (do-update-tipography it state typography file-id)))))

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
        (let [data        (dsh/lookup-file-data state)
              [path name] (cpn/split-group-name new-name)
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
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-typography id))]
        (rx/of (dch/commit-changes changes))))))

(defn- add-component2
  "This is the second step of the component creation."
  ([selected]
   (add-component2 nil selected))
  ([id-ref selected]
   (ptk/reify ::add-component2
     ev/Event
     (-data [_]
       {::ev/name "add-component"
        :shapes (count selected)})

     ptk/WatchEvent
     (watch [it state _]
       (let [file-id  (:current-file-id state)
             page-id  (:current-page-id state)
             objects  (dsh/lookup-page-objects state file-id page-id)
             shapes   (dwg/shapes-for-grouping objects selected)
             parents  (into #{} (map :parent-id) shapes)]
         (when-not (empty? shapes)
           (let [[root component-id changes]
                 (cll/generate-add-component (pcb/empty-changes it) shapes objects page-id file-id
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
       (let [objects            (dsh/lookup-page-objects state)
             selected           (->> (d/nilv ids (dsh/lookup-selected state))
                                     (cfh/clean-loops objects))
             selected-objects   (map #(get objects %) selected)
             ;; We don't want to change the structure of component copies
             can-make-component (every? true? (map #(ctn/valid-shape-for-component? objects %) selected-objects))]

         (when can-make-component
           (rx/of (add-component2 id-ref selected))))))))

(defn add-multiple-components
  "Add several new components to current file library, from the currently selected shapes."
  []
  (ptk/reify ::add-multiple-components
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects            (dsh/lookup-page-objects state)
            selected           (->> (dsh/lookup-selected state)
                                    (cfh/clean-loops objects))
            selected-objects   (map #(get objects %) selected)
            ;; We don't want to change the structure of component copies
            can-make-component (every? true? (map #(ctn/valid-shape-for-component? objects %) selected-objects))
            added-components   (map (fn [id]
                                      (with-meta (add-component2 [id])
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
          (let [data    (dsh/lookup-file-data state)
                changes (-> (pcb/empty-changes it)
                            (cll/generate-rename-component id new-name data))]
            (rx/of (dch/commit-changes changes))))))))

(defn rename-component-and-main-instance
  [component-id name]
  (ptk/reify ::rename-component-and-main-instance
    ptk/WatchEvent
    (watch [_ state _]
      (let [name        (str/trim name)
            clean-name  (cpn/clean-path name)
            valid?      (and (not (str/ends-with? name "/"))
                             (string? clean-name)
                             (not (str/blank? clean-name)))
            data       (dsh/lookup-file-data state)
            component  (dm/get-in data [:components component-id])]

        (when (and valid? component)
          (let [shape-id (:main-instance-id component)
                page-id  (:main-instance-page component)]

            (rx/concat
             (rx/of (rename-component component-id clean-name))

             (when (and shape-id page-id)
               (rx/of (dwsh/update-shapes [shape-id] #(assoc % :name clean-name) {:page-id page-id :stack-undo? true}))))))))))

(defn duplicate-component
  "Create a new component copied from the one with the given id."
  ([library-id component-id]
   (duplicate-component library-id component-id (uuid/next)))
  ([library-id component-id new-component-id]
   (ptk/reify ::duplicate-component
     ptk/WatchEvent
     (watch [it state _]
       (let [libraries          (dsh/lookup-libraries state)
             library            (get libraries library-id)

             [main-instance changes]
             (-> (pcb/empty-changes it nil)
                 (cll/generate-duplicate-component library component-id new-component-id))]
         (rx/of
          (ptk/data-event :layout/update {:ids [(:id main-instance)]})
          (dch/commit-changes changes)))))))

(defn delete-component
  "Delete the component with the given id, from the current file library."
  [{:keys [id]}]
  (dm/assert!
   "expected valid uuid for `id`"
   (uuid? id))

  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id       (:current-file-id state)
            fdata         (dsh/lookup-file-data state file-id)
            component     (ctkl/get-component fdata id)
            page-id       (:main-instance-page component)
            root-id       (:main-instance-id component)

            page          (dsh/get-page fdata page-id)
            objects       (:objects page)

            undo-group    (uuid/next)
            undo-id       (js/Symbol)

            [all-parents changes]
            (-> (pcb/empty-changes it page-id)
                ;; Deleting main root triggers component delete
                (cls/generate-delete-shapes fdata page objects #{root-id} {:undo-group undo-group
                                                                           :undo-id undo-id}))]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwt/clear-thumbnail (:current-file-id state) page-id root-id "component")
         (dc/detach-comment-thread #{root-id})
         (dch/commit-changes changes)
         (ptk/data-event :layout/update {:ids all-parents :undo-group undo-group})
         (dwu/commit-undo-transaction undo-id))))))

(defn restore-component
  "Restore a deleted component, with the given id, in the given file library."
  [library-id component-id]
  (dm/assert! (uuid? library-id))
  (dm/assert! (uuid? component-id))
  (ptk/reify ::restore-component
    ptk/WatchEvent
    (watch [it state _]
      (let [current-file-id (:current-file-id state)
            local?          (= current-file-id library-id)

            ldata        (dsh/lookup-file-data state library-id)
            component    (get-in ldata [:components component-id])
            comp-page-id (:main-instance-page component)
            comp-page    (dsh/get-page ldata comp-page-id)
            objects      (:objects comp-page)

            changes (-> (pcb/empty-changes it)
                        (cll/generate-restore-component ldata component-id library-id comp-page objects))

            page-id
            (->> changes :redo-changes (keep :page-id) first)

            frames
            (->> changes :redo-changes (keep :frame-id))]

        (rx/of (dch/commit-changes changes)
               (when local?
                 (ptk/data-event :layout/update {:page-id page-id :ids frames})))))))

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
  ([file-id component-id position {:keys [start-move? initial-point id-ref origin]}]
   (dm/assert! (uuid? file-id))
   (dm/assert! (uuid? component-id))
   (dm/assert! (gpt/point? position))
   (ptk/reify ::instantiate-component
     ptk/WatchEvent
     (watch [it state _]
       (let [page      (dsh/lookup-page state)
             libraries (dsh/lookup-libraries state)

             objects   (:objects page)
             changes   (-> (pcb/empty-changes it (:id page))
                           (pcb/with-objects objects))

             current-file-id (:current-file-id state)

             [new-shape changes]
             (cll/generate-instantiate-component changes
                                                 objects
                                                 file-id
                                                 component-id
                                                 position
                                                 page
                                                 libraries)
             component (ctn/get-component-from-shape new-shape libraries)

             undo-id (js/Symbol)]

         (when id-ref
           (reset! id-ref (:id new-shape)))

         (rx/of (ptk/event ::ev/event
                           {::ev/name "use-library-component"
                            ::ev/origin origin
                            :external-library (not= file-id current-file-id)
                            :is-variant (ctk/is-variant? component)})
                (dwu/start-undo-transaction undo-id)
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
      (let [page-id   (:current-page-id state)
            file-id   (:current-file-id state)

            fdata     (dsh/lookup-file-data state file-id)
            libraries (dsh/lookup-libraries state)

            changes   (-> (pcb/empty-changes it)
                          (cll/generate-detach-component id fdata page-id libraries))]

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
            file-id          (:current-file-id state)

            ;; FIXME: revisit, innefficient access
            objects          (dsh/lookup-page-objects state page-id)

            libraries        (dsh/lookup-libraries state)
            fdata            (dsh/lookup-file-data state file-id)

            selected         (->> state
                                  (dsh/lookup-selected)
                                  (cfh/clean-loops objects))

            selected-objects (map #(get objects %) selected)
            copies           (filter ctk/in-component-copy? selected-objects)
            can-detach?      (and (seq copies)
                                  (every? #(not (ctn/has-any-copy-parent? objects %)) selected-objects))

            changes (when can-detach?
                      (reduce
                       (fn [changes id]
                         (cll/generate-detach-component changes id fdata page-id libraries))
                       (pcb/empty-changes it)
                       selected))]

        (rx/of (when can-detach?
                 (dch/commit-changes changes)))))))

(defn go-to-component-file
  [file-id component update-layout?]

  (assert (uuid? file-id) "expected an uuid for `file-id`")
  (assert (ctk/check-component component) "expected a valid component")

  (ptk/reify ::nav-to-component-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :file-id file-id)
                       (assoc :page-id (:main-instance-page component))
                       (assoc :component-id (:id component))
                       (assoc :update-layout update-layout?))]
        (rx/of (rt/nav :workspace params ::rt/new-window true))))))

(defn go-to-local-component
  ;; id is the id of the component to go
  ;; additional-ids are ids of additional components on the same page
  ;; that will be selected and zoomed along the main one
  ;; update-layout? indicates if it should send a :layout/update event
  ;; for the parents of the component
  [& {:keys [id additional-ids update-layout? retries] :or {retries 0} :as options}]
  (ptk/reify ::go-to-local-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [current-page-id (:current-page-id state)
            data            (dsh/lookup-file-data state)
            objects         (dsh/lookup-page-objects state current-page-id)

            select-and-zoom
            (fn [ids]
              (let [parent-ids (when update-layout?
                                 (keep #(-> (get objects %) :parent-id) ids))]

                (if (and update-layout? (empty? parent-ids) (< retries 8))
                  ;; The objects are not loaded yet, wait and try again
                  (->> (rx/of (go-to-local-component (assoc options :retries (inc retries))))
                       (rx/delay 250))
                  (rx/concat
                   (rx/of (dws/select-shapes ids)
                          dwz/zoom-to-selected-shape)
                   (when update-layout?
                     (rx/of (ptk/data-event :layout/update {:ids parent-ids})))))))

            redirect-to-page
            (fn [page-id ids]
              (rx/merge
               (->> stream
                    (rx/filter (ptk/type? ::dwpg/initialize-page))
                    (rx/take 1)
                    (rx/observe-on :async)
                    (rx/mapcat (fn [_] (select-and-zoom ids))))
               (rx/of (dcm/go-to-workspace :page-id page-id))))

            get-main-instance-id
            (fn [id page-id]
              (let [component (dm/get-in data [:components id])]
                (when (= (:main-instance-page component) page-id)
                  (:main-instance-id component))))]

        (when-let [component (dm/get-in data [:components id])]
          (let [page-id  (:main-instance-page component)
                shape-id (:main-instance-id component)
                additional-shape-ids (keep #(get-main-instance-id % page-id)
                                           additional-ids)
                ids (into (d/ordered-set shape-id) additional-shape-ids)]
            (when (some? page-id)
              (if (= page-id current-page-id)
                (select-and-zoom ids)
                (redirect-to-page page-id ids)))))))))

(defn library-thumbnails-fetched
  [thumbnails]
  (ptk/reify ::library-thumbnails-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update state :thumbnails merge thumbnails))))

(defn fetch-library-thumbnails
  [library-id]
  (ptk/reify ::fetch-library-thumbnails
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-file-object-thumbnails {:file-id library-id :tag "component"})
           (rx/map library-thumbnails-fetched)))))

(defn ext-library-changed
  [library-id modified-at revn changes]

  (assert (uuid? library-id)
          "expected valid uuid for library-id")

  (assert (ch/check-changes changes)
          "expected valid changes vector")

  (ptk/reify ::ext-library-changed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:files library-id]
                     assoc :modified-at modified-at :revn revn)
          (d/update-in-when [:files library-id :data]
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
      (let [libraries  (:files state)
            page-id    (:current-page-id state)

            file       (dsh/lookup-file state)
            data       (:data file)

            container  (ctn/get-container data :page page-id)
            undo-id    (js/Symbol)

            changes
            (-> (pcb/empty-changes it)
                (cll/generate-reset-component file libraries container id))]

        (log/debug :msg "RESET-COMPONENT finished" :js/rchanges (log-changes
                                                                 (:redo-changes changes)
                                                                 file))
        (rx/of (dwu/start-undo-transaction undo-id)
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
       (let [page-id       (:current-page-id state)

             libraries     (dsh/lookup-libraries state)
             file          (dsh/lookup-file state)
             fdata         (:data file)

             container     (ctn/get-container fdata :page page-id)
             shape         (ctn/get-shape container id)]

         (when (ctk/instance-head? shape)
           (let [changes
                 (-> (pcb/empty-changes it)
                     (pcb/set-undo-group undo-group)
                     (pcb/with-container container)
                     (cll/generate-sync-shape-inverse fdata libraries container id))

                 ldata     (->> (:component-file shape)
                                (dsh/lookup-file-data state))

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
                                           fdata)
                        :js/nonlocal-changes (log-changes
                                              (:redo-changes nonlocal-changes)
                                              fdata))

             (rx/of
              (when (seq (:redo-changes local-changes))
                (dch/commit-changes (assoc local-changes
                                           :file-id (:id file))))
              (when (seq (:redo-changes nonlocal-changes))
                (dch/commit-changes (assoc nonlocal-changes
                                           :file-id (:id ldata))))))))))))

(defn- update-component-thumbnail-sync
  [state component-id file-id tag]
  (let [data      (dsh/lookup-file-data state file-id)
        component (ctkl/get-component data component-id)
        page-id   (:main-instance-page component)
        root-id   (:main-instance-id component)]
    (dwt/update-thumbnail file-id page-id root-id tag "update-component-thumbnail-sync")))

(defn update-component-sync
  ([shape-id file-id] (update-component-sync shape-id file-id nil))
  ([shape-id file-id undo-group]
   (ptk/reify ::update-component-sync
     ptk/WatchEvent
     (watch [_ state _]
       (let [current-file-id (:current-file-id state)
             current-file?   (= current-file-id file-id)

             page            (dsh/lookup-page state)
             shape           (ctn/get-shape page shape-id)
             component-id    (:component-id shape)
             undo-id         (js/Symbol)]
         (rx/of
          (dwu/start-undo-transaction undo-id)
          (update-component shape-id undo-group)

          ;; These two calls are necessary for properly sync thumbnails
          ;; when a main component does not live in the same page
          (update-component-thumbnail-sync state component-id file-id "frame")
          (update-component-thumbnail-sync state component-id file-id "component")

          (sync-file current-file-id file-id :components component-id undo-group)
          (when (not current-file?)
            (sync-file file-id file-id :components component-id undo-group))
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

(defn component-swap
  "Swaps a component with another one"
  [shape file-id id-new-component keep-touched?]
  (dm/assert! (uuid? id-new-component))
  (dm/assert! (uuid? file-id))
  (ptk/reify ::component-swap
    ptk/WatchEvent
    (watch [it state _]
      ;; First delete shapes so we have space in the layout otherwise we can have problems
      ;; in the grid creating new rows/columns to make space
      (let [libraries   (dsh/lookup-libraries state)
            page        (dsh/lookup-page state)
            objects     (:objects page)
            parent      (get objects (:parent-id shape))

            ldata       (dsh/lookup-file-data state file-id)
            orig-shapes (when keep-touched? (cfh/get-children-with-self objects (:id shape)))

            ;; If the target parent is a grid layout we need to pass the target cell
            target-cell (when (ctsl/grid-layout? parent)
                          (ctsl/get-cell-by-shape-id parent (:id shape)))

            index (find-shape-index objects (:parent-id shape) (:id shape))

            ;; Store the properties that need to be maintained when the component is swapped
            keep-props-values (select-keys shape ctk/swap-keep-attrs)

            undo-id (js/Symbol)
            undo-group (uuid/next)

            [new-shape all-parents changes]
            (-> (pcb/empty-changes it (:id page))
                (pcb/set-undo-group undo-group)
                (cll/generate-component-swap objects shape ldata page libraries id-new-component
                                             index target-cell keep-props-values keep-touched?))

            updated-objects  (pcb/get-objects changes)
            new-children-ids (cfh/get-children-ids-with-self updated-objects (:id new-shape))

            [changes parents-of-swapped]
            (if keep-touched?
              (clv/generate-keep-touched changes new-shape shape orig-shapes page libraries ldata)
              [changes []])
            update-layout-ids (concat all-parents parents-of-swapped new-children-ids)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (ptk/data-event :layout/update {:ids update-layout-ids :undo-group undo-group})
         (dwu/commit-undo-transaction undo-id)
         (dws/select-shape (:id new-shape) false))))))

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
                  :file (debug-pretty-file file-id state)
                  :id-new-component id-new-component
                  :undo-id undo-id)
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (rx/map #(component-swap % file-id id-new-component false) (rx/from shapes))
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
         (d/assoc-in-when state [:files library-id :synced-at] (ct/now))
         state))

     ptk/WatchEvent
     (watch [it state _]
       (when (and (some? file-id) (some? library-id)) ; Prevent race conditions while navigating out of the file
         (log/info :msg "SYNC-FILE"
                   :file (debug-pretty-file file-id state)
                   :library (debug-pretty-file library-id state)
                   :asset-type asset-type
                   :asset-id asset-id
                   :undo-group undo-group)
         (let [ldata           (dsh/lookup-file-data state file-id)
               libraries       (dsh/lookup-libraries state)
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
                                 (->> (ch/frames-changed ldata change)
                                      (map #(assoc %1 :page-id (:page-id change)))))

               updated-frames  (->> changes
                                    :redo-changes
                                    (mapcat find-frames)
                                    distinct)]

           (log/debug :msg "SYNC-FILE finished" :js/rchanges (log-changes
                                                              (:redo-changes changes)
                                                              ldata))
           (rx/concat
            (rx/of (set-updating-library false)
                   (ntf/hide {:tag :sync-dialog}))
            (when (seq (:redo-changes changes))
              (rx/of (dch/commit-changes changes)))
            (when-not (empty? updated-frames)
              (let [frames-by-page (->> updated-frames
                                        (group-by :page-id))]
                (rx/merge
                 ;; Emit one layout/update event for each page
                 (rx/from
                  (map (fn [[page-id frames]]
                         (ptk/data-event :layout/update
                                         {:page-id page-id
                                          :ids (map :id frames)
                                          :undo-group undo-group}))
                       frames-by-page))
                 (->> (rx/from updated-frames)
                      (rx/mapcat
                       (fn [shape]
                         (rx/of
                          (dwt/clear-thumbnail file-id (:page-id shape) (:id shape) "frame")
                          (when-not (= (:frame-id shape) uuid/zero)
                            (dwt/clear-thumbnail file-id (:page-id shape) (:frame-id shape) "frame")))))))))

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
      (let [file-id (:current-file-id state)]
        (assoc-in state [:files file-id :ignore-sync-until] (ct/now))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :ignore-file-library-sync-status
                      {:file-id file-id
                       :date (ct/now)})
             (rx/ignore))))))

(defn assets-need-sync
  "Get a lazy sequence of all the assets of each type in the library that have
  been modified after the last sync of the library. The sync date may be
  overriden by providing a ignore-until parameter."
  ([library file-data]
   (assets-need-sync library file-data nil))
  ([library file-data ignore-until]
   (when (not= (:id library) (:id file-data))
     (let [sync-date (max (:synced-at library) (or ignore-until 0))]
       (when (> (:modified-at library) sync-date)
         (ctf/used-assets-changed-since file-data library sync-date))))))

(defn notify-sync-file
  "Notify the user that there are updates in the libraries used by the
   current file, and ask if he wants to update them now."
  []
  (ptk/reify ::notify-sync-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id      (:current-file-id state)
            file         (dsh/lookup-file state file-id)
            file-data    (get file :data)
            ignore-until (get file :ignore-sync-until)

            libraries-need-sync
            (->> (vals (get state :files))
                 (filter #(= (:library-of %) file-id))
                 (filter #(seq (assets-need-sync % file-data ignore-until))))

            do-more-info
            #(modal/show! :libraries-dialog {:starting-tab "updates" :file-id file-id})

            do-update
            #(do (apply st/emit! (map (fn [library]
                                        (sync-file (:current-file-id state)
                                                   (:id library)))
                                      libraries-need-sync))
                 (st/emit! (ntf/hide)))

            do-dismiss
            #(st/emit! ignore-sync (ntf/hide))]

        (when (seq libraries-need-sync)
          (rx/of (ntf/dialog
                  :content (tr "workspace.updates.there-are-updates")
                  :controls :inline-actions
                  :links   [{:label (tr "workspace.updates.more-info")
                             :callback do-more-info}]
                  :cancel {:label (tr "workspace.updates.dismiss")
                           :callback do-dismiss}
                  :accept {:label (tr "workspace.updates.update")
                           :callback do-update}
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
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/update-component id #(assoc % :modified-at (ct/now))))]
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
    (watch [_ _ stream]
      (let [stopper-s
            (->> stream
                 (rx/filter #(or (= ::dwpg/finalize-page (ptk/type %))
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

        (when (contains? cf/flags :component-thumbnails)
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
      (update-in state [:files id] assoc :is-shared is-shared))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params        {:id id :is-shared is-shared}]
        (rx/concat
         (->> (rp/cmd! :set-file-shared params)
              (rx/ignore))
         (when is-shared
           (let [has-variants? (->> (dsh/lookup-file-data state)
                                    :components
                                    vals
                                    (some ctk/is-variant?))]
             (if has-variants?
               (rx/of (ptk/event ::ev/event {::ev/name "set-file-variants-shared" ::ev/origin "workspace"}))
               (rx/empty)))))))))

;; --- Link and unlink Files

(defn libraries-fetched
  [file-id libraries]
  (ptk/reify ::libraries-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update state :files merge
              (->> libraries
                   (map #(assoc % :library-of file-id))
                   (d/index-by :id))))))

(defn- load-library-file
  [file-id library-id]
  (ptk/reify ::load-library-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (get state :features)]
        (rx/merge
         (->> (rp/cmd! :get-file {:id library-id :features features})
              (rx/merge-map fpmap/resolve-file)
              (rx/map (fn [file]
                        (libraries-fetched file-id [file]))))
         (->> (rp/cmd! :get-file-object-thumbnails {:file-id library-id :tag "component"})
              (rx/map (fn [thumbnails]
                        (fn [state]
                          (update state :thumbnails merge thumbnails))))))))))

(defn link-file-to-library
  [file-id library-id]
  (ptk/reify ::attach-library
    ev/Event
    (-data [_]
      {::ev/name "attach-library"
       :file-id file-id
       :library-id library-id})

    ptk/WatchEvent
    (watch [_ state _]
      (let [libraries        (:shared-files state)
            library          (get libraries library-id)
            variants-count   (-> library :library-summary :variants count)

            loaded-libraries (->> (dsh/lookup-libraries state)
                                  (remove (fn [[_ lib]]
                                            (or (nil? (:data lib))
                                                (empty? (:data lib)))))
                                  (map first)
                                  set)]
        (rx/concat
         (rx/merge
          (->> (rp/cmd! :link-file-to-library {:file-id file-id :library-id library-id})
               (rx/merge-map (fn [libraries-to-load]
                               (as-> libraries-to-load $
                                 (remove loaded-libraries $)
                                 (conj $ library-id)
                                 (map #(load-library-file file-id %) $))))))
         (rx/of (ptk/reify ::attach-library-finished))
         (when (pos? variants-count)
           (->> (rp/cmd! :get-library-usage {:file-id library-id})
                (rx/map (fn [library-usage]
                          (ptk/event ::ev/event {::ev/name "attach-library-variants"
                                                 :file-id file-id
                                                 :library-id library-id
                                                 :variants-count variants-count
                                                 :library-used-in (:used-in library-usage)}))))))))))

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
      (update state :files dissoc library-id))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:file-id file-id
                    :library-id library-id}]
        (->> (rp/cmd! :unlink-file-from-library params)
             (rx/ignore))))))
