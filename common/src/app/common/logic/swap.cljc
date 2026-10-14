;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.swap
  "Component swap: replace a copy head with a copy of another component, and
   carry the overrides of a copy across a variant switch."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.common :as gco]
   [app.common.logic.instances :as cli]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.path.segment :as segment]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [clojure.set :as set]))

(defn- set-path-new-values
  [current-shape prev-shape transform]
  (let [new-content   (segment/transform-content
                       (:content current-shape)
                       (gmt/transform-in (gpt/point 0 0) transform))
        new-points    (-> (segment/content->selrect new-content)
                          (grc/rect->points))
        points-center (gco/points->center new-points)
        new-selrect   (gsh/calculate-selrect new-points points-center)
        shape         (assoc current-shape
                             :content new-content
                             :points new-points
                             :selrect new-selrect)

        prev-center   (segment/content-center (:content prev-shape))
        delta         (gpt/subtract points-center (first new-points))
        new-pos       (gpt/subtract prev-center delta)]
    (gsh/absolute-move shape new-pos)))

(defn- switch-path-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   ref-shape            ; The referenced shape on the main component
                        ; before the switch
   attr]
  (let [old-width (-> ref-shape :selrect :width)
        new-width (-> prev-shape :selrect :width)

        old-height (-> ref-shape :selrect :height)
        new-height (-> prev-shape :selrect :height)

        transform (-> (gpt/point (/ new-width old-width)
                                 (/ new-height old-height))
                      (gmt/scale-matrix))

        shape     (set-path-new-values current-shape prev-shape transform)]
    (get shape attr)))


(defn- sized-geometry-value
  "The value of `attr`, `:selrect` or `:points`, of `shape` resized to
  `width` and `height`, at the same position and with the same transform."
  [shape width height attr]
  (let [{:keys [x y] :as selrect} (:selrect shape)
        selrect (assoc selrect
                       :width width
                       :height height
                       :x1 x
                       :y1 y
                       :x2 (+ x width)
                       :y2 (+ y height))]
    (case attr
      :selrect
      selrect

      :points
      (-> selrect
          (grc/rect->points)
          (gsh/transform-points
           (grc/rect->center selrect)
           (or (:transform shape) (gmt/matrix)))))))

(defn- switch-fixed-layout-geom-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   origin-shape         ; The original shape
   attr]
  ;; When there is a layout with fixed h or v sizing, we need
  ;; to keep the width/height (and recalculate selrect and points)
  (let [size (fn [dimension sizing]
               (let [prev    (-> prev-shape :selrect dimension)
                     current (-> current-shape :selrect dimension)
                     origin  (-> origin-shape :selrect dimension)]
                 (cond
                   (= :fix (sizing prev-shape)) current
                   ;; same-size: preserve override
                   (= origin current)           prev
                   ;; different-size: use new component's
                   :else                        current)))
        width  (size :width :layout-item-h-sizing)
        height (size :height :layout-item-v-sizing)]
    (case attr
      :width  width
      :height height
      (sized-geometry-value current-shape width height attr))))


(defn- switch-geom-change-value
  [prev-shape current-shape attr]
  ;; Composite geometry stores absolute coordinates. When preserving a size
  ;; override across variants, keep the target variant's position and only carry
  ;; the previous dimensions; otherwise :x/:y can disagree with :selrect/:points.
  (sized-geometry-value current-shape
                        (-> prev-shape :selrect :width)
                        (-> prev-shape :selrect :height)
                        attr))


