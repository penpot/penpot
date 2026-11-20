;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files-shape-compact-test
  (:require
   [app.common.data :as d]
   [app.common.files.shape-compact :as sc]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; --- Helpers

(defn- make-rect
  [props]
  (cts/setup-shape (merge {:type :rect :x 0 :y 0 :width 10 :height 10} props)))

(defn- make-path
  [props]
  (cts/setup-shape
   (merge {:type :path
           :content [{:command :move-to :params {:x 0 :y 0}}
                     {:command :line-to :params {:x 10 :y 10}}]}
          props)))

;; --- round-values

(t/deftest round-values-integers-unchanged
  (t/is (= 5 (sc/round-values 5)))
  (t/is (= 0 (sc/round-values 0))))

(t/deftest round-values-floats-rounded
  (t/is (= 0.6 (sc/round-values 0.6000000238418579)))
  (t/testing "a large float32 artifact"
    ;; Both runtimes round through a double, so the fourth decimal agrees.
    (t/is (= 4213.6001
             (sc/round-values 4213.60009765625))))
  (t/is (= 0.1235 (sc/round-values 0.12345678))))

(t/deftest round-values-nested-maps
  (let [data {:x 100.1234567 :y 200.9876543
              :meta {:opacity 0.6000000238418579}}]
    (t/is (= {:x 100.1235 :y 200.9877
              :meta {:opacity 0.6}}
             (sc/round-values data)))))

(t/deftest round-values-vectors
  (let [data [1.2345678 2.00001 3]]
    (t/is (= [1.2346 2.0 3] (sc/round-values data)))))

(t/deftest round-values-non-numeric-unchanged
  (t/is (= "hello" (sc/round-values "hello")))
  (t/is (= :foo (sc/round-values :foo)))
  (t/is (= true (sc/round-values true)))
  (t/is (nil? (sc/round-values nil)))
  (let [id (uuid/next)]
    (t/is (= id (sc/round-values id)))))

;; --- compact-shape

(t/deftest compact-shape-removes-nils
  (let [shape {:id (uuid/next) :type :rect :name "test"
               :flip-x nil :flip-y nil
               :fills [] :strokes []}
        c (sc/compact-shape shape)]
    (t/is (not (contains? c :flip-x)))
    (t/is (not (contains? c :flip-y)))
    (t/is (not (contains? c :fills)))
    (t/is (not (contains? c :strokes)))))

(t/deftest compact-shape-removes-identity-transform
  (let [shape (make-rect {:x 100 :y 200 :width 50 :height 30})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :transform)))
    (t/is (not (contains? c :transform-inverse)))))

(t/deftest compact-shape-keeps-non-identity-transform
  (let [shape (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                  (assoc :transform (gmt/matrix 1 0.5 0 1 0 0)))
        c     (sc/compact-shape shape)]
    (t/is (contains? c :transform))
    (t/is (contains? c :transform-inverse))))

(t/deftest compact-shape-keeps-a-non-identity-inverse
  ;; A drifted shape can carry an identity transform next to a derived
  ;; inverse. The reader restores a missing inverse as the identity, so
  ;; dropping that one would rewrite the stored value.
  (let [inverse (gmt/matrix 1 0.5 0 1 0 0)
        shape   (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                    (assoc :transform (gmt/matrix)
                           :transform-inverse inverse))
        c       (sc/compact-shape shape)]
    (t/is (not (contains? c :transform)))
    (t/is (contains? c :transform-inverse))
    (t/is (= inverse (:transform-inverse (sc/expand-shape c))))))

(t/deftest compact-shape-keeps-a-near-identity-shear
  ;; A shear of 0.0008 survives the four-decimal rounding of the format and
  ;; moves a border by units on a big shape, so it is data: the transform, its
  ;; inverse and the geometry all stay in the package.
  (let [transform (gmt/matrix 1 0.0008 0 1 0 0)
        inverse   (gmt/inverse transform)
        shape     (-> (make-rect {:x 100 :y 200 :width 10000 :height 10000})
                      (assoc :transform transform
                             :transform-inverse inverse))
        c         (sc/compact-shape shape)
        e         (sc/expand-shape c)]
    (t/is (contains? c :transform) "the shear is not the identity")
    (t/is (contains? c :transform-inverse))
    (t/is (contains? c :selrect) "the geometry stays")
    (t/is (contains? c :points))
    (t/is (= transform (:transform e)) "and reads back unchanged")
    (t/is (= inverse (:transform-inverse e)))
    (t/is (= (:selrect shape) (:selrect e)))
    (t/is (= (:points shape) (:points e)))))

