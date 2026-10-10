;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.container-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-shape
  "Build a realistic shape using setup-shape, so it has proper geometric
  data (selrect, points, transform, …) and follows project data standards."
  [id & {:as attrs}]
  (cts/setup-shape (merge {:type :rect
                           :x 0
                           :y 0
                           :width 100
                           :height 100}
                          attrs
                          {:id id})))

(defn- objects-map
  "Build an objects map from a seq of shapes."
  [& shapes]
  (into {} (map (juxt :id identity) shapes)))

;; The sentinel root shape (uuid/zero) recognised by cfh/root?
(def root-id uuid/zero)

(defn- root-shape
  "Create the page-root frame shape (id = uuid/zero, type :frame)."
  []
  (cts/setup-shape {:id root-id
                    :type :frame
                    :x 0
                    :y 0
                    :width 100
                    :height 100}))

;; ---------------------------------------------------------------------------
;; Tests – base cases
;; ---------------------------------------------------------------------------

(t/deftest find-component-main-nil-shape
  (t/testing "returns nil when shape is nil"
    (t/is (nil? (ctc/find-component-main {} nil)))))

(t/deftest find-component-main-root-shape
  (t/testing "returns nil when shape is the page root (uuid/zero)"
    (let [root   (root-shape)
          objects (objects-map root)]
      (t/is (nil? (ctc/find-component-main objects root))))))

(t/deftest find-component-main-no-parent-id
  (t/testing "returns the shape itself when parent-id is nil (v1 component root)"
    (let [id    (uuid/next)
          ;; Simulate a v1 component root: setup-shape produces a full shape,
          ;; then we explicitly clear :parent-id to nil, which is how legacy
          ;; component roots appear in deserialized data.
          shape (assoc (make-shape id) :parent-id nil)
          objects (objects-map shape)]
      (t/is (= shape (ctc/find-component-main objects shape))))))

(t/deftest find-component-main-main-instance
  (t/testing "returns the shape when it is a main-instance"
    (let [parent-id (uuid/next)
          id        (uuid/next)
          parent    (make-shape parent-id)
          shape     (make-shape id :parent-id parent-id :main-instance true)
          objects   (objects-map parent shape)]
      (t/is (= shape (ctc/find-component-main objects shape))))))

(t/deftest find-component-main-instance-head-stops-when-only-direct-child
  (t/testing "returns nil when hitting an instance-head that is not main (only-direct-child? true)"
    (let [parent-id   (uuid/next)
          id          (uuid/next)
          ;; instance-head? ← has :component-id but NOT :main-instance
          shape       (make-shape id
                                  :parent-id parent-id
                                  :component-id (uuid/next))
          parent      (make-shape parent-id)
          objects     (objects-map parent shape)]
      (t/is (nil? (ctc/find-component-main objects shape true))))))

(t/deftest find-component-main-instance-root-stops-when-not-only-direct-child
  (t/testing "returns nil when hitting an instance-root and only-direct-child? is false"
    (let [parent-id   (uuid/next)
          id          (uuid/next)
          ;; instance-root? ← has :component-root true
          shape       (make-shape id
                                  :parent-id parent-id
                                  :component-id (uuid/next)
                                  :component-root true)
          parent      (make-shape parent-id)
          objects     (objects-map parent shape)]
      (t/is (nil? (ctc/find-component-main objects shape false))))))

(t/deftest find-component-main-walks-to-main-ancestor
  (t/testing "traverses ancestors and returns the first main-instance found"
    (let [gp-id    (uuid/next)
          p-id     (uuid/next)
          child-id (uuid/next)
          grandparent (make-shape gp-id :parent-id nil :main-instance true)
          parent      (make-shape p-id  :parent-id gp-id)
          child       (make-shape child-id :parent-id p-id)
          objects     (objects-map grandparent parent child)]
      (t/is (= grandparent (ctc/find-component-main objects child))))))

;; ---------------------------------------------------------------------------
;; Tests – cycle detection (the bug fix)
;; ---------------------------------------------------------------------------

(t/deftest find-component-main-direct-self-loop
  (t/testing "returns nil (no crash) when a shape's parent-id points to itself"
    (let [id    (uuid/next)
          ;; deliberately malformed: parent-id == id (self-loop)
          shape (make-shape id :parent-id id)
          objects (objects-map shape)]
      (t/is (nil? (ctc/find-component-main objects shape))))))

