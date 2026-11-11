;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.trim-test
  (:require
   [app.common.files.changes :as ch]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.objects-map :as omap]
   [app.common.types.path :as path]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.worker.selection :as selection]
   [app.worker.snap :as snap]
   [app.worker.trim :as trim]
   [cljs.test :as t :include-macros true]
   [clojure.set :as set]))

(t/use-fixtures :each thi/test-fixture)

(defn- register-bool
  "Computes the content of `:bool` from its children."
  [file]
  (update file :data ch/process-changes
          [{:type :reg-objects :page-id (thf/current-page-id file) :shapes [(thi/id :bool)]}]))

(defn- make-file
  "A component main and copy, a board with a styled group, a text, and a
  bool of a rounded rect and a path."
  []
  (-> (thf/sample-file :file)
      (tho/add-simple-component-with-copy :component :main-root :main-child :copy-root
                                          :copy-root-params {:x 200})
      (tho/add-frame :board :x 400 :y 0 :width 300 :height 300)
      (tho/add-group :group :parent-label :board)
      (tho/add-rect :rect :parent-label :group :x 420 :y 20 :width 50 :height 50
                    :fills (ths/sample-fills-color :fill-color "#ff0000")
                    :strokes [{:stroke-width 4 :stroke-alignment :outer
                               :stroke-color "#000000" :stroke-opacity 1}])
      (tho/add-text :text "Hello")
      (ths/add-sample-shape :bool :type :bool :bool-type :union
                            :x 0 :y 400 :width 100 :height 100)
      (tho/add-rect :rounded :parent-label :bool :x 0 :y 400 :width 60 :height 60
                    :r1 10 :r2 10 :r3 10 :r4 10)
      (ths/add-sample-shape :path :parent-label :bool :type :path
                            :content (path/content [{:command :move-to :params {:x 30 :y 430}}
                                                    {:command :line-to :params {:x 100 :y 430}}
                                                    {:command :line-to :params {:x 100 :y 500}}
                                                    {:command :close-path :params {}}]))
      (register-bool)))

(defn- get-page-objects
  [data page-id]
  (get-in data [:pages-index page-id :objects]))

(defn- replay-full
  [data page-id changes]
  (-> (ch/process-changes data changes false)
      (get-page-objects page-id)
      (trim/trim-objects)))

(deftype ReadRecorder [inner dropped-keys reads-ref]
  ;; A trimmed shape that notes each read of an attribute the trim dropped
  ;; or reduced; writing an attribute makes later reads of it safe.
  ILookup
  (-lookup [this k]
    (-lookup this k nil))
  (-lookup [_ k not-found]
    (when (contains? dropped-keys k)
      (swap! reads-ref conj k))
    (get inner k not-found))

  IAssociative
  (-contains-key? [_ k]
    (when (contains? dropped-keys k)
      (swap! reads-ref conj k))
    (contains? inner k))
  (-assoc [_ k v]
    (ReadRecorder. (assoc inner k v) (disj dropped-keys k) reads-ref))

  IMap
  (-dissoc [_ k]
    (ReadRecorder. (dissoc inner k) (disj dropped-keys k) reads-ref))

  ICollection
  (-conj [this entry]
    (if (vector? entry)
      (-assoc this (nth entry 0) (nth entry 1))
      (reduce-kv -assoc this entry)))

  IEmptyableCollection
  (-empty [_]
    (empty inner))

  ICounted
  (-count [_]
    (count inner))

  ISeqable
  (-seq [_]
    (seq inner))

  IKVReduce
  (-kv-reduce [_ f init]
    (reduce-kv f init inner))

  IMeta
  (-meta [_]
    (meta inner))

  IWithMeta
  (-with-meta [_ m]
    (ReadRecorder. (with-meta inner m) dropped-keys reads-ref))

  IEquiv
  (-equiv [_ other]
    (= inner (if (instance? ReadRecorder other) (.-inner ^js other) other)))

  IHash
  (-hash [_]
    (hash inner))

  IFn
  (-invoke [this k]
    (-lookup this k nil))
  (-invoke [this k not-found]
    (-lookup this k not-found)))

