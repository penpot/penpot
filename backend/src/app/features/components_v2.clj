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
   [app.common.files.changes :as cp]
   [app.common.files.changes-builder :as fcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.files.validate :as cfv]
   [app.common.fressian :as fres]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gshp]
   [app.common.logging :as l]
   [app.common.logic.libraries :as cll]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.svg :as csvg]
   [app.common.svg.shapes-builder :as sbuilder]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.grid :as ctg]
   [app.common.types.modifiers :as ctm]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.path :as ctsp]
   [app.common.types.shape.text :as ctsx]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.media :as media]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-snapshot :as fsnap]
   [app.rpc.commands.media :as cmd.media]
   [app.storage :as sto]
   [app.storage.impl :as impl]
   [app.storage.tmp :as tmp]
   [app.svgo :as svgo]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [clojure.set :refer [rename-keys]]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [promesa.util :as pu]))


(def ^:dynamic *stats*
  "A dynamic var for setting up state for collect stats globally."
  nil)

(def ^:dynamic *cache*
  "A dynamic var for setting up a cache instance."
  false)

(def ^:dynamic *skip-on-graphic-error*
  "A dynamic var for setting up the default error behavior for graphics processing."
  nil)

(def ^:dynamic ^:private *system*
  "An internal var for making the current `system` available to all
  internal functions without the need to explicitly pass it top down."
  nil)

(def ^:dynamic ^:private *file-stats*
  "An internal dynamic var for collect stats by file."
  nil)

(def ^:dynamic ^:private *team-stats*
  "An internal dynamic var for collect stats by team."
  nil)

(def grid-gap 50)
(def frame-gap 200)
(def max-group-size 50)

