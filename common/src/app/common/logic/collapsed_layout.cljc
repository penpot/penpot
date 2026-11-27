;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.collapsed-layout
  "Repairs layout children saved at the 0.01 px minimum size (issue 13353).
  It only gives back a size that is known exactly: the sizing of the main of
  a copy, the stored layout of a text, or a hug board whose content got one
  of those. Anything else stays as it is."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(def ^:private min-size
  "Largest size that still counts as the 0.01 px minimum."
  0.011)

(def ^:private narrow-size
  "A text or layout board narrower than this (px) under a restored shape
  counts as damaged: a word needs more, so it is a leftover of the collapse."
  8)

(def ^:private axes
  [:h :v])

(def ^:private axis-attrs
  {:h {:sizing :layout-item-h-sizing :size :width}
   :v {:sizing :layout-item-v-sizing :size :height}})

(defn- layout-child?
  [objects shape]
  (and (ctl/any-layout-immediate-child? objects shape)
       (not (ctl/position-absolute? shape))))

(defn- small?
  [shape axis]
  (let [size (get shape (get-in axis-attrs [axis :size]))]
    (and (number? size) (<= size min-size))))

(defn- measurable?
  "Texts and layout boards size themselves, so a `:fix` at the minimum is
  damage. Any other shape can be thin on purpose (dividers, spacers)."
  [shape]
  (or (cfh/text-shape? shape)
      (and (cfh/frame-shape? shape) (ctl/any-layout? shape))))

(defn- collapsed-axis?
  "With `any-fix?` a `:fix` shape of any type counts: a copy whose main
  fills proves the damage."
  [objects shape axis any-fix?]
  (let [sizing (get-in axis-attrs [axis :sizing])]
    (and (small? shape axis)
         (case (get shape sizing)
           :fix  (or any-fix? (measurable? shape))
           ;; A fill child of a hug parent in a collapsing configuration
           :fill (not= :fill (get (ctl/fix-child-sizing objects {} shape) sizing))
           false))))