(t/deftest compact-shape-keeps-a-near-identity-inverse
  (let [inverse (gmt/matrix 1 0.0008 0 1 0 0)
        shape   (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                    (assoc :transform (gmt/matrix)
                           :transform-inverse inverse))
        c       (sc/compact-shape shape)]
    (t/is (not (contains? c :transform)) "the exact identity is still pruned")
    (t/is (contains? c :transform-inverse) "the near-identity inverse stays")
    (t/is (= inverse (:transform-inverse (sc/expand-shape c))))))

(t/deftest compact-shape-drops-an-exact-identity-transform
  (let [shape (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                  (assoc :transform (gmt/matrix)
                         :transform-inverse (gmt/matrix)))
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :transform)))
    (t/is (not (contains? c :transform-inverse)))
    (t/is (not (contains? c :selrect)))
    (t/is (not (contains? c :points)))))

(t/deftest compact-shape-restores-the-children-of-an-empty-container
  ;; A frame or group with no children drops its empty `:shapes` like any other
  ;; empty collection, and the reader puts the vector back: the schema requires
  ;; it on both types, so a compacted empty board must read back valid.
  (doseq [type [:frame :group]]
    (let [shape (make-rect {:id (uuid/next)
                            :name "empty"
                            :type type
                            :parent-id uuid/zero
                            :frame-id uuid/zero})
          c     (sc/compact-shape shape)
          e     (sc/expand-shape c)]
      (t/is (not (contains? c :shapes)) (str type " drops the empty children"))
      (t/is (= [] (:shapes e)) (str type " reads the empty children back"))
      (t/is (sm/validate cts/schema:shape e)
            (str type " stays valid after the round trip")))))

(t/deftest compact-shape-removes-derivable-geometry-rect
  (let [shape (make-rect {:x 100 :y 200 :width 50 :height 30})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :selrect)))
    (t/is (not (contains? c :points)))))

(t/deftest compact-shape-keeps-geometry-with-rotation
  (let [shape (-> (make-rect {:x 100 :y 200 :width 50 :height 30})
                  (assoc :rotation 45))
        c     (sc/compact-shape shape)]
    (t/is (contains? c :selrect))
    (t/is (contains? c :points))))

(t/deftest compact-shape-path-keeps-selrect
  (let [shape (make-path {})
        c     (sc/compact-shape shape)]
    (t/is (contains? c :selrect))
    (t/is (not (contains? c :points)))
    (t/is (contains? c :content))))

(t/deftest compact-shape-removes-page-id
  (let [shape (assoc (make-rect {}) :page-id (uuid/next))
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :page-id)))))

(t/deftest compact-shape-removes-defaults
  (let [shape (make-rect {:rotation 0 :proportion-lock false :blocked false})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :rotation)))
    (t/is (not (contains? c :proportion-lock)))
    (t/is (not (contains? c :blocked)))))

(t/deftest compact-shape-keeps-non-defaults
  (let [shape (make-rect {:rotation 30 :blocked true :opacity 0.5})
        c     (sc/compact-shape shape)]
    (t/is (= 30 (:rotation c)))
    (t/is (true? (:blocked c)))
    (t/is (= 0.5 (:opacity c)))))

(t/deftest compact-shape-drops-the-extended-defaults
  (let [shape (-> (make-rect {:x 0 :y 0 :width 10 :height 10})
                  (assoc :opacity 1
                         :blend-mode :normal
                         :layout-item-absolute false
                         :layout-item-z-index 0
                         :layout-item-margin {:m1 0 :m2 0 :m3 0 :m4 0}))
        c     (sc/compact-shape shape)]
    (doseq [k [:opacity :blend-mode :layout-item-absolute :layout-item-z-index
               :layout-item-margin :r1 :r2 :r3 :r4]]
      (t/is (not (contains? c k)) (str (name k) " dropped")))))

