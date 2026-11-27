;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-modifiers-layout-test
  "Resizes inside nested flex layouts, checked through
  `gm/set-objects-modifiers`: the geometry of every shape after each
  scenario, and that a resize always leaves the layouts settled. Only for
  JS: on the JVM the flex layout calculation multiplies by nil and fails."
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Helpers

(defn- board
  "Add a flex board that hugs its content in both directions, unless
  `attrs` say otherwise."
  [file label parent-label & {:as attrs}]
  (ths/add-sample-shape file label
                        (merge {:type :frame
                                :name (name label)
                                :x 0 :y 0 :width 100 :height 100
                                :layout :flex
                                :layout-flex-dir :row
                                :layout-item-h-sizing :auto
                                :layout-item-v-sizing :auto
                                :layout-gap {:row-gap 10 :column-gap 10}
                                :layout-padding-type :multiple
                                :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}}
                               (when parent-label
                                 {:parent-label parent-label})
                               attrs)))

(defn- rect
  [file label parent-label width height & {:as attrs}]
  (ths/add-sample-shape file label
                        (merge {:type :rect
                                :name (name label)
                                :x 0 :y 0 :width width :height height
                                :parent-label parent-label}
                               attrs)))

(defn- page-objects
  [file]
  (:objects (thf/current-page file)))

(defn- with-fixed-ids
  "Call `build-fn` with ids made in sequence. Layouts walk sets and maps
  keyed by id, so random ids could change the results between runs."
  [build-fn]
  (with-redefs [uuid/next thi/next-uuid]
    (build-fn)))

(defn- apply-modif-tree
  "`objects` with the geometry modifiers of `modif-tree` applied."
  [objects modif-tree]
  (reduce-kv (fn [objects id {:keys [modifiers]}]
               (cond-> objects
                 (contains? objects id)
                 (update id gsh/transform-shape modifiers)))
             objects
             modif-tree))

(defn- run-modifiers
  ([objects modif-tree]
   (run-modifiers objects modif-tree nil))
  ([objects modif-tree params]
   (apply-modif-tree objects (gm/set-objects-modifiers modif-tree objects params))))