(defn- stuck-axes
  "Axes on which the layout child `shape` is stuck at the minimum size."
  [objects shape any-fix?]
  (when (layout-child? objects shape)
    (into #{} (filter #(collapsed-axis? objects shape % any-fix?)) axes)))

(defn- depth
  [objects shape]
  (count (cfh/get-parent-ids objects (:id shape))))

(defn- add-repaired
  [acc id shape-axes]
  (update-in acc [:repaired id] (fnil into #{}) shape-axes))

(defn- resize
  [shape axis value]
  (let [attr (get-in axis-attrs [axis :size])]
    (gsh/transform-shape shape (ctm/change-dimensions-modifiers shape attr value {:ignore-lock? true}))))

;; Copies: exact restore from the main shape

(defn- frozen-axis?
  "The override of a sizing at `:fix`."
  [shape axis]
  (let [sizing (get-in axis-attrs [axis :sizing])]
    (and (= :fix (get shape sizing))
         (contains? (:touched shape) sizing))))

(defn- collapsed-lines?
  "A stored layout of more than one line, each with one character."
  [position-data]
  (and (> (count position-data) 1)
       (every? #(some-> (:text %) count (<= 1)) position-data)))

(defn- damaged-frozen?
  "A frozen override that shows the collapse: a text or layout board narrower
  than `narrow-size` on the axis, or a text whose stored layout is collapsed.
  Any other shape, or a wider one, may keep its override on purpose."
  [shape axis]
  (and (measurable? shape)
       (or (< (get shape (get-in axis-attrs [axis :size])) narrow-size)
           (and (cfh/text-shape? shape)
                (collapsed-lines? (:position-data shape))))))

(defn- copy-restores
  "Subset of `shape-axes` where giving the copy `shape` the sizing of its
  `ref-shape` repairs it: the main fills or hugs there and the parent of the
  copy does not collapse that sizing. The siblings are checked against the
  restored shape."
  [objects shape ref-shape shape-axes]
  (into #{}
        (filter (fn [axis]
                  (let [sizing    (get-in axis-attrs [axis :sizing])
                        value     (get ref-shape sizing)
                        candidate (assoc shape sizing value)]
                    (and (contains? #{:fill :auto} value)
                         (= value (get (ctl/fix-child-sizing (assoc objects (:id shape) candidate) {} candidate)
                                       sizing))))))
        shape-axes))

(defn- restore-axes
  [shape ref-shape shape-axes]
  (let [shape (reduce (fn [shape axis]
                        (let [sizing    (get-in axis-attrs [axis :sizing])
                              size-attr (get-in axis-attrs [axis :size])]
                          ;; A collapsed shape also gets the size of the main, so
                          ;; the layout that follows starts from a valid geometry:
                          ;; a fill child of a board at 0.01 would get a negative size
                          (cond-> (-> shape
                                      (assoc sizing (get ref-shape sizing))
                                      (d/update-when :touched disj sizing))
                            (and (small? shape axis)
                                 (> (get ref-shape size-attr) min-size))
                            (resize axis (get ref-shape size-attr)))))
                      shape
                      shape-axes)]
    (cond-> shape
      (empty? (:touched shape)) (dissoc :touched))))

(defn- restore-copies
  "Restores the sizing of the copies stuck at the minimum, and of the damaged
  frozen shapes inside a restored one. Goes top-down so the shapes inside a
  restored one see their new parent."
  [file page libs {:keys [objects] :as acc}]
  (let [copies (->> (vals objects)
                    (filter #(and (ctk/in-component-copy? %)
                                  (layout-child? objects %)
                                  (some (fn [axis] (or (small? % axis)
                                                       (and (frozen-axis? % axis)
                                                            (damaged-frozen? % axis))))
                                        axes)))
                    (sort-by #(depth objects %)))]
    (reduce
     (fn [{:keys [objects repaired] :as acc} copy]
       (let [shape      (get objects (:id copy))
             inherited  (into #{} (mapcat repaired) (cfh/get-parent-ids objects (:id shape)))
             stuck      (into (stuck-axes objects shape true)
                              (filter #(and (frozen-axis? shape %)
                                            (contains? inherited %)
                                            (damaged-frozen? shape %)))
                              axes)
             ref-shape  (when (seq stuck)
                          (ctf/find-ref-shape file page libs shape))
             restorable (when ref-shape
                          (copy-restores objects shape ref-shape stuck))]
         (if (empty? restorable)
           acc
           (-> acc
               (assoc-in [:objects (:id shape)] (restore-axes shape ref-shape restorable))
               (add-repaired (:id shape) restorable)))))
     acc
     copies)))

;; Texts: the width of the stored layout

(defn- position-data-width
  "Width of the lines in the stored layout of the text `shape`. Nil when
  there is none, or when it is a collapsed layout (one character per line)."
  [{:keys [position-data]}]
  (when (seq position-data)
    (let [min-x (transduce (map :x) min ##Inf position-data)
          max-x (transduce (map #(+ (:x %) (:width %))) max ##-Inf position-data)
          width (- max-x min-x)]
      (when (and (> width min-size) (not (collapsed-lines? position-data)))
        width))))

(defn- fix-texts
  "Gives the text that is not a copy and is stuck at the minimum width the
  width of its stored layout, with one px of margin."
  [{:keys [objects] :as acc}]
  (reduce
   (fn [acc text]
     (let [width (when (contains? (stuck-axes objects text false) :h)
                   (position-data-width text))]
       (if (some? width)
         (-> acc
             (assoc-in [:objects (:id text)]
                       (-> (resize text :h (inc (mth/ceil width)))
                           (assoc :layout-item-h-sizing :fix)))
             (add-repaired (:id text) #{:h}))
         acc)))
   acc
   (filter #(and (cfh/text-shape? %)
                 (not (ctk/in-component-copy? %))
                 (small? % :h))
           (vals objects))))

;; Boards: hug the content that got an exact size

(defn- hug-axes
  "Subset of the `stuck` axes of the board `board` where it can hug: it has
  collapsed descendants, each one repaired by now, and no direct child still
  fills that axis (a hug board would collapse it again)."
  [original {:keys [objects repaired]} board stuck]
  (letfn [(layout-descendants [shape]
            (mapcat (fn [child]
                      (cons child (layout-descendants child)))
                    (remove ctl/position-absolute?
                            (keep #(get objects %) (:shapes shape)))))]
    (let [children (->> (:shapes board)
                        (keep #(get objects %))
                        (remove ctl/position-absolute?))]
      (into #{}
            (filter (fn [axis]
                      (let [sizing    (get-in axis-attrs [axis :sizing])
                            collapsed (filter #(contains? (stuck-axes original (get original (:id %)) true) axis)
                                              (layout-descendants board))]
                        (and (seq collapsed)
                             (every? #(contains? (get repaired (:id %)) axis) collapsed)
                             (not-any? #(= :fill (get % sizing)) children)))))
            stuck))))

(defn- hug-boards
  "Sets a board that is not a copy and is stuck at the minimum to hug, only
  when everything collapsed inside got an exact size. Goes bottom-up, so a
  board that starts to hug counts for its parent."
  [original {:keys [objects] :as acc}]
  (reduce
   (fn [{:keys [objects] :as acc} board]
     (let [board  (get objects (:id board))
           stuck  (stuck-axes objects board false)
           to-hug (when (seq stuck)
                    (hug-axes original acc board stuck))]
       (if (empty? to-hug)
         acc
         (-> acc
             (assoc-in [:objects (:id board)]
                       (reduce #(assoc %1 (get-in axis-attrs [%2 :sizing]) :auto) board to-hug))
             (add-repaired (:id board) to-hug)))))
   acc
   (->> (vals objects)
        (filter #(and (ctl/any-layout? %)
                      (not (ctk/in-component-copy? %))
                      (some (fn [axis] (small? % axis)) axes)))
        (sort-by #(depth objects %) >))))

;; Layout

(defn- layout-roots
  "Top-most layout boards among the repaired `ids` and their parents."
  [objects ids]
  (let [boards (into #{}
                     (mapcat (fn [id]
                               (let [shape  (get objects id)
                                     parent (get objects (:parent-id shape))]
                                 (cond-> []
                                   (ctl/any-layout? shape)  (conj id)
                                   (ctl/any-layout? parent) (conj (:id parent))))))
                     ids)]
    (remove #(some boards (cfh/get-parent-ids objects %)) boards)))

(defn- layout-repaired
  "Runs the layout of the repaired boards, so the shapes get the sizes and
  positions the layout gives them."
  [objects ids]
  (let [roots (layout-roots objects ids)]
    (if (empty? roots)
      objects
      (let [modif-tree (gm/set-objects-modifiers
                        (into {} (map #(vector % {:modifiers (ctm/reflow-modifiers)})) roots)
                        objects)]
        (gsh/apply-objects-modifiers objects modif-tree)))))

(defn repair-page
  "Repairs the layout children of `page` stuck at the minimum size, with the
  `libs` of its file to find the main of the copies. Returns the new
  `:objects`, the very same instance when nothing needed repair, and the
  `:ids` of the repaired shapes."
  [file page libs]
  (let [objects (:objects page)
        acc     (->> {:objects objects :repaired {}}
                     (restore-copies file page libs)
                     (fix-texts)
                     (hug-boards objects))
        ids     (into #{} (keys (:repaired acc)))]
    (if (empty? ids)
      {:objects objects :ids ids}
      {:objects (layout-repaired (:objects acc) ids)
       :ids     ids})))