(t/deftest compact-shape-keeps-an-explicit-align-self
  ;; Absence means automatic alignment and the sidebar clears the override with
  ;; nil, so `:stretch` is a value of the shape and not a default of the format:
  ;; the writer never drops it.
  (let [shape (assoc (make-rect {}) :layout-item-align-self :stretch)
        c     (sc/compact-shape shape)]
    (t/is (= :stretch (:layout-item-align-self c)))))

(t/deftest compact-shape-does-not-invent-an-align-self
  (let [shape    (make-rect {})
        compact  (sc/compact-shape shape)
        expanded (sc/expand-shape compact)]
    (t/is (not (contains? shape :layout-item-align-self)))
    (t/is (not (contains? compact :layout-item-align-self)))
    (t/is (not (contains? expanded :layout-item-align-self))
          "the reader leaves the absent key absent")
    (t/is (sm/validate cts/schema:shape expanded)
          "and the shape still validates")))

(t/deftest compact-expand-roundtrip-preserves-every-align-self
  (doseq [value [nil :stretch :center :start :end]]
    (let [shape  (cond-> (make-rect {})
                   (some? value) (assoc :layout-item-align-self value))
          result (sc/expand-shape (sc/compact-shape shape))]
      (t/is (= value (:layout-item-align-self result))
            (str "align-self " value " survives")))))

(t/deftest compact-shape-keeps-an-explicit-grow-type
  ;; Absence and `:fixed` take different branches in the text resize path, so
  ;; the writer keeps the value the shape carries instead of pruning it.
  (let [shape (-> (cts/setup-shape {:type :text :x 0 :y 0 :width 10 :height 10})
                  (assoc :grow-type :fixed))
        c     (sc/compact-shape shape)]
    (t/is (= :fixed (:grow-type c)))))

(t/deftest compact-shape-does-not-invent-a-grow-type
  (let [shape    (cts/setup-shape {:type :text :x 0 :y 0 :width 10 :height 10})
        compact  (sc/compact-shape shape)
        expanded (sc/expand-shape compact)]
    (t/is (not (contains? shape :grow-type))
          "the minimal text carries no grow type")
    (t/is (not (contains? compact :grow-type)))
    (t/is (not (contains? expanded :grow-type))
          "the reader leaves the absent key absent")))

(t/deftest compact-shape-keeps-the-layout-item-sizing
  ;; `:fix` is the renderer's default but not the sync logic's: absence and
  ;; `:fix` take different branches there, so the key is never pruned.
  (let [shape (-> (make-rect {})
                  (assoc :layout-item-h-sizing :fix
                         :layout-item-v-sizing :fix))
        c     (sc/compact-shape shape)]
    (t/is (= :fix (:layout-item-h-sizing c)))
    (t/is (= :fix (:layout-item-v-sizing c)))))

(t/deftest compact-shape-keeps-a-non-default-opacity-and-blend-mode
  (let [c (sc/compact-shape (assoc (make-rect {}) :opacity 0.5 :blend-mode :multiply))]
    (t/is (= 0.5 (:opacity c)))
    (t/is (= :multiply (:blend-mode c)))))

(t/deftest compact-shape-keeps-the-radii-on-a-type-that-has-none
  (let [shape (-> (cts/setup-shape {:type :text :x 0 :y 0 :width 10 :height 10})
                  (assoc :r1 0 :r2 0 :r3 0 :r4 0))
        c     (sc/compact-shape shape)]
    (t/is (= 0 (:r1 c)))
    (t/is (= 0 (:r4 c)))))