(defn- equal-geometry?
  "Returns true when the value of `attr` in `shape` is considered equal
   to the corresponding value in `origin-shape`, ignoring positional
   displacement (x/y).
   For :selrect we compare width/height only;
   for :points we normalise each vector so the first point is the
   origin before comparing.
   For :content on path shapes we compare the bounding-box width/height
   of the path segments, again ignoring absolute position.

   Comparisons use `mth/close?` (and `gpt/close?` for points) rather than
   exact `=` because `previous-shape` here may carry sub-pixel drift from
   interactive transform modifiers (e.g. an alt-drag duplicate of a
   variant whose children are component instances). Without tolerance
   this guard would miss equivalent geometries and let the `:else` branch
   in `update-attrs-on-switch` carry stale `:selrect`/`:points` from the
   pre-switch shape onto the freshly instantiated target."
  [shape origin-shape attr]
  (or (and (= attr :selrect)
           (mth/close? (-> shape :selrect :width)  (-> origin-shape :selrect :width))
           (mth/close? (-> shape :selrect :height) (-> origin-shape :selrect :height)))
      (and (= attr :points)
           (let [normalize-pts (fn [pts]
                                 (when (seq pts)
                                   (let [f (first pts)]
                                     (mapv #(gpt/subtract % f) pts))))
                 a (normalize-pts (get shape :points))
                 b (normalize-pts (get origin-shape :points))]
             (and (= (count a) (count b))
                  (every? identity (map gpt/close? a b)))))
      (and (= attr :content)
           (cfh/path-shape? shape)
           (let [ca (:content shape)
                 cb (:content origin-shape)]
             (and (some? ca) (some? cb)
                  (let [selrect-a (segment/content->selrect ca)
                        selrect-b (segment/content->selrect cb)]
                    (and (some? selrect-a) (some? selrect-b)
                         (mth/close? (:width selrect-a) (:width selrect-b))
                         (mth/close? (:height selrect-a) (:height selrect-b)))))))))

(defn- switch-attr-operations
  "The `[attr value]` pairs that carry `attr` over a variant switch, from
   `previous-shape`, the copy shape before the switch placed like
   `current-shape`, into `current-shape`, its clean copy of the new
   variant. Only an override (a group in `touched`) is carried, and only
   where `origin-ref-shape`, the main shape before the switch, and the new
   copy agree on the attribute (decision (a) of the component contract);
   geometry carries the override's size at the new position."
  [current-shape previous-shape origin-ref-shape touched text-auto? attr]
  (let [sync-group
        (ctk/resolve-sync-group (:type previous-shape) attr)

        skip-attr?
        (or
         ;; For auto text, avoid copying geometry-driven attrs on switch.
         (and text-auto?
              (contains? #{:points :selrect :width :height :position-data} attr))

         ;; If the attribute is not valid for the destiny, don't copy it
         (not (cts/is-allowed-switch-keep-attr? attr (:type current-shape)))

         ;; If the values are already equal, don't copy them
         (= (get previous-shape attr) (get current-shape attr))

         ;; If :selrect/:points values are already equal ignoring displacement,
         ;; don't copy them
         (equal-geometry? previous-shape origin-ref-shape attr)

         ;; If the attr is not touched, don't copy it
         (not (touched sync-group))

         ;; If both variants (origin and destiny) don't have the same value
         ;; for that attribute, don't copy it.
         ;; Exceptions: :points :selrect and :content can be different
         ;;
         ;; Sample:
         ;; 1. We have a variant with C1 (bg red) and C2 (bg blue).
         ;; 2. We make a copy of C1 called Copy.
         ;; 3. We set Copy’s bg to green (so it it has an override on the bg).
         ;; 4. We switch Copy to use C2 as base.
         ;; 5. The bg of Copy now is blue (we ignore the override)
         (and
          (not (contains? #{:points :selrect :content} attr))
          (not= (get origin-ref-shape attr) (get current-shape attr)))

         ;; The :content attr cant't be copied to elements of different type
         (and (= attr :content) (not= (:type previous-shape) (:type current-shape))))

        ;; On texts, both text (the actual letters)
        ;; and attrs (bold, font, etc) are in the same attr :content.
        ;; If only one of them is touched, we want to address this case and
        ;; only update the untouched one
        text-change?
        (and (not skip-attr?)
             (cfh/text-shape? current-shape)
             (cfh/text-shape? previous-shape)
             (= :content attr)
             (touched sync-group))

        path-change?
        (and (= :path (:type current-shape))
             (contains? #{:points :selrect :content} attr))

        ;; position-data is a special case because can be affected by :geometry-group and :content-group
        ;; so, if the position-data changes but the geometry is touched we need to reset the position-data
        ;; so it's calculated again
        reset-pos-data?
        (and (not skip-attr?)
             (cfh/text-shape? previous-shape)
             (or (= attr :position-data) (= attr :selrect))
             (not= (:position-data previous-shape) (:position-data current-shape))
             (touched :geometry-group))

        skip?
        (or skip-attr?
            ;; If we are going to reset the position data, skip the selrect attr
            (and reset-pos-data? (= attr :selrect))
            ;; Avoid copying composite geometry attrs (:selrect/:points) when the
            ;; variant dimensions differ but neither sizing is :fix. Without this,
            ;; :width/:height are correctly skipped by the check above
            ;; but :selrect/:points would still carry the old override dimensions,
            ;; leaving the shape in an inconsistent state. When :fix sizing is
            ;; present, switch-fixed-layout-geom-change-value handles the composite
            ;; attrs and must NOT be bypassed. Path shapes are also handled
            ;; separately via switch-path-change-value.
            (and (contains? #{:selrect :points} attr)
                 (not path-change?)
                 (not (or (= :fix (:layout-item-h-sizing previous-shape))
                          (= :fix (:layout-item-v-sizing previous-shape))))
                 (or (not= (get origin-ref-shape :width) (get current-shape :width))
                     (not= (get origin-ref-shape :height) (get current-shape :height)))))

        attr-val
        (when-not skip?
          (cond
            ;; If position data changes and the geometry group is touched
            ;; we need to put to nil so we can regenerate it
            reset-pos-data?
            nil

            ;; Each text override that the old and the new main agree on is
            ;; carried over the new main's content
            text-change?
            (txt/merge-overrides (:content previous-shape)
                                 (:content current-shape)
                                 (set/difference (txt/text-overrides touched)
                                                 (txt/override-groups (:content origin-ref-shape)
                                                                      (:content current-shape))))

            path-change?
            (switch-path-change-value previous-shape
                                      current-shape
                                      origin-ref-shape
                                      attr)

            (and (or (= :fix (:layout-item-h-sizing previous-shape))
                     (= :fix (:layout-item-v-sizing previous-shape)))
                 (contains? #{:points :selrect :width :height} attr))
            (switch-fixed-layout-geom-change-value previous-shape current-shape origin-ref-shape attr)

            (and (contains? #{:points :selrect} attr)
                 (not path-change?))
            (switch-geom-change-value previous-shape current-shape attr)

            :else
            (get previous-shape attr)))]

    ;; If the final attr-value is the actual value, skip
    (if (or skip? (= attr-val (get current-shape attr)))
      []
      (cond-> []
        ;; On a text-change, we want to force a position-data reset
        ;; so it's calculated again
        text-change?
        (conj [:position-data nil])

        ;; On a rotation operation we need to keep also the transformation matrixes
        (= attr :rotation)
        (conj [:transform (:transform previous-shape)]
              [:transform-inverse (:transform-inverse previous-shape)])

        :always
        (conj [attr attr-val])))))

(defn update-attrs-on-switch
  "Copy attributes that have changed in the shape previous to the switch
   to the current shape (post switch). Used only on variants switch"
  ;; NOTE: This function have similitudes but is very different to
  ;; cll/update-attrs:
  ;; In components (cll/update-attrs), the source shape is "clean", and the destination
  ;; shape may have touched elements that shouldn't be overwritten.
  ;; In variants (update-attrs-on-switch), the destination shape is "clean",
  ;; and it's the source shape that may have touched elements, and we only want
  ;; to copy those touched elements.
  [changes current-shape previous-shape current-root prev-root origin-ref-shape container]
  (let [;; We need to sync only the position relative to the origin of the component.
        ;; (see cll/update-attrs for a full explanation)
        previous-shape (cll/reposition-shape previous-shape prev-root current-root)
        touched        (get previous-shape :touched #{})
        text-auto?     (and (cfh/text-shape? current-shape)
                            (contains? #{:auto-height :auto-width} (:grow-type current-shape)))

        [roperations uoperations]
        (cll/attr-operations current-shape
                             #(switch-attr-operations current-shape previous-shape origin-ref-shape
                                                      touched text-auto? %))

        updated-attrs (into #{} (map :attr) roperations)
        updated-sync-groups (into #{}
                                  (keep #(ctk/resolve-sync-group (:type previous-shape) %))
                                  updated-attrs)
        new-content (some #(when (= (:attr %) :content) (:val %)) roperations)
        new-touched (set/union (or (:touched current-shape) #{})
                               updated-sync-groups
                               (when (and (cfh/text-shape? current-shape) (some? new-content))
                                 (txt/override-groups (:content current-shape) new-content)))
        roperations (into [{:type :set-touched :touched new-touched}] roperations)
        uoperations (into (list {:type :set-touched :touched (:touched current-shape)}) uoperations)]
    (cond-> changes
      (> (count roperations) 1)
      (-> (cll/add-update-attr-changes current-shape container roperations uoperations)
          (cll/generate-update-tokens container current-shape previous-shape touched false updated-attrs)))))

(defn- generate-keep-orientation
  "Give the new head of a swap, and its subtree, the flips and the rotation
  of the shape it replaces, around the head's own center. They come from
  its parent, as when the parent is rotated, and are not overrides."
  [changes new-shape shape]
  (if-let [orient (cll/orient-like new-shape shape (gsh/shape->center new-shape))]
    (pcb/update-shapes changes
                       (cfh/get-children-ids-with-self (pcb/get-objects changes) (:id new-shape))
                       orient
                       {:ignore-touched true})
    changes))

(defn- generate-swap-in-copy
  "A swap inside a copy keeps the position: the new head takes the place of
  the main shape the old one referred to."
  [changes new-shape shape file page libraries]
  (let [file*    {:id (:id file) :data file}
        objects  (pcb/get-objects changes)
        position (ctf/find-ref-shape file* page libraries shape
                                     :include-deleted? true
                                     :with-context? true)]
    (pcb/update-shapes changes
                       (cfh/get-children-ids-with-self objects (:id new-shape))
                       (ctf/take-position file* libraries objects new-shape (:shape-ref shape) position)
                       {:ignore-touched true})))

(defn generate-new-shape-for-swap
  [changes shape file page libraries id-new-component index target-cell keep-props-values]
  (let [objects      (:objects page)
        position     (gpt/point (:x shape) (:y shape))
        changes      (-> changes
                         (pcb/with-objects objects))
        position     (-> position (with-meta {:cell target-cell}))
        parent       (get objects (:parent-id shape))
        inside-copy? (ctk/in-component-copy? parent)

        [new-shape changes]
        ;; When we make a swap of an item, there can be copies which refer to that item,
        ;; so we want to assign the item id to the new instanciated copy, to mantain that reference
        (cli/generate-instantiate-component changes
                                            objects
                                            (:id file)
                                            id-new-component
                                            position
                                            page
                                            libraries
                                            (:id shape)
                                            (:parent-id shape)
                                            (:frame-id shape)
                                            {(:id shape) (:id shape)} ;; keep the id of the original shape
                                            {:force-frame? true})

        changes
        (cond-> changes
          inside-copy?
          (generate-swap-in-copy new-shape shape file page libraries))

        new-shape
        (get (pcb/get-objects changes) (:id new-shape))

        changes
        (generate-keep-orientation changes new-shape shape)]

    [new-shape (-> changes
                   ;; Restore the properties
                   (pcb/update-shapes [(:id new-shape)] #(d/patch-object % keep-props-values)
                                      {:ignore-touched true})

                   ;; We need to set the same index as the original shape
                   (pcb/change-parent (:parent-id shape) [new-shape] index {:allow-altering-copies true
                                                                            :ignore-touched true}))]))

(defn generate-component-swap
  [changes objects shape file page libraries id-new-component
   index target-cell keep-props-values ignore-swapped?]
  (let [;; When we keep the touched properties, we can't delete the
        ;; swapped children (we will keep them too)
        ignore-swapped-fn
        (if ignore-swapped?
          #(ctk/swapped? (get objects %))
          (constantly false))

        ;; In the cases where the swapped shape was the first element of the masked group it would make the group to loose the
        ;; mask property as part of the sanitization check on generate-delete-shapes, passing "ignore-mask" to prevent this
        [all-parents changes]
        (-> changes
            (cls/generate-delete-shapes
             file page objects (d/ordered-set (:id shape))
             {:allow-altering-copies true :ignore-children-fn ignore-swapped-fn :ignore-mask true :ignore-flows-for #{(:id shape)}}))
        [new-shape changes]
        (-> changes
            (generate-new-shape-for-swap shape file page libraries id-new-component index target-cell keep-props-values))]
    [new-shape all-parents changes]))
