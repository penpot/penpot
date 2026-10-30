;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.component-geometry
  "Geometry rules of component copies: where a sync or a variant switch puts
  the shapes of a copy, relative to its main. The geometry operations live in
  `app.common.geom`; this namespace decides which ones to apply."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.component :as ctk]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path.segment :as segment]))

(defn reposition-shape
  "Places `shape`, of the `origin-root` instance, in the `dest-root` instance,
  so the geometry of both can be compared and copied. It moves the shape by
  the offset between the roots and, when the user transformed `dest-root`
  (its geometry is touched), it also applies the rotation and flips of
  `dest-root` relative to `origin-root`, around the center of `dest-root`."
  [shape origin-root dest-root]
  (if (ctk/touched-group? dest-root :geometry-group)
    (gsh/transform-relative shape origin-root dest-root)
    (gsh/translate-relative shape origin-root dest-root)))

(defn- update-geometry-untouched
  "Applies `update-fn` to the shapes without marking them touched."
  [changes ids update-fn container]
  (pcb/concat-changes
   changes
   (-> (pcb/empty-changes)
       (pcb/with-container container)
       (pcb/with-objects (:objects container))
       (pcb/update-shapes ids update-fn {:ignore-touched true}))))

;; --- Component sync

(defn calc-geometry-after-sync
  "Calculates the geometry the sync leaves the copy shape `shape-inst` with.
  Returns `[shape modifiers]`: the shape with that geometry, and the modifiers
  to apply to it, or nil.

  - When the sync resets its geometry (`geometry-kept` false), the shape takes
    `main-geometry`, the geometry of its main placed in the copy.
  - When the user touched its geometry (`geometry-kept` true), the shape keeps
    it, and follows its parent when the sync changes the parent:
    `parent-change` is `[before after]`, or nil for the shape the sync starts
    from."
  [shape-inst main-geometry geometry-kept parent-change]
  (cond
    (not geometry-kept)
    [main-geometry nil]

    (some? parent-change)
    (let [[parent-before parent-after] parent-change
          modifiers (gsh/follow-parent-modifiers shape-inst parent-before parent-after)]
      [(gsh/transform-shape shape-inst modifiers) modifiers])

    :else
    [shape-inst nil]))

(defn generate-transform-untouched
  "Applies `modifiers` to `shape` without marking it touched."
  [changes container shape modifiers]
  (cond-> changes
    (not (ctm/empty? modifiers))
    (update-geometry-untouched [(:id shape)]
                               #(gsh/transform-shape % modifiers)
                               container)))

(defn calc-swap-position-after-sync
  "Calculates where a sync puts a swapped child that the user didn't move:
  the place of the slot's occupant `child-main` in the main, shifted by the
  offset between where the sync leaves the parent (`parent-after`) and where
  the main puts it (`parent-main`)."
  [child-main root-main root-inst parent-after parent-main]
  (let [main-gap (gpt/subtract (grc/rect->point (:selrect parent-after))
                               (grc/rect->point (:selrect parent-main)))]
    (-> (reposition-shape child-main root-main root-inst)
        (:selrect)
        (grc/rect->point)
        (gpt/add main-gap))))

(defn generate-follow-parent
  "Makes `child` and its children follow the change of the parent of `child`
  from `parent-before` to `parent-after`, each one through its own
  constraints, without marking them touched. With `target-pos`, the subtree
  then moves so the top-left of `child` lands on it."
  [changes container child parent-before parent-after target-pos]
  (let [shapes       (gm/follow-parent-shapes (:objects container) (:id child)
                                              parent-before parent-after target-pos)
        shapes-by-id (into {} (map (juxt :id identity)) shapes)]
    (cond-> changes
      (seq shapes)
      (update-geometry-untouched (mapv :id shapes)
                                 #(get shapes-by-id (:id %))
                                 container))))

;; --- Variant switch

(defn switch-previous-geometry
  "Expresses `previous-shape`, a shape of a copy before a variant switch, in
  the frame of the new copy: resized the way its constraints follow the
  change from its old parent to its new one, then moved from `prev-root` to
  `current-root`."
  [previous-shape prev-parent current-parent prev-root current-root]
  (-> previous-shape
      (gsh/resize-with-parent prev-parent current-parent)
      (reposition-shape prev-root current-root)))

(defn equal-geometry?
  "Returns true when `attr` has the same geometry in `shape` and
  `origin-shape`, wherever they are: the size for :selrect and for the
  :content of paths, the form of the points for :points. It compares with a
  tolerance, as `shape` can carry sub-pixel drift from transform modifiers."
  [shape origin-shape attr]
  (or (and (= attr :selrect)
           (grc/close-size? (:selrect shape) (:selrect origin-shape)))
      (and (= attr :points)
           (gpo/close-ignoring-position? (:points shape) (:points origin-shape)))
      (and (= attr :content)
           (cfh/path-shape? shape)
           (let [ca (:content shape)
                 cb (:content origin-shape)]
             (and (some? ca) (some? cb)
                  (let [selrect-a (segment/content->selrect ca)
                        selrect-b (segment/content->selrect cb)]
                    (and (some? selrect-a) (some? selrect-b)
                         (grc/close-size? selrect-a selrect-b))))))))

(defn switch-path-change-value
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

        shape     (gsh/transform-path-content current-shape
                                              transform
                                              (segment/content-center (:content prev-shape)))]
    (get shape attr)))

(defn switch-fixed-layout-geom-change-value
  [prev-shape           ; The shape before the switch
   current-shape        ; The shape after the switch (a clean copy)
   origin-shape         ; The original shape
   attr]
  ;; When there is a layout with fixed h or v sizing, we need
  ;; to keep the width/height (and recalculate selrect and points)
  (let [prev-width     (-> prev-shape :selrect :width)
        current-width  (-> current-shape :selrect :width)
        origin-width   (-> origin-shape :selrect :width)

        prev-height    (-> prev-shape :selrect :height)
        current-height (-> current-shape :selrect :height)
        origin-height  (-> origin-shape :selrect :height)

        h-sizing       (:layout-item-h-sizing prev-shape)
        v-sizing       (:layout-item-v-sizing prev-shape)

        final-width    (if (= :fix h-sizing)
                         current-width
                         (if (= origin-width current-width)
                           prev-width       ;; same-size: preserve override
                           current-width))  ;; different-size: use new component's

        final-height   (if (= :fix v-sizing)
                         current-height
                         (if (= origin-height current-height)
                           prev-height      ;; same-size: preserve override
                           current-height))] ;; different-size: use new component's

    (case attr
      :width
      final-width

      :height
      final-height

      (:selrect :points)
      (get (gsh/set-selrect-size current-shape final-width final-height) attr))))

(defn switch-geom-change-value
  [prev-shape current-shape attr]
  ;; Composite geometry stores absolute coordinates. When preserving a size
  ;; override across variants, keep the target variant's position and only carry
  ;; the previous dimensions; otherwise :x/:y can disagree with :selrect/:points.
  (case attr
    (:selrect :points)
    (get (gsh/set-selrect-size current-shape
                               (-> prev-shape :selrect :width)
                               (-> prev-shape :selrect :height))
         attr)))