(t/deftest compact-shape-drops-empty-touched-and-applied-tokens
  (let [shape (assoc (make-rect {})
                     :touched #{}
                     :applied-tokens {}
                     :component-property-references {})
        c     (sc/compact-shape shape)]
    (t/is (not (contains? c :touched)))
    (t/is (not (contains? c :applied-tokens)))
    (t/is (not (contains? c :component-property-references))))

  (let [c (sc/compact-shape (assoc (make-rect {}) :touched #{:geometry-group}))]
    (t/is (= #{:geometry-group} (:touched c)))))

(t/deftest compact-shape-preserves-component-attrs
  (let [comp-id (uuid/next)
        ref-id  (uuid/next)
        shape   (-> (make-rect {})
                    (assoc :component-id comp-id
                           :component-file (uuid/next)
                           :shape-ref ref-id
                           :touched #{:geometry-group}))
        c       (sc/compact-shape shape)]
    (t/is (= comp-id (:component-id c)))
    (t/is (= ref-id (:shape-ref c)))
    (t/is (= #{:geometry-group} (:touched c)))))

;; --- expand-shape

(t/deftest expand-shape-restores-transform
  (let [compact  {:id (uuid/next) :type :rect :name "test"
                  :x 100 :y 200 :width 50 :height 30
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)]
    (t/is (gmt/matrix? (:transform expanded)))
    (t/is (gmt/matrix? (:transform-inverse expanded)))
    (t/is (true? (gmt/unit? (:transform expanded))))))

(t/deftest expand-shape-restores-selrect-rect
  (let [compact  {:id (uuid/next) :type :rect :name "test"
                  :x 100 :y 200 :width 50 :height 30
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)]
    (t/is (grc/rect? (:selrect expanded)))
    (t/is (= 100 (:x (:selrect expanded))))
    (t/is (= 200 (:y (:selrect expanded))))
    (t/is (= 50 (:width (:selrect expanded))))
    (t/is (= 30 (:height (:selrect expanded))))))

(t/deftest expand-shape-restores-points
  (let [compact  {:id (uuid/next) :type :rect :name "test"
                  :x 100 :y 200 :width 50 :height 30
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)
        points   (:points expanded)]
    (t/is (vector? points))
    (t/is (= 4 (count points)))
    (t/is (every? gpt/point? points))))

(t/deftest expand-shape-path
  (let [compact  {:id (uuid/next) :type :path :name "test"
                  :selrect (grc/make-rect 0 0 10 10)
                  :content [{:command :move-to :params {:x 0 :y 0}}
                            {:command :line-to :params {:x 10 :y 10}}]
                  :frame-id uuid/zero :parent-id uuid/zero}
        expanded (sc/expand-shape compact)]
    (t/is (grc/rect? (:selrect expanded)))
    (t/is (vector? (:points expanded)))
    (t/is (= 4 (count (:points expanded))))))

(t/deftest expand-shape-removes-nil-extmap-keys
  (let [expanded (sc/expand-shape {:id (uuid/next) :type :rect :name "test"
                                   :x 1 :y 1 :width 10 :height 10
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :unknown nil})]
    (t/is (not (contains? expanded :unknown))
          "a nil extmap key does not survive the expansion")))

(t/deftest expand-shape-turns-an-explicit-nil-into-the-default
  ;; nil means "unset" in this model, so the default applies and no nil is left
  (let [expanded (sc/expand-shape {:id (uuid/next) :type :rect :name "test"
                                   :x 1 :y 1 :width 10 :height 10
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :opacity nil})]
    (t/is (= 1 (:opacity expanded)))
    (t/is (some? (:opacity expanded)))))

(t/deftest expand-shape-keeps-the-nil-geometry-of-a-path
  (let [shape    (make-path {})
        expanded (sc/expand-shape (sc/compact-shape shape))]
    (t/is (contains? expanded :x) "a base field stays present")
    (t/is (contains? expanded :width) "a base field stays present")
    (t/is (nil? (:x expanded)))
    (t/is (nil? (:width expanded)))
    (t/is (some? (:content expanded)))))

;; --- Round-trip

(t/deftest compact-expand-roundtrip-rect
  (let [shape    (make-rect {:x 123 :y 456 :width 78 :height 90})
        compact  (sc/compact-shape shape)
        result   (sc/expand-shape compact)]
    (t/is (= (:id shape) (:id result)))
    (t/is (= (:type shape) (:type result)))
    (t/is (= (:name shape) (:name result)))
    (t/is (= (:x shape) (:x result)))
    (t/is (= (:y shape) (:y result)))
    (t/is (= (:width shape) (:width result)))
    (t/is (= (:height shape) (:height result)))
    (t/is (grc/rect? (:selrect result)))
    (t/is (gmt/matrix? (:transform result)))
    (t/is (sm/validate cts/schema:shape result))))

(t/deftest compact-expand-roundtrip-preserves-ids
  (let [id       (uuid/next)
        frame-id (uuid/next)
        parent-id (uuid/next)
        shape    (-> (make-rect {:x 10 :y 20 :width 30 :height 40})
                     (assoc :id id :frame-id frame-id :parent-id parent-id))
        compact  (sc/compact-shape shape)
        result   (sc/expand-shape compact)]
    (t/is (= id (:id result)))
    (t/is (= frame-id (:frame-id result)))
    (t/is (= parent-id (:parent-id result)))))

;; --- compact-page / expand-page

(defn- make-tree-page
  "A page shaped like a real one: a zero-uuid root frame, a frame inside the
  page root, a group inside that frame with one rect, and a shape at the page
  level. Every id, parent and frame follows the product's conventions."
  []
  (let [page-id  (uuid/next)
        frame-id (uuid/next)
        group-id (uuid/next)
        rect-id  (uuid/next)
        free-id  (uuid/next)
        root     (-> (cts/setup-shape
                      {:id uuid/zero :name "Root" :type :frame
                       :x 0 :y 0 :width 1000 :height 1000
                       :parent-id uuid/zero :frame-id uuid/zero})
                     (assoc :shapes [frame-id free-id]))
        frame    (-> (cts/setup-shape
                      {:id frame-id :name "Board" :type :frame
                       :x 0 :y 0 :width 400 :height 300
                       :parent-id uuid/zero :frame-id uuid/zero})
                     (assoc :shapes [group-id]))
        group    (-> (cts/setup-shape
                      {:id group-id :name "Group" :type :group
                       :x 10 :y 10 :width 100 :height 80
                       :parent-id frame-id :frame-id frame-id})
                     (assoc :shapes [rect-id]
                            :constraints-h :left
                            :constraints-v :top))
        rect     (-> (cts/setup-shape
                      {:id rect-id :name "Rect" :type :rect
                       :x 20 :y 20 :width 40 :height 30
                       :parent-id group-id :frame-id frame-id})
                     (assoc :constraints-h :scale
                            :constraints-v :scale))
        free     (cts/setup-shape
                  {:id free-id :name "Free" :type :rect
                   :x 500 :y 500 :width 20 :height 10
                   :parent-id uuid/zero :frame-id uuid/zero})]
    {:id page-id
     :name "Page"
     :objects (d/ordered-map uuid/zero root
                             frame-id frame
                             group-id group
                             rect-id rect
                             free-id free)}))

(defn- compact-page*
  "What the writers do: compact and round every object, then drop the ids."
  [page]
  (-> page
      (update :objects
              (fn [objects]
                (reduce-kv (fn [result id shape]
                             (assoc result id (-> shape sc/compact-shape sc/round-values)))
                           objects
                           objects)))
      (sc/compact-page)))

(def ^:private owned-keys
  [:id :parent-id :frame-id :x :y :width :height :selrect :points :shapes
   :constraints-h :constraints-v])

(defn- page-view
  [page keys]
  (update page :objects
          (fn [objects]
            (reduce-kv (fn [result id shape]
                         (assoc result id (select-keys shape keys)))
                       {}
                       objects))))

(t/deftest compact-page-drops-the-redundant-id
  (let [page   (make-tree-page)
        stored (compact-page* page)]
    (doseq [[_ shape] (:objects stored)]
      (t/is (not (contains? shape :id)) "the key is the identity")
      (t/is (contains? shape :parent-id) "the parent is data, not a derivation")
      (t/is (contains? shape :frame-id) "the frame is data, not a derivation"))))

(t/deftest compact-object-matches-the-page-pass
  ;; The compact writers that serialize one shape at a time use
  ;; `compact-object` instead of materializing the page's objects map; both
  ;; paths must produce the same stored shape.
  (let [page   (make-tree-page)
        stored (compact-page* page)]
    (doseq [id (keys (:objects page))]
      (t/is (= (get-in stored [:objects id])
               (sc/compact-object id (get-in page [:objects id])))
            (str "compact-object of " id " equals the page pass")))))

(t/deftest compact-page-expand-page-roundtrip
  (let [page     (make-tree-page)
        expanded (sc/expand-page (compact-page* page))]
    (t/is (= (set (keys (:objects page))) (set (keys (:objects expanded))))
          "no shape is lost")
    (t/is (= (page-view page owned-keys)
             (page-view expanded owned-keys))
          "ids, tree and geometry read back unchanged")))

(t/deftest compact-page-does-not-invent-a-grow-type
  (let [text-id  (uuid/next)
        text     (cts/setup-shape
                  {:id text-id :name "Text" :type :text
                   :x 0 :y 0 :width 100 :height 20
                   :parent-id uuid/zero :frame-id uuid/zero})
        page     {:id (uuid/next) :objects (d/ordered-map text-id text)}
        expanded (sc/expand-page (compact-page* page))]
    (t/is (not (contains? (get-in expanded [:objects text-id]) :grow-type))
          "a page round trip does not invent the key")))

(t/deftest compact-page-keeps-an-id-that-does-not-match-its-key
  (let [page   (make-tree-page)
        id     (first (keys (:objects page)))
        stored (-> page
                   (update-in [:objects id] assoc :id (uuid/next))
                   (compact-page*))]
    (t/is (contains? (get-in stored [:objects id]) :id)
          "a mismatched id stays in the package instead of being dropped")))

(t/deftest compact-page-keeps-the-tree-ids-verbatim
  (let [page     (make-tree-page)
        rect-id  (->> (:objects page)
                      (keep (fn [[id shape]] (when (= :rect (:type shape)) id)))
                      first)
        frame-id (uuid/next)
        parent-id (uuid/next)
        page     (-> page
                     (update-in [:objects rect-id] assoc :frame-id frame-id)
                     (update-in [:objects rect-id] assoc :parent-id parent-id))
        stored   (compact-page* page)]
    (t/is (= frame-id (get-in stored [:objects rect-id :frame-id]))
          "the frame stays as it was stored")
    (t/is (= parent-id (get-in stored [:objects rect-id :parent-id]))
          "the parent stays as it was stored")
    (t/is (= parent-id (get-in (sc/expand-page stored) [:objects rect-id :parent-id]))
          "and reads back unchanged")))

(t/deftest compact-page-keeps-the-stored-parent-of-a-shape-listed-twice
  ;; A shape cannot be the child of two containers, so this is a corrupt tree.
  ;; The parent is data: it never comes from inverting `:shapes`, so the stored
  ;; value must survive even when the tree disagrees with it.
  (let [page     (make-tree-page)
        rect-id  (->> (:objects page)
                      (keep (fn [[id shape]] (when (= :rect (:type shape)) id)))
                      first)
        frame-id (->> (:objects page)
                      (keep (fn [[id shape]]
                              (when (and (= :frame (:type shape))
                                         (not= id uuid/zero))
                                id)))
                      first)
        page     (update-in page [:objects frame-id :shapes] conj rect-id)
        expanded (sc/expand-page (compact-page* page))]
    (t/is (= (get-in page [:objects rect-id :parent-id])
             (get-in expanded [:objects rect-id :parent-id]))
          "the stored parent survives when another container lists the shape")))

(t/deftest expand-page-keeps-the-fields-of-a-pre-pruning-page
  (let [page     (make-tree-page)
        id       (first (keys (:objects page)))
        drifted  (assoc-in page [:objects id :parent-id] (uuid/next))
        expanded (sc/expand-page drifted)]
    (t/is (= (page-view drifted [:id :parent-id :frame-id])
             (page-view expanded [:id :parent-id :frame-id]))
          "nothing that is present is overwritten")))

(t/deftest compact-page-keeps-the-map-type-and-the-order
  (let [page   (make-tree-page)
        stored (compact-page* page)]
    (t/is (d/ordered-map? (:objects stored)))
    (t/is (= (keys (:objects page)) (keys (:objects stored))))))

(t/deftest compact-page-drops-the-constraints-that-match-the-tree
  (let [page   (make-tree-page)
        stored (compact-page* page)]
    (doseq [[id shape] (:objects stored)]
      (t/is (not (contains? shape :constraints-h)) (str "no constraints-h on " id))
      (t/is (not (contains? shape :constraints-v)) (str "no constraints-v on " id)))))

(t/deftest expand-page-restores-the-derived-constraints
  (let [page     (make-tree-page)
        expanded (sc/expand-page (compact-page* page))
        shapes   (vals (:objects expanded))
        group    (->> shapes (filter #(= :group (:type %))) first)
        rect     (->> shapes
                      (filter #(and (= :rect (:type %))
                                    (= (:id group) (:parent-id %))))
                      first)]
    (t/is (= :left (:constraints-h group)) "a group inside a frame keeps constraints")
    (t/is (= :top (:constraints-v group)))
    (t/is (= :scale (:constraints-h rect)) "a shape inside a group scales")
    (t/is (= :scale (:constraints-v rect)))))

(t/deftest compact-page-keeps-a-constraint-that-does-not-match-the-tree
  (let [page    (make-tree-page)
        rect-id (->> (:objects page)
                     (keep (fn [[id shape]]
                             (when (and (= :rect (:type shape))
                                        (= :scale (:constraints-h shape)))
                               id)))
                     first)
        page    (assoc-in page [:objects rect-id :constraints-h] :left)
        stored  (compact-page* page)]
    (t/is (= :left (get-in stored [:objects rect-id :constraints-h]))
          "the stored value stays")
    (t/is (= :left (:constraints-h (get-in (sc/expand-page stored) [:objects rect-id])))
          "and reads back unchanged")))

;; --- Generative


(t/deftest compact-expand-schema-valid
  (smt/check!
   (smt/for [shape (sg/generator cts/schema:shape)]
     (let [compact  (sc/compact-shape shape)
           expanded (sc/expand-shape compact)]
       (sm/validate cts/schema:shape expanded)))
   {:num 200}))

(t/deftest compact-expand-preserves-geometry
  (smt/check!
   (smt/for [shape (sg/generator cts/schema:shape)]
     (let [compact  (sc/compact-shape shape)
           expanded (sc/expand-shape compact)]
       (and (= (:id shape) (:id expanded))
            (= (:type shape) (:type expanded))
            (= (:name shape) (:name expanded))
            (= (:frame-id shape) (:frame-id expanded))
            (= (:parent-id shape) (:parent-id expanded))
            (= (:component-id shape) (:component-id expanded))
            (= (:component-file shape) (:component-file expanded))
            (= (:shape-ref shape) (:shape-ref expanded))
            ;; An empty set is stored as absent, and absence means "nothing
            ;; touched" everywhere it is read.
            (= (or (:touched shape) #{}) (or (:touched expanded) #{})))))
   {:num 200}))

(t/deftest compact-expand-preserves-content
  (smt/check!
   (smt/for [shape (sg/generator cts/schema:shape)]
     (let [compact  (sc/compact-shape shape)
           expanded (sc/expand-shape compact)]
       (or (not= :path (:type shape))
           (and (= (:id shape) (:id expanded))
                (= (:content shape) (:content expanded))
                (some? (:selrect expanded))
                (vector? (:points expanded))))))
   {:num 50}))

;; --- Defaults scoped by type

(t/deftest expand-shape-restores-only-the-defaults-its-type-carries
  (let [rect  (sc/expand-shape (sc/compact-shape (make-rect {})))
        frame (sc/expand-shape
               (sc/compact-shape
                (cts/setup-shape {:type :frame :x 0 :y 0 :width 10 :height 10})))
        group (sc/expand-shape
               (sc/compact-shape
                (cts/setup-shape {:type :group :x 0 :y 0 :width 10 :height 10})))]

    (t/testing "the frame-only keys are not invented on a rect"
      (doseq [k [:hide-fill-on-export :show-content :hide-in-viewer]]
        (t/is (not (contains? rect k)) (str (name k) " stays absent"))))

    (t/testing "a frame gets them back"
      (doseq [k [:hide-fill-on-export :show-content :hide-in-viewer]]
        (t/is (contains? frame k) (str (name k) " restored"))))

    (t/testing "the mask flag belongs to groups"
      (t/is (not (contains? rect :masked-group)))
      (t/is (contains? group :masked-group)))

    (t/testing "generic defaults are still restored on every type"
      (t/is (= 1 (:opacity rect)))
      (t/is (false? (:blocked rect)))
      (t/is (= {:m1 0 :m2 0 :m3 0 :m4 0} (:layout-item-margin rect))))))