(t/deftest find-component-main-two-node-cycle
  (t/testing "returns nil (no crash) for a two-node circular reference A→B→A"
    (let [id-a  (uuid/next)
          id-b  (uuid/next)
          shape-a (make-shape id-a :parent-id id-b)
          shape-b (make-shape id-b :parent-id id-a)
          objects (objects-map shape-a shape-b)]
      (t/is (nil? (ctc/find-component-main objects shape-a))))))

(t/deftest find-component-main-multi-node-cycle
  (t/testing "returns nil (no crash) for a longer cycle A→B→C→A"
    (let [id-a  (uuid/next)
          id-b  (uuid/next)
          id-c  (uuid/next)
          shape-a (make-shape id-a :parent-id id-b)
          shape-b (make-shape id-b :parent-id id-c)
          shape-c (make-shape id-c :parent-id id-a)
          objects (objects-map shape-a shape-b shape-c)]
      (t/is (nil? (ctc/find-component-main objects shape-a))))))

(t/deftest find-component-main-only-direct-child-with-cycle
  (t/testing "cycle detection works correctly with only-direct-child? false as well"
    (let [id-a  (uuid/next)
          id-b  (uuid/next)
          shape-a (make-shape id-a :parent-id id-b)
          shape-b (make-shape id-b :parent-id id-a)
          objects (objects-map shape-a shape-b)]
      (t/is (nil? (ctc/find-component-main objects shape-a false))))))

;; ---------------------------------------------------------------------------
;; Placement rules: where shapes may go when moved or pasted
;; ---------------------------------------------------------------------------

(defn- placement-file
  "A board with a frame, a group and a rect inside; components :c1 and :c2,
   a copy of :c1; and two variant containers, :v01 with mains :vm1 and :vm2,
   :v02 with mains :vm3 and :vm4."
  []
  (-> (thf/sample-file :file)
      (tho/add-frame :board)
      (tho/add-frame :inner :parent-label :board)
      (tho/add-group :group :parent-label :board)
      (tho/add-rect :rect :parent-label :board)
      (tho/add-simple-component :c1 :main1 :main1-child)
      (tho/add-simple-component :c2 :main2 :main2-child)
      (thc/instantiate-component :c1 :copy1 :children-labels [:copy1-child])
      (thv/add-variant :v01 :vc1 :vm1 :vc2 :vm2)
      (thv/add-variant :v02 :vc3 :vm3 :vc4 :vm4)))

(defn- page-objects
  [file]
  (:objects (thf/current-page file)))

(defn- label
  [id]
  (if (= id uuid/zero) :root (thi/label id)))