(defn- record-reads
  "Wraps each trimmed shape so reads of attributes that differ from the
  full shape are noted in `reads-ref`."
  [trimmed-objects full-objects reads-ref]
  (reduce-kv (fn [objects id shape]
               (let [full    (get full-objects id)
                     dropped (into #{}
                                   (filter #(and (some? (get full %))
                                                 (not= (get full %) (get shape %))))
                                   (keys full))]
                 (assoc objects id (ReadRecorder. shape dropped reads-ref))))
             {}
             trimmed-objects))

(defn- unwrap-objects
  [objects]
  (update-vals objects #(if (instance? ReadRecorder %) (.-inner ^js %) %)))

(defn- replay-trimmed
  "What the worker does: replay on the trimmed page, then trim the
  candidate objects again. Reads of trimmed attributes go to `reads-ref`."
  [data page-id changes reads-ref]
  (let [page        (get-in data [:pages-index page-id])
        trimmed     (trim/trim-page page)
        old-objects (:objects trimmed)
        recorded    (assoc trimmed :objects (record-reads old-objects (:objects page) reads-ref))
        new-objects (-> (ch/process-changes {:pages-index {page-id recorded}} changes false)
                        (get-page-objects page-id)
                        (unwrap-objects))
        ids         (ch/get-candidate-object-ids old-objects new-objects page-id changes)]
    (trim/retrim-objects old-objects new-objects ids)))

(defn- set-attrs-change
  [page-id id attrs]
  {:type :mod-obj :id id :page-id page-id
   :operations (mapv (fn [[attr val]] {:type :set :attr attr :val val}) attrs)})

(defn- move-change
  [data page-id id delta]
  (let [shape (get (get-page-objects data page-id) id)]
    (set-attrs-change page-id id (-> (gsh/move shape delta)
                                     (select-keys [:x :y :selrect :points])))))

(defn- get-scenarios
  [data page-id]
  (let [objects    (get-page-objects data page-id)
        copy-child (-> objects (get (thi/id :copy-root)) :shapes first)
        text       (get objects (thi/id :text))]
    {"add a shape into a board"
     [{:type :add-obj :id (uuid/next) :page-id page-id
       :parent-id (thi/id :board) :frame-id (thi/id :board)
       :obj (ths/sample-shape nil :type :rect :name "new" :x 450 :y 50 :width 10 :height 10
                              :frame-id (thi/id :board) :parent-id (thi/id :board))}]

     "rename and recolor a shape"
     [(set-attrs-change page-id (thi/id :rect)
                        {:name "renamed"
                         :fills (ths/sample-fills-color :fill-color "#00ff00")})]

     "edit a copied child (touched)"
     [(set-attrs-change page-id copy-child
                        {:fills (ths/sample-fills-color :fill-color "#00ff00")})]

     "change a text"
     [(set-attrs-change page-id (thi/id :text)
                        {:content (txt/change-text (:content text) "Bye")})]

     "delete a group"
     [{:type :del-obj :id (thi/id :group) :page-id page-id}]

     "move a group into another board"
     [{:type :mov-objects :page-id page-id
       :parent-id (thi/id :main-root) :shapes [(thi/id :group)]}]

     "move a shape into a copy (refused)"
     [{:type :mov-objects :page-id page-id
       :parent-id (thi/id :copy-root) :shapes [(thi/id :text)]}]

     "move a child out of a copy (refused)"
     [{:type :mov-objects :page-id page-id
       :parent-id uuid/zero :shapes [copy-child]}]

     "move a copy into its main (nesting loop, refused)"
     [{:type :mov-objects :page-id page-id
       :parent-id (thi/id :main-root) :shapes [(thi/id :copy-root)]}]

     "reorder the children of a copy (refused)"
     [{:type :reorder-children :page-id page-id
       :parent-id (thi/id :copy-root) :shapes (reverse (get-in objects [(thi/id :copy-root) :shapes]))}]

     "move a rect inside a group, then register the group"
     [(move-change data page-id (thi/id :rect) (gpt/point 30 0))
      {:type :reg-objects :page-id page-id :shapes [(thi/id :rect)]}]

     "move a rounded rect inside a bool, then register the bool"
     [(move-change data page-id (thi/id :rounded) (gpt/point 0 -40))
      {:type :reg-objects :page-id page-id :shapes [(thi/id :rounded)]}]

     "fix the children of a board"
     [{:type :fix-obj :id (thi/id :board) :page-id page-id}]}))

(t/deftest replay-on-trimmed-page-matches-full-replay
  (let [file    (make-file)
        data    (:data file)
        page-id (thf/current-page-id file)]
    (doseq [[label changes] (get-scenarios data page-id)]
      (t/testing label
        (t/is (= (replay-full data page-id changes)
                 (replay-trimmed data page-id changes (atom #{}))))))))

;; The touched pass looks for the component root, which only edits the
;; file's components; the worker has none.
(def ^:private touched-pass-reads #{:component-root :main-instance})

(defn- get-set-attrs
  "Attributes the `:mod-obj` changes set; `set-shape-attr` reads their old
  value only to update `:touched`, which the trim drops."
  [changes]
  (into #{}
        (comp (filter #(= :mod-obj (:type %)))
              (mapcat :operations)
              (keep :attr))
        changes))

(t/deftest replay-on-trimmed-page-reads-only-kept-attributes
  (let [file    (make-file)
        data    (:data file)
        page-id (thf/current-page-id file)]
    (doseq [[label changes] (get-scenarios data page-id)]
      (t/testing label
        (let [reads (atom #{})]
          (replay-trimmed data page-id changes reads)
          (t/is (= #{} (set/difference @reads touched-pass-reads (get-set-attrs changes)))))))))

(t/deftest trim-keeps-only-what-the-worker-reads
  (let [file    (make-file)
        page    (thf/current-page file)
        objects (trim/trim-objects (:objects page))]
    (t/testing "fills only tell whether there are any"
      (t/is (= [{}] (get-in objects [(thi/id :rect) :fills]))))
    (t/testing "strokes keep width and alignment"
      (t/is (= [{:stroke-width 4 :stroke-alignment :outer}]
               (get-in objects [(thi/id :rect) :strokes]))))
    (t/testing "names and text content are dropped"
      (t/is (nil? (get-in objects [(thi/id :rect) :name])))
      (t/is (nil? (get-in objects [(thi/id :text) :content]))))
    (t/testing "path content is kept"
      (t/is (some? (get-in objects [(thi/id :path) :content]))))))

(t/deftest trim-reads-objects-maps
  (let [file    (make-file)
        objects (:objects (thf/current-page file))]
    (t/is (= (trim/trim-objects objects)
             (trim/trim-objects (omap/wrap objects))))))

(t/deftest indexes-answer-the-same-on-a-trimmed-page
  (let [file    (make-file)
        page    (thf/current-page file)
        page-id (:id page)
        full    {:selection (selection/add-page {} page)
                 :snap      (snap/add-page {} page)}
        trimmed (let [page (trim/trim-page page)]
                  {:selection (selection/add-page {} page)
                   :snap      (snap/add-page {} page)})
        rects   [(grc/make-rect 0 0 1000 1000)
                 (grc/make-rect 440 40 1 1)
                 (grc/make-rect 420 20 1 1)
                 (grc/make-rect 90 420 1 1)
                 (grc/make-rect 10 480 1 1)]]
    (doseq [rect rects
            opts [{} {:full-frame? true} {:include-frames? true :using-selrect? true}]]
      (t/testing (str "selection " rect " " opts)
        (let [query (merge {:page-id page-id :rect rect} opts)]
          (t/is (= (selection/query (:selection full) query)
                   (selection/query (:selection trimmed) query))))))
    (doseq [frame-id [uuid/zero (thi/id :board)]
            axis     [:x :y]]
      (t/testing (str "snap " frame-id " " axis)
        (t/is (= (snap/query (:snap full) page-id frame-id axis [0 1000])
                 (snap/query (:snap trimmed) page-id frame-id axis [0 1000])))))))