(defn decode-row
  [{:keys [features data] :as row}]
  (cond-> row
    (some? features)
    (assoc :features (db/decode-pgarray features #{}))

    (some? data)
    (assoc :data (blob/decode data))))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE PREPARATION BEFORE MIGRATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-recent-color?
  (sm/lazy-validator ::ctc/recent-color))

(def valid-color?
  (sm/lazy-validator ::ctc/color))

(def valid-fill?
  (sm/lazy-validator cts/schema:fill))

(def valid-stroke?
  (sm/lazy-validator ::cts/stroke))

(def valid-flow?
  (sm/lazy-validator ::ctp/flow))

(def valid-text-content?
  (sm/lazy-validator ::ctsx/content))

(def valid-path-content?
  (sm/lazy-validator ::ctsp/content))

(def valid-path-segment?
  (sm/lazy-validator ::ctsp/segment))

(def valid-rgb-color-string?
  (sm/lazy-validator ::ctc/rgb-color))

(def valid-shape-points?
  (sm/lazy-validator cts/schema:points))

(def valid-image-attrs?
  (sm/lazy-validator cts/schema:image-attrs))

(def valid-column-grid-params?
  (sm/lazy-validator ::ctg/column-params))

(def valid-square-grid-params?
  (sm/lazy-validator ::ctg/square-params))


(defn- prepare-file-data
  "Apply some specific migrations or fixes to things that are allowed in v1 but not in v2,
   or that are the result of old bugs."
  [file-data libraries]
  (let [detached-ids  (volatile! #{})

        detach-shape
        (fn [container shape]
          ;; Detach a shape and make necessary adjustments.
          (let [is-component? (let [root-shape (ctst/get-shape container (:id container))]
                                (and (some? root-shape) (nil? (:parent-id root-shape))))
                parent        (ctst/get-shape container (:parent-id shape))
                in-copy?      (ctn/in-any-component? (:objects container) parent)]

            (letfn [(detach-recursive [container shape first?]

                      ;; If the shape is inside a component, add it to detached-ids. This list is used
                      ;; later to process other copies that was referencing a detached nested copy.
                      (when is-component?
                        (vswap! detached-ids conj (:id shape)))

                      ;; Detach the shape and all children until we find a subinstance.
                      (if (or first? in-copy? (not (ctk/instance-head? shape)))
                        (as-> container $
                          (ctn/update-shape $ (:id shape) ctk/detach-shape)
                          (reduce #(detach-recursive %1 %2 false)
                                  $
                                  (map (d/getf (:objects container)) (:shapes shape))))

                        ;; If this is a subinstance head and the initial shape whas not itself a
                        ;; nested copy, stop detaching and promote it to root.
                        (ctn/update-shape container (:id shape) #(assoc % :component-root true))))]

              (detach-recursive container shape true))))

        fix-bad-children
        (fn [file-data]
          ;; Remove any child that does not exist. And also remove duplicated children.
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals (partial fix-shape container)))

                  (fix-shape [container shape]
                    (let [objects (:objects container)]
                      (d/update-when shape :shapes
                                     (fn [shapes]
                                       (->> shapes
                                            (d/removev #(nil? (get objects %)))
                                            (into [] (distinct)))))))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-missing-image-metadata
        (fn [file-data]
          ;; Delete broken image shapes with no metadata.
          (letfn [(fix-container [container]
                    (d/update-when container :objects #(reduce-kv fix-shape % %)))

                  (fix-shape [objects id shape]
                    (if (and (cfh/image-shape? shape)
                             (nil? (:metadata shape)))
                      (-> objects
                          (dissoc id)
                          (d/update-in-when [(:parent-id shape) :shapes]
                                            (fn [shapes] (filterv #(not= id %) shapes))))
                      objects))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-invalid-page
        (fn [file-data]
          (letfn [(update-page [page]
                    (-> page
                        (update :name (fn [name]
                                        (if (nil? name)
                                          "Page"
                                          name)))
                        (update :options fix-options)))

                  (fix-background [options]
                    (if (and (contains? options :background)
                             (not (valid-rgb-color-string? (:background options))))
                      (dissoc options :background)
                      options))

                  (fix-saved-grids [options]
                    (d/update-when options :saved-grids
                                   (fn [grids]
                                     (cond-> grids
                                       (and (contains? grids :column)
                                            (not (valid-column-grid-params? (:column grids))))
                                       (dissoc :column)

                                       (and (contains? grids :row)
                                            (not (valid-column-grid-params? (:row grids))))
                                       (dissoc :row)

                                       (and (contains? grids :square)
                                            (not (valid-square-grid-params? (:square grids))))
                                       (dissoc :square)))))

                  (fix-options [options]
                    (-> options
                        ;; Some pages has invalid data on flows, we proceed just to
                        ;; delete them.
                        (d/update-when :flows #(filterv valid-flow? %))
                        (fix-saved-grids)
                        (fix-background)))]

            (update file-data :pages-index update-vals update-page)))

        ;; Sometimes we found that the file has issues in the internal
        ;; data structure of the local library; this function tries to
        ;; fix that issues.
        fix-file-data
        (fn [file-data]
          (letfn [(fix-colors-library [colors]
                    (let [colors (dissoc colors nil)]
                      (reduce-kv (fn [colors id color]
                                   (if (valid-color? color)
                                     colors
                                     (dissoc colors id)))
                                 colors
                                 colors)))]
            (-> file-data
                (d/update-when :colors fix-colors-library)
                (d/update-when :typographies dissoc nil))))

        fix-big-geometry-shapes
        (fn [file-data]
          ;; At some point in time, we had a bug that generated shapes
          ;; with huge geometries that did not validate the
          ;; schema. Since we don't have a way to fix those shapes, we
          ;; simply proceed to delete it. We ignore path type shapes
          ;; because they have not been affected by the bug.
          (letfn [(fix-container [container]
                    (d/update-when container :objects #(reduce-kv fix-shape % %)))

                  (fix-shape [objects id shape]
                    (cond
                      (or (cfh/path-shape? shape)
                          (cfh/bool-shape? shape))
                      objects

                      (or (and (number? (:x shape)) (not (sm/valid-safe-number? (:x shape))))
                          (and (number? (:y shape)) (not (sm/valid-safe-number? (:y shape))))
                          (and (number? (:width shape)) (not (sm/valid-safe-number? (:width shape))))
                          (and (number? (:height shape)) (not (sm/valid-safe-number? (:height shape)))))
                      (-> objects
                          (dissoc id)
                          (d/update-in-when [(:parent-id shape) :shapes]
                                            (fn [shapes] (filterv #(not= id %) shapes))))

                      :else
                      objects))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        ;; Some files has totally broken shapes, we just remove them
        fix-completly-broken-shapes
        (fn [file-data]
          (letfn [(update-object [objects id shape]
                    (cond
                      (nil? (:type shape))
                      (let [ids (cfh/get-children-ids objects id)]
                        (-> objects
                            (dissoc id)
                            (as-> $ (reduce dissoc $ ids))
                            (d/update-in-when [(:parent-id shape) :shapes]
                                              (fn [shapes] (filterv #(not= id %) shapes)))))

                      (and (cfh/text-shape? shape)
                           (not (valid-text-content? (:content shape))))
                      (dissoc objects id)

                      (and (cfh/path-shape? shape)
                           (not (valid-path-content? (:content shape))))
                      (-> objects
                          (dissoc id)
                          (d/update-in-when [(:parent-id shape) :shapes]
                                            (fn [shapes] (filterv #(not= id %) shapes))))

                      :else
                      objects))

                  (update-container [container]
                    (d/update-when container :objects #(reduce-kv update-object % %)))]

            (-> file-data
                (update :pages-index update-vals update-container)
                (update :components update-vals update-container))))

        fix-shape-geometry
        (fn [file-data]
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (cond
                      (and (cfh/image-shape? shape)
                           (valid-image-attrs? shape)
                           (grc/valid-rect? (:selrect shape))
                           (not (valid-shape-points? (:points shape))))
                      (let [selrect  (:selrect shape)
                            metadata (:metadata shape)
                            selrect  (grc/make-rect
                                      (:x selrect)
                                      (:y selrect)
                                      (:width metadata)
                                      (:height metadata))
                            points   (grc/rect->points selrect)]
                        (assoc shape
                               :selrect selrect
                               :points points))

                      (and (cfh/text-shape? shape)
                           (valid-text-content? (:content shape))
                           (not (valid-shape-points? (:points shape)))
                           (seq (:position-data shape)))
                      (let [selrect (->> (:position-data shape)
                                         (map (juxt :x :y :width :height))
                                         (map #(apply grc/make-rect %))
                                         (grc/join-rects))
                            points  (grc/rect->points selrect)]

                        (assoc shape
                               :x (:x selrect)
                               :y (:y selrect)
                               :width (:width selrect)
                               :height (:height selrect)
                               :selrect selrect
                               :points points))

                      (and (cfh/text-shape? shape)
                           (valid-text-content? (:content shape))
                           (not (valid-shape-points? (:points shape)))
                           (grc/valid-rect? (:selrect shape)))
                      (let [selrect (:selrect shape)
                            points  (grc/rect->points selrect)]
                        (assoc shape
                               :x (:x selrect)
                               :y (:y selrect)
                               :width (:width selrect)
                               :height (:height selrect)
                               :points points))

                      (and (or (cfh/rect-shape? shape)
                               (cfh/svg-raw-shape? shape)
                               (cfh/circle-shape? shape))
                           (not (valid-shape-points? (:points shape)))
                           (grc/valid-rect? (:selrect shape)))
                      (let [selrect (if (grc/valid-rect? (:svg-viewbox shape))
                                      (:svg-viewbox shape)
                                      (:selrect shape))
                            points  (grc/rect->points selrect)]
                        (assoc shape
                               :x (:x selrect)
                               :y (:y selrect)
                               :width (:width selrect)
                               :height (:height selrect)
                               :selrect selrect
                               :points points))

                      (and (= :icon (:type shape))
                           (grc/valid-rect? (:selrect shape))
                           (valid-shape-points? (:points shape)))
                      (-> shape
                          (assoc :type :rect)
                          (dissoc :content)
                          (dissoc :metadata)
                          (dissoc :segments)
                          (dissoc :x1 :y1 :x2 :y2))

                      (and (cfh/group-shape? shape)
                           (grc/valid-rect? (:selrect shape))
                           (not (valid-shape-points? (:points shape))))
                      (assoc shape :points (grc/rect->points (:selrect shape)))

                      :else
                      shape))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-empty-components
        (fn [file-data]
          (letfn [(fix-component [components id component]
                    (let [root-shape (ctst/get-shape component (:id component))]
                      (if (or (empty? (:objects component))
                              (nil? root-shape)
                              (nil? (:type root-shape)))
                        (dissoc components id)
                        components)))]

            (-> file-data
                (d/update-when :components #(reduce-kv fix-component % %)))))

        fix-components-with-component-root
        ;;In v1 no components in the library should have component-root
        (fn [file-data]
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (dissoc shape :component-root))]

            (-> file-data
                (update :components update-vals fix-container))))

        fix-non-existing-component-ids
        ;; Check component ids have valid values.
        (fn [file-data]
          (let [libraries (assoc-in libraries [(:id file-data) :data] file-data)]
            (letfn [(fix-container [container]
                      (d/update-when container :objects update-vals fix-shape))

                    (fix-shape [shape]
                      (let [component-id   (:component-id shape)
                            component-file (:component-file shape)
                            library        (get libraries component-file)]

                        (cond-> shape
                          (and (some? component-id)
                               (some? library)
                               (nil? (ctkl/get-component (:data library) component-id)))
                          (ctk/detach-shape))))]

              (-> file-data
                  (update :pages-index update-vals fix-container)
                  (d/update-when :components update-vals fix-container)))))

        fix-misc-shape-issues
        (fn [file-data]
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-gap-value [gap]
                    (if (or (= gap ##Inf)
                            (= gap ##-Inf))
                      0
                      gap))

                  (fix-shape [shape]
                    (cond-> shape
                      ;; Some shapes has invalid gap value
                      (contains? shape :layout-gap)
                      (update :layout-gap (fn [layout-gap]
                                            (if (number? layout-gap)
                                              {:row-gap layout-gap :column-gap layout-gap}
                                              (-> layout-gap
                                                  (d/update-when :column-gap fix-gap-value)
                                                  (d/update-when :row-gap fix-gap-value)))))

                      ;; Fix name if missing
                      (nil? (:name shape))
                      (assoc :name (d/name (:type shape)))

                      ;; Remove v2 info from components that have been copied and pasted
                      ;; from a v2 file
                      (some? (:main-instance shape))
                      (dissoc :main-instance)

                      (and (contains? shape :transform)
                           (not (gmt/valid-matrix? (:transform shape))))
                      (assoc :transform (gmt/matrix))

                      (and (contains? shape :transform-inverse)
                           (not (gmt/valid-matrix? (:transform-inverse shape))))
                      (assoc :transform-inverse (gmt/matrix))

                      ;; Fix broken fills
                      (seq (:fills shape))
                      (update :fills (fn [fills] (filterv valid-fill? fills)))

                      ;; Fix broken strokes
                      (seq (:strokes shape))
                      (update :strokes (fn [strokes] (filterv valid-stroke? strokes)))

                      ;; Fix some broken layout related attrs, probably
                      ;; of copypaste on flex layout betatest period
                      (true? (:layout shape))
                      (assoc :layout :flex)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        ;; There are some bugs in the past that allows convert text to
        ;; path and this fix tries to identify this cases and fix them converting
        ;; the shape back to text shape

        fix-text-shapes-converted-to-path
        (fn [file-data]
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (if (and (cfh/path-shape? shape)
                             (contains? shape :content)
                             (some? (:selrect shape))
                             (valid-text-content? (:content shape)))
                      (let [selrect (:selrect shape)]
                        (-> shape
                            (assoc :x (:x selrect))
                            (assoc :y (:y selrect))
                            (assoc :width (:width selrect))
                            (assoc :height (:height selrect))
                            (assoc :type :text)))
                      shape))]
            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-broken-paths
        (fn [file-data]
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (cond
                      (and (cfh/path-shape? shape)
                           (seq (:content shape))
                           (not (valid-path-content? (:content shape))))
                      (let [shape (update shape :content fix-path-content)]
                        (if (not (valid-path-content? (:content shape)))
                          shape
                          (let [[points selrect] (gshp/content->points+selrect shape (:content shape))]
                            (-> shape
                                (dissoc :bool-content)
                                (dissoc :bool-type)
                                (assoc :points points)
                                (assoc :selrect selrect)))))

                      ;; When we fount a bool shape with no content,
                      ;; we convert it to a simple rect
                      (and (cfh/bool-shape? shape)
                           (not (seq (:bool-content shape))))
                      (let [selrect (or (:selrect shape)
                                        (grc/make-rect))
                            points  (grc/rect->points selrect)]
                        (-> shape
                            (assoc :x (:x selrect))
                            (assoc :y (:y selrect))
                            (assoc :width (:height selrect))
                            (assoc :height (:height selrect))
                            (assoc :selrect selrect)
                            (assoc :points points)
                            (assoc :type :rect)
                            (assoc :transform (gmt/matrix))
                            (assoc :transform-inverse (gmt/matrix))
                            (dissoc :bool-content)
                            (dissoc :shapes)
                            (dissoc :content)))

                      :else
                      shape))

                  (fix-path-content [content]
                    (let [[seg1 :as content] (filterv valid-path-segment? content)]
                      (if (and seg1 (not= :move-to (:command seg1)))
                        (let [params (select-keys (:params seg1) [:x :y])]
                          (into [{:command :move-to :params params}] content))
                        content)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-recent-colors
        (fn [file-data]
          ;; Remove invalid colors in :recent-colors
          (d/update-when file-data :recent-colors
                         (fn [colors]
                           (filterv valid-recent-color? colors))))

        fix-broken-parents
        (fn [file-data]
          ;; Find children shapes whose parent-id is not set to the parent that contains them.
          ;; Remove them from the parent :shapes list.
          (letfn [(fix-container [container]
                    (d/update-when container :objects #(reduce-kv fix-shape % %)))

                  (fix-shape [objects id shape]
                    (reduce (fn [objects child-id]
                              (let [child (get objects child-id)]
                                (cond-> objects
                                  (and (some? child) (not= id (:parent-id child)))
                                  (d/update-in-when [id :shapes]
                                                    (fn [shapes] (filterv #(not= child-id %) shapes))))))
                            objects
                            (:shapes shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

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
                (d/update-when :components update-vals fix-container))))

        remove-nested-roots
        (fn [file-data]
          ;; Remove :component-root in head shapes that are nested.
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals (partial fix-shape container)))

                  (fix-shape [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (and (ctk/instance-root? shape)
                               (ctn/in-any-component? (:objects container) parent))
                        (dissoc shape :component-root)
                        shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        add-not-nested-roots
        (fn [file-data]
          ;; Add :component-root in head shapes that are not nested.
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals (partial fix-shape container)))

                  (fix-shape [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (and (ctk/subinstance-head? shape)
                               (not (ctn/in-any-component? (:objects container) parent)))
                        (assoc shape :component-root true)
                        shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-orphan-copies
        (fn [file-data]
          ;; Detach shapes that were inside a copy (have :shape-ref) but now they aren't.
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))

                  (fix-shape [container shape]
                    (let [shape  (ctst/get-shape container (:id shape)) ; Get the possibly updated shape
                          parent (ctst/get-shape container (:parent-id shape))]
                      (if (and (ctk/in-component-copy? shape)
                               (not (ctk/instance-head? shape))
                               (not (ctk/in-component-copy? parent)))
                        (detach-shape container shape)
                        container)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-components-without-id
        (fn [file-data]
          ;; We have detected some components that have no :id attribute.
          ;; Regenerate it from the components map.
          (letfn [(fix-component [id component]
                    (if (some? (:id component))
                      component
                      (assoc component :id id)))]

            (-> file-data
                (d/update-when :components #(d/mapm fix-component %)))))

        remap-refs
        (fn [file-data]
          ;; Remap shape-refs so that they point to the near main.
          ;; At the same time, if there are any dangling ref, detach the shape and its children.
          (let [count  (volatile! 0)

                fix-shape
                (fn [container shape]
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
                            (do
                              (vswap! count inc)
                              (ctn/update-shape container (:id shape) #(assoc % :shape-ref (:id near-shape))))
                            ;; If not found, it may be a fostered component. Try to locate a direct shape
                            ;; in the head component.
                            (let [head           (ctn/get-head-shape (:objects container) shape)
                                  library-2      (get libraries (:component-file head))
                                  component-2    (ctkl/get-component (:data library-2) (:component-id head) true)
                                  direct-shape-2 (ctf/get-component-shape (:data library-2) component-2 (:shape-ref shape))]
                              (if (some? direct-shape-2)
                                ;; If it exists, there is nothing else to do.
                                container
                                ;; If not found, detach shape and all children.
                                ;; container
                                (do
                                  (vswap! count inc)
                                  (detach-shape container shape))))))))
                    container))

                fix-container
                (fn [container]
                  (reduce fix-shape container (ctn/shapes-seq container)))]

            [(-> file-data
                 (update :pages-index update-vals fix-container)
                 (d/update-when :components update-vals fix-container))
             @count]))

        remap-refs-recur
        ;; remapping refs can generate cascade changes so we call it until no changes are done
        (fn [file-data]
          (loop [f-data file-data]
            (let [[f-data count] (remap-refs f-data)]
              (if (= count 0)
                f-data
                (recur f-data)))))

        fix-converted-copies
        (fn [file-data]
          ;; If the user has created a copy and then converted into a path or bool,
          ;; detach it because the synchronization will no longer work.
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))

                  (fix-shape [container shape]
                    (if (and (ctk/instance-head? shape)
                             (or (cfh/path-shape? shape)
                                 (cfh/bool-shape? shape)))
                      (detach-shape container shape)
                      container))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        wrap-non-group-component-roots
        (fn [file-data]
          ;; Some components have a root that is not a group nor a frame
          ;; (e.g. a path or a svg-raw). We need to wrap them in a frame
          ;; for this one to became the root.
          (letfn [(fix-component [component]
                    (let [root-shape (ctst/get-shape component (:id component))]
                      (if (or (cfh/group-shape? root-shape)
                              (cfh/frame-shape? root-shape))
                        component
                        (let [new-id      (uuid/next)
                              frame       (-> (cts/setup-shape
                                               {:type :frame
                                                :id (:id component)
                                                :x (:x (:selrect root-shape))
                                                :y (:y (:selrect root-shape))
                                                :width (:width (:selrect root-shape))
                                                :height (:height (:selrect root-shape))
                                                :name (:name component)
                                                :shapes [new-id]
                                                :show-content true})
                                              (assoc :frame-id nil
                                                     :parent-id nil))
                              root-shape' (assoc root-shape
                                                 :id new-id
                                                 :parent-id (:id frame)
                                                 :frame-id (:id frame))]
                          (update component :objects assoc
                                  (:id frame) frame
                                  (:id root-shape') root-shape')))))]

            (-> file-data
                (d/update-when :components update-vals fix-component))))

        detach-non-group-instance-roots
        (fn [file-data]
          ;; If there is a copy instance whose root is not a frame or a group, it cannot
          ;; be easily repaired, and anyway it's not working in production, so detach it.
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))

                  (fix-shape [container shape]
                    (if (and (ctk/instance-head? shape)
                             (not (#{:group :frame} (:type shape))))
                      (detach-shape container shape)
                      container))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        transform-to-frames
        (fn [file-data]
          ;; Transform component and copy heads fron group to frames, and set the
          ;; frame-id of its childrens
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (if (or (nil? (:parent-id shape)) (ctk/instance-head? shape))
                      (let [frame?     (= :frame (:type shape))
                            not-group? (not= :group (:type shape))]
                        (assoc shape                         ; Old groups must be converted
                               :type :frame                  ; to frames and conform to spec
                               :fills (if not-group? (d/nilv (:fills shape) []) [])  ; Groups never should have fill
                               :shapes (or (:shapes shape) [])
                               :hide-in-viewer (if frame? (boolean (:hide-in-viewer shape)) true)
                               :show-content   (if frame? (boolean (:show-content shape)) true)
                               :r1 (or (:r1 shape) 0)
                               :r2 (or (:r2 shape) 0)
                               :r3 (or (:r3 shape) 0)
                               :r4 (or (:r4 shape) 0)))
                      shape))]
            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        remap-frame-ids
        (fn [file-data]
          ;; Remap the frame-ids of the primary childs of the head instances
          ;; to point to the head instance.
          (letfn [(fix-container
                    [container]
                    (d/update-when container :objects update-vals (partial fix-shape container)))

                  (fix-shape
                    [container shape]
                    (let [parent (ctst/get-shape container (:parent-id shape))]
                      (if (ctk/instance-head? parent)
                        (assoc shape :frame-id (:id parent))
                        shape)))]
            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))

        fix-frame-ids
        (fn [file-data]
          ;; Ensure that frame-id of all shapes point to the parent or to the frame-id
          ;; of the parent, and that the destination is indeed a frame.
          (letfn [(fix-container [container]
                    (d/update-when container :objects #(cfh/reduce-objects % fix-shape %)))

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
                (d/update-when :components update-vals fix-container))))

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
                (d/update-when :components update-vals fix-component))))

        fix-false-copies
        (fn [file-data]
          ;; Find component heads that are not main-instance but have not :shape-ref.
          ;; Also shapes that have :shape-ref but are not in a copy.
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))

                  (fix-shape
                    [container shape]
                    (if (or (and (ctk/instance-head? shape)
                                 (not (ctk/main-instance? shape))
                                 (not (ctk/in-component-copy? shape)))
                            (and (ctk/in-component-copy? shape)
                                 (nil? (ctn/get-head-shape (:objects container) shape {:allow-main? true}))))
                      (detach-shape container shape)
                      container))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))


        fix-component-root-without-component
        (fn [file-data]
          ;; Ensure that if component-root is set component-file and component-id are set too
          (letfn [(fix-container [container]
                    (d/update-when container :objects update-vals fix-shape))

                  (fix-shape [shape]
                    (cond-> shape
                      (and (ctk/instance-root? shape)
                           (or (not (ctk/instance-head? shape))
                               (not (some? (:component-file shape)))))
                      (dissoc :component-id
                              :component-file
                              :component-root)))]
            (-> file-data
                (update :pages-index update-vals fix-container))))


        fix-copies-names
        (fn [file-data]
                  ;; Rename component heads to add the component path to the name
          (letfn [(fix-container [container]
                    (d/update-when container :objects #(cfh/reduce-objects % fix-shape %)))

                  (fix-shape [objects shape]
                    (let [root         (ctn/get-component-shape objects shape)
                          libraries    (assoc-in libraries [(:id file-data) :data] file-data)
                          library      (get libraries (:component-file root))
                          component    (ctkl/get-component (:data library) (:component-id root) true)
                          path         (str/trim (:path component))]
                      (if (and (ctk/instance-head? shape)
                               (some? component)
                               (= (:name component) (:name shape))
                               (not (str/empty? path)))
                        (update objects (:id shape) assoc :name (str path " / " (:name component)))
                        objects)))]

            (-> file-data
                (update :pages-index update-vals fix-container))))

        fix-copies-of-detached
        (fn [file-data]
                  ;; Find any copy that is referencing a  shape inside a component that have
                  ;; been detached in a previous fix. If so, undo the nested copy, converting
                  ;; it into a direct copy.
                  ;;
                  ;; WARNING: THIS SHOULD BE CALLED AT THE END OF THE PROCESS.
          (letfn [(fix-container [container]
                    (reduce fix-shape container (ctn/shapes-seq container)))
                  (fix-shape [container shape]
                    (cond-> container
                      (@detached-ids (:shape-ref shape))
                      (detach-shape shape)))]

            (-> file-data
                (update :pages-index update-vals fix-container)
                (d/update-when :components update-vals fix-container))))]

    (-> file-data
        (fix-file-data)
        (fix-invalid-page)
        (fix-misc-shape-issues)
        (fix-recent-colors)
        (fix-missing-image-metadata)
        (fix-text-shapes-converted-to-path)
        (fix-broken-paths)
        (fix-big-geometry-shapes)
        (fix-shape-geometry)
        (fix-empty-components)
        (fix-components-with-component-root)
        (fix-non-existing-component-ids)
        (fix-completly-broken-shapes)
        (fix-bad-children)
        (fix-broken-parents)
        (fix-orphan-shapes)
        (fix-orphan-copies)
        (remove-nested-roots)
        (add-not-nested-roots)
        (fix-components-without-id)
        (fix-converted-copies)
        (remap-refs-recur)
        (wrap-non-group-component-roots)
        (detach-non-group-instance-roots)
        (transform-to-frames)
        (remap-frame-ids)
        (fix-frame-ids)
        (fix-component-nil-objects)
        (fix-false-copies)
        (fix-component-root-without-component)
        (fix-copies-names)
        (fix-copies-of-detached)))); <- Do not add fixes after this and fix-orphan-copies call

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS MIGRATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-asset-groups
  [assets generic-name]
  (let [;; Group by first element of the path.
        groups (d/group-by #(first (cfh/split-path (:path %))) assets)
        ;; If there is a group called as the generic-name we have to preserve it
        unames (into #{} (keep str) (keys groups))
        groups (rename-keys groups {generic-name (cfh/generate-unique-name generic-name unames)})

        ;; Split large groups in chunks of max-group-size elements
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

        ;; Sort assets in each group by path
        groups (update-vals groups (fn [assets]
                                     (sort-by (fn [{:keys [path name]}]
                                                (str/lower (cfh/merge-path-item path name)))
                                              assets)))]

    ;; Sort groups by name
    (into (sorted-map) groups)))

(defn- create-frame
  [name position width height]
  (cts/setup-shape
   {:type :frame
    :x (:x position)
    :y (:y position)
    :width (+ width grid-gap)
    :height (+ height grid-gap)
    :name name
    :frame-id uuid/zero
    :parent-id uuid/zero}))

(defn- migrate-components
  "If there is any component in the file library, add a new 'Library
  backup', generate main instances for all components there and remove
  shapes from library components. Mark the file with the :components-v2 option."
  [file-data libraries]
  (let [file-data  (prepare-file-data file-data libraries)
        components (ctkl/components-seq file-data)]
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

                    ;; Let's calculate the top shame name from the components path and name
                    root-shape (-> (first shapes)
                                   (assoc :name (cfh/merge-path-item (:path component) (:name component))))

                    shapes (assoc shapes 0 root-shape)

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
          (some-> *stats* (swap! update :processed-components (fnil + 0) total))
          (some-> *team-stats* (swap! update :processed-components (fnil + 0) total))
          (some-> *file-stats* (swap! assoc :processed-components total)))

        (add-instance-grids file-data)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GRAPHICS MIGRATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-shapes-for-bitmap
  "Convert a media object that contains a bitmap image into shapes,
  one shape of type :image and one group that contains it."
  [{:keys [name width height id mtype]} frame-id position]
  (let [frame-shape (-> (cts/setup-shape
                         {:type :frame
                          :x (:x position)
                          :y (:y position)
                          :width width
                          :height height
                          :name name
                          :frame-id frame-id
                          :parent-id frame-id})
                        (assoc
                         :proportion (float (/ width height))
                         :proportion-lock true))

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
                      :parent-id (:id frame-shape)
                      :constraints-h :scale
                      :constraints-v :scale})]
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
  [svg-data file-id media-id]
  (letfn [(process-image [{:keys [href] :as item}]
            (try
              (let [item (if (str/starts-with? href "data:")
                           (let [[mtype data] (parse-datauri href)
                                 size         (alength ^bytes data)
                                 path         (tmp/tempfile :prefix "penpot.media.download.")
                                 written      (io/write* path data :size size)]

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

              (catch Throwable _
                (l/wrn :hint "unable to process embedded images on svg file"
                       :file-id (str file-id)
                       :media-id (str media-id))
                nil)))

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

(defn- resolve-sobject-id
  [id]
  (let [fmobject (db/get *system* :file-media-object {:id id}
                         {::sql/columns [:media-id]})]
    (:media-id fmobject)))

(defn get-sobject-content
  [id]
  (let [storage  (::sto/storage *system*)
        sobject  (sto/get-object storage id)]

    (when-not sobject
      (throw (RuntimeException. "sobject is nil")))
    (when (> (:size sobject) 1135899)
      (throw (RuntimeException. "svg too big")))

    (with-open [stream (sto/get-object-data storage sobject)]
      (slurp stream))))

(defn get-optimized-svg
  [sid]
  (let [svg-text (get-sobject-content sid)
        svg-text (if (contains? cf/flags :backend-svgo)
                   (svgo/optimize *system* svg-text)
                   svg-text)]
    (csvg/parse svg-text)))

(def base-path "/data/cache")

(defn get-sobject-cache-path
  [sid]
  (let [path (impl/id->path sid)]
    (fs/join base-path path)))

(defn get-cached-svg
  [sid]
  (let [path (get-sobject-cache-path sid)]
    (if (fs/exists? path)
      (with-open [^java.lang.AutoCloseable stream (io/input-stream path)]
        (let [reader (fres/reader stream)]
          (fres/read! reader)))
      (get-optimized-svg sid))))

(defn- create-shapes-for-svg
  [{:keys [id] :as mobj} file-id objects frame-id position]
  (let [sid      (resolve-sobject-id id)
        svg-data (if *cache*
                   (get-cached-svg sid)
                   (get-optimized-svg sid))
        svg-data (collect-and-persist-images svg-data file-id id)
        svg-data (assoc svg-data :name (:name mobj))]

    (sbuilder/create-svg-shapes svg-data position objects frame-id frame-id #{} false)))

(defn- process-media-object
  [fdata page-id frame-id mobj position shape-cb]
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

        [_ _ changes]
        (cll/generate-add-component changes
                                    [shape]
                                    (:objects page)
                                    (:id page)
                                    file-id
                                    true
                                    nil
                                    cfsh/prepare-create-artboard-from-selection)]

    (shape-cb shape)
    (:redo-changes changes)))

(defn- create-media-grid
  [fdata page-id frame-id grid media-group shape-cb]
  (letfn [(process [fdata mobj position]
            (let [position (gpt/add position (gpt/point grid-gap grid-gap))
                  tp       (dt/tpoint)
                  err      (volatile! false)]
              (try
                (let [changes (process-media-object fdata page-id frame-id mobj position shape-cb)]
                  (cp/process-changes fdata changes false))

                (catch Throwable cause
                  (vreset! err true)
                  (let [cause (pu/unwrap-exception cause)
                        edata (ex-data cause)]
                    (cond
                      (instance? org.xml.sax.SAXParseException cause)
                      (l/inf :hint "skip processing media object: invalid svg found"
                             :file-id (str (:id fdata))
                             :id (str (:id mobj)))

                      (= (:type edata) :not-found)
                      (l/inf :hint "skip processing media object: underlying object does not exist"
                             :file-id (str (:id fdata))
                             :id (str (:id mobj)))

                      :else
                      (let [skip? *skip-on-graphic-error*]
                        (l/wrn :hint "unable to process file media object"
                               :skiped skip?
                               :file-id (str (:id fdata))
                               :id (str (:id mobj))
                               :cause cause)
                        (when-not skip?
                          (throw cause))))
                    nil))
                (finally
                  (let [elapsed (tp)]
                    (l/trc :hint "graphic processed"
                           :file-id (str (:id fdata))
                           :media-id (str (:id mobj))
                           :error @err
                           :elapsed (dt/format-duration elapsed)))))))]

    (->> (d/zip media-group grid)
         (reduce (fn [fdata [mobj position]]
                   (or (process fdata mobj position) fdata))
                 (assoc-in fdata [:options :components-v2] true)))))

(defn- fix-graphics-size
  [fdata new-grid page-id frame-id]
  (let [modify-shape (fn [page shape-id modifiers]
                       (ctn/update-shape page shape-id #(gsh/transform-shape % modifiers)))

        resize-frame (fn [page]
                       (let [{:keys [width height]} (meta new-grid)

                             frame  (ctst/get-shape page frame-id)
                             width  (+ width grid-gap)
                             height (+ height grid-gap)

                             modif-frame (ctm/resize nil
                                                     (gpt/point (/ width (:width frame))
                                                                (/ height (:height frame)))
                                                     (gpt/point (:x frame) (:y frame)))]

                         (modify-shape page frame-id modif-frame)))

        move-components (fn [page]
                          (let [frame (get (:objects page) frame-id)
                                shapes (map (d/getf (:objects page)) (:shapes frame))]
                            (->> (d/zip shapes new-grid)
                                 (reduce (fn [page [shape position]]
                                           (let [position (gpt/add position (gpt/point grid-gap grid-gap))
                                                 modif-shape (ctm/move nil
                                                                       (gpt/point (- (:x position) (:x (:selrect shape)))
                                                                                  (- (:y position) (:y (:selrect shape)))))
                                                 children-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))]
                                             (reduce #(modify-shape %1 %2 modif-shape)
                                                     page
                                                     children-ids)))
                                         page))))]
    (-> fdata
        (ctpl/update-page page-id resize-frame)
        (ctpl/update-page page-id move-components))))

(defn- migrate-graphics
  [fdata]
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
        (some-> *stats* (swap! update :processed-graphics (fnil + 0) total))
        (some-> *team-stats* (swap! update :processed-graphics (fnil + 0) total))
        (some-> *file-stats* (swap! assoc :processed-graphics total)))

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
                                                                          true))
                new-shapes (volatile! [])
                add-shape #(vswap! new-shapes conj %)

                fdata' (create-media-grid fdata page-id (:id frame) grid assets add-shape)

                ;; When svgs had different width&height and viewport,
                ;; sometimes the old graphics importer didn't
                ;; calculate well the media object size. So, after
                ;; migration we recalculate grid size from the actual
                ;; size of the created shapes.
                fdata' (if-let [grid (ctst/generate-shape-grid @new-shapes position grid-gap)]
                         (let [{new-width :width new-height :height} (meta grid)]
                           (if-not (and (mth/close? width new-width) (mth/close? height new-height))
                             (do
                               (l/inf :hint "fixing graphics sizes"
                                      :file-id (str (:id fdata))
                                      :group group-name)
                               (fix-graphics-size fdata' grid page-id (:id frame)))
                             fdata'))
                         fdata')]

            (recur (next groups)
                   fdata'
                   (gpt/add position (gpt/point 0 (+ height (* 2 grid-gap) frame-gap))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRIVATE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- migrate-fdata
  [fdata libs]
  (let [migrated? (dm/get-in fdata [:options :components-v2])]
    (if migrated?
      fdata
      (let [fdata (migrate-components fdata libs)
            fdata (migrate-graphics fdata)]
        (update fdata :options assoc :components-v2 true)))))

;; FIXME: revisit this fn
(defn- fix-version*
  [{:keys [version] :as file}]
  (if (int? version)
    file
    (let [version (or (-> file :data :version) 0)]
      (-> file
          (assoc :version version)
          (update :data dissoc :version)))))

(defn- fix-version
  [file]
  (let [file (fix-version* file)]
    (if (> (:version file) 22)
      (assoc file :version 22)
      file)))

(defn- get-file
  [system id]
  (binding [pmap/*load-fn* (partial fdata/load-pointer system id)]
    (-> (db/get system :file {:id id}
                {::db/remove-deleted false
                 ::db/check-deleted false})
        (decode-row)
        (update :data assoc :id id)
        (update :data fdata/process-pointers deref)
        (update :data fdata/process-objects (partial into {}))
        (fix-version)
        (fmg/migrate-file))))

(defn get-team
  [system team-id]
  (-> (db/get system :team {:id team-id}
              {::db/remove-deleted false
               ::db/check-deleted false})
      (update :features db/decode-pgarray #{})))

(defn- validate-file!
  [file libs]
  (cfv/validate-file! file libs)
  (cfv/validate-file-schema! file))

(defn- persist-file!
  [{:keys [::db/conn] :as system} {:keys [id] :as file}]
  (let [file (if (contains? (:features file) "fdata/objects-map")
               (fdata/enable-objects-map file)
               file)

        file (if (contains? (:features file) "fdata/pointer-map")
               (binding [pmap/*tracked* (pmap/create-tracked)]
                 (let [file (fdata/enable-pointer-map file)]
                   (fdata/persist-pointers! system id)
                   file))
               file)

        ;; Ensure all files has :data with id
        file (update file :data assoc :id id)]

    (db/update! conn :file
                {:data (blob/encode (:data file))
                 :features (db/create-array conn "text" (:features file))
                 :version (:version file)
                 :revn (:revn file)}
                {:id (:id file)})))

(defn- process-file!
  [{:keys [::db/conn] :as system} {:keys [id] :as file} & {:keys [validate?]}]
  (let [libs  (->> (files/get-file-libraries conn id)
                   (into [file] (comp (map :id)
                                      (map (partial get-file system))))
                   (d/index-by :id))

        file  (-> file
                  (update :data migrate-fdata libs)
                  (update :features conj "components/v2"))]

    (when validate?
      (validate-file! file libs))

    file))

(def ^:private sql:get-and-lock-team-files
  "SELECT f.id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?
      AND p.deleted_at IS NULL
      AND f.deleted_at IS NULL
      FOR UPDATE")

(defn get-and-lock-team-files
  [conn team-id]
  (->> (db/cursor conn [sql:get-and-lock-team-files team-id])
       (map :id)))

(defn update-team!
  [system {:keys [id] :as team}]
  (let [conn   (db/get-connection system)
        params (-> team
                   (update :features db/encode-pgarray conn "text")
                   (dissoc :id))]
    (db/update! conn :team
                params
                {:id id})
    team))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate-file!
  [system file-id & {:keys [validate? skip-on-graphic-error? label rown]}]
  (let [tpoint (dt/tpoint)
        err    (volatile! false)]

    (binding [*file-stats* (atom {})
              *skip-on-graphic-error* skip-on-graphic-error?]
      (try
        (l/dbg :hint "migrate:file:start"
               :file-id (str file-id)
               :validate validate?
               :skip-on-graphic-error skip-on-graphic-error?)

        (db/tx-run! system
                    (fn [system]
                      (binding [*system* system]
                        (when (string? label)
                          (fsnap/create-file-snapshot! system nil file-id (str "migration/" label)))

                        (let [file (get-file system file-id)
                              file (process-file! system file :validate? validate?)]

                          (persist-file! system file)))))

        (catch Throwable cause
          (vreset! err true)
          (l/wrn :hint "error on processing file"
                 :file-id (str file-id)
                 :cause cause)
          (throw cause))

        (finally
          (let [elapsed    (tpoint)
                components (get @*file-stats* :processed-components 0)
                graphics   (get @*file-stats* :processed-graphics 0)]

            (l/dbg :hint "migrate:file:end"
                   :file-id (str file-id)
                   :graphics graphics
                   :components components
                   :validate validate?
                   :rown rown
                   :error @err
                   :elapsed (dt/format-duration elapsed))

            (some-> *stats* (swap! update :processed-files (fnil inc 0)))
            (some-> *team-stats* (swap! update :processed-files (fnil inc 0)))))))))

(defn migrate-team!
  [system team-id & {:keys [validate? rown skip-on-graphic-error? label]}]

  (l/dbg :hint "migrate:team:start"
         :team-id (dm/str team-id)
         :rown rown)

  (let [tpoint (dt/tpoint)
        err    (volatile! false)

        migrate-file
        (fn [system file-id]
          (migrate-file! system file-id
                         :label label
                         :validate? validate?
                         :skip-on-graphic-error? skip-on-graphic-error?))
        migrate-team
        (fn [{:keys [::db/conn] :as system} team-id]
          (let [{:keys [id features] :as team} (get-team system team-id)]
            (if (contains? features "components/v2")
              (l/inf :hint "team already migrated")
              (let [features (-> features
                                 (disj "ephimeral/v2-migration")
                                 (conj "components/v2")
                                 (conj "layout/grid")
                                 (conj "styles/v2"))]

                (run! (partial migrate-file system)
                      (get-and-lock-team-files conn id))

                (->> (assoc team :features features)
                     (update-team! conn))))))]

    (binding [*team-stats* (atom {})]
      (try
        (db/tx-run! system migrate-team team-id)

        (catch Throwable cause
          (vreset! err true)
          (l/wrn :hint "error on processing team"
                 :team-id (str team-id)
                 :cause cause)
          (throw cause))

        (finally
          (let [elapsed    (tpoint)
                components (get @*team-stats* :processed-components 0)
                graphics   (get @*team-stats* :processed-graphics 0)
                files      (get @*team-stats* :processed-files 0)]

            (when-not @err
              (some-> *stats* (swap! update :processed-teams (fnil inc 0))))

            (l/dbg :hint "migrate:team:end"
                   :team-id (dm/str team-id)
                   :rown rown
                   :files files
                   :components components
                   :graphics graphics
                   :elapsed (dt/format-duration elapsed))))))))