(defn- place
  "The labels of the parent and the frame that the shapes `labels` go to
   when moved, or pasted with `pasting?`, into `target`."
  [file target labels & {:keys [pasting? libraries objects]}]
  (let [objects  (or objects (page-objects file))
        children (map #(ths/get-shape file %) labels)]
    (mapv label (ctc/find-valid-parent-and-frame-ids
                 (thi/id target) objects children pasting?
                 (or libraries {(:id file) file})))))

(defn- delete-component
  [file component-label]
  (assoc-in file [:data :components (thi/id component-label) :deleted] true))

(t/deftest placement-of-plain-shapes
  (let [file (placement-file)]
    (t/is (= [:board :board] (place file :board [:main1])))
    (t/testing "inside a group the frame is the group's frame"
      (t/is (= [:group :board] (place file :group [:rect]))))
    (t/testing "the three-argument arity moves, without libraries"
      (t/is (= [:inner :inner]
               (mapv label (ctc/find-valid-parent-and-frame-ids
                            (thi/id :inner) (page-objects file)
                            [(ths/get-shape file :rect)])))))))

(t/deftest placement-inside-a-copy
  (let [file (placement-file)]
    (t/testing "nothing goes into a copy or a shape inside it"
      (t/is (= [:root :root] (place file :copy1 [:rect])))
      (t/is (= [:root :root] (place file :copy1-child [:rect]))))
    (t/testing "a shape may stay in the copy it already is in"
      (t/is (= [:copy1 :copy1] (place file :copy1 [:copy1-child]))))
    (t/testing "but a pasted one is a new shape"
      (t/is (= [:root :root] (place file :copy1 [:copy1-child] :pasting? true))))))

(t/deftest placement-of-a-main
  (let [file (placement-file)]
    (t/testing "a main is not moved into another main, nor inside one"
      (t/is (= [:root :root] (place file :main2 [:main1])))
      (t/is (= [:root :root] (place file :main2-child [:main1]))))
    (t/testing "a plain shape may go into a main"
      (t/is (= [:main2 :main2] (place file :main2 [:rect]))))
    (t/testing "a pasted main becomes a copy, so it may go into a main"
      (t/is (= [:main2 :main2] (place file :main2 [:main1] :pasting? true))))
    (t/testing "unless its component is deleted: then it is pasted as a main"
      (let [libraries {(:id file) (delete-component file :c1)}]
        (t/is (= [:root :root] (place file :main2 [:main1] :pasting? true :libraries libraries)))))
    (t/testing "a cut main, no longer in the page, follows the same rule"
      (let [objects   (dissoc (page-objects file) (thi/id :main1) (thi/id :main1-child))
            libraries {(:id file) (delete-component file :c1)}]
        (t/is (= [:main2 :main2] (place file :main2 [:main1] :pasting? true :objects objects)))
        (t/is (= [:root :root] (place file :main2 [:main1] :pasting? true
                                      :objects objects :libraries libraries)))))))

(t/deftest placement-of-a-variant-container
  (let [file (placement-file)]
    (t/is (= [:board :board] (place file :board [:v01])))
    (t/testing "not inside another variant container"
      (t/is (= [:root :root] (place file :v02 [:v01]))))
    (t/testing "not inside a main, even pasted"
      (t/is (= [:root :root] (place file :main2 [:v01] :pasting? true))))))

(t/deftest placement-inside-a-variant-container
  (let [file (placement-file)]
    (t/testing "only mains go into a variant container"
      (t/is (= [:root :root] (place file :v01 [:rect])))
      (t/is (= [:v01 :v01] (place file :v01 [:main1]))))
    (t/testing "a pasted variant does not go inside a sibling variant"
      (t/is (= [:v01 :v01] (place file :vm2 [:vm1] :pasting? true))))
    (t/testing "but it may go inside a variant of another container"
      (t/is (= [:vm3 :vm3] (place file :vm3 [:vm1] :pasting? true))))))

(t/deftest placement-that-makes-a-component-loop
  (let [file (placement-file)]
    (t/testing "a copy does not go inside its own main"
      (t/is (= [:root :root] (place file :main1 [:copy1] :pasting? true)))
      (t/is (= [:main2 :main2] (place file :main2 [:copy1] :pasting? true))))
    (t/testing "a main does not go inside itself, even pasted"
      (t/is (= [:root :root] (place file :main1-child [:main1])))
      (t/is (= [:root :root] (place file :main1-child [:main1] :pasting? true))))
    (t/testing "a plain shape inside its own child is let through (`relocate-shapes` drops it)"
      (t/is (= [:inner :inner] (place file :inner [:board]))))))

(t/deftest placement-with-a-validation-cache
  (let [file     (placement-file)
        objects  (page-objects file)
        children [(ths/get-shape file :main1)]
        libs     {(:id file) file}
        cache    (ctc/parent-validation-cache objects children libs)]
    (t/is (= [:root :root]
             (mapv label (ctc/find-valid-parent-and-frame-ids
                          (thi/id :main2) objects children false libs cache))))
    (t/is (= [:board :board]
             (mapv label (ctc/find-valid-parent-and-frame-ids
                          (thi/id :board) objects children false libs cache))))))

(t/deftest valid-shape-for-component
  (let [file    (placement-file)
        objects (page-objects file)
        valid?  #(ctc/valid-shape-for-component? objects (ths/get-shape file %))]
    (t/is (valid? :rect))
    (t/is (valid? :board))
    (t/testing "a main can't be inside another main"
      (t/is (not (valid? :main1-child))))
    (t/testing "a main can't hold another main"
      (t/is (not (valid? :v01))))
    (t/testing "a main can't be inside a copy"
      (t/is (not (valid? :copy1-child))))))

(t/deftest first-valid-parent
  (let [file    (placement-file)
        objects (page-objects file)
        first-valid #(label (:id (ctc/get-first-valid-parent objects (thi/id %))))]
    (t/is (= :rect (first-valid :rect)))
    (t/is (= :vm1 (first-valid :vm1)))
    (t/testing "copies and variant containers are skipped"
      (t/is (= :root (first-valid :copy1-child)))
      (t/is (= :root (first-valid :v01))))))

;; ---------------------------------------------------------------------------
;; Heads, containers and instances
;; ---------------------------------------------------------------------------

(t/deftest head-shape-of-nothing-and-of-the-root
  (let [objects (page-objects (placement-file))]
    (t/is (nil? (ctc/get-head-shape objects nil)))
    (t/is (nil? (ctc/get-head-shape objects (get objects uuid/zero))))
    (t/is (= [] (ctc/get-child-heads objects (uuid/next))))))

(t/deftest children-in-instance-stop-at-a-nested-copy
  (let [file    (-> (thf/sample-file :file)
                    (tho/add-nested-component :c1 :main1 :main1-child
                                              :c2 :main2 :nested-head))
        objects (page-objects file)
        labels  #(set (map (comp label :id) (ctc/get-children-in-instance objects (thi/id %))))]
    (t/is (= #{:main1 :main1-child} (labels :main1)))
    (t/is (= #{:main2} (labels :main2)))
    ;; each ancestor comes back once per child below it
    (tm/known-failure "F59" 2 3 (count (ctc/get-children-in-instance objects (thi/id :main1))))))

(t/deftest copy-parent-above-a-plain-parent
  ;; a rect in a plain group inside a copy: the copy is two levels up
  (let [copy-id  (uuid/next)
        group-id (uuid/next)
        copy     (make-shape copy-id :type :frame :component-id (uuid/next)
                             :component-file (uuid/next) :component-root true
                             :shape-ref (uuid/next) :shapes [group-id])
        group    (make-shape group-id :type :group :parent-id copy-id :shapes [])
        rect     (make-shape (uuid/next) :parent-id group-id)
        objects  (objects-map copy group rect)]
    (t/is (ctc/has-any-copy-parent? objects group))
    ;; the walk passes on the parent's id where it expects a shape, so it
    ;; stops after the direct parent
    (tm/known-failure "F63" true false (ctc/has-any-copy-parent? objects rect))))

(t/deftest container-of-a-component
  (let [file      (placement-file)
        container (ctc/get-container (:data file) :component (thi/id :c1))]
    (t/is (ctc/component? container))
    (t/is (not (ctc/page? container)))
    (t/is (= (thi/id :main1) (:main-instance-id container)))))

(t/deftest container-root
  (let [root-id  (uuid/next)
        root     (assoc (make-shape root-id) :parent-id nil)
        child    (make-shape (uuid/next) :parent-id root-id)]
    (t/is (= root (ctc/get-container-root {:objects (objects-map root child)})))))

(t/deftest swap-keep-attrs-keep-auto-sizing-of-a-layout
  (let [shape (make-shape (uuid/next)
                          :type :frame
                          :layout :flex
                          :layout-item-h-sizing :auto
                          :layout-item-v-sizing :auto
                          :layout-item-margin {:m1 10})
        fixed (assoc shape
                     :layout-item-h-sizing :fix
                     :layout-item-v-sizing :fix)]
    (t/is (= [:auto :auto nil]
             ((juxt :layout-item-h-sizing :layout-item-v-sizing :layout-item-margin)
              (ctc/remove-swap-keep-attrs shape))))
    (t/is (= [nil nil]
             ((juxt :layout-item-h-sizing :layout-item-v-sizing)
              (ctc/remove-swap-keep-attrs fixed))))
    (t/testing "auto sizing out of a layout is not kept"
      (t/is (= [nil nil]
               ((juxt :layout-item-h-sizing :layout-item-v-sizing)
                (ctc/remove-swap-keep-attrs (dissoc shape :layout))))))))

(t/deftest component-instance-with-default-options
  (let [file      (placement-file)
        component (ctkl/get-component (:data file) (thi/id :c1))
        [root shapes] (ctc/make-component-instance (thf/current-page file) component
                                                   (:data file) (gpt/point 0 0))]
    (t/is (= (thi/id :c1) (:component-id root)))
    (t/is (:component-root root))
    (t/is (not (ctk/main-instance? root)))
    (t/is (= 2 (count shapes)))
    (t/testing "each shape references its main"
      (t/is (= #{(thi/id :main1) (thi/id :main1-child)} (set (map :shape-ref shapes)))))))
