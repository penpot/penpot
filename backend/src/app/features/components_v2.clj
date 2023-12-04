;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.components-v2
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cp]
   [app.common.files.changes-builder :as fcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.libraries-helpers :as cflh]
   [app.common.files.migrations :as pmg]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.files.validate :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as l]
   [app.common.svg :as csvg]
   [app.common.svg.shapes-builder :as sbuilder]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.sse :as sse]
   [app.media :as media]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-snapshot :as fsnap]
   [app.rpc.commands.media :as cmd.media]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

(def ^:dynamic *system* nil)
(def ^:dynamic *stats* nil)
(def ^:dynamic *file-stats* nil)
(def ^:dynamic *team-stats* nil)
(def ^:dynamic *semaphore* nil)
(def ^:dynamic *skip-on-error* true)

(def grid-gap 50)
(def frame-gap 200)
(def max-group-size 50)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE PREPARATION BEFORE MIGRATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- prepare-file-data
  "Apply some specific migrations or fixes to things that are allowed in v1 but not in v2,
   or that are the result of old bugs."
  [file-data libraries]
  (let [detached-ids  (volatile! #{})

        detach-shape
        (fn [container shape]
          ;; Detach a shape. If it's inside a component, add it to detached-ids, for further use.
          (let [is-component? (let [root-shape (ctst/get-shape container (:id container))]
                                (and (some? root-shape) (nil? (:parent-id root-shape))))]
            (when is-component?
              (vswap! detached-ids conj (:id shape)))
            (ctk/detach-shape shape)))

        fix-orphan-shapes
        (fn [file-data]
          ;; Find shapes that are not listed in their parent's children list.
          ;; Remove them, and also their children
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))

                  (fix-shape
                    [container shape]
                    (if-not (or (= (:id shape) uuid/zero)
                                (nil? (:parent-id shape)))
                      (let [parent (ctst/get-shape container (:parent-id shape))
                            exists? (d/index-of (:shapes parent) (:id shape))]
                        (if (nil? exists?)
                          (let [ids (cfh/get-children-ids-with-self (:objects container) (:id shape))]
                            (update container :objects #(reduce dissoc % ids)))
                          container))
                      container))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        remove-nested-roots
        (fn [file-data]
          ;; Remove :component-root in head shapes that are nested.
          (letfn [(fix-container [container]
                    (update container :objects update-vals (partial fix-shape container)))

                  (fix-shape [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (and (ctk/instance-root? shape)
                               (ctn/in-any-component? (:objects container) parent))
                        (dissoc shape :component-root)
                        shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        add-not-nested-roots
        (fn [file-data]
          ;; Add :component-root in head shapes that are not nested.
          (letfn [(fix-container [container]
                    (update container :objects update-vals (partial fix-shape container)))

                  (fix-shape [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (and (ctk/subinstance-head? shape)
                               (not (ctn/in-any-component? (:objects container) parent)))
                        (assoc shape :component-root true)
                        shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        fix-orphan-copies
        (fn [file-data]
          ;; Detach shapes that were inside a copy (have :shape-ref) but now they aren't.
          (letfn [(fix-container [container]
                    (update container :objects update-vals (partial fix-shape container)))

                  (fix-shape [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (and (ctk/in-component-copy? shape)
                               (not (ctk/instance-head? shape))
                               (not (ctk/in-component-copy? parent)))
                        (detach-shape container shape)
                        shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        remap-refs
        (fn [file-data]
          ;; Remap shape-refs so that they point to the near main.
          ;; At the same time, if there are any dangling ref, detach the shape and its children.
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))

                  (fix-shape [container shape]
                    (if (ctk/in-component-copy? shape)
                      ;; First look for the direct shape.
                      (let [root         (ctn/get-component-shape (:objects container) shape)
                            libraries    (assoc-in libraries [(:id file-data) :data] file-data)
                            library      (get libraries (:component-file root))
                            component    (ctkl/get-component (:data library) (:component-id root) true)
                            direct-shape (ctf/get-component-shape (:data library) component (:shape-ref shape))]
                        (if (some? direct-shape)
                          ;; If it exists, there is nothing else to do.
                          container
                          ;; If not found, find the near shape.
                          (let [near-shape (d/seek #(= (:shape-ref %) (:shape-ref shape))
                                                   (ctf/get-component-shapes (:data library) component))]
                            (if (some? near-shape)
                              ;; If found, update the ref to point to the near shape.
                              (ctn/update-shape container (:id shape) #(assoc % :shape-ref (:id near-shape)))
                              ;; If not found, it may be a fostered component. Try to locate a direct shape
                              ;; in the head component.
                              (let [head           (ctn/get-head-shape (:objects container) shape)
                                    library-2      (get libraries (:component-file head))
                                    component-2    (ctkl/get-component (:data library-2) (:component-id head) true)
                                    direct-shape-2 (ctf/get-component-shape (:data library-2) component-2 (:shape-ref shape))]
                                (if (some? direct-shape-2)
                                  ;; If it exists, there is nothing else to do.
                                  container
                                  ;; If not found, detach shape and all children (stopping if a nested instance is reached)
                                  (let [children (ctn/get-children-in-instance (:objects container) (:id shape))]
                                    (reduce #(ctn/update-shape %1 (:id %2) (partial detach-shape %1))
                                            container
                                            children))))))))
                      container))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        fix-copies-of-detached
        (fn [file-data]
          ;; Find any copy that is referencing a detached shape inside a component, and
          ;; undo the nested copy, converting it into a direct copy.
          (letfn [(fix-container [container]
                    (update container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (cond-> shape
                      (@detached-ids (:shape-ref shape))
                      (dissoc shape
                              :component-id
                              :component-file
                              :component-root)))]
            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        transform-to-frames
        (fn [file-data]
          ;; Transform component and copy heads to frames, and set the
          ;; frame-id of its childrens
          (letfn [(fix-container
                    [container]
                    (update container :objects update-vals fix-shape))

                  (fix-shape
                    [shape]
                    (if (or (nil? (:parent-id shape)) (ctk/instance-head? shape))
                      (assoc shape
                        :type :frame                  ; Old groups must be converted
                        :fills (or (:fills shape) []) ; to frames and conform to spec
                        :hide-in-viewer (or (:hide-in-viewer shape) true)
                        :rx (or (:rx shape) 0)
                        :ry (or (:ry shape) 0))
                      shape))]
            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        remap-frame-ids
        (fn [file-data]
          ;; Remap the frame-ids of the primary childs of the head instances
          ;; to point to the head instance.
          (letfn [(fix-container
                    [container]
                    (update container :objects update-vals (partial fix-shape container)))

                  (fix-shape
                    [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (ctk/instance-head? parent)
                        (assoc shape :frame-id (:id parent))
                        shape)))]
            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        fix-frame-ids
        (fn [file-data]
          ;; Ensure that frame-id of all shapes point to the parent or to the frame-id
          ;; of the parent, and that the destination is indeed a frame.
          (letfn [(fix-container [container]
                    (update container :objects #(cfh/reduce-objects % fix-shape %)))

                  (fix-shape [objects shape]
                    (let [parent (when (:parent-id shape)
                                   (get objects (:parent-id shape)))
                          error? (when (some? parent)
                                   (if (= (:type parent) :frame)
                                     (not= (:frame-id shape) (:id parent))
                                     (not= (:frame-id shape) (:frame-id parent))))]
                      (if error?
                        (let [nearest-frame (cfh/get-frame objects (:parent-id shape))
                              frame-id      (or (:id nearest-frame) uuid/zero)]
                          (update objects (:id shape) assoc :frame-id frame-id))
                        objects)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (update :components update-vals fix-container))))

        fix-component-nil-objects
        (fn [file-data]
          ;; Ensure that objects of all components is not null
          (letfn [(fix-component [component]
                    (if (and (contains? component :objects) (nil? (:objects component)))
                      (if (:deleted component)
                        (assoc component :objects {})
                        (dissoc component :objects))
                      component))]
            (-> file-data
                (update :components update-vals fix-component))))]

    (-> file-data
        (fix-orphan-shapes)
        (remove-nested-roots)
        (add-not-nested-roots)
        (fix-orphan-copies)
        (remap-refs)
        (fix-copies-of-detached)
        (transform-to-frames)
        (remap-frame-ids)
        (fix-frame-ids)
        (fix-component-nil-objects))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS MIGRATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-asset-groups
  [assets generic-name]
  (let [; Group by first element of the path.
        groups (d/group-by #(first (cfh/split-path (:path %))) assets)

        ; Split large groups in chunks of max-group-size elements
        groups (loop [groups (seq groups)
                      result {}]
                 (if (empty? groups)
                   result
                   (let [[group-name assets] (first groups)
                         group-name (if (or (nil? group-name) (str/empty? group-name))
                                      generic-name
                                      group-name)]
                     (if (<= (count assets) max-group-size)
                       (recur (next groups)
                              (assoc result group-name assets))
                       (let [splits (-> (partition-all max-group-size assets)
                                        (d/enumerate))]
                         (recur (next groups)
                                (reduce (fn [result [index split]]
                                          (let [split-name (str group-name " " (inc index))]
                                            (assoc result split-name split)))
                                        result
                                        splits)))))))

        ; Sort assets in each group by path
        groups (update-vals groups (fn [assets]
                                     (sort-by (fn [{:keys [path name]}]
                                                (str/lower (cfh/merge-path-item path name)))
                                              assets)))

        ; Sort groups by name
        groups (into (sorted-map) groups)]
    groups))

(defn- create-frame
  [name position width height]
  (cts/setup-shape
   {:type :frame
    :x (:x position)
    :y (:y position)
    :width (+ width (* 2 grid-gap))
    :height (+ height (* 2 grid-gap))
    :name name
    :frame-id uuid/zero
    :parent-id uuid/zero}))

(defn- migrate-components
  "If there is any component in the file library, add a new 'Library
  backup', generate main instances for all components there and remove
  shapes from library components.  Mark the file with
  the :components-v2 option."
  [file-data libraries]
  (sse/tap {:type :migration-progress
            :section :components})
  (let [components (ctkl/components-seq file-data)]
    (if (empty? components)
      (assoc-in file-data [:options :components-v2] true)
      (let [[file-data page-id start-pos]
            (ctf/get-or-add-library-page file-data frame-gap)

            migrate-component-shape
            (fn [shape delta component-file component-id frame-id]
              (cond-> shape
                (nil? (:parent-id shape))
                (assoc :parent-id frame-id
                       :main-instance true
                       :component-root true
                       :component-file component-file
                       :component-id component-id)

                (nil? (:frame-id shape))
                (assoc :frame-id frame-id)

                :always
                (gsh/move delta)))

            add-main-instance
            (fn [file-data component frame-id position]
              (let [shapes (cfh/get-children-with-self (:objects component)
                                                       (:id component))

                    root-shape (first shapes)
                    orig-pos   (gpt/point (:x root-shape) (:y root-shape))
                    delta      (gpt/subtract position orig-pos)

                    xf-shape (map #(migrate-component-shape %
                                                            delta
                                                            (:id file-data)
                                                            (:id component)
                                                            frame-id))
                    new-shapes
                    (into [] xf-shape shapes)

                    find-frame-id ; if its parent is a frame, the frame-id should be the parent-id
                    (fn [page shape]
                      (let [parent (ctst/get-shape page (:parent-id shape))]
                        (if (= :frame (:type parent))
                          (:id parent)
                          (:frame-id parent))))

                    add-shapes
                    (fn [page]
                      (reduce (fn [page shape]
                                (ctst/add-shape (:id shape)
                                                shape
                                                page
                                                (find-frame-id page shape)
                                                (:parent-id shape)
                                                nil     ; <- As shapes are ordered, we can safely add each
                                                true))  ;    one at the end of the parent's children list.
                              page
                              new-shapes))

                    update-component
                    (fn [component]
                      (-> component
                          (assoc :main-instance-id (:id root-shape)
                                 :main-instance-page page-id)
                          (dissoc :objects)))]

                (-> file-data
                    (ctpl/update-page page-id add-shapes)
                    (ctkl/update-component (:id component) update-component))))

            add-instance-grid
            (fn [fdata frame-id grid assets]
              (reduce (fn [result [component position]]
                        (sse/tap {:type :migration-progress
                                  :section :components
                                  :name (:name component)})
                        (add-main-instance result component frame-id (gpt/add position
                                                                              (gpt/point grid-gap grid-gap))))
                      fdata
                      (d/zip assets grid)))

            add-instance-grids
            (fn [fdata]
              (let [components (ctkl/components-seq fdata)
                    groups     (get-asset-groups components "Components")]
                (loop [groups   (seq groups)
                       fdata    fdata
                       position start-pos]
                  (if (empty? groups)
                    fdata
                    (let [[group-name assets]    (first groups)
                          grid                   (ctst/generate-shape-grid
                                                  (map (partial ctf/get-component-root fdata) assets)
                                                  position
                                                  grid-gap)
                          {:keys [width height]} (meta grid)
                          frame                  (create-frame group-name position width height)
                          fdata                  (ctpl/update-page fdata
                                                                   page-id
                                                                   #(ctst/add-shape (:id frame)
                                                                                    frame
                                                                                    %
                                                                                    (:id frame)
                                                                                    (:id frame)
                                                                                    nil
                                                                                    true))]
                      (recur (next groups)
                             (add-instance-grid fdata (:id frame) grid assets)
                             (gpt/add position (gpt/point 0 (+ height (* 2 grid-gap) frame-gap)))))))))]

        (let [total (count components)]
          (some-> *stats* (swap! update :processed/components (fnil + 0) total))
          (some-> *team-stats* (swap! update :processed/components (fnil + 0) total))
          (some-> *file-stats* (swap! assoc :processed/components total)))

        (-> file-data
            (prepare-file-data libraries)
            (add-instance-grids))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GRAPHICS MIGRATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-shapes-for-bitmap
  "Convert a media object that contains a bitmap image into shapes,
  one shape of type :image and one group that contains it."
  [{:keys [name width height id mtype]} frame-id position]
  (let [frame-shape (cts/setup-shape
                     {:type :frame
                      :x (:x position)
                      :y (:y position)
                      :width width
                      :height height
                      :name name
                      :frame-id frame-id
                      :parent-id frame-id})

        img-shape   (cts/setup-shape
                     {:type :image
                      :x (:x position)
                      :y (:y position)
                      :width width
                      :height height
                      :metadata {:id id
                                 :width width
                                 :height height
                                 :mtype mtype}
                      :name name
                      :frame-id (:id frame-shape)
                      :parent-id (:id frame-shape)})]
    [frame-shape [img-shape]]))

(defn- parse-datauri
  [data]
  (let [[mtype b64-data] (str/split data ";base64," 2)
        mtype (subs mtype (inc (str/index-of mtype ":")))
        data  (-> b64-data bc/str->bytes bc/b64->bytes)]
    [mtype data]))

(defn- extract-name
  [href]
  (let [query-idx (d/nilv (str/last-index-of href "?") 0)
        href (if (> query-idx 0) (subs href 0 query-idx) href)
        filename (->> (str/split href "/") (last))
        ext-idx (str/last-index-of filename ".")]
    (if (> ext-idx 0) (subs filename 0 ext-idx) filename)))

(defn- collect-and-persist-images
  [svg-data file-id]
  (letfn [(process-image [{:keys [href] :as item}]
            (try
              (let [item (if (str/starts-with? href "data:")
                           (let [[mtype data] (parse-datauri href)
                                 size         (alength data)
                                 path         (tmp/tempfile :prefix "penpot.media.download.")
                                 written      (io/write-to-file! data path :size size)]

                             (when (not= written size)
                               (ex/raise :type :internal
                                         :code :mismatch-write-size
                                         :hint "unexpected state: unable to write to file"))

                             (-> item
                                 (assoc :size size)
                                 (assoc :path path)
                                 (assoc :filename "tempfile")
                                 (assoc :mtype mtype)))

                           (let [result (cmd.media/download-image *system* href)]
                             (-> (merge item result)
                                 (assoc :name (extract-name href)))))]

                ;; The media processing adds the data to the
                ;; input map and returns it.
                (media/run {:cmd :info :input item}))

              (catch Throwable cause
                (l/warn :hint "unexpected exception on processing internal image shape (skiping)"
                        :cause cause)
                (when-not *skip-on-error*
                  (throw cause)))))

          (persist-image [acc {:keys [path size width height mtype href] :as item}]
            (let [storage (::sto/storage *system*)
                  conn    (::db/conn *system*)
                  hash    (sto/calculate-hash path)
                  content (-> (sto/content path size)
                              (sto/wrap-with-hash hash))
                  params  {::sto/content content
                           ::sto/deduplicate? true
                           ::sto/touched-at (:ts item)
                           :content-type mtype
                           :bucket "file-media-object"}
                  image   (sto/put-object! storage params)
                  fmo-id  (uuid/next)]

              (db/exec-one! conn
                            [cmd.media/sql:create-file-media-object
                             fmo-id
                             file-id true (:name item "image")
                             (:id image)
                             nil
                             width
                             height
                             mtype])

              (assoc acc href {:id fmo-id
                               :mtype mtype
                               :width width
                               :height height})))]

    (let [images (->> (csvg/collect-images svg-data)
                      (transduce (keep process-image)
                                 (completing persist-image) {}))]
      (assoc svg-data :image-data images))))

(defn- get-svg-content
  [id]
  (let [storage  (::sto/storage *system*)
        conn     (::db/conn *system*)
        fmobject (db/get conn :file-media-object {:id id})
        sobject  (sto/get-object storage (:media-id fmobject))]

    (with-open [stream (sto/get-object-data storage sobject)]
      (slurp stream))))

(defn- create-shapes-for-svg
  [{:keys [id] :as mobj} file-id objects frame-id position]
  (let [svg-text  (get-svg-content id)

        optimizer (::csvg/optimizer *system*)
        svg-text  (csvg/optimize optimizer svg-text)

        svg-data  (-> (csvg/parse svg-text)
                      (assoc :name (:name mobj))
                      (collect-and-persist-images file-id))]

    (sbuilder/create-svg-shapes svg-data position objects frame-id frame-id #{} false)))

(defn- process-media-object
  [fdata page-id frame-id mobj position]
  (let [page    (ctpl/get-page fdata page-id)
        file-id (get fdata :id)

        [shape children]
        (if (= (:mtype mobj) "image/svg+xml")
          (create-shapes-for-svg mobj file-id (:objects page) frame-id position)
          (create-shapes-for-bitmap mobj frame-id position))

        shape (assoc shape :name (-> "Graphics"
                                     (cfh/merge-path-item (:path mobj))
                                     (cfh/merge-path-item (:name mobj))))

        changes
        (-> (fcb/empty-changes nil)
            (fcb/set-save-undo? false)
            (fcb/with-page page)
            (fcb/with-objects (:objects page))
            (fcb/with-library-data fdata)
            (fcb/delete-media (:id mobj))
            (fcb/add-objects (cons shape children)))

        ;; NOTE: this is a workaround for `generate-add-component`, it
        ;; is needed because that function always starts from empty
        ;; changes; so in this case we need manually add all shapes to
        ;; the page and then use that page for the
        ;; `generate-add-component` function
        page
        (reduce (fn [page shape]
                  (ctst/add-shape (:id shape)
                                  shape
                                  page
                                  frame-id
                                  frame-id
                                  nil
                                  true))
                page
                (cons shape children))

        [_ _ changes2]
        (cflh/generate-add-component nil
                                     [shape]
                                     (:objects page)
                                     (:id page)
                                     file-id
                                     true
                                     nil
                                     cfsh/prepare-create-artboard-from-selection)
        changes (fcb/concat-changes changes changes2)]

    (:redo-changes changes)))

(defn- create-media-grid
  [fdata page-id frame-id grid media-group]
  (let [factory  (px/thread-factory :virtual true)
        executor (px/fixed-executor :parallelism 10 :factory factory)
        process  (fn [mobj position]
                   (let [position (gpt/add position (gpt/point grid-gap grid-gap))
                         tp1      (dt/tpoint)]
                     (try
                       (process-media-object fdata page-id frame-id mobj position)
                       (catch Throwable cause
                         (l/wrn :hint "unable to process file media object (skiping)"
                                :file-id (str (:id fdata))
                                :id (str (:id mobj))
                                :cause cause)

                         (if-not *skip-on-error*
                           (throw cause)
                           nil))
                       (finally
                         (l/trc :hint "graphic processed"
                                :file-id (str (:id fdata))
                                :media-id (str (:id mobj))
                                :elapsed (dt/format-duration (tp1)))))))]
    (try
      (->> (d/zip media-group grid)
           (map (fn [[mobj position]]
                  (l/trc :hint "submit graphic processing" :file-id (str (:id fdata)) :id (str (:id mobj)))
                  (sse/tap {:type :migration-progress
                            :section :graphics
                            :name (:name mobj)})
                  (px/submit! executor (partial process mobj position))))
           (reduce (fn [fdata promise]
                     (if-let [changes (deref promise)]
                       (-> (assoc-in fdata [:options :components-v2] true)
                           (cp/process-changes changes false))
                       fdata))
                   fdata))
      (finally
        (pu/close! executor)))))

(defn- migrate-graphics
  [fdata]
  (sse/tap {:type :migration-progress
            :section :graphics})
  (if (empty? (:media fdata))
    fdata
    (let [[fdata page-id start-pos]
          (ctf/get-or-add-library-page fdata frame-gap)

          media (->> (vals (:media fdata))
                     (map (fn [{:keys [width height] :as media}]
                            (let [points (-> (grc/make-rect 0 0 width height)
                                             (grc/rect->points))]
                              (assoc media :points points)))))

          groups (get-asset-groups media "Graphics")]

      (let [total (count media)]
        (some-> *stats* (swap! update :processed/graphics (fnil + 0) total))
        (some-> *team-stats* (swap! update :processed/graphics (fnil + 0) total))
        (some-> *file-stats* (swap! assoc :processed/graphics total)))

      (loop [groups (seq groups)
             fdata fdata
             position start-pos]
        (if (empty? groups)
          fdata
          (let [[group-name assets]    (first groups)
                grid                   (ctst/generate-shape-grid assets position grid-gap)
                {:keys [width height]} (meta grid)
                frame                  (create-frame group-name position width height)
                fdata                  (ctpl/update-page fdata
                                                         page-id
                                                         #(ctst/add-shape (:id frame)
                                                                          frame
                                                                          %
                                                                          (:id frame)
                                                                          (:id frame)
                                                                          nil
                                                                          true))]
            (recur (next groups)
                   (create-media-grid fdata page-id (:id frame) grid assets)
                   (gpt/add position (gpt/point 0 (+ height (* 2 grid-gap) frame-gap))))))))))

(defn- migrate-file-data
  [fdata libs]
  (let [migrated? (dm/get-in fdata [:options :components-v2])]
    (if migrated?
      fdata
      (let [fdata (migrate-components fdata libs)
            fdata (migrate-graphics fdata)]
        (update fdata :options assoc :components-v2 true)))))

(defn- process-file
  [{:keys [id] :as file} & {:keys [validate? throw-on-validate?]}]
  (let [conn (::db/conn *system*)]
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (partial files/load-pointer conn id)
              cfeat/*wrap-with-pointer-map-fn*
              (if (contains? (:features file) "fdata/pointer-map") pmap/wrap identity)
              cfeat/*wrap-with-objects-map-fn*
              (if (contains? (:features file) "fdata/objectd-map") omap/wrap identity)]

      (let [file (-> file
                     (update :data blob/decode)
                     (update :data assoc :id id)
                     (pmg/migrate-file))

            libs (->> (files/get-file-libraries conn id)
                      (into [file] (map (fn [{:keys [id]}]
                                          (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
                                            (-> (db/get conn :file {:id id})
                                                (files/decode-row)
                                                (files/process-pointers deref) ; ensure all pointers resolved
                                                (pmg/migrate-file))))))
                      (d/index-by :id))

            file (-> file
                     (update :data migrate-file-data libs)
                     (update :features conj "components/v2"))]

        (when (contains? (:features file) "fdata/pointer-map")
          (files/persist-pointers! conn id))

        (db/update! conn :file
                    {:data (blob/encode (:data file))
                     :features (db/create-array conn "text" (:features file))
                     :revn (:revn file)}
                    {:id (:id file)})

        (when validate?
          (if throw-on-validate?
            (cfv/validate-file! file libs)
            (doseq [error (cfv/validate-file file libs)]
              (l/wrn :hint "migrate:file:validation-error"
                     :file-id (str (:id file))
                     :file-name (:name file)
                     :error error))))

        (dissoc file :data)))))

(defn migrate-file!
  [system file-id & {:keys [validate? throw-on-validate?]}]
  (let [tpoint  (dt/tpoint)
        file-id (if (string? file-id)
                  (parse-uuid file-id)
                  file-id)]
    (binding [*file-stats* (atom {})]
      (try
        (l/dbg :hint "migrate:file:start" :file-id (str file-id))

        (let [system (update system ::sto/storage media/configure-assets-storage)]
          (db/tx-run! system
                      (fn [{:keys [::db/conn] :as system}]
                        (binding [*system* system]
                          (fsnap/take-file-snapshot! system {:file-id file-id :label "migration/components-v2"})
                          (-> (db/get conn :file {:id file-id})
                              (update :features db/decode-pgarray #{})
                              (process-file :validate? validate?
                                            :throw-on-validate? throw-on-validate?))))))

        (finally
          (let [elapsed    (tpoint)
                components (get @*file-stats* :processed/components 0)
                graphics   (get @*file-stats* :processed/graphics 0)]

            (l/dbg :hint "migrate:file:end"
                   :file-id (str file-id)
                   :graphics graphics
                   :components components
                   :elapsed (dt/format-duration elapsed))

            (some-> *stats* (swap! update :processed/files (fnil inc 0)))
            (some-> *team-stats* (swap! update :processed/files (fnil inc 0)))))))))

(defn migrate-team!
  [system team-id & {:keys [validate? throw-on-validate?]}]
  (let [tpoint  (dt/tpoint)
        team-id (if (string? team-id)
                  (parse-uuid team-id)
                  team-id)]
    (l/dbg :hint "migrate:team:start" :team-id (dm/str team-id))
    (binding [*team-stats* (atom {})]
      (try
        ;; We execute this out of transaction because we want this
        ;; change to be visible to all other sessions before starting
        ;; the migration
        (let [sql (str "UPDATE team SET features = "
                       "    array_append(features, 'ephimeral/v2-migration') "
                       " WHERE id = ?")]
          (db/exec-one! system [sql team-id]))

        (db/tx-run! system
                    (fn [{:keys [::db/conn] :as system}]
                      ;; Lock the team
                      (db/exec-one! conn ["SET idle_in_transaction_session_timeout = 0"])

                      (let [{:keys [features] :as team} (-> (db/get conn :team {:id team-id})
                                                            (update :features db/decode-pgarray #{}))]

                        (if (contains? features "components/v2")
                          (l/dbg :hint "team already migrated")
                          (let [sql  (str/concat
                                      "SELECT f.id FROM file AS f "
                                      "  JOIN project AS p ON (p.id = f.project_id) "
                                      "WHERE p.team_id = ? AND f.deleted_at IS NULL AND p.deleted_at IS NULL "
                                      "FOR UPDATE")]

                            (doseq [file-id (->> (db/exec! conn [sql team-id])
                                                 (map :id))]
                              (migrate-file! system file-id
                                             :validate? validate?
                                             :throw-on-validate? throw-on-validate?))

                            (let [features (-> features
                                               (disj "ephimeral/v2-migration")
                                               (conj "components/v2")
                                               (conj "layout/grid")
                                               (conj "styles/v2"))]
                              (db/update! conn :team
                                          {:features (db/create-array conn "text" features)}
                                          {:id team-id})))))))
        (finally
          (some-> *semaphore* ps/release!)
          (let [elapsed (tpoint)]
            (some-> *stats* (swap! update :processed/teams (fnil inc 0)))

            ;; We execute this out of transaction because we want this
            ;; change to be visible to all other sessions before starting
            ;; the migration
            (let [sql (str "UPDATE team SET features = "
                           "    array_remove(features, 'ephimeral/v2-migration') "
                           " WHERE id = ?")]
              (db/exec-one! system [sql team-id]))

            (let [components (get @*team-stats* :processed/components 0)
                  graphics   (get @*team-stats* :processed/graphics 0)
                  files      (get @*team-stats* :processed/files 0)]
              (l/dbg :hint "migrate:team:end"
                     :team-id (dm/str team-id)
                     :files files
                     :components components
                     :graphics graphics
                     :elapsed (dt/format-duration elapsed)))))))))