(defn- settle
  "`objects` after laying out every layout that holds a leaf shape, through
  a property change that leaves the shapes as they are."
  [objects]
  (run-modifiers
   objects
   (into {}
         (comp (remove #(= uuid/zero (:id %)))
               (filter #(empty? (:shapes %)))
               (map (fn [{:keys [id name]}]
                      [id {:modifiers (ctm/change-property (ctm/empty) :name name)}])))
         (vals objects))))

(defn- resize
  "`objects` after resizing the shapes in `sizes`, a map of label to a map
  with a new `:width`, `:height` or both. With `changed-labels`, only the
  layouts that change, or have those labels, are laid out again."
  ([objects sizes]
   (resize objects sizes nil))
  ([objects sizes changed-labels]
   (run-modifiers
    objects
    (into {}
          (map (fn [[label {:keys [width height]}]]
                 (let [shape (get objects (thi/id label))]
                   [(:id shape) {:modifiers (ctm/change-size shape width height)}])))
          sizes)
    (when (some? changed-labels)
      {:changed-layout-ids (into #{} (map thi/id) changed-labels)}))))

(defn- geometry
  "Map of label to [x y width height] of every shape of `objects`."
  [objects]
  (into (sorted-map)
        (comp (remove #(= uuid/zero (key %)))
              (map (fn [[id shape]]
                     (let [{:keys [x y width height]} (:selrect shape)]
                       [(thi/label id)
                        (mapv #(mth/precision % 2) [x y width height])]))))
        objects))

;; ---- Scenarios

(defn- chips-page
  "A page board holding rows of chips, each chip an icon and a label: hug
  flex layouts three levels deep."
  [file]
  (-> file
      (board :page-board nil :layout-flex-dir :column)
      (board :row-a :page-board)
      (board :chip-a1 :row-a)
      (rect :icon-a1 :chip-a1 16 16)
      (rect :label-a1 :chip-a1 40 16)
      (board :chip-a2 :row-a)
      (rect :icon-a2 :chip-a2 16 16)
      (rect :label-a2 :chip-a2 60 16)
      (board :row-b :page-board)
      (board :chip-b1 :row-b)
      (rect :icon-b1 :chip-b1 16 16)
      (rect :label-b1 :chip-b1 30 16)))

(def ^:private scenarios
  [{:name  :hug-chain
    :build chips-page
    :sizes {:label-a1 {:width 80}}}

   {:name  :hug-chain-shrink
    :build chips-page
    :sizes {:label-a2 {:width 20 :height 30}}}

   {:name  :hug-chain-two-branches
    :build chips-page
    :sizes {:label-a1 {:width 80} :label-b1 {:height 40}}}

   {:name  :hug-chain-new-padding
    :doc   "A padding already changed on a chip, then a resize inside it."
    :build chips-page
    :prepare
    (fn [objects]
      (assoc-in objects [(thi/id :chip-a1) :layout-padding] {:p1 9 :p2 9 :p3 9 :p4 9}))
    :changed #{:chip-a1}
    :sizes {:label-a1 {:width 50}}}

   {:name  :fill-children-in-hug-column
    :build
    (fn [file]
      (-> file
          (board :card nil :layout-flex-dir :column)
          (rect :wide :card 120 20)
          (rect :filler :card 10 20 :layout-item-h-sizing :fill)
          (board :footer :card :layout-item-h-sizing :fill)
          (rect :button :footer 30 20)))
    :sizes {:wide {:width 200}}}

   {:name  :fill-boards-in-fixed-row
    :build
    (fn [file]
      (-> file
          (board :section nil :layout-item-h-sizing :fix :width 300)
          (board :left :section :layout-item-h-sizing :fill :layout-flex-dir :column)
          (rect :left-item :left 40 30)
          (board :right :section :layout-item-h-sizing :fill :layout-flex-dir :column)
          (rect :right-item :right 40 30)
          (rect :right-fill :right 10 10 :layout-item-h-sizing :fill)))
    :sizes {:left-item {:height 90} :right-item {:width 200}}}

   {:name  :centered-chain
    :build
    (fn [file]
      (-> file
          (board :frame nil
                 :layout-item-h-sizing :fix :layout-item-v-sizing :fix
                 :width 400 :height 200
                 :layout-justify-content :center :layout-align-items :center)
          (board :group-row :frame :layout-justify-content :center)
          (rect :c1 :group-row 30 30)
          (board :inner :group-row :layout-justify-content :end :layout-align-items :end)
          (rect :c2 :inner 20 50)
          (rect :c3 :inner 25 25)))
    :sizes {:c2 {:width 60 :height 20}}}

   {:name  :space-between
    :build
    (fn [file]
      (-> file
          (board :bar nil :layout-item-h-sizing :fix :width 360
                 :layout-justify-content :space-between :layout-align-items :center)
          (board :start :bar)
          (rect :logo :start 40 40)
          (board :middle :bar)
          (rect :m1 :middle 30 10)
          (rect :m2 :middle 30 10)
          (board :end :bar)
          (rect :avatar :end 24 24)))
    :sizes {:m1 {:width 70} :logo {:height 60}}}

   {:name  :wrap-row
    :build
    (fn [file]
      (-> file
          (board :tags nil :layout-item-h-sizing :fix :width 200 :layout-wrap-type :wrap)
          (rect :t1 :tags 50 20)
          (rect :t2 :tags 50 20)
          (rect :t3 :tags 50 20)
          (board :t4 :tags)
          (rect :t4-label :t4 30 12)))
    :sizes {:t4-label {:width 120}}}

   {:name  :reverse-and-stretch
    :build
    (fn [file]
      (-> file
          (board :list nil :layout-flex-dir :row-reverse :layout-align-items :stretch)
          (rect :a :list 30 20 :layout-item-v-sizing :fill)
          (board :b :list :layout-flex-dir :column-reverse :layout-align-items :center)
          (rect :b1 :b 20 20)
          (rect :b2 :b 40 20)
          (rect :c :list 30 40)))
    :sizes {:b2 {:width 90 :height 60}}}

   {:name  :absolute-child
    :build
    (fn [file]
      (-> file
          (board :badge-holder nil)
          (rect :content :badge-holder 50 50)
          (rect :badge :badge-holder 10 10 :layout-item-absolute true :x 45 :y -5)))
    :sizes {:content {:width 80}}}

   {:name  :max-width
    :build
    (fn [file]
      (-> file
          (board :outer nil :layout-flex-dir :column)
          (board :limited :outer :layout-item-max-w 120)
          (rect :text-like :limited 60 20)
          (rect :other :outer 50 50)))
    :sizes {:text-like {:width 200}}}

   {:name  :hidden-child
    :build
    (fn [file]
      (-> file
          (board :holder nil)
          (rect :visible :holder 40 40)
          (rect :hidden :holder 40 40 :hidden true)
          (rect :after :holder 40 40)))
    :sizes {:visible {:width 60}}}])

(defn- run-scenario
  "Objects of `scenario` after settling its board and then applying its
  resizes. With `only-changed?`, only the layouts that change, or are in
  its `:changed` labels, are laid out again."
  ([scenario]
   (run-scenario scenario false))
  ([{:keys [build prepare sizes changed]} only-changed?]
   (let [objects (-> (with-fixed-ids #(build (thf/sample-file :file1)))
                     (page-objects)
                     (settle))]
     (-> objects
         (cond-> (some? prepare) (prepare))
         (resize sizes (when only-changed? (or changed #{})))))))

(def ^:private expected
  "Geometry after each scenario, as computed when the tests were written."
  {:absolute-child
   {:badge [45 -5 10 10]
    :badge-holder [0 0 96 66]
    :content [8 8 80 50]}

   :centered-chain
   {:c1 [245.5 79.5 30 30]
    :c2 [167.5 92.5 60 20]
    :c3 [132.5 87.5 25 25]
    :frame [0 0 400 200]
    :group-row [116.5 71.5 167 57]
    :inner [124.5 79.5 111 41]}

   :fill-boards-in-fixed-row
   {:left [155 8 137 106]
    :left-item [163 16 40 90]
    :right [8 8 137 66]
    :right-fill [16 16 121 10]
    :right-item [16 36 200 30]
    :section [0 0 300 16.01]}

   :fill-children-in-hug-column
   {:button [16 16 30 20]
    :card [0 0 216 112]
    :filler [8 54 200 20]
    :footer [8 8 200 36]
    :wide [8 84 200 20]}

   :hidden-child
   {:after [8 8 40 40]
    :hidden [0 0 40 40]
    :holder [0 0 126 56]
    :visible [58 8 60 40]}

   :hug-chain
   {:chip-a1 [128 74 122 32]
    :chip-a2 [16 74 102 32]
    :chip-b1 [16 16 72 32]
    :icon-a1 [226 82 16 16]
    :icon-a2 [94 82 16 16]
    :icon-b1 [64 24 16 16]
    :label-a1 [136 82 80 16]
    :label-a2 [24 82 60 16]
    :label-b1 [24 24 30 16]
    :page-board [0 0 266 122]
    :row-a [8 66 250 48]
    :row-b [8 8 88 48]}

   :hug-chain-new-padding
   {:chip-a1 [128 74 94 34]
    :chip-a2 [16 74 102 32]
    :chip-b1 [16 16 72 32]
    :icon-a1 [197 83 16 16]
    :icon-a2 [94 82 16 16]
    :icon-b1 [64 24 16 16]
    :label-a1 [137 83 50 16]
    :label-a2 [24 82 60 16]
    :label-b1 [24 24 30 16]
    :page-board [0 0 238 124]
    :row-a [8 66 222 50]
    :row-b [8 8 88 48]}

   :hug-chain-shrink
   {:chip-a1 [88 74 82 32]
    :chip-a2 [16 74 62 46]
    :chip-b1 [16 16 72 32]
    :icon-a1 [146 82 16 16]
    :icon-a2 [54 82 16 16]
    :icon-b1 [64 24 16 16]
    :label-a1 [96 82 40 16]
    :label-a2 [24 82 20 30]
    :label-b1 [24 24 30 16]
    :page-board [0 0 186 136]
    :row-a [8 66 170 62]
    :row-b [8 8 88 48]}

   :hug-chain-two-branches
   {:chip-a1 [128 98 122 32]
    :chip-a2 [16 98 102 32]
    :chip-b1 [16 16 72 56]
    :icon-a1 [226 106 16 16]
    :icon-a2 [94 106 16 16]
    :icon-b1 [64 24 16 16]
    :label-a1 [136 106 80 16]
    :label-a2 [24 106 60 16]
    :label-b1 [24 24 30 40]
    :page-board [0 0 266 146]
    :row-a [8 90 250 48]
    :row-b [8 8 88 72]}

   :max-width
   {:limited [8 68 216 36]
    :other [8 8 50 50]
    :outer [0 0 232 112]
    :text-like [16 76 200 20]}

   :reverse-and-stretch
   {:a [8 8 30 106]
    :b [48 8 106 106]
    :b1 [91 16 20 20]
    :b2 [56 46 90 60]
    :c [164 8 30 40]
    :list [0 0 202 122]}

   :space-between
   {:avatar [16 34 24 24]
    :bar [0 0 360 92]
    :end [8 26 40 40]
    :logo [304 16 40 60]
    :m1 [157 41 70 10]
    :m2 [117 41 30 10]
    :middle [109 33 126 26]
    :start [296 8 56 76]}

   :wrap-row
   {:t1 [128 46 50 20]
    :t2 [68 46 50 20]
    :t3 [8 46 50 20]
    :t4 [8 8 136 28]
    :t4-label [16 16 120 12]
    :tags [0 0 200 74]}})

(t/deftest resizes-in-nested-layouts
  (doseq [{:keys [name] :as scenario} scenarios]
    (thi/reset-idmap!)
    (t/testing (str name)
      (let [objects (run-scenario scenario)]
        (t/is (= (get expected name) (geometry objects)))
        (t/is (= (geometry objects) (geometry (settle objects))))))))

;; ---- Random boards

(defn- next-random
  "Next number from `rng*`, below `n`. A linear congruential generator, so
  the boards are the same on every platform."
  [rng* n]
  (let [seed (mod (+ (* @rng* 1103515245) 12345) 2147483648)]
    (reset! rng* seed)
    (mod (quot seed 65536) n)))

(defn- pick
  [rng* coll]
  (nth coll (next-random rng* (count coll))))

(defn- random-board
  "File with a random tree of flex boards and rects, up to four levels
  deep, with random directions, sizings, alignments, gaps and paddings."
  [rng*]
  (let [counter* (atom 0)
        label    #(keyword (str % (swap! counter* inc)))]
    (letfn [(add-board [file label* parent-label depth]
              (let [sizings (if parent-label [:auto :auto :fix :fill] [:auto :fix])
                    file    (board file label* parent-label
                                   :layout-flex-dir (pick rng* [:row :column :row :column :row-reverse])
                                   :layout-item-h-sizing (pick rng* sizings)
                                   :layout-item-v-sizing (pick rng* sizings)
                                   :layout-justify-content (pick rng* [:start :start :center :end :space-between])
                                   :layout-align-items (pick rng* [:start :start :center :end :stretch])
                                   :layout-wrap-type (pick rng* [:nowrap :nowrap :nowrap :wrap])
                                   :width (+ 40 (next-random rng* 200))
                                   :height (+ 40 (next-random rng* 200))
                                   :layout-gap {:row-gap (next-random rng* 12)
                                                :column-gap (next-random rng* 12)}
                                   :layout-padding {:p1 (next-random rng* 10) :p2 (next-random rng* 10)
                                                    :p3 (next-random rng* 10) :p4 (next-random rng* 10)})]
                (reduce (fn [file _]
                          (if (and (< depth 3) (< (next-random rng* 10) 4))
                            (add-board file (label "b") label* (inc depth))
                            (rect file (label "r") label*
                                  (+ 4 (next-random rng* 60))
                                  (+ 4 (next-random rng* 40))
                                  :layout-item-h-sizing (pick rng* [:fix :fix :fix :fill])
                                  :layout-item-v-sizing (pick rng* [:fix :fix :fix :fill]))))
                        file
                        (range (inc (next-random rng* 4))))))]
      (add-board (thf/sample-file :file1) :root nil 0))))

(defn- random-sizes
  "New sizes for one to three random shapes of `objects`. A size that hugs
  its content is left as it is."
  [rng* objects]
  (let [ids (->> (keys objects) (remove #{uuid/zero}) (sort) (vec))]
    (into {}
          (keep (fn [_]
                  (let [shape  (get objects (pick rng* ids))
                        width  (+ 4 (next-random rng* 120))
                        height (+ 4 (next-random rng* 80))
                        size   (cond-> {}
                                 (not= :auto (:layout-item-h-sizing shape)) (assoc :width width)
                                 (not= :auto (:layout-item-v-sizing shape)) (assoc :height height))]
                    (when (seq size)
                      [(thi/label (:id shape)) size]))))
          (range (inc (next-random rng* 3))))))

(t/deftest resizes-leave-layouts-settled
  (doseq [seed (range 1 201)]
    (thi/reset-idmap!)
    (let [rng*    (atom seed)
          objects (-> (with-fixed-ids #(random-board rng*)) (page-objects) (settle))
          sizes   (random-sizes rng* objects)
          resized (resize objects sizes)]
      (t/testing (str "seed " seed)
        (t/is (= (geometry objects) (geometry (settle objects))))
        (t/is (= (geometry resized) (geometry (settle resized))))))))

(def ^:private changed-scenarios
  "Layouts whose attributes changed before a resize elsewhere in their
  board, listed in `:changed`."
  [{:name    :centered-chip-new-padding
    :build   (fn [file]
               (-> (chips-page file)
                   (ths/update-shape :chip-a1 :layout-justify-content :center)))
    ;; Only the sides change, so the centered content doesn't move until
    ;; the chip takes its new size
    :prepare (fn [objects]
               (assoc-in objects [(thi/id :chip-a1) :layout-padding] {:p1 8 :p2 9 :p3 8 :p4 9}))
    :changed #{:chip-a1}
    :sizes   {:label-b1 {:width 50}}}

   {:name    :child-new-margin
    :build   chips-page
    :prepare (fn [objects]
               (assoc-in objects [(thi/id :icon-a1) :layout-item-margin] {:m1 0 :m2 6 :m3 0 :m4 6}))
    :changed #{:icon-a1 :chip-a1}
    :sizes   {:label-b1 {:width 50}}}])

(t/deftest changed-layouts-are-laid-out-again
  (doseq [{:keys [name] :as scenario} changed-scenarios]
    (thi/reset-idmap!)
    (let [full    (geometry (run-scenario scenario false))
          _       (thi/reset-idmap!)
          changed (geometry (run-scenario scenario true))]
      (t/testing (str name)
        (t/is (= full changed))))))

(defn- close-geometry?
  "True when the geometries `a` and `b` differ by less than the rounding
  of moving shapes instead of laying them out again."
  [a b]
  (and (= (set (keys a)) (set (keys b)))
       (every? (fn [[label values]]
                 (every? true? (map #(mth/close? %1 %2 0.011) values (get b label))))
               a)))

(t/deftest resizes-laying-out-only-changed-layouts
  (doseq [{:keys [name] :as scenario} scenarios]
    (thi/reset-idmap!)
    (t/testing (str name)
      (t/is (= (get expected name) (geometry (run-scenario scenario true))))))

  (doseq [seed (range 1 201)]
    (thi/reset-idmap!)
    (let [rng*    (atom seed)
          objects (-> (with-fixed-ids #(random-board rng*)) (page-objects) (settle))
          sizes   (random-sizes rng* objects)]
      (t/testing (str "seed " seed)
        (t/is (close-geometry? (geometry (resize objects sizes))
                               (geometry (resize objects sizes #{}))))))))
