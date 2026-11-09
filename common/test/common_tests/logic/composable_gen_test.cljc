;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-gen-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.math :as mth]
   [app.common.schema.test :as smt]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.grid :as grid]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.composable.variants :as variants]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [clojure.walk :as walk]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest canonical-retained-references-survive-independent-uuid-allocation
  (let [program     {:world [0 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                     :ops [[:library-add-child 0] [:library-add-child 0] [:library-sync] [:library-undo 0]]}
        situation   (:situation (cg/run program))
        identifiers (atom #{})
        _           (walk/postwalk #(do (when (uuid? %) (swap! identifiers conj %)) %) situation)
        mapping     (into {uuid/zero uuid/zero}
                          (map #(vector % (uuid/next))) (sort-by str (disj @identifiers uuid/zero)))
        renamed     (walk/postwalk #(if (uuid? %) (get mapping %) %) situation)
        expected    (oracle/canonical situation)
        actual      (oracle/canonical renamed)]
    (t/is (true? (oracle/equivalent? expected actual)))
    (t/is (empty? (for [file (vals expected)
                        objects (cf/object-trees file)
                        shape (vals objects)
                        :when (uuid? (:shape-ref shape))]
                    (:shape-ref shape))))))

(t/deftest canonical-retained-references-preserve-identity-and-reject-corruption
  (let [situation (:situation
                   (cg/run {:world [0 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                            :ops [[:library-add-child 0] [:library-add-child 0] [:library-sync]
                                  [:library-undo 0] [:library-undo 0] [:library-add-child 0]]}))
        source-op (cg/resolve-op situation {} [:library-add-child 0])
        owner     (:library-id source-op)
        main-id   (get-in source-op [:shape :id])
        source    (get (cf/all-files situation) owner)
        current   (:shapes (tm/shape-by-id (tm/with-file situation source) main-id))
        copy      (:shape (first (filter #(= main-id (get-in % [:shape :shape-ref])) (cg/tree situation))))
        accepted  (get (cf/reference-libraries situation copy) owner)
        prior     (:shapes (tm/shape-by-id (tm/with-file situation accepted) main-id))
        retained  (set (remove (set current) prior))
        objects   (:objects (thf/current-page (tm/file situation)))
        images    (filterv #(contains? retained (:shape-ref (get objects %))) (:shapes copy))
        id        (first images)
        other     (second images)
        canonical (oracle/canonical situation)
        trees     (mapcat cf/object-trees (vals canonical))
        live-ids  (into #{} (mapcat keys) trees)
        missing   (remove live-ids (keep :shape-ref (mapcat vals trees)))
        replaced  (first (remove (set prior) current))
        corrupt   (fn [changes]
                    (let [file    (tm/file situation)
                          page-id (:id (thf/current-page file))]
                      (oracle/canonical
                       (tm/with-file situation
                         (reduce-kv #(assoc-in %1 [:data :pages-index page-id :objects %2 :shape-ref] %3)
                                    file changes)))))]
    (t/is (= 2 (count retained)))
    (t/is (= [2 2] (sort (vals (frequencies missing)))))
    (t/is (not-any? uuid? missing))
    (doseq [changes [{id (uuid/next)}
                     {id (:shape-ref (get objects other))}
                     {id (:shape-ref (get objects other)) other (:shape-ref (get objects id))}
                     {id replaced}]]
      (t/is (false? (oracle/equivalent? canonical (corrupt changes)))))
    (let [file (first (filter #(= (:name source) (:name %)) (vals canonical)))
          main (first (filter :main-instance (mapcat vals (cf/object-trees file))))]
      (t/is (= (mapv #(conj (:id main) %) (range (count (:shapes main)))) (:shapes main))))))

(t/deftest canonical-retained-versions-do-not-merge-replacements-at-the-same-path
  (let [situation (:situation
                   (cg/run {:world [0 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
                            :ops [[:library-add-child 0] [:reset 0] [:library-undo 0]
                                  [:library-add-child 0] [:reset 1] [:library-undo 0]]}))
        canonical (oracle/canonical situation)
        trees     (mapcat cf/object-trees (vals canonical))
        live-ids  (into #{} (mapcat keys) trees)
        retained  (remove live-ids (keep :shape-ref (mapcat vals trees)))
        objects   (:objects (thf/current-page (tm/file situation)))
        missing   (vec (filter #(and (:shape-ref %) (not (some (fn [tree] (get tree (:shape-ref %)))
                                                               (mapcat cf/object-trees (vals (cf/all-files situation))))))
                               (vals objects)))
        file      (tm/file situation)
        page-id   (:id (thf/current-page file))
        swapped   (-> file
                      (assoc-in [:data :pages-index page-id :objects (:id (first missing)) :shape-ref] (:shape-ref (second missing)))
                      (assoc-in [:data :pages-index page-id :objects (:id (second missing)) :shape-ref] (:shape-ref (first missing))))]
    (t/is (= 2 (count retained) (count (set retained)) (count missing)))
    (t/is (not-any? uuid? retained))
    (t/is (false? (oracle/equivalent? canonical (oracle/canonical (tm/with-file situation swapped)))))))

(t/deftest empty-grid-direction-edits-resolve-and-change-the-requested-shape
  (doseq [depth [0 1]
          scope [nil 1]]
    (let [spec      [1 depth {:layout :grid :grid-empty true}]
          initial   (cg/initial-state spec)
          situation (:situation initial)
          tuple     [:grid-direction 0 0 1 scope]
          resolved  (cg/resolve-op situation {} tuple)
          grids     (filter #(= :grid (get-in % [:shape :layout])) (cg/tree situation))
          target-id (:id (:shape (first (filter #(= :grid (get-in % [:shape :layout]))
                                                (cg/candidates situation {} :edit scope)))))
          real      (r/settle (r/step situation (n/edit-grid (constantly target-id) :direction :column)))
          stepped   (cg/step initial tuple)]
      (t/is (some? (thf/validate-file! (tm/file situation) (cf/libraries situation))))
      (t/is (seq grids))
      (t/is (every? #(and (empty? (get-in % [:shape :shapes]))
                          (= {} (get-in % [:shape :layout-grid-cells]))) grids))
      (t/is (= :column (:layout-grid-dir (tm/shape-by-id real target-id))))
      (t/is (= (oracle/snapshot situation) (oracle/snapshot (r/step real (n/undo)))))
      (t/is (= :executed (get-in resolved [:trace :status])))
      (t/is (= target-id (get-in resolved [:shape :id])))
      (t/is (= 1 (get-in stepped [:coverage :random :grid-direction :changed])))
      (t/is (nil? (:op (cg/resolve-op situation {} [:grid-cell 0 0 0 scope]))))
      (t/is (true? (try (cg/run {:world spec :ops [tuple [:undo] [:redo]]}) true
                        (catch #?(:clj Exception :cljs js/Error) error
                          (select-keys (ex-data error) [:invariant :phase :group :failing-op]))))))))

(t/deftest empty-grid-direction-intent-survives-component-histories
  (doseq [depth [0 1]
          tail [[[:grid-direction 0 0 0 1]] [[:update-main 0]] [[:reset 0]]
                [[:duplicate 0]] [[:detach 0]] [[:add-child 0]]]]
    (let [program {:world [1 depth {:layout :grid :grid-empty true}]
                   :ops (into [[:grid-direction 0 0 1 1]] (concat tail [[:sync] [:undo] [:redo]]))}]
      (t/is (true? (try (cg/run program) true
                        (catch #?(:clj Exception :cljs js/Error) error
                          (select-keys (ex-data error) [:invariant :phase :group :failing-op]))))
            (pr-str program)))))

(t/deftest generated-grid-growth-retains-authored-cells-through-sync-and-undo
  (doseq [direction [:row :column]
          turn-copy? [false true]]
    (let [ops (vec (concat (when (= direction :column) [[:grid-direction 0 0 1 0]])
                           [[:grid-cell 0 0 0 1]]
                           (when turn-copy? [[:grid-direction 0 0 (if (= direction :row) 1 0) 1]])
                           [[:add-child 0] [:add-child 0] [:sync] [:undo] [:redo]]))
          program {:world [1 0 {:layout :grid :layout-children true :grid-square true}]
                   :ops ops}]
      (t/is (true? (try (cg/run program) true
                        (catch #?(:clj Exception :cljs js/Error) error
                          (select-keys (ex-data error) [:invariant :phase :group :failing-op]))))
            (pr-str program)))))

(t/deftest grown-grid-intent-ignores-result-cells-and-copy-track-bounds
  (let [before    (-> (cg/initial-state [1 0 {:layout :grid :layout-children true :grid-square true}])
                      (cg/step [:grid-cell 0 0 0 1]) cg/settle-state
                      (cg/step [:add-child 0]) cg/settle-state)
        id        (get-in (cg/resolve-op (:situation before) (:model before) [:grid-cell 0 0 0 1]) [:shape :id])
        grown     (-> before (cg/step [:add-child 0]) cg/settle-state)
        situation (:situation grown)
        file      (tm/file situation)
        page      (thf/current-page file)
        objects   (:objects page)
        shape     (get objects id)
        source    (get objects (:shape-ref shape))
        cell-id   (:id (first (filter #(= [1 3] [(:row %) (:column %)]) (vals (:layout-grid-cells shape)))))
        extra-id  (uuid/next)
        expected  (:model grown)
        cells     (get-in expected [id :layout-grid-cells :layout-grid-cells])
        predict   (fn [copy reference]
                    (shadow/settle (:model before) (assoc objects id copy) {id reference}))
        check     (fn [copy model]
                    (let [file (assoc-in file [:data :pages-index (:id page) :objects id] copy)]
                      (try (oracle/check (tm/with-file situation file) model) nil
                           (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error))))))]
    (t/is (= 6 (count cells)))
    (t/is (= #{[1 1] [1 2] [1 3] [2 1] [2 2] [2 3]} (set (map (juxt :row :column) cells))))
    (t/is (= (frequencies (:shapes shape)) (frequencies (mapcat :shapes cells))))
    (t/is (nil? (check shape expected)))
    (let [stale   (tm/shape-by-id (:situation before) id)
          pending (predict stale source)]
      (t/is (= (:model before) pending) "A source ahead of its pending copy cannot grow that copy's model")
      (t/is (grid/matches-context? stale (get-in pending [id :layout-grid-cells :layout-grid-cells]))))
    (t/is (= expected (predict shape (update source :layout-grid-cells
                                             #(update-vals % (fn [cell] (assoc cell :align-self :end :shapes [])))))))
    (doseq [corrupt [#(assoc-in % [:layout-grid-cells cell-id :align-self] :end)
                     #(assoc-in % [:layout-grid-cells cell-id :justify-self] :center)
                     #(assoc-in % [:layout-grid-cells cell-id :position] :manual)
                     #(assoc-in % [:layout-grid-cells cell-id :shapes] [])
                     #(assoc-in % [:layout-grid-cells cell-id :shapes] [(first (:shapes shape))])
                     #(assoc-in % [:layout-grid-cells cell-id :column] 4)
                     #(assoc-in % [:layout-grid-cells extra-id]
                                {:id extra-id :row 2 :column 4 :row-span 1 :column-span 1
                                 :position :auto :align-self :auto :justify-self :auto :shapes []})
                     #(update % :layout-grid-columns conj {:type :flex :value 1})
                     #(assoc % :layout-grid-dir :column)]]
      (let [copy  (corrupt shape)
            model (predict copy source)]
        (t/is (= expected model) "Corrupt result values cannot supply expected cells")
        (t/is (= :I9 (check copy model)))))
    (t/is (= :I3 (check (update shape :touched disj :layout-grid-cells) expected)))
    (let [remapped (grid/remap-groups (get expected id) (zipmap (:shapes shape) (reverse (:shapes shape))))
          cells    (get-in remapped [:layout-grid-cells :layout-grid-cells])]
      (t/is (false? (grid/matches-context? (update shape :layout-grid-columns conj {:type :flex :value 1}) cells))))
    (let [prior    (get-in before [:model id :layout-grid-cells :layout-grid-cells])
          ;; Both source and copy lose their track growth, while the new child remains.
          dropped  (assoc shape :layout-grid-columns (vec (take 2 (:layout-grid-columns shape))))
          context  (grid/get-context dropped)
          cells    (grid/settle-cells prior dropped objects context)]
      (t/is (= 6 (count cells)))
      (t/is (= (frequencies (:shapes shape)) (frequencies (mapcat :shapes cells))))
      (t/is (false? (grid/matches-context? dropped cells))))))

(t/deftest grid-growth-context-survives-remapping-and-library-acceptance
  (doseq [program
          (concat
           (for [tail [[[:duplicate 0] [:undo] [:redo]] [[:update-main 0] [:undo] [:redo]]
                       [[:reset 0] [:undo] [:redo]] [[:detach 0] [:undo] [:redo]]]]
             {:world [1 0 {:layout :grid :layout-children true :grid-square true}]
              :ops (into [[:grid-cell 0 0 0 1] [:add-child 0] [:add-child 0] [:sync 1]] tail)})
           [{:world [1 1 {:layout :grid :layout-children true :grid-square true}]
             :ops [[:grid-cell 0 0 0 1] [:add-child 0] [:add-child 0] [:sync 1] [:detach 0] [:undo] [:redo]]}
            {:world [1 0 {:library true :library-depth 0 :library-grid true :stale-libraries true}]
             :ops [[:grid-cell 0 0 0 2] [:library-add-child 0] [:library-add-child 0]
                   [:edit 0 1 1 2] [:library-sync] [:undo] [:redo]]}])]
    (t/is (true? (try (cg/run program) true
                      (catch #?(:clj Exception :cljs js/Error) error
                        (select-keys (ex-data error) [:invariant :phase :group :failing-op]))))
          (pr-str program))))

(t/deftest whole-program-failures-keep-the-same-signature-without-a-skipped-tail
  (doseq [[world ops source?]
          [[[1 0] [[:edit 0 0 1 1] [:edit 0 1 1 1]] false]
           [[1 0 {:library true}] [[:edit 0 0 1 1] [:edit 0 1 1 1]] false]
           [[1 0 {:library true :stale-libraries true}]
            [[:library-edit 0 0 1] [:library-edit 0 1 1]] true]]
          direction [:undo :redo]]
    (let [short-program {:world world :ops ops}
          long-program  (update short-program :ops conj [:restore 0])
          phase         (keyword (str (when source? "source-") "sequence-" (name direction)))
          step          r/step
          previous      (atom nil)
          corrupt       (fn [situation]
                          (let [file    (tm/file situation)
                                page    (thf/current-page file)
                                id      (get-in (first (filter #(get-in % [:shape :main-instance])
                                                               (cg/tree situation))) [:shape :id])
                                changes (cls/generate-update-shapes
                                         (pcb/empty-changes nil (:id page)) [id]
                                         #(assoc % :opacity 0.375) (:objects page) {:ignore-touched true})]
                            (tm/with-file situation (thf/apply-changes file changes))))
          failure       (fn [run]
                          (try (reset! previous nil) (run) nil
                               (catch #?(:clj Exception :cljs js/Error) error
                                 (select-keys (ex-data error) [:invariant :phase :failing-op :trace]))))
          coverage      (atom {})
          signature     (atom nil)
          real          (cg/run long-program)]
      (t/is (map? (cg/run short-program)) "Real whole-program undo/redo passes")
      (t/is (= 2 (get-in real [:executed (if source? :library-edit :edit)])))
      (t/is (= :skip (:status (peek (:trace real)))))
      (with-redefs [r/step (fn [situation op]
                             (let [after (step situation op)
                                   kind  (n/op-kind op)
                                   prior @previous]
                               (reset! previous kind)
                               (if (= direction prior kind) (corrupt after) after)))]
        (doseq [program [long-program short-program]]
          (let [data (failure #(cg/run program))]
            (t/is (= :I7 (:invariant data)))
            (t/is (= phase (:phase data)))
            (t/is (= [:executed :executed]
                     (mapv :status (take 2 (:trace data)))))
            (t/is (nil? (:failing-op data)) "The failure belongs to the whole history")))
        (t/is (some? (failure #(cg/check-program long-program coverage signature))))
        (t/is (= [:I7 nil phase nil] @signature))
        (t/is (some? (failure #(cg/check-program short-program coverage signature)))
              "Removing the skipped tail must preserve the whole-program failure")))))

(t/deftest deferred-failures-name-the-executed-action-and-keep-the-shrink-signature
  (let [short-program {:world [1 0] :ops [[:edit 0 0 1 1] [:update-main 0]]}
        long-program  (update short-program :ops conj [:style 0 3 1 1])
        settle        r/settle
        corrupt       (fn [situation]
                        (let [id   ((n/main-head-of "random-0") situation)
                              main (tm/shape-by-id situation id)]
                          (if (= "#ff0000" (get-in main [:fills 0 :fill-color]))
                            (let [file    (tm/file situation)
                                  page    (thf/current-page file)
                                  ids     (mapv (comp :id :shape)
                                                (filter #(= (:component-id main) (get-in % [:shape :component-id]))
                                                        (cg/tree situation)))
                                  changes (cls/generate-update-shapes
                                           (pcb/empty-changes nil (:id page)) ids
                                           #(assoc % :fills (ths/sample-fills-color :fill-color "#FFFFFF"))
                                           (:objects page) {:ignore-touched true})]
                              (tm/with-file situation (thf/apply-changes file changes)))
                            situation)))
        failure       (fn [run]
                        (try (run) nil
                             (catch #?(:clj Exception :cljs js/Error) error
                               (select-keys (ex-data error) [:invariant :phase :failing-op :trace]))))
        coverage      (atom {})
        signature     (atom nil)]
    (t/is (map? (cg/run long-program)) "The real program passes before corrupting settlement")
    (with-redefs [r/settle (fn
                             ([situation] (corrupt (settle situation (r/pending-components situation))))
                             ([situation ids] (corrupt (settle situation ids))))]
      (doseq [program [long-program short-program]]
        (let [data (failure #(cg/run program))]
          (t/is (= :I9 (:invariant data)))
          (t/is (= :requested-update-main (:phase data)))
          (t/is (= [:edit :update-main] (mapv :kind (:trace data))))
          (t/is (= [:update-main 0] (:failing-op data)))))
      (t/is (some? (failure #(cg/check-program long-program coverage signature))))
      (t/is (= [:I9 nil :requested-update-main :update-main] @signature))
      (t/is (some? (failure #(cg/check-program short-program coverage signature)))
            "Removing an unexecuted tuple must preserve the failure"))
    (with-redefs [n/change-property (fn [target _property _value] (n/reset target))]
      (let [data (failure #(cg/run short-program))]
        (t/is (= :I9 (:invariant data)))
        (t/is (= :requested-scalar (:phase data)))
        (t/is (= [:edit 0 0 1 1] (:failing-op data))))
      (t/is (true? (cg/check-program short-program coverage signature))
            "A genuinely different shrink failure remains rejected"))))

(t/deftest immediate-failures-name-the-action-being-executed
  (let [program {:world [1 0] :ops [[:edit 0 0 1 1] [:update-main 0]]}
        failure (with-redefs [n/update-main n/reset]
                  (try (cg/run program) nil
                       (catch #?(:clj Exception :cljs js/Error) error (ex-data error))))]
    (t/is (= :I9 (:invariant failure)))
    (t/is (= :requested-update-main (:phase failure)))
    (t/is (= [:edit] (mapv :kind (:trace failure))))
    (t/is (= [:update-main 0] (:failing-op failure)))))

(t/deftest update-main-intent-rejects-losing-the-copy-value-while-every-copy-agrees
  (doseq [program [{:world [1 0] :ops [[:edit 0 0 1 1] [:update-main 0]]}
                   {:world [1 0 {:library true}] :ops [[:edit 0 0 1 2] [:update-main 0]]}
                   {:world [1 0 {:library true :library-depth 0 :library-grid true}]
                    :ops [[:grid-cell 0 0 0 2] [:update-main 0]]}
                   {:world [1 0 {:layout :grid :layout-children true :grid-square true}]
                    :ops [[:grid-cell 0 0 0 1] [:update-main 0]]}]]
    (t/is (map? (cg/run program)) "Real update-main preserves the authored value")
    (let [failure (with-redefs [n/update-main n/reset]
                    (try (cg/run program) nil
                         (catch #?(:clj Exception :cljs js/Error) error (ex-data error))))]
      (t/is (= :I9 (:invariant failure)) (pr-str failure))
      (t/is (= :requested-update-main (:phase failure)) (pr-str failure)))))

(t/deftest update-main-checks-scalar-text-and-geometry-intent
  (let [styles   (for [property (range (count cg/style-edits))]
                   {:world [1 0 {:layout (if (= property 26) :grid :flex)
                                 :layout-children true :layout-item-margins true}]
                    :ops [[:style 0 property (if (= property 27) 0 1) 1] [:update-main 0]]})
        texts    (for [unit (range (count cg/text-style-edits))]
                   {:world [1 0 {:text true :text-styles true}]
                    :ops [[:text-style 1 unit 1] [:update-main 0]]})
        geometry (for [action (range 1 6)]
                   {:world [1 0] :ops [[:transform 0 action 2 4] [:update-main 0]]})
        programs (concat styles texts geometry
                         [{:world [1 0 {:text true :text-styles true}]
                           :ops [[:text 1 0 1] [:update-main 0]]}
                          {:world [1 0] :ops [[:transform 0 0 1 2] [:update-main 0]]}])]
    (doseq [program programs]
      (let [result  (cg/run program)
            failure (with-redefs [n/update-main n/reset]
                      (try (cg/run program) nil
                           (catch #?(:clj Exception :cljs js/Error) error (ex-data error))))]
        (t/is (= 1 (get-in result [:coverage :random :update-main :changed])) (pr-str program))
        (t/is (= :I9 (:invariant failure)) (pr-str [program failure]))
        (t/is (= :requested-update-main (:phase failure)) (pr-str [program failure]))))))

(t/deftest update-main-intent-survives-until-deferred-sync-finishes
  (let [edited    (-> (cg/initial-state [1 0]) (cg/step [:edit 0 0 1 1]) cg/settle-state)
        id        (get-in (cg/resolve-op (:situation edited) (:model edited) [:update-main 0]) [:shape :id])
        source    (tm/shape-by-id (:situation edited) id)
        main      (tm/shape-by-id (:situation edited) (:shape-ref source))
        ids       (mapv (comp :id :shape) (filter #(= (:component-id source) (get-in % [:shape :component-id]))
                                                  (cg/tree (:situation edited))))
        updated   (cg/step edited [:update-main 0])
        settle    r/settle
        corrupt   (fn [situation]
                    (let [file    (tm/file situation)
                          page    (thf/current-page file)
                          changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page)) ids
                                                              #(assoc % :fills (:fills main)) (:objects page)
                                                              {:ignore-touched true})]
                      (tm/with-file situation (thf/apply-changes file changes))))
        check     (fn [synchronize]
                    (with-redefs [r/settle (fn
                                             ([situation] (corrupt (settle situation (r/pending-components situation))))
                                             ([situation components] (corrupt (settle situation components))))]
                      (try (cg/settle-state (cond-> updated synchronize (cg/step [:sync]))) nil
                           (catch #?(:clj Exception :cljs js/Error) error (ex-data error)))))]
    (t/is (= (:fills source) (:fills (tm/shape-by-id (:situation updated) (:id main)))))
    (doseq [synchronize [false true]]
      (let [failure (check synchronize)]
        (t/is (= :I9 (:invariant failure)) (pr-str failure))
        (t/is (= :requested-update-main (:phase failure)) (pr-str failure))))))

(t/deftest authored-grid-cell-values-are-checked-against-intent-instead-of-the-main
  (let [situation (cg/world [1 0 {:layout :grid :layout-children true}])
        id        ((n/nth-copy-head-of "random-0" 0) situation)
        shape     (tm/shape-by-id situation id)
        cell-id   (:id (first (sort-by (juxt :row :column) (vals (:layout-grid-cells shape)))))
        intended  (assoc-in shape [:layout-grid-cells cell-id :align-self] :end)
        file      (tm/file situation)
        page      (thf/current-page file)
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page)) [id]
                                              (constantly intended) (:objects page) {})
        edited    (tm/with-file situation (thf/apply-changes file changes))
        model     {id {:layout-grid-cells (shadow/values intended :layout-grid-cells)}}
        failure   (try (oracle/check edited model) nil
                       (catch #?(:clj Exception :cljs js/Error) error (ex-data error)))]
    (t/is (= #{:layout-grid-cells} (:touched (tm/shape-by-id edited id))))
    (t/is (nil? failure) (pr-str failure))))

(t/deftest grid-overrides-survive-component-histories
  (doseq [ops [[[:grid-direction 0 0 1 1] [:grid-direction 0 0 1 0]
                [:grid-direction 0 0 0 0] [:update-main 0]
                [:grid-direction 0 0 0 1] [:reset 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:grid-cell 0 1 1 0] [:grid-cell 0 0 2 0]
                [:update-main 0] [:grid-cell 0 1 2 1] [:reset 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:duplicate 0] [:undo] [:redo] [:update-main 0]]
               [[:grid-direction 0 0 1 1] [:add-child 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:remove-child 0] [:undo] [:redo]]
               [[:grid-cell 0 0 0 1] [:detach 0] [:undo] [:redo]]]]
    (let [result (cg/run {:world [1 0 {:layout :grid :layout-children true :grid-square true}] :ops ops})]
      (doseq [kind (set (map first ops))]
        (t/is (zero? (get-in result [:coverage :random kind :skipped] 0)) (str kind " in " ops))
        (when-not (#{:undo :redo} kind)
          (t/is (pos? (get-in result [:coverage :random kind :changed] 0)) (str kind " in " ops)))))))

(t/deftest grid-directions-track-both-groups-and-reject-dropped-main-edits
  (let [initial  (cg/initial-state [1 0 {:layout :grid :layout-children true :grid-square true}])
        resolved (cg/resolve-op (:situation initial) {} [:grid-direction 0 0 1 1])
        id       (get-in resolved [:shape :id])
        edited   (-> initial (cg/step [:grid-direction 0 0 1 1]) cg/settle-state)
        file     (tm/file (:situation edited))
        path     [:data :pages-index (:id (thf/current-page file)) :objects id]
        check    (fn [file]
                   (try (oracle/check (tm/with-file (:situation edited) file) (:model edited)) nil
                        (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error)))))
        main     (tm/shape-by-id (:situation initial) (:shape-ref (:shape resolved)))
        dropped  (fn [action value]
                   (try (oracle/check-requested-grid main main action value) nil
                        (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error)))))]
    (t/is (= #{:layout-grid-dir :layout-grid-cells} (set (keys (get (:model edited) id)))))
    (t/is (= :I9 (check (assoc-in file (conj path :layout-grid-dir) :row))))
    (t/is (= :I3 (check (update-in file (conj path :touched) disj :layout-grid-dir))))
    (t/is (= :I3 (check (update-in file (conj path :touched) disj :layout-grid-cells))))
    (t/is (= :I9 (dropped :direction :column)))))

(t/deftest detaching-an-outer-copy-remaps-inherited-grid-cell-intent
  (let [result (cg/run {:world [1 1 {:layout :grid :layout-children true :grid-square true}]
                        :ops [[:grid-cell 0 0 0 1] [:detach 0] [:undo] [:redo]]})]
    (t/is (= 1 (get-in result [:coverage :random :grid-cell :changed])))
    (t/is (= 1 (get-in result [:coverage :random :detach :changed])))))

(t/deftest grid-intent-rejects-corrupt-values-members-and-missing-groups
  (let [initial   (cg/initial-state [1 0 {:layout :grid :layout-children true :grid-square true}])
        resolved  (cg/resolve-op (:situation initial) {} [:grid-cell 0 0 0 1])
        id        (get-in resolved [:shape :id])
        cell-id   (:id (first (sort-by (juxt :row :column) (vals (get-in resolved [:shape :layout-grid-cells])))))
        edited    (-> initial (cg/step [:grid-cell 0 0 0 1]) cg/settle-state)
        situation (:situation edited)
        file      (tm/file situation)
        path      [:data :pages-index (:id (thf/current-page file)) :objects id]
        invariant (fn [file]
                    (try (oracle/check (tm/with-file situation file) (:model edited)) nil
                         (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error)))))]
    (t/is (= :I9 (invariant (assoc-in file (into path [:layout-grid-cells cell-id :align-self]) :start))))
    (t/is (= :I9 (invariant (assoc-in file (into path [:layout-grid-cells cell-id :justify-self]) :end))))
    (t/is (= :I9 (invariant (assoc-in file (into path [:layout-grid-cells cell-id :shapes]) []))))
    (t/is (= :I4 (invariant (update-in file (conj path :shapes) #(vec (reverse %))))))
    (t/is (= :I3 (invariant (update-in file (conj path :touched) disj :layout-grid-cells))))))

(t/deftest nesting-a-copy-alongside-a-hidden-layout-child-stays-settled
  (t/is (map? (cg/run {:world [3 0 {:text true :text-styles true :layout :flex}]
                       :ops [[:delete 9 9 1 2] [:text 5 9 1 3] [:style 3 1 1]
                             [:restore 5 8 1 6] [:nest 5 6 0 2] [:undo] [:redo]]}))))

(t/deftest swapping-a-copy-with-authored-position-constraints-stays-settled
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout :flex :layout-copies true}]
                       :ops [[:style 12 9 11 6] [:delete 10 14 10 12] [:swap 17 2 14 5]
                             [:undo] [:redo]]}))))

(t/deftest nesting-a-swapped-copy-in-a-blocked-main-authors-its-lock
  (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout nil :layout-copies true}]
                       :ops [[:style 3 2 1] [:swap 0 0 0 0] [:nest 0 0 0 0]
                             [:undo] [:redo]]}))))

(t/deftest nesting-lock-expectations-reject-a-missing-lock-or-override
  (let [state (reduce cg/step
                      (cg/initial-state [2 0 {:library true :variants true :rich-variants true
                                              :text true :text-styles true :layout nil :layout-copies true}])
                      [[:style 3 2 1] [:swap 0 0 0 0]])
        id (get-in (cg/resolve-op (:situation state) (:model state) [:nest 0]) [:shape :id])
        nested (-> state (cg/step [:nest 0]) cg/settle-state)
        situation (:situation nested)
        model (:model nested)
        file (tm/file situation)
        path [:data :pages-index (:id (thf/current-page file)) :objects id]
        invariant (fn [file]
                    (try (oracle/check (tm/with-file situation file) model) nil
                         (catch #?(:clj Exception :cljs js/Error) error
                           (:invariant (ex-data error)))))]
    (t/is (= {:blocked true} (get-in model [id :modifiable-group])))
    (t/is (= :I9 (invariant (assoc-in file (conj path :blocked) false))))
    (t/is (= :I3 (invariant (update-in file (conj path :touched) disj :modifiable-group))))))

(t/deftest generated-swap-can-target-a-loaded-library-component
  (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true
                                    :library-variants true :variants true}]
                       :ops [[:delete 0] [:swap 3]]}))))

(t/deftest scalar-style-edits-author-their-own-group-and-propagate-from-mains
  (doseq [property (range 14)]
    (let [result (cg/run {:world [2 1 {:layout :flex}]
                          :ops [[:style 0 property 1 1] [:style 1 property 1 0]
                                [:sync] [:undo] [:redo]]})]
      (t/is (pos? (get-in result [:coverage :random :style :changed] 0))
            (str "style property " property)))))

(t/deftest frame-only-styles-target-frames-in-each-edit-scope
  (let [situation (cg/world [2 1 {:library true :text true}])]
    (doseq [property [5 6] scope [0 1 2]]
      (let [resolved (cg/resolve-op situation {} [:style 1 property 1 scope])]
        (t/is (= :frame (get-in resolved [:shape :type]))
              (str "property " property ", scope " scope))))))

(t/deftest generated-effect-and-corner-edits-have-distinct-inputs
  (let [situation (cg/world [2 1 {}])]
    (doseq [[index attr] [[14 :shadow] [15 :blur] [16 :background-blur]
                          [17 :r2] [18 :r3] [19 :r4]]]
      (let [resolved (cg/resolve-op situation {} [:style 0 index 1 1])]
        (t/is (= attr (:property resolved)))
        (t/is (some? (:op resolved)))
        (when (= attr (:property resolved))
          (t/is (not= (get (:shape resolved) attr) (:value resolved))))))))

(t/deftest effect-and-corner-overrides-reject-lost-values-or-groups
  (doseq [[index attr group] [[14 :shadow :shadow-group] [15 :blur :blur-group]
                              [16 :background-blur :blur-group] [17 :r2 :radius-group]
                              [18 :r3 :radius-group] [19 :r4 :radius-group]]]
    (let [initial   (cg/initial-state [1 0 {}])
          resolved  (cg/resolve-op (:situation initial) {} [:style 0 index 1 1])
          shape     (:shape resolved)
          expected  (shadow/values (assoc shape attr (:value resolved)) group)
          edited    (-> initial (cg/step [:style 0 index 1 1]) cg/settle-state)
          situation (:situation edited)
          model     (:model edited)
          file      (tm/file situation)
          path      [:data :pages-index (:id (thf/current-page file)) :objects (:id shape)]
          invariant (fn [file]
                      (try (oracle/check (tm/with-file situation file) model) nil
                           (catch #?(:clj Exception :cljs js/Error) error
                             (:invariant (ex-data error)))))]
      (t/is (= expected (get-in model [(:id shape) group])))
      (t/is (= :I9 (invariant (update-in file path
                                         #(merge (dissoc % attr) (select-keys shape [attr]))))))
      (t/is (= :I3 (invariant (update-in file (conj path :touched) disj group)))))))

(t/deftest main-effect-and-corner-edits-reach-clean-copies-and-spare-overrides
  (doseq [property (range 14 20)]
    (let [initial    (cg/initial-state [1 0 {}])
          situation  (:situation initial)
          resolved   (cg/resolve-op situation {} [:style 0 property 1 1])
          copy       (:shape resolved)
          attr       (:property resolved)
          main-index (first (keep-indexed (fn [index entry]
                                            (when (= (:shape-ref copy) (get-in entry [:shape :id])) index))
                                          (cg/candidates situation {} :edit 0)))
          main-edit  [:style main-index property 0 0]
          copies     (filter #(= (:shape-ref copy) (get-in % [:shape :shape-ref])) (cg/tree situation))
          inherited  (-> initial (cg/step main-edit) cg/settle-state)
          overridden (-> inherited (cg/step [:style 0 property 1 1]) cg/settle-state)
          updated    (-> overridden (cg/step (assoc main-edit 3 2)) cg/settle-state)
          requested  (:value (cg/resolve-op situation {} (assoc main-edit 3 2)))]
      (t/is (some? main-index))
      (t/is (= 2 (count copies)))
      (t/is (not= requested (:value resolved)))
      (doseq [{:keys [shape]} copies]
        (let [actual (tm/shape-by-id (:situation updated) (:id shape))]
          (t/is (= (if (= (:id copy) (:id shape)) (:value resolved) requested) (get actual attr)))
          (t/is (= (= (:id copy) (:id shape)) (boolean (seq (:touched actual)))))))
      (t/is (map? (cg/run {:world [1 0 {}]
                           :ops [main-edit [:style 0 property 1 1] (assoc main-edit 3 2)
                                 [:undo] [:redo]]}))))))

(t/deftest generated-effects-and-corners-survive-component-operations
  (doseq [property (range 14 20)]
    (t/is (map? (cg/run {:world [2 1 {:variants true :rich-variants true}]
                         :ops [[:style 0 property 1 1] [:style 1 property 1 0]
                               [:undo] [:redo] [:update-main 0]
                               [:style 0 property 0 1] [:reset 0]
                               [:style 0 property 1 1] [:detach 0]
                               [:undo] [:redo]]})))))

(t/deftest generated-layout-container-edits-have-distinct-inputs
  (let [situation (cg/world [1 0 {:layout :flex}])]
    (doseq [[index attr] [[20 :layout-flex-dir] [21 :layout-wrap-type]
                          [22 :layout-justify-content] [23 :layout-align-content]
                          [24 :layout-gap-type] [25 :layout-padding-type]]]
      (let [resolved (cg/resolve-op situation {} [:style 0 index 1 1])]
        (t/is (= attr (:property resolved)))
        (t/is (some? (:op resolved)))
        (t/is (not= (get (:shape resolved) attr) (:value resolved)))))))

(t/deftest layout-container-fixture-has-several-children-before-copying
  (doseq [layout [:flex :grid]]
    (let [situation (cg/world [1 0 {:layout layout :layout-children true}])
          main-id   ((n/main-head-of "random-0") situation)
          entries   (cg/tree situation)
          heads     (filter #(or (= main-id (get-in % [:shape :id]))
                                 (= main-id (get-in % [:shape :shape-ref]))) entries)
          ids       (map (comp :id :shape) entries)]
      (t/is (= 3 (count heads)))
      (t/is (= (count ids) (count (set ids))))
      (doseq [{:keys [shape]} heads]
        (t/is (= 3 (count (:shapes shape))))
        (t/is (= {:row-gap 5 :column-gap 9} (:layout-gap shape)))
        (t/is (= {:p1 3 :p2 6 :p3 9 :p4 12} (:layout-padding shape)))))))

(def layout-container-cases
  [[20 :layout-flex-dir :layout-flex-dir #{:flex}]
   [21 :layout-wrap-type :layout-wrap-type #{:flex}]
   [22 :layout-justify-content :layout-justify-content #{:flex :grid}]
   [23 :layout-align-content :layout-align-content #{:flex :grid}]
   [24 :layout-gap-type :layout-gap #{:flex :grid}]
   [25 :layout-padding-type :layout-padding #{:flex :grid}]
   [26 :layout-justify-items :layout-justify-items #{:grid}]])

(def layout-item-cases
  [[27 :layout-item-margin-type :layout-item-margin #{:flex :grid}]
   [28 :layout-item-align-self :layout-item-align-self #{:flex}]
   [29 :layout-item-h-sizing :layout-item-h-sizing #{:flex :grid}]
   [30 :layout-item-v-sizing :layout-item-v-sizing #{:flex :grid}]
   [31 :layout-item-min-w :layout-item-min-w #{:flex :grid}]
   [32 :layout-item-max-w :layout-item-max-w #{:flex :grid}]
   [33 :layout-item-min-h :layout-item-min-h #{:flex :grid}]
   [34 :layout-item-max-h :layout-item-max-h #{:flex :grid}]
   [35 :layout-item-absolute :layout-item-absolute #{:flex :grid}]
   [36 :layout-item-z-index :layout-item-z-index #{:flex :grid}]])

(t/deftest remaining-layout-inputs-resolve-only-in-their-own-context
  (doseq [layout [nil :flex :grid]
          [property attr _ layouts] (into [[26 :layout-justify-items :layout-justify-items #{:grid}]]
                                          layout-item-cases)]
    (let [situation (cg/world [1 0 {:layout layout :layout-children true}])
          resolved  (cg/resolve-op situation {} [:style 0 property 1 1])
          parent    (tm/shape-by-id situation (get-in resolved [:shape :parent-id]))]
      (t/is (= attr (:property resolved)))
      (if (contains? layouts layout)
        (do
          (t/is (some? (:op resolved)))
          (t/is (not= (get (:shape resolved) attr) (:value resolved)))
          (t/is (= layout (if (= property 26) (get-in resolved [:shape :layout]) (:layout parent)))))
        (do
          (t/is (nil? (:op resolved)))
          (t/is (= :skip (get-in resolved [:trace :status]))))))))

(t/deftest asymmetric-item-margins-are-settled-before-copying
  (doseq [layout [:flex :grid]]
    (let [situation (cg/world [1 0 {:layout layout :layout-children true :layout-item-margins true}])
          main-id   ((n/main-rect-of "random-0") situation)
          images    (filter #(or (= main-id (get-in % [:shape :id]))
                                 (= main-id (get-in % [:shape :shape-ref]))) (cg/tree situation))]
      (t/is (= 3 (count images)))
      (doseq [{:keys [shape]} images]
        (t/is (= {:m1 2 :m2 4 :m3 6 :m4 8} (:layout-item-margin shape)))
        (t/is (= :multiple (:layout-item-margin-type shape))))
      (t/is (= :valid (try (oracle/check situation {}) :valid
                           (catch #?(:clj Exception :cljs js/Error) error
                             (select-keys (ex-data error) [:invariant :group]))))))))

(t/deftest multi-child-layout-fixtures-start-settled-before-user-actions
  (doseq [layout [:flex :grid] depth [0 1]]
    (let [situation (cg/world [2 depth {:layout layout :layout-copies true :layout-children true}])
          main      (ths/get-shape (tm/file situation) :sync-random-0-head)]
      (t/is (= {:row-gap 5 :column-gap 9} (:layout-gap main)))
      (t/is (= {:p1 3 :p2 6 :p3 9 :p4 12} (:layout-padding main)))
      (when (= layout :grid)
        (let [assigned (mapcat :shapes (vals (:layout-grid-cells main)))]
          (t/is (= (set (:shapes main)) (set assigned)))
          (t/is (= (count (:shapes main)) (count assigned)))))
      (t/is (= :valid (try (oracle/check situation {}) :valid
                           (catch #?(:clj Exception :cljs js/Error) error
                             (select-keys (ex-data error) [:invariant :group]))))))))

(t/deftest empty-grid-fixtures-keep-children-of-other-grids-assigned
  (doseq [depth [0 1 2] layout-children [false true]]
    (let [spec      [2 depth {:layout :grid :grid-empty true :layout-copies true
                              :layout-children layout-children}]
          situation (cg/world spec)]
      (doseq [{:keys [shape]} (cg/tree situation)
              :when (= :grid (:layout shape))]
        (t/is (= (set (:shapes shape)) (set (mapcat :shapes (vals (:layout-grid-cells shape)))))
              (pr-str spec (:name shape))))
      (t/is (map? (cg/run {:world spec :ops [[:sync] [:grid-direction 0 0 1 1]]}))
            (pr-str spec)))))

(t/deftest generated-layout-worlds-start-with-settled-layouts
  (doseq [layout [:flex :grid] margins [false true] copies [false true]]
    (let [spec      [2 2 {:variants true :rich-variants true :text true :text-styles true
                          :layout layout :layout-item-margins margins :layout-copies copies}]
          situation (cg/world spec)
          reflowed  (cf/apply-changes situation (n/get-layout-reflow-changes situation))]
      (t/is (= (:data (tm/file situation)) (:data (tm/file reflowed))) (pr-str spec))))
  (t/is (map? (cg/run {:world [2 2 {:variants true :rich-variants true :text true :text-styles true
                                    :layout :grid :layout-item-margins true :layout-copies true}]
                       :ops [[:grid-direction 0 0 0 1] [:grid-cell 0 0 0 1]]}))))

(t/deftest moving-a-flow-layout-child-does-not-author-initial-reflow
  (let [result (cg/run {:world [2 0 {:text true :text-styles true :layout :flex :layout-children true}]
                        :ops [[:transform 0 0 0 2]]})]
    (t/is (empty? (:model result)))
    (t/is (every? #(empty? (get-in % [:shape :touched])) (cg/tree (:situation result))))))

(t/deftest changing-source-margin-settles-copies-before-moving-a-flow-child
  (let [result (cg/run {:world [2 0 {:text true :text-styles true :layout :flex :layout-children true}]
                        :ops [[:style 0 10 1] [:transform 0 0 0 2]]})]
    (t/is (every? #(not (contains? % :geometry-group)) (vals (:model result))))))

(t/deftest justifying-a-single-flex-child-keeps-layout-finite
  (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout :flex :layout-copies true}]
                       :ops [[:style 0 22 1]]}))))

(t/deftest editing-library-copy-text-does-not-author-an-empty-typography
  (let [result (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true
                                     :stale-libraries true :library-depth 1 :library true
                                     :rich-variants true :library-text true :layout-copies true :text true}]
                        :ops [[:text-style 14 0 0 0]]})]
    (t/is (every? #(not (contains? % :text-content-typography)) (vals (:model result))))))

(t/deftest layout-property-reflow-joins-the-primary-undo-group
  (let [initial (cg/world [1 0 {:layout :flex :layout-children true}])
        id      (thi/id :sync-random-0-copyhead-0)
        ids     (:shapes (tm/shape-by-id initial id))
        points  (fn [situation] (mapv #(:points (tm/shape-by-id situation %)) ids))
        changed (r/step initial (n/change-property :sync-random-0-copyhead-0 :layout-gap
                                                   {:row-gap 20 :column-gap 25}))
        undone  (r/step changed (n/undo))
        redone  (r/step undone (n/redo))]
    (t/is (not= (points initial) (points changed)))
    (t/is (= #{:layout-gap} (:touched (tm/shape-by-id changed id))))
    (t/is (every? #(empty? (:touched (tm/shape-by-id changed %))) ids))
    (t/is (= (oracle/snapshot initial) (oracle/snapshot undone)))
    (t/is (= (oracle/snapshot changed) (oracle/snapshot redone)))))

(t/deftest layout-container-edits-refuse-inapplicable-targets
  (doseq [layout [nil :flex :grid] scope [0 1 2]]
    (let [situation (cg/world [2 1 {:layout layout :layout-copies true :library true :text true}])]
      (doseq [[property attr _ layouts] layout-container-cases]
        (let [resolved (cg/resolve-op situation {} [:style 0 property 1 scope])]
          (t/is (= attr (:property resolved)))
          (if (and (contains? layouts layout) (not= scope 2))
            (do
              (t/is (some? (:op resolved)))
              (t/is (= :frame (get-in resolved [:shape :type])))
              (t/is (= layout (get-in resolved [:shape :layout]))))
            (do
              (t/is (nil? (:op resolved)))
              (t/is (= :skip (get-in resolved [:trace :status]))))))))))

(t/deftest layout-container-overrides-retain-values-and-companions
  (doseq [[property attr group layouts] layout-container-cases
          layout (sort layouts)]
    (t/testing (str attr " in " layout)
      (let [initial    (cg/initial-state [1 0 {:layout layout :layout-children true}])
            situation  (:situation initial)
            resolved   (cg/resolve-op situation {} [:style 0 property 1 1])
            shape      (:shape resolved)
            id         (:id shape)
            expected   (case attr
                         :layout-gap-type {:layout-gap-type (:value resolved) :layout-gap (:layout-gap shape)}
                         :layout-padding-type {:layout-padding-type (:value resolved) :layout-padding (:layout-padding shape)}
                         {attr (:value resolved)})
            edited     (-> initial (cg/step [:style 0 property 1 1]) cg/settle-state)
            after      (:situation edited)
            file       (tm/file after)
            path       [:data :pages-index (:id (thf/current-page file)) :objects id]
            invariant  (fn [file]
                         (try (oracle/check (tm/with-file after file) (:model edited)) nil
                              (catch #?(:clj Exception :cljs js/Error) error
                                (:invariant (ex-data error)))))
            main-index (first (keep (fn [index]
                                      (when (= (:shape-ref shape)
                                               (get-in (cg/resolve-op situation {} [:style index property 0 0])
                                                       [:shape :id])) index))
                                    (range (count (cg/tree situation)))))
            updated    (-> edited (cg/step [:style main-index property 1 0]) cg/settle-state
                           (cg/step [:style main-index property 0 0]) cg/settle-state)
            value      (:value (cg/resolve-op situation {} [:style main-index property 0 0]))
            copies     (filter #(= (:shape-ref shape) (get-in % [:shape :shape-ref])) (cg/tree situation))]
        (t/is (= expected (get-in edited [:model id group])))
        (t/is (= #{group} (:touched (tm/shape-by-id after id))))
        (t/is (= (select-keys shape [:layout-gap :layout-padding])
                 (select-keys (tm/shape-by-id after id) [:layout-gap :layout-padding])))
        (t/is (= :I9 (invariant (assoc-in file (conj path attr) (get shape attr)))))
        (t/is (= :I3 (invariant (update-in file (conj path :touched) disj group))))
        (t/is (some? main-index))
        (t/is (= 2 (count copies)))
        (doseq [{copy :shape} copies]
          (let [actual (tm/shape-by-id (:situation updated) (:id copy))]
            (t/is (= (if (= id (:id copy)) (:value resolved) value) (get actual attr)))
            (t/is (= (if (= id (:id copy)) #{group} #{}) (set (:touched actual))))))
        (t/is (= expected (get-in updated [:model id group])))))))

(t/deftest layout-container-histories-survive-component-operations
  (doseq [[property _ _ layouts] layout-container-cases
          layout (sort layouts)]
    (let [result (cg/run {:world [1 0 {:layout layout :layout-children true}]
                          :ops [[:style 0 property 1 1] [:style 0 property 1 0]
                                [:style 0 property 0 0] [:undo] [:redo] [:update-main 0]
                                [:style 0 property 0 1] [:reset 0]
                                [:style 0 property 0 1] [:detach 0] [:undo] [:redo]]})]
      (doseq [kind [:style :update-main :reset :detach]]
        (t/is (pos? (get-in result [:coverage :random kind :changed] 0))
              (str kind " changes " property " in " layout))))))

(t/deftest adding-a-flex-child-before-making-a-copy-child-absolute-stays-settled
  (t/is (map? (cg/run {:world [1 0 {:layout :flex :layout-children true}]
                       :ops [[:add-child 0] [:style 2 35 1 1]]}))))

(t/deftest full-settlement-comparison-still-rejects-absolute-child-drift
  (let [initial      (-> (cg/initial-state [1 0 {:layout :flex :layout-children true}])
                         (cg/step [:add-child 0]) cg/settle-state)
        id           (get-in (cg/resolve-op (:situation initial) {} [:style 2 35 1 1]) [:shape :id])
        edited       (cg/step initial [:style 2 35 1 1])
        settled      (assoc edited :situation (r/settle (:situation edited)) :checkpoint nil)
        settle       r/settle
        corrupt-full (fn [situation component-ids]
                       (let [result (settle situation component-ids)
                             file   (tm/file result)
                             path   [:data :pages-index (:id (thf/current-page file)) :objects id]]
                         (tm/with-file result (update-in file path gsh/move (gpt/point 0.25 0)))))
        invariant    (with-redefs [r/settle (fn
                                              ([situation] (settle situation (r/pending-components situation)))
                                              ([situation ids] (corrupt-full situation ids)))]
                       (try (cg/settle-state settled) nil
                            (catch #?(:clj Exception :cljs js/Error) error
                              (:invariant (ex-data error)))))]
    (t/is (= :I6 invariant))))

(t/deftest layout-item-overrides-retain-values-and-companions
  (doseq [[property attr group layouts] layout-item-cases
          layout (sort layouts)]
    (t/testing (str attr " in " layout)
      (let [initial    (cg/initial-state [1 0 {:layout layout :layout-children true :layout-item-margins true}])
            situation  (:situation initial)
            authored   (if (= property 27) 0 1)
            other      (if (= property 27) 1 0)
            resolved   (cg/resolve-op situation {} [:style 0 property authored 1])
            shape      (:shape resolved)
            id         (:id shape)
            expected   (if (= property 27)
                         {:layout-item-margin-type :simple :layout-item-margin {:m1 2 :m2 4 :m3 6 :m4 8}}
                         {attr (:value resolved)})
            edited     (-> initial (cg/step [:style 0 property authored 1]) cg/settle-state)
            after      (:situation edited)
            file       (tm/file after)
            path       [:data :pages-index (:id (thf/current-page file)) :objects id]
            invariant  (fn [file]
                         (try (oracle/check (tm/with-file after file) (:model edited)) nil
                              (catch #?(:clj Exception :cljs js/Error) error
                                (:invariant (ex-data error)))))
            reverted   (cond-> (if (contains? shape attr)
                                 (assoc-in file (conj path attr) (get shape attr))
                                 (update-in file path dissoc attr))
                         (and (= property 35) (= layout :grid))
                         (assoc-in (conj (pop path) (:parent-id shape) :layout-grid-cells)
                                   (:layout-grid-cells (tm/shape-by-id situation (:parent-id shape)))))
            main-index (first (keep (fn [index]
                                      (when (= (:shape-ref shape)
                                               (get-in (cg/resolve-op situation {} [:style index property other 0])
                                                       [:shape :id])) index))
                                    (range (count (cg/tree situation)))))
            updated    (-> edited (cg/step [:style main-index property authored 0]) cg/settle-state
                           (cg/step [:style main-index property other 0]) cg/settle-state)
            value      (:value (cg/resolve-op situation {} [:style main-index property other 0]))
            copies     (filter #(= (:shape-ref shape) (get-in % [:shape :shape-ref])) (cg/tree situation))
            ;; leaving the layout's flow keeps the copy's place as its own
            groups     (cond-> #{group} (= attr :layout-item-absolute) (conj :geometry-group))]
        (t/is (= expected (get-in edited [:model id group])))
        (t/is (= groups (:touched (tm/shape-by-id after id))))
        (t/is (= (:layout-item-margin shape) (:layout-item-margin (tm/shape-by-id after id))))
        (t/is (= :I9 (invariant reverted)))
        (t/is (= :I3 (invariant (update-in file (conj path :touched) disj group))))
        (t/is (some? main-index))
        (t/is (= 2 (count copies)))
        (doseq [{copy :shape} copies]
          (let [actual (tm/shape-by-id (:situation updated) (:id copy))]
            (t/is (= (if (= id (:id copy)) (:value resolved) value) (get actual attr)))
            (t/is (= (if (= id (:id copy)) groups #{}) (set (:touched actual))))))
        (t/is (= expected (get-in updated [:model id group])))))))

(t/deftest hug-sizing-edits-target-layout-boards-and-survive-component-histories
  (doseq [property [29 30] scope [0 1 2]]
    (t/is (nil? (:op (cg/resolve-op (cg/world [1 0 {}]) {} [:style 0 property 2 scope])))
          "hug needs the board's own layout"))
  (doseq [layout [:flex :grid] property [29 30] scope [0 1]
          tail [[] [[:update-main 0]] [[:reset 0]] [[:undo] [:redo]]]]
    (let [program {:world [1 0 {:layout layout :layout-children true :layout-copies true}]
                   :ops (into [[:style 0 property 2 scope]] tail)}
          result  (cg/run program)]
      (t/is (= 1 (get-in result [:coverage :random :style :changed])) (pr-str program)))))

(t/deftest layout-item-histories-survive-component-operations
  (doseq [[property _ _ layouts] layout-item-cases
          layout (sort layouts)]
    (let [authored (if (= property 27) 0 1)
          other    (if (= property 27) 1 0)
          result   (cg/run {:world [1 0 {:layout layout :layout-children true :layout-item-margins true}]
                            :ops [[:style 0 property authored 1] [:style 0 property authored 0]
                                  [:style 0 property other 0] [:undo] [:redo] [:update-main 0]
                                  [:style 0 property other 1] [:reset 0]
                                  [:style 0 property other 1] [:detach 0] [:undo] [:redo]]})]
      (doseq [kind [:style :update-main :reset :detach]]
        (t/is (pos? (get-in result [:coverage :random kind :changed] 0))
              (str kind " changes " property " in " layout))))))

(t/deftest text-style-edits-cover-every-style-unit
  (doseq [[unit group] (map-indexed vector
                                    [:text-content-family :text-content-weight :text-content-size
                                     :text-content-line-height :text-content-spacing :text-content-decoration
                                     :text-content-case :text-content-fills :text-content-typography
                                     :text-content-align :text-content-direction :text-content-valign])]
    (let [result (cg/run {:world [2 1 {:text true :text-styles true}]
                          :ops [[:text-style 1 unit 1] [:sync] [:undo] [:redo]]})
          id (thi/id :copy-child)]
      (t/is (= 1 (get-in result [:coverage :random :text-style :changed])) (str group))
      (t/is (contains? (get (:model result) id) group) (str group)))))

(t/deftest text-style-intent-rejects-a-dropped-main-commit
  (doseq [unit (range 12)]
    (let [initial (cg/initial-state [2 1 {:text true :text-styles true}])]
      (t/is (= {:invariant :I9 :phase :requested-text}
               (try (with-redefs [r/step (fn [situation _] situation)]
                      (cg/step initial [:text-style 0 unit 1]))
                    nil
                    (catch #?(:clj Exception :cljs js/Error) error
                      (select-keys (ex-data error) [:invariant :phase]))))
            (str "text unit " unit)))))

(t/deftest library-dependencies-wait-for-each-consumers-acceptance
  (let [initial (cg/initial-state [2 0 {:library true :stale-libraries true :library-dependency true}])
        situation (:situation initial)
        files (tm/aux-files situation)
        source-id (some (fn [[id file]] (when (= "random-library" (:name file)) id)) files)
        dependent-id (some (fn [[id file]] (when (= "dependent-library" (:name file)) id)) files)
        source-shape (some #(when (and (= :rect (get-in % [:shape :type]))
                                       (nil? (get-in % [:shape :shape-ref]))) (:shape %))
                           (cg/tree (tm/with-file situation (get files source-id))))
        source-index (some #(when (= (:id source-shape)
                                     (:id (:shape (cg/resolve-op situation {} [:library-edit % 0 1])))) %) (range 20))
        dependent-child (some #(when (= (:id source-shape) (get-in % [:shape :shape-ref])) (:shape %))
                              (cg/tree (tm/with-file situation (get files dependent-id))))
        copy-id (some #(when (= (:id dependent-child) (get-in % [:shape :shape-ref]))
                         (get-in % [:shape :id])) (cg/tree situation))
        edited (-> initial (cg/step [:library-edit source-index 0 1]) cg/settle-state)
        dependent-accepted (-> edited (cg/step [:library-accept 0]) cg/settle-state)
        accepted (-> dependent-accepted (cg/step [:library-sync 0]) cg/settle-state)
        fill #(get-in (tm/shape-by-id (:situation %) copy-id) [:fills 0 :fill-color])]
    (t/is (some? dependent-child))
    (t/is (some? copy-id))
    (t/is (= setup/labeled-fill (fill edited)))
    (t/is (= setup/labeled-fill (fill dependent-accepted)))
    (t/is (= "#ff0000" (fill accepted)))
    (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true :library-dependency true}]
                         :ops [[:library-edit source-index 0 1] [:library-accept 0] [:library-sync 0]
                               [:library-undo 0] [:library-redo 0] [:undo] [:redo]]})))))

(t/deftest a-local-cascade-keeps-an-unaccepted-swaps-library-inputs
  (let [program {:world [2 1 {:library true :stale-libraries true :library-dependency true}]
                 :ops [[:swap 1] [:swap 18] [:library-add-child 16] [:edit 0 1 1 :copy-head]]}
        result (cg/run program)
        situation (:situation result)
        head-at #(some (fn [{:keys [path shape]}] (when (= [2 0] path) shape)) (cg/tree %))
        head (head-at situation)
        owner (:component-file head)
        main-id (get-in (cf/all-files situation) [owner :data :components (:component-id head) :main-instance-id])
        main #(tm/shape-by-id (tm/with-file situation (get % owner)) main-id)
        advanced (cf/remember-subtree situation (:id (tm/file situation)) (:id head) (cf/all-files situation))]
    (t/is (= 1 (count (:shapes head))))
    (t/is (= 1 (count (:shapes (main (cf/reference-libraries situation head))))))
    (t/is (= 2 (count (:shapes (main (cf/all-files situation))))))
    (t/is (= :I4 (try (oracle/check advanced (:model result)) nil
                      (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error))))))
    (let [accepted (cg/run (update program :ops conj [:library-sync 1]))]
      (t/is (= 2 (count (:shapes (head-at (:situation accepted)))))))))

(t/deftest geometry-edits-cover-mains-copy-children-and-nested-copies
  (doseq [action (range 6) scope (range 5)]
    (let [result (cg/run {:world [2 2]
                          :ops [[:transform 0 action 3 scope] [:sync] [:undo] [:redo]]})]
      (t/is (= 1 (get-in result [:coverage :random :transform :changed] 0))
            (str "geometry action " action ", scope " scope)))))

(t/deftest hiding-a-grid-main-child-then-moving-its-main-stays-synced
  (t/is (map? (cg/run {:world [2 1 {:text true :text-styles true :layout :grid}]
                       :ops [[:style 3 1 3 0] [:transform 0 0 0 0]]}))))

(t/deftest showing-a-hidden-grid-main-child-assigns-it-a-cell-again
  (let [result (cg/run {:world [2 1 {:text true :text-styles true :layout :grid}]
                        :ops [[:style 3 1 1 0] [:style 3 1 0 0]]})
        situation (:situation result)
        shape (:shape (cg/resolve-op situation (:model result) [:style 3 1 0 0]))
        parent (tm/shape-by-id situation (:parent-id shape))]
    (t/is (false? (:hidden shape)))
    (t/is (some #(some #{(:id shape)} (:shapes %)) (vals (:layout-grid-cells parent))))))

(t/deftest hiding-a-grid-copy-child-keeps-derived-cells-consistent
  (t/is (map? (cg/run {:world [2 1 {:text true :text-styles true :layout :grid}]
                       :ops [[:style 6 1 3 0] [:transform 0 0 0 0]
                             [:undo] [:redo]]}))))

(t/deftest applying-a-typography-after-a-swap-is-already-fully-synced
  (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout nil :layout-copies true}]
                       :ops [[:swap 2] [:text-style 4 8 1] [:add-child 0] [:undo] [:undo] [:undo] [:redo] [:redo] [:redo]]}))))

(t/deftest detaching-after-a-nested-switch-keeps-placement-stable-through-sync
  (t/is (map? (cg/run {:world [2 2 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout :flex :layout-copies true}]
                       :ops [[:nest 7 0 0 0] [:switch 3 0 0 0] [:style 3 7 1] [:detach 6 0 0 0]
                             [:undo] [:undo] [:undo] [:undo] [:redo] [:redo] [:redo] [:redo]]}))))

(t/deftest applying-typography-to-a-main-drops-a-mixed-copys-link-without-an-override
  (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout nil :layout-copies true}]
                       :ops [[:swap 0 0 0 0] [:text 23 3 1 0] [:text-style 22 8 1]
                             [:undo] [:undo] [:undo] [:redo] [:redo] [:redo]]}))))

(t/deftest typography-reference-oracle-rejects-wrong-retention-and-missing-inheritance
  (doseq [mixed? [true false]]
    (let [state     (cond-> (cg/initial-state [2 0 {:text true :text-styles true}])
                      mixed? (cg/step [:text 1 1 1]))
          state     (-> state (cg/step [:text-style 0 8 1]) (update :situation r/settle))
          situation (:situation state)
          file      (tm/file situation)
          page      (thf/current-page file)
          copy      (ths/get-shape file :copy-child)
          content   (-> (thtx/view (:content copy))
                        (thtx/restyle :typography (when mixed? :generated-typography))
                        thtx/content)
          corrupted (tm/with-file situation
                      (assoc-in file [:data :pages-index (:id page) :objects (:id copy) :content] content))]
      (t/is (nil? (oracle/check situation (:model state))))
      (t/is (= :I4 (try (oracle/check corrupted (:model state)) nil
                        (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error)))))
            (if mixed? "mixed styles must lose the reference" "fully inherited styles must keep the reference")))))

(t/deftest detaching-a-rotated-switched-copy-keeps-nested-placement-stable
  (doseq [angle (range 4)]
    (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true
                                      :text true :text-styles true :layout nil :layout-copies true}]
                         :ops [[:duplicate 1 0 0 0] [:duplicate 1 0 0 0] [:switch 3 0 0 0]
                               [:rotate 5 0 0 angle] [:detach 5 0 0 0]
                               [:undo] [:undo] [:undo] [:undo] [:undo]
                               [:redo] [:redo] [:redo] [:redo] [:redo]]})))))

(t/deftest detaching-settles-new-placement-without-pulling-stale-content
  (let [state     (-> (reduce cg/step
                              (cg/initial-state [2 0 {:library true :variants true :rich-variants true
                                                      :text true :text-styles true :layout nil :layout-copies true}])
                              [[:duplicate 1 0 0 0] [:duplicate 1 0 0 0] [:switch 3 0 0 0] [:rotate 5]])
                      cg/settle-state)
        situation (:situation state)
        copy      (:shape (first (filter #(= [:file 0 :page 0 5 0] (:path %)) (cg/forest situation))))
        near      (tm/shape-by-id situation (:shape-ref copy))
        source-id (:shape-ref near)
        stale     (tm/with-file situation (ths/update-shape-by-id (tm/file situation) source-id :opacity 0.25))
        detached  (r/step stale (:op (cg/resolve-op stale (:model state) [:detach 5])))
        head      (tm/shape-by-id detached (:id copy))
        swapped   (tm/shape-by-id detached (first (:shapes head)))]
    (t/is (= 0.25 (:opacity (tm/shape-by-id detached source-id))))
    (t/is (= (:opacity copy) (:opacity head)) "detach must not accept the main's new content")
    (t/is (= #{:swap-group} (:touched swapped)) "the placement correction stays inherited")))

(t/deftest applying-an-inherited-typography-authors-even-equal-units
  (let [state (-> (cg/initial-state [2 0 {:text true :text-styles true}])
                  (cg/step [:text-style 0 8 1]) cg/settle-state)
        before (tm/shape-by-id (:situation state) (thi/id :copy-child))
        result (-> state (cg/step [:text-style 1 8 1]) cg/settle-state)
        after (tm/shape-by-id (:situation result) (thi/id :copy-child))
        groups #{:content-group :text-content-typography :text-content-family
                 :text-content-weight :text-content-size :text-content-line-height
                 :text-content-spacing :text-content-case}]
    (t/is (= (thtx/view (:content before)) (thtx/view (:content after))))
    (t/is (empty? (:touched before)))
    (t/is (= groups (:touched after)))
    (t/is (= groups (set (keys (get (:model result) (:id after))))))
    (let [undone (-> result (cg/step [:undo]) cg/settle-state)]
      (t/is (= (oracle/snapshot (:situation state)) (oracle/snapshot (:situation undone)))))
    (t/is (= 2 (get-in result [:coverage :random :text-style :changed] 0))
          "both the main's value edit and the copy's authorship count as mutations")))

(t/deftest deleted-variant-content-keeps-its-size-when-the-position-resizes
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:switch 1] [:delete 0] [:restore 0] [:delete 1]
                             [:resize 0] [:undo] [:redo]]}))))

(defn- translate-result
  [situation id]
  (let [file (tm/file situation)
        page (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page)) [id]
                                            #(gsh/move % (gpt/point 5 0)) (:objects page)
                                            {:ignore-touched true})]
    (tm/with-file situation (thf/apply-changes file changes))))

(t/deftest geometry-expectations-do-not-read-the-primary-result
  (doseq [tuple [[:resize 0 0 1] [:rotate 0 0 1] [:transform 0 0 1 0]]]
    (let [initial (cg/initial-state [2 1])
          original r/step
          invariant (try
                      (with-redefs [r/step (fn [situation op]
                                             (let [result (original situation op)]
                                               (if (:target op)
                                                 (translate-result result (tm/target-shape-id situation (:target op)))
                                                 result)))]
                        (cg/step initial tuple))
                      nil
                      (catch #?(:clj Exception :cljs js/Error) error
                        (select-keys (ex-data error) [:invariant :phase])))]
      (t/is (= {:invariant :I9 :phase :requested-geometry} invariant)))))

(t/deftest authored-child-position-follows-its-parent-and-rejects-drift
  (let [initial (cg/initial-state [2 1])
        id (get-in (cg/resolve-op (:situation initial) {} [:transform 0 0 1 2]) [:shape :id])
        authored (-> initial (cg/step [:transform 0 0 1 2]) cg/settle-state)
        moved (-> authored (cg/step [:transform 0 0 1 4]) cg/settle-state)
        broken (translate-result (:situation moved) id)]
    (t/is (nil? (oracle/check (:situation moved) (:model moved))))
    (t/is (= :I9 (try (oracle/check broken (:model moved) false) nil
                      (catch #?(:clj Exception :cljs js/Error) error (:invariant (ex-data error))))))))

(t/deftest pushed-authored-child-position-uses-the-main-frame
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:transform 0 0 0 3] [:edit 1 0 1 1] [:update-main 1]]}))))

(t/deftest accepting-an-upstream-library-after-its-dependent-was-refreshed
  (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true :library-dependency true}]
                       :ops [[:library-edit 3 0 1] [:library-accept 0] [:library-sync 1]]}))))

(t/deftest detached-inherited-child-position-uses-the-copy-parent-frame
  (t/is (map? (cg/run {:world [3 2 {:text true :layout nil}]
                       :ops [[:transform 1 0 0 2] [:nest 3] [:detach 2]]}))))

(t/deftest reflecting-a-rotated-copy-settles-descendant-flips
  (t/is (map? (cg/run {:world [2 1 {:text true :layout :grid}]
                       :ops [[:rotate 1] [:transform 2 16 0 9]]}))))

(t/deftest reflecting-a-plain-copy-child-settles-descendant-flips
  ;; the main detaches its nested copy, so the copy's nested head is a plain
  ;; copy child; reflecting it leaves its turned child as a full sync does
  (t/is (map? (cg/run {:world [3 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:transform 3 9 1 7] [:remove-child 0 0 0 0] [:detach 4 0 0 0]
                             [:transform 0 4 0 7]]}))))

(t/deftest swapping-after-a-resized-variant-keeps-child-placement
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false :rich-variants true
                                    :text true :layout nil :layout-copies true}]
                       :ops [[:resize 0] [:switch 0] [:swap 0]]}))))

(t/deftest switching-after-a-main-move-and-child-removal-stays-settled
  (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true :text true
                                    :layout :flex :layout-copies true}]
                       :ops [[:delete 0] [:restore 0] [:transform 4 0 0 0] [:remove-child 8]
                             [:style 5 7 0 0] [:switch 4] [:rotate 0 2 0 0]]}))))

(t/deftest reflecting-a-rotated-nested-copy-settles-descendant-flips
  (t/is (map? (cg/run {:world [2 1 {:text true :layout :grid}]
                       :ops [[:nest 11] [:rotate 1] [:transform 2 16 0 18]]}))))

(t/deftest detach-keeps-an-authored-swap-inside-a-surviving-copy
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:switch 10] [:switch 0] [:detach 2]]}))))

(t/deftest reflecting-a-parent-keeps-an-authored-childs-relative-orientation
  (t/is (map? (cg/run {:world [2 1]
                       :ops [[:transform 0 0 1 2] [:rotate 0] [:transform 0 4 0 4]
                             [:undo] [:redo]]}))))

(t/deftest variant-worlds-contain-distinct-structure-size-text-and-properties
  (let [situation (cg/world [2 2 {:variants true :rich-variants true :text true
                                  :layout :grid :layout-copies true}])
        members (:members (n/variant-set situation "random-variants"))
        roots (map #(tm/shape-by-id situation (thi/id (:root-label %))) members)
        components (map #(get-in (tm/file situation) [:data :components (:component-id %)]) members)]
    (t/is (= 4 (count members)))
    (t/is (true? (every? #(= 3 (count (:variant-properties %))) components)))
    (t/is (> (count (set (map (juxt :width :height) roots))) 1))
    (t/is (> (count (set (map (comp count :shapes) roots))) 1))
    (t/is (true? (every? (fn [root] (some #(= :text (:type (tm/shape-by-id situation %))) (:shapes root))) roots)))
    (t/is (boolean (some #(and (:layout (:shape %))
                               (some (fn [id] (:component-id (tm/shape-by-id situation id)))
                                     (:shapes (:shape %)))) (cg/tree situation))))))

(t/deftest nearest-variant-prefers-earlier-properties-and-rejects-a-wrong-choice
  (let [initial (cg/initial-state [2 2 {:variants true :rich-variants true}])
        situation (:situation initial)
        file (tm/file situation)
        members (:members (n/variant-set situation "random-variants"))
        [a b _ d] (map #(get-in file [:data :components (:component-id %)]) members)
        shape {:component-file (:id file) :component-id (:id a)}
        items (cg/candidates situation {} :switch nil)
        index (some (fn [[index entry]]
                      (when (and (= (:id a) (get-in entry [:shape :component-id]))
                                 (= 0 (:variant-pos entry))
                                 (= (:id b) (get-in entry [:destination :shape :component-id]))) index))
                    (map-indexed vector items))]
    (t/is (= (:id b) (:id (variants/nearest file shape 0 "Large"))))
    (t/is (some? index))
    (t/is (= {:invariant :I9 :phase :requested-component}
             (try (with-redefs [clv/find-switch-target (constantly d)]
                    (cg/step initial [:switch index]))
                  nil
                  (catch #?(:clj Exception :cljs js/Error) error
                    (select-keys (ex-data error) [:invariant :phase])))))))

(t/deftest switch-shadow-carries-compatible-text-style-units
  (doseq [[new-size carry?] [[14 true] [22 false]]]
    (let [content #(thtx/content [[["Same text" {:size %}]]])
          root {:type :frame :name "Board" :width 100 :height 100 :rotation 0}
          old-main (assoc root :id :old :shapes [:old-text])
          new-main (assoc root :id :new :shapes [:new-text])
          text {:type :text :name "Caption" :width 70 :height 25 :rotation 0}
          before {:old old-main :old-text (assoc text :id :old-text :parent-id :old :content (content 14))
                  :copy (assoc root :id :copy :shape-ref :old :shapes [:copy-text])
                  :copy-text (assoc text :id :copy-text :shape-ref :old-text :parent-id :copy :content (content 18))}
          after {:new new-main :new-text (assoc text :id :new-text :parent-id :new :content (content new-size))
                 :copy (assoc root :id :copy :shape-ref :new :shapes [:result-text])
                 :result-text (assoc text :id :result-text :shape-ref :new-text :parent-id :copy :content (content new-size))}
          model {:copy-text {:content-group {} :text-content-size {:content (content 18)}}}
          result (shadow/swap model before after :copy :old :new true)
          expected (get-in result [:result-text :text-content-size])]
      (t/is (= carry? (some? expected)))
      (when carry?
        (t/is (= {} (get-in result [:result-text :content-group])))
        (t/is (false? (shadow/same-value? (:result-text after) :text-content-size expected)))))))

(t/deftest switch-shadow-keeps-a-typography-only-with-its-style-ownership
  (thi/new-id! :file1)
  (thi/new-id! :heading)
  (doseq [[new-size keep-reference?] [[14 true] [22 false]]]
    (let [content #(thtx/content [[["Same text" {:size %}]]])
          applied (thtx/content
                   (thtx/apply-typography [[["Same text"]]] :heading
                                          {:family "Roboto" :weight 700 :italic true :size 22
                                           :line-height 1.6 :spacing 2 :case "uppercase"}))
          root {:type :frame :name "Board" :width 100 :height 100 :rotation 0}
          text {:type :text :name "Caption" :width 70 :height 25 :rotation 0}
          before {:old (assoc root :id :old :shapes [:old-text])
                  :old-text (assoc text :id :old-text :parent-id :old :content (content 14))
                  :copy (assoc root :id :copy :shape-ref :old :shapes [:copy-text])
                  :copy-text (assoc text :id :copy-text :shape-ref :old-text :parent-id :copy :content applied)}
          after {:new (assoc root :id :new :shapes [:new-text])
                 :new-text (assoc text :id :new-text :parent-id :new :content (content new-size))
                 :copy (assoc root :id :copy :shape-ref :new :shapes [:result-text])
                 ;; A corrupt result must not supply the model's expected values.
                 :result-text (assoc text :id :result-text :shape-ref :new-text :parent-id :copy
                                     :content (content 99))}
          groups #{:content-group :text-content-typography :text-content-family
                   :text-content-weight :text-content-size :text-content-line-height
                   :text-content-spacing :text-content-case}
          model {:copy-text (zipmap groups (repeat {:content applied}))}
          predicted (get (shadow/swap model before after :copy :old :new true) :result-text)
          expected-groups (cond-> groups
                            (not keep-reference?) (disj :text-content-size :text-content-typography))]
      (t/is (= expected-groups (set (keys predicted))))
      (t/is (false? (shadow/same-value? (:result-text after) :text-content-family
                                        (:text-content-family predicted))))
      (when keep-reference?
        (t/is (false? (shadow/same-value? (:result-text after) :text-content-typography
                                          (:text-content-typography predicted))))))))

(t/deftest typography-ownership-stays-consistent-when-a-variant-changes-size
  (t/is (map? (cg/run {:world [2 0 {:library true :variants true :rich-variants true
                                    :text true :text-styles true :layout nil :layout-copies true}]
                       :ops [[:text-style 4 8 1] [:switch 1 0 0 0]
                             [:undo] [:undo] [:redo] [:redo]]}))))

(t/deftest accepting-a-library-does-not-record-equal-numeric-geometry
  (let [before (-> (cg/initial-state [2 1 {:library true :variants true}])
                   (cg/step [:resize 10 2 2 10]) cg/settle-state)
        after (-> before (cg/step [:library-sync 3 1 2 8]) cg/settle-state)]
    (t/is (= (r/undo-depth (:situation before)) (r/undo-depth (:situation after))))
    (t/is (= (oracle/snapshot (:situation before)) (oracle/snapshot (:situation after))))))

(t/deftest source-library-edits-wait-for-explicit-consumer-acceptance
  (let [initial (cg/initial-state [2 1 {:library true :stale-libraries true}])
        situation (:situation initial)
        library (first (vals (tm/aux-files situation)))
        source (:shape (first (filter #(and (= :rect (get-in % [:shape :type]))
                                            (nil? (get-in % [:shape :shape-ref])))
                                      (cg/tree (tm/with-file situation library)))))
        copies (filter #(= (:id source) (get-in % [:shape :shape-ref])) (cg/tree situation))
        copy-id (get-in (first copies) [:shape :id])
        edited (-> initial (cg/step [:library-edit 0 0 1]) cg/settle-state)
        source-after (get-in (cf/all-files (:situation edited))
                             [(:id library) :data :pages-index (first (get-in library [:data :pages]))
                              :objects (:id source)])
        accepted (-> edited (cg/step [:library-sync]) cg/settle-state)
        undone (-> accepted (cg/step [:undo]) cg/settle-state)
        redone (-> undone (cg/step [:redo]) cg/settle-state)
        fill #(get-in (tm/shape-by-id (:situation %) copy-id) [:fills 0 :fill-color])]
    (t/is (seq copies))
    (t/is (= "#ff0000" (get-in source-after [:fills 0 :fill-color])))
    (t/is (= (get (oracle/snapshot situation) (:id (tm/file situation)))
             (get (oracle/snapshot (:situation edited)) (:id (tm/file situation)))))
    (t/is (= setup/labeled-fill (fill edited)))
    (t/is (= "#ff0000" (fill accepted)))
    (t/is (= setup/labeled-fill (fill undone)))
    (t/is (= "#ff0000" (fill redone)))
    (t/is (= 1 (get-in accepted [:coverage :random :library-sync :changed])))))

(t/deftest independent-source-edits-and-acceptance-have-cross-file-inverses
  (let [result (cg/run {:world [2 1 {:library true :stale-libraries true}]
                        :ops [[:library-edit 0 0 1] [:library-undo] [:library-redo]
                              [:library-sync] [:undo] [:redo]]})]
    (t/is (= 1 (get-in result [:coverage :random :library-edit :changed])))
    (t/is (= 1 (get-in result [:coverage :random :library-sync :changed])))
    (t/is (= 1 (count (tm/aux-files (:situation result)))))))

(t/deftest stale-copy-edits-survive-acceptance-and-reset-uses-the-loaded-main
  (let [spec [2 1 {:library true :stale-libraries true}]
        initial (cg/initial-state spec)
        situation (:situation initial)
        library-id (first (keys (tm/aux-files situation)))
        candidates (cg/candidates situation {} :edit 2)
        [index entry] (first (keep-indexed (fn [index entry]
                                             (when (= :rect (get-in entry [:shape :type])) [index entry]))
                                           candidates))
        id (get-in entry [:shape :id])
        parent (get-in entry [:shape :parent-id])
        reset-index (first (keep-indexed #(when (= parent (get-in %2 [:shape :id])) %1)
                                         (cg/candidates situation {} :reset nil)))
        edited (-> initial (cg/step [:library-edit 0 0 1])
                   (cg/step [:edit index 0 2 2]) cg/settle-state)
        accepted (-> edited (cg/step [:library-sync]) cg/settle-state)
        reset (-> edited (cg/step [:reset reset-index]) cg/settle-state)
        reset-accepted (-> reset (cg/step [:library-sync]) cg/settle-state)
        fill #(get-in (tm/shape-by-id (:situation %) id) [:fills 0 :fill-color])]
    (t/is (some? library-id))
    (t/is (some? reset-index))
    (t/is (= "#00ff00" (fill accepted)))
    (t/is (= #{:fill-group} (set (:touched (tm/shape-by-id (:situation accepted) id)))))
    (t/is (= "#ff0000" (fill reset)))
    (t/is (= "#ff0000" (fill reset-accepted)))
    (t/is (map? (cg/run {:world spec
                         :ops [[:library-edit 0 0 1] [:edit index 0 2 2]
                               [:reset reset-index] [:library-sync] [:undo] [:redo]]})))))

(t/deftest selective-pull-journals-reference-inputs-for-an-unchanged-head
  (let [initial   (cg/initial-state [0 0 {:library true :library-depth 0 :stale-libraries true}])
        situation (:situation initial)
        target    (get-in (cg/resolve-op situation {} [:reset 0]) [:shape :id])
        head      (tm/shape-by-id situation target)
        owner     (:component-file head)
        sibling   (first (for [{:keys [shape]} (cg/tree situation)
                               :when (and (= (:shape-ref head) (:shape-ref shape)) (not= target (:id shape)))]
                           (:id shape)))
        original  (:data (get (cf/all-files situation) owner))
        edited    (-> initial (cg/step [:library-edit 0 0 1]) cg/settle-state)
        loaded    (:data (get (cf/all-files (:situation edited)) owner))
        pulled    (-> edited (cg/step [:reset 0]) cg/settle-state)
        undone    (-> pulled (cg/step [:undo]) cg/settle-state)
        redone    (-> undone (cg/step [:redo]) cg/settle-state)
        reference (fn [state id]
                    (:data (get (cf/reference-libraries (:situation state)
                                                        (tm/shape-by-id (:situation state) id)) owner)))]
    (t/is (some? sibling))
    (t/is (= (tm/shape-by-id (:situation edited) target) (tm/shape-by-id (:situation pulled) target)))
    (t/is (not= (oracle/snapshot (:situation edited)) (oracle/snapshot (:situation pulled))))
    (t/is (= loaded (reference pulled target)))
    (t/is (= original (reference pulled sibling)))
    (t/is (= (oracle/snapshot (:situation edited)) (oracle/snapshot (:situation undone))))
    (t/is (= original (reference undone target)))
    (t/is (= loaded (:data (get (cf/all-files (:situation undone)) owner))))
    (t/is (= (oracle/snapshot (:situation pulled)) (oracle/snapshot (:situation redone))))
    (t/is (= loaded (reference redone target)))
    (t/is (= original (reference redone sibling)))))

(t/deftest library-acceptance-journals-unchanged-heads-in-each-copy
  (doseq [depth [0 1]]
    (let [initial   (cg/initial-state [0 0 {:library true :library-depth depth :stale-libraries true}])
          situation (:situation initial)
          head      (:shape (cg/resolve-op situation {} [:reset 0]))
          owner     (:component-file head)
          ids       (vec (for [{:keys [shape]} (cg/tree situation)
                               :when (= (:shape-ref head) (:shape-ref shape))]
                           (:id shape)))
          original  (:data (get (cf/all-files situation) owner))
          edited    (-> initial (cg/step [:library-edit 0 0 1]) cg/settle-state)
          loaded    (:data (get (cf/all-files (:situation edited)) owner))
          accepted  (-> edited (cg/step [:library-sync]) cg/settle-state)
          undone    (-> accepted (cg/step [:undo]) cg/settle-state)
          redone    (-> undone (cg/step [:redo]) cg/settle-state)
          reference (fn [state id]
                      (:data (get (cf/reference-libraries (:situation state)
                                                          (tm/shape-by-id (:situation state) id)) owner)))]
      (t/is (= 2 (count ids)))
      (doseq [id ids]
        (t/is (= (tm/shape-by-id (:situation edited) id) (tm/shape-by-id (:situation accepted) id)))
        (t/is (= loaded (reference accepted id)))
        (t/is (= original (reference undone id)))
        (t/is (= loaded (reference redone id))))
      (t/is (not= (oracle/snapshot (:situation edited)) (oracle/snapshot (:situation accepted))))
      (t/is (= (oracle/snapshot (:situation edited)) (oracle/snapshot (:situation undone))))
      (t/is (= (oracle/snapshot (:situation accepted)) (oracle/snapshot (:situation redone))))
      (t/is (= loaded (:data (get (cf/all-files (:situation undone)) owner)))))))

(t/deftest stale-copy-can-swap-before-accepting-the-library-update
  (let [spec [2 1 {:library true :stale-libraries true}]
        initial (cg/initial-state spec)
        situation (:situation initial)
        library-id (first (keys (tm/aux-files situation)))
        index (first (keep-indexed #(when (= library-id (get-in %2 [:shape :component-file])) %1)
                                   (cg/candidates situation {} :swap nil)))
        result (cg/run {:world spec
                        :ops [[:library-edit 0 0 1] [:swap index]
                              [:library-sync] [:undo] [:redo]]})]
    (t/is (some? index))
    (t/is (= 1 (get-in result [:coverage :random :swap :changed])))
    (t/is (= 1 (get-in result [:coverage :random :library-sync :changed])))))

(t/deftest update-main-does-not-nest-a-component-inside-itself
  ;; A swap in a variant's main reaches a slot switched to that variant inside
  ;; a copy of the swap's new component, so the copy holds its own component.
  (let [program {:world [2 2 {:variants true :rich-variants true :text true :text-styles true
                              :library true :layout :flex :layout-copies true}]
                 :ops [[:nest 9 0 0 0] [:switch 9 0 0 0] [:nest 2 0 0 0] [:delete 0 0 0 0]
                       [:swap 10 0 0 0] [:edit 3 0 1 1] [:update-main 3]]}
        before (:situation (cg/settle-state (reduce cg/step (cg/initial-state (:world program))
                                                    (butlast (:ops program)))))
        objects (:objects (thf/current-page (tm/file before)))
        nests-itself? (fn [shape]
                        (some #(= (:component-id shape) (:component-id %))
                              (cfh/get-children objects (:id shape))))
        copy (some #(when (and (ctk/instance-root? %) (not (ctk/main-instance? %)) (nests-itself? %)) %)
                   (vals objects))
        updated (-> before (r/step (n/update-main (constantly (:id copy)))) r/settle)]
    (t/is (some? copy))
    (t/is (= (oracle/snapshot before) (oracle/snapshot updated)))
    (t/is (map? (cg/run program)))))

(t/deftest pushing-a-local-override-does-not-accept-unrelated-source-edits
  (let [spec [2 1 {:library true :stale-libraries true :library-variants true}]
        initial (cg/initial-state spec)
        situation (:situation initial)
        {:keys [shape library-id]} (cg/resolve-op situation {} [:library-edit 1 0 1])
        copy-id (some #(when (= (:id shape) (get-in % [:shape :shape-ref]))
                         (get-in % [:shape :id])) (cg/tree situation))
        pushed (-> initial (cg/step [:library-edit 1 0 1])
                   (cg/step [:edit 0 1 1 2]) (cg/step [:update-main 0]) cg/settle-state)
        after (:situation pushed)
        source (tm/with-file after (get (tm/aux-files after) library-id))
        reference (get (cf/reference-libraries after (:id (tm/file after)) copy-id) library-id)]
    (t/is (some? copy-id))
    (t/is (= setup/labeled-fill (get-in (tm/shape-by-id after copy-id) [:fills 0 :fill-color])))
    (t/is (= setup/labeled-fill
             (get-in (tm/shape-by-id (tm/with-file after reference) (:id shape)) [:fills 0 :fill-color])))
    (t/is (= "#ff0000" (get-in (tm/shape-by-id source (:id shape)) [:fills 0 :fill-color])))
    (t/is (= 1 (get-in pushed [:coverage :random :update-main :changed])))))

(t/deftest source-history-does-not-undo-consumer-edits
  (t/is (map? (cg/run {:world [2 1 {:library true :stale-libraries true}]
                       :ops [[:library-edit 0 0 1] [:edit 0 1 1 2]
                             [:library-sync] [:library-undo] [:library-redo]
                             [:undo] [:undo] [:redo] [:redo]]}))))

(t/deftest no-op-acceptance-does-not-rewrite-an-earlier-edits-undo-baseline
  (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true}]
                       :ops [[:edit 1 0 2 2] [:library-edit 0 0 1]
                             [:library-sync] [:undo] [:undo] [:redo] [:redo]]}))))

(t/deftest coverage-distinguishes-random-changes-no-ops-and-skips
  (let [result (cg/run {:world [2 0]
                        :ops [[:edit 0 0 1 1] [:edit 0 0 1 1]
                              [:restore 0] [:sync 0]]})]
    (t/is (= {:attempted 2 :eligible 2 :changed 1 :no-op 1}
             (select-keys (get-in result [:coverage :random :edit])
                          [:attempted :eligible :changed :no-op])))
    (t/is (= {:attempted 1 :eligible 0 :skipped 1}
             (select-keys (get-in result [:coverage :random :restore])
                          [:attempted :eligible :skipped])))
    (t/is (= 1 (get-in result [:coverage :random :sync :no-op])))))

(t/deftest setup-does-not-count-as-random-mutation-coverage
  (let [result (cg/run {:world [2 0]
                        :prefix [[:edit 0 0 1 1]]
                        :ops [[:restore 0] [:sync 0]]})]
    (t/is (= 1 (get-in result [:coverage :setup :edit :changed])))
    (t/is (nil? (get-in result [:coverage :random :edit])))
    (t/is (= 1 (get-in result [:coverage :random :restore :skipped])))))

(t/deftest coverage-rejects-vacuous-random-tails-and-underrepresented-kinds
  (doseq [counts [{:attempted 100 :eligible 0 :skipped 100}
                  {:attempted 100 :eligible 100 :no-op 100}]]
    (t/is (seq (cg/coverage-errors {:setup {:edit {:changed 100}}
                                    :random {:edit counts}}
                                   [:edit]))))
  (t/is (seq (cg/coverage-errors {:random {:edit {:attempted 1000 :changed 1000}
                                           :reset {:attempted 1000 :changed 1 :no-op 999}}}
                                 [:edit :reset])))
  (t/is (empty? (cg/coverage-errors {:random {:edit {:attempted 10 :changed 10}
                                              :sync {:attempted 10 :no-op 10}}}
                                    [:edit :sync]))))

(t/deftest oracle-rejects-phantom-and-missing-overrides
  (let [situation (setup/simple-component-with-labeled-copy)
        shape (tm/resolve-shape situation :copy-child)
        changed (-> (tm/file situation)
                    (ths/update-shape :copy-child :opacity 0.4))
        authored (shadow/edit {} shape :opacity 0.4)
        missing (ths/update-shape changed :copy-child :touched nil)]
    (t/is (nil? (oracle/check situation {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation changed) {})))
    (t/is (nil? (oracle/check (tm/with-file situation changed) authored)))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation missing) authored)))))

(t/deftest oracle-rejects-lost-propagation-and-overwritten-values
  (let [situation (setup/simple-component-with-labeled-copy)
        shape (tm/resolve-shape situation :copy-child)
        changed (ths/update-shape (tm/file situation) :copy-child :opacity 0.4)
        authored (shadow/edit {} shape :opacity 0.4)
        stale (ths/update-shape changed :copy-child :touched nil)
        lost (ths/update-shape changed :copy-child :opacity 0.7)]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation stale) {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation lost) authored)))))

(t/deftest equal-values-do-not-clear-an-authored-group
  (let [result (cg/run {:world [2 0] :ops [[:edit 3 1 1] [:edit 3 1 0]
                                           [:sync 0] [:undo] [:redo]]})]
    (t/is (pos? (get-in result [:executed :edit] 0)))
    (t/is (seq (:trace result)))))

(t/deftest replay-preserves-targets-and-skips
  (let [program {:world [2 1] :ops [[:reset 0] [:edit 3 0 1] [:sync 1]
                                    [:update-main 0] [:detach 0] [:undo] [:redo]]}
        result (cg/run program)]
    (t/is (= (:trace result) (:trace (cg/run program (:trace result)))))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (cg/run program [{:kind :wrong}])))))

(t/deftest transforms-run-without-a-frontend-and-check-their-inverses
  (let [result (cg/run {:world [2 0] :ops [[:edit 0 0 1]
                                           [:resize 0 0 0] [:sync 0] [:rotate 0 0 0]
                                           [:edit 0 0 2] [:undo] [:redo]]})]
    (t/is (= 1 (get-in result [:executed :resize] 0)))
    (t/is (= 1 (get-in result [:executed :rotate] 0)))))

(t/deftest structure-and-text-operations-run-through-production-nodes
  (let [result (cg/run {:world [2 0 {:text true :layout :flex}]
                        :ops [[:edit 0 0 1] [:add-child 0] [:remove-child 0]
                              [:duplicate 0] [:nest 0] [:delete 0] [:restore 0]
                              [:text 0 0 1] [:undo] [:redo]]})]
    (doseq [kind [:add-child :remove-child :duplicate :nest :delete :restore :text]]
      (t/is (pos? (get-in result [:executed kind] 0)) (str kind " did not execute")))))

(t/deftest empty-candidates-are-logged-and-empty-runs-rejected
  (let [result (cg/run {:world [2 0] :ops [[:restore 0] [:edit 0 0 1]]})]
    (t/is (= :skip (get-in result [:trace 0 :status])))
    (t/is (string? (get-in result [:trace 0 :reason]))))
  (t/is (thrown? #?(:clj Exception :cljs js/Error)
                 (cg/run {:world [2 0] :ops [[:restore 0]]}))))

(t/deftest random-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Component operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage [:edit :reset :update-main :detach :sync :undo :redo]))
          (pr-str (cg/coverage-errors @coverage [:edit :reset :update-main :detach :sync :undo :redo])))))

(t/deftest random-structure-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/structure-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Structure operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage cg/structure-kinds))
          (pr-str (cg/coverage-errors @coverage cg/structure-kinds)))))

(t/deftest partial-sync-keeps-independent-components-pending
  (let [situation (cg/world [3 0])
        mains (take 2 (filter #(get-in % [:shape :main-instance]) (cg/tree situation)))
        pending (reduce (fn [s entry]
                          (r/step s (n/change-property
                                     (constantly (get-in entry [:shape :id])) :fills "#ff0000")))
                        situation mains)
        ids (r/pending-components pending)
        partial (r/settle pending [(first (sort-by str ids))])
        settled (r/settle partial)]
    (t/is (= 2 (count ids)))
    (t/is (= 1 (count (r/pending-components partial))))
    (t/is (empty? (r/pending-components settled)))))

(t/deftest swaps-and-variant-switches-run-in-a-three-level-world
  (let [program {:world [2 2 {:variants true}]
                 :ops [[:switch 0] [:swap 0] [:undo] [:redo]
                       [:reset 0] [:switch 1] [:sync 0]]}
        result (cg/run program)]
    (t/is (pos? (get-in result [:executed :swap] 0)))
    (t/is (pos? (get-in result [:executed :switch] 0)))
    (t/is (= (:trace result) (:trace (cg/run program (:trace result)))))))

(t/deftest variant-carry-follows-the-two-mains
  (doseq [differ? [false true]]
    (let [spec [2 2 {:variants true :variant-diff differ?}]
          initial (cg/initial-state spec)
          situation (:situation initial)
          head (:shape (cg/resolve-op situation {} [:switch 0]))
          child (first (:shapes head))
          index (first (keep-indexed #(when (= child (get-in %2 [:shape :id])) %1)
                                     (cg/candidates situation {} :edit 1)))
          result (cg/run {:world spec :ops [[:edit index 0 2 1] [:switch 0] [:sync 0]
                                            [:undo] [:redo]]})
          groups (mapcat keys (vals (:model result)))]
      (t/is (some? index))
      (t/is (= (not differ?) (boolean (some #{:fill-group} groups)))))))

(t/deftest random-swap-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/swap-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Swap operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage cg/swap-kinds))
          (pr-str (cg/coverage-errors @coverage cg/swap-kinds)))))

(t/deftest generated-variant-set-edits-cover-every-edit
  (let [spec [2 2 {:variants true :rich-variants true}]
        situation (cg/world spec)]
    (doseq [[selector edit] (map vector (range 5) [:add :remove :rename :reorder :set-value])]
      (t/is (= edit (get-in (cg/resolve-op situation {} [:variant-property 0 selector 1 1]) [:trace :edit])))
      (t/is (= 1 (get-in (cg/run {:world spec :ops [[:variant-property 0 selector 1 1] [:switch 0]
                                                    [:undo] [:redo] [:sync 0]]})
                         [:executed :variant-property]))))))

(t/deftest variant-set-edit-intent-keeps-values-and-unique-names
  (let [props [{:name "Size" :value "Small"} {:name "State" :value "Idle"}]
        component {:id :c :variant-properties props}
        added (get-in (variants/property-edit [component] 0 1 1) [:expected :c])
        renamed (get-in (variants/property-edit [component] 2 0 0) [:expected :c])]
    (t/is (variants/properties-match? added (conj props {:name "Size (1)" :value "On"})))
    (t/is (not (variants/properties-match? added (conj props {:name "Size" :value "On"}))))
    (t/is (not (variants/properties-match? added (conj props {:name "Size (1)" :value ""}))))
    (t/is (variants/properties-match? renamed [{:name "State (1)" :value "Small"} {:name "State" :value "Idle"}]))
    (t/is (not (variants/properties-match? renamed props)))
    (t/is (= [{:name "State" :value "Idle"} {:name "Size" :value "Small"}]
             (get-in (variants/property-edit [component] 3 0 2) [:expected :c])))
    (t/is (nil? (variants/property-edit [{:id :c :variant-properties [(first props)]}] 1 0 0)))))

(t/deftest added-variants-join-their-set-with-a-new-value
  (let [result (cg/run {:world [2 2 {:variants true :rich-variants true}]
                        :ops [[:add-variant 0] [:switch 0] [:add-variant 1] [:undo] [:redo] [:sync 0]]})]
    (t/is (= 2 (get-in result [:executed :add-variant])))))

(t/deftest adding-a-variant-after-deleting-a-member-takes-a-value-no-member-has
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:add-variant 0] [:switch 0] [:add-variant 0 0 0 0] [:delete 2 0 0 0]
                             [:add-variant 0 0 0 0]]}))))

(t/deftest switching-a-copy-text-without-the-mains-typography-keeps-that-override
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:add-variant 0] [:switch 5] [:delete 25 0 0 0] [:text-style 12 8 1]
                             [:text 26 3 0 0] [:add-variant 2] [:switch 0] [:variant-property 0 0 0 0]
                             [:switch 20]]}))))

(t/deftest adding-a-variant-to-a-set-of-many-properties-takes-a-new-last-value
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:variant-property 0 0 0 0] [:switch 0] [:variant-property 0 0 0 0] [:switch 0]
                             [:variant-property 0 0 0 0] [:variant-property 0 0 0 0] [:switch 0]
                             [:variant-property 0 0 0 0] [:switch 0] [:variant-property 0 0 0 0] [:switch 0]
                             [:add-variant 0] [:switch 0] [:add-variant 0] [:switch 0]]}))))

(t/deftest switching-a-copy-text-that-holds-a-typography-keeps-all-its-styles
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:text-style 13 0 1] [:add-variant 11 0 0 0] [:nest 7 0 0 0]
                             [:variant-property 0 0 0 0] [:switch 4] [:text-style 20 20 1 0]
                             [:add-variant 0] [:switch 0] [:variant-property 0 0 0 0] [:switch 14]
                             [:sync 0 0 0 0]]}))))

(t/deftest generated-transforms-and-combines-make-sets-to-switch-in
  (let [spec [2 2 {:variants true :rich-variants true :text true}]]
    (doseq [kind [:make-variant :combine]]
      (let [result (cg/run {:world spec :ops [[kind 0] [:switch 0] [:variant-property 0 0 0 0]
                                              [:undo] [:redo] [:sync 0]]})]
        (t/is (= 1 (get-in result [:executed kind])))))))

(t/deftest generated-library-set-edits-reach-the-consumer
  (let [spec [2 1 {:library true :stale-libraries true :library-variants true :library-text true
                   :library-depth 1 :variants true :rich-variants true :text true :text-styles true}]]
    (doseq [ops (concat (for [selector (range 5)] [[:library-variant-property 0 selector 1 1]])
                        [[[:library-add-variant 0]] [[:library-make-variant 0]] [[:library-combine 0]]])]
      (let [result (cg/run {:world spec :ops (into ops [[:library-sync] [:switch 0] [:library-undo]
                                                        [:library-redo] [:library-sync] [:sync 0]])})]
        (t/is (= 1 (get-in result [:executed (ffirst ops)])))))))

(t/deftest undoing-a-library-variant-a-consumer-switched-to-keeps-it-deleted
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 0 0 0 0] [:library-add-variant 0 0 0 0] [:library-sync] [:switch 0]]}))))

(t/deftest moving-a-shape-in-a-restored-library-variant-moves-it-by-the-requested-offset
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:library-make-variant 0] [:library-sync] [:switch 0] [:library-delete 1]
                             [:library-sync] [:library-restore 1] [:library-sync] [:library-transform 0 0 4 1]]}))))

(t/deftest switching-a-copy-keeps-the-layout-item-overrides-of-its-children
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children true :grid-empty true :layout-copies true :text true}]
                       :ops [[:make-variant 2 0 0 0] [:make-variant 14 0 0 0] [:make-variant 14 0 0 0]
                             [:style 13 27 1] [:make-variant 0] [:switch 18]]}))))

(t/deftest making-a-variant-of-a-hidden-main-lays-out-its-board
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:style 0 1 1] [:make-variant 0 0 0 0]]}))))

(t/deftest switching-a-copy-to-an-identical-grid-keeps-its-cell-override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:grid-direction 0 0 0 1] [:grid-cell 0 0 0 1] [:swap 0 0 0 0] [:make-variant 4]
                             [:switch 0]]}))))

(t/deftest switching-a-copy-ends-a-swap-its-new-variant-also-holds
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:make-variant 2] [:switch 0] [:variant-property 0 0 0 0] [:switch 4]
                             [:variant-property 0 0 0 0] [:switch 1] [:make-variant 13 0 0 0]
                             [:variant-property 0 0 0 0] [:switch 6]]}))))

(t/deftest switching-a-copy-to-an-identical-grid-it-already-shows-adds-no-override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:grid-cell 1 0 0 0] [:delete 5 0 0 0] [:make-variant 4 0 0 0]
                             [:variant-property 0 0 0 0] [:switch 9]]}))))

(t/deftest a-swap-a-switch-ends-shows-its-new-position
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 8 0 0 0] [:resize 0 0 0 0] [:make-variant 7] [:switch 6]
                             [:combine 13 0 0 0] [:variant-property 0 0 0 0] [:switch 13] [:make-variant 0]
                             [:switch 0] [:make-variant 2 0 0 0] [:make-variant 0] [:switch 16]]}))))

(t/deftest updating-a-variant-main-from-a-renamed-copy-keeps-the-variant-name
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:duplicate 0 0 0 0] [:edit 2 2 0 1] [:combine 0] [:switch 0]
                             [:update-main 0 0 0 0]]}))))

(t/deftest switching-a-copy-carries-the-constraints-it-leaves-unset
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:variant-property 0 0 0 0] [:switch 3] [:style 25 7 1] [:combine 0] [:switch 1]
                             [:make-variant 19] [:switch 26]]}))))

(t/deftest updating-a-main-from-a-switched-copy-keeps-the-text-overrides-it-pushes
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:combine 0] [:switch 10] [:text-style 6 0 1] [:text-style 12 8 1]
                             [:edit 3 0 1 1] [:update-main 3]]}))))

(t/deftest updating-a-main-from-a-copy-in-a-variant-it-moves-keeps-the-copy-in-place
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 0 0 0 0] [:rotate 0 0 0 0] [:make-variant 0 0 0 0] [:swap 0 0 0 0]
                             [:duplicate 0 0 0 0] [:text-style 0 0 1] [:update-main 0 0 0 0]]}))))

(t/deftest switching-a-resized-copy-text-to-a-typography-variant-holds-no-typography
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:text 4 1 1 0] [:text-style 0 8 1] [:switch 1 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest switching-a-turned-copy-keeps-the-flip-its-child-inherits
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 17 0 0 0] [:duplicate 9 0 0 0] [:make-variant 21 0 0 0]
                             [:transform 6 16 0 18] [:rotate 15 0 0 0] [:switch 14 0 0 0]]}))))

(t/deftest switching-a-turned-copy-back-keeps-its-child-in-place
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout :flex :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 0 0 0 0] [:variant-property 0 1 0 0] [:switch 0] [:delete 4 0 0 0]
                             [:delete 7 0 0 0] [:make-variant 8 0 0 0] [:transform 13 3 7 0] [:combine 7]
                             [:switch 7] [:resize 10 0 0 0] [:combine 1] [:switch 10]]}))))

(t/deftest switching-a-copy-drops-a-swap-where-the-new-variant-holds-a-plain-board
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:combine 0] [:switch 1] [:make-variant 1 0 0 0] [:add-variant 4] [:switch 8]
                             [:make-variant 0 0 0 0] [:detach 0 0 0 0] [:variant-property 0 0 0 0]
                             [:switch 12]]}))))

(t/deftest switching-a-copy-carries-no-override-of-a-swap-its-main-makes
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:edit 3 0 0 1] [:variant-property 0 6 0 0] [:switch 0] [:make-variant 5]
                             [:switch 13] [:make-variant 13 0 0 0] [:add-variant 0] [:switch 10]]}))))

(t/deftest adding-variants-beside-a-scaled-member-keeps-the-file-valid
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 0] [:restore 0] [:style 1 8 1] [:add-variant 0] [:switch 0]
                             [:add-variant 0 0 0 0]]}))))

(t/deftest switching-a-copy-whose-text-equals-the-new-variant-holds-no-override
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:library-text-style 0 8 1] [:text 10 0 1] [:library-add-variant 0 0 0 0]
                             [:library-make-variant 0] [:library-sync] [:switch 0] [:library-text 0 0 1]
                             [:library-text 5 0 1] [:library-variant-property 0 0 0 0] [:library-sync]
                             [:switch 6] [:swap 7 0 0 0] [:library-make-variant 0] [:library-sync]
                             [:switch 12]]}))))

(t/deftest switching-a-copy-sized-like-the-new-variant-keeps-its-typography
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 0 0 0 0] [:text-style 20 4 1] [:library-text-style 0 4 15 0]
                             [:library-add-variant 0] [:library-sync] [:switch 0] [:library-text-style 6 8 1]
                             [:library-variant-property 0 0 0 0] [:library-sync] [:switch 19]]}))))

(t/deftest switching-a-copy-to-the-variant-its-child-swaps-to-drops-the-swap
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:library-make-variant 8 0 0 0] [:library-combine 0 0 0 0] [:detach 0 0 0 0]
                             [:library-add-variant 16] [:library-sync] [:switch 4]
                             [:library-variant-property 0 0 0 0] [:library-sync] [:switch 6]
                             [:library-variant-property 0 0 0 0] [:library-sync] [:switch 4]]}))))

(t/deftest restoring-a-variant-an-undo-removed-keeps-its-swap
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:make-variant 10 0 0 0] [:make-variant 0] [:switch 0] [:combine 4] [:switch 12]
                             [:combine 15] [:switch 19] [:combine 0 0 0 0] [:make-variant 14 0 0 0]
                             [:undo 0 0 0 0] [:restore 0 0 0 0]]}))))

(t/deftest switching-a-copy-to-the-variant-its-swap-holds-carries-no-override
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 18 0 0 0] [:swap 0 0 0 0] [:switch 0 0 0 0] [:add-variant 0 0 0 0]
                             [:combine 17 0 0 0] [:make-variant 2 0 0 0] [:make-variant 6 0 0 0]
                             [:swap 11 0 0 0] [:make-variant 4] [:switch 9] [:switch 16 0 0 0]
                             [:combine 7] [:switch 11]]}))))

(t/deftest resetting-a-fill-off-a-swapped-child-gives-it-its-own-size
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :flex :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 26 0 0 0] [:delete 16] [:restore 16] [:delete 23] [:restore 23]
                             [:swap 26 0 0 0] [:switch 0] [:nest 29 0 0 0] [:duplicate 3 0 0 0]
                             [:reset 0 0 0 0] [:nest 14 0 0 0] [:switch 6 0 0 0] [:style 11 29 1]
                             [:reset 2]]}))))

(t/deftest constraining-a-shape-after-its-parent-resized-keeps-it-in-place
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:detach 7 0 0 0] [:combine 21 0 0 0] [:detach 9 0 0 0] [:add-variant 9 0 0 0]
                             [:transform 12 0 0 18] [:add-variant 8] [:switch 0] [:add-variant 11 0 0 0]
                             [:resize 25 0 0 0] [:style 22 9 1]]}))))

(t/deftest switching-a-copy-drops-a-swap-holding-a-copy-it-would-be-inside
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 18 0 0 0] [:swap 0 0 0 0] [:switch 0 0 0 0] [:add-variant 0 0 0 0]
                             [:combine 17 0 0 0] [:make-variant 2 0 0 0] [:make-variant 6 0 0 0]
                             [:swap 12 0 0 0] [:make-variant 4] [:switch 9] [:switch 16 0 0 0]
                             [:combine 7] [:switch 11]]}))))

(t/deftest switching-a-turned-copy-keeps-the-turn-its-child-authors
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :flex :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children true :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 3 0 0 0] [:library-add-child 0] [:library-sync] [:remove-child 5 0 0 0]
                             [:library-delete 2 0 0 0] [:library-combine 10] [:library-sync] [:switch 10]
                             [:library-add-variant 1 0 0 0] [:transform 12 3 0 7] [:library-add-variant 1 0 0 0]
                             [:library-add-variant 0 0 0 0] [:library-transform 0 3 4 0] [:library-add-child 5]
                             [:library-sync] [:library-variant-property 0 0 0 0] [:library-sync] [:switch 10]]}))))

(t/deftest switching-a-grid-copy-places-its-kept-swap-in-its-cell
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :layout-item-margins false :grid-square true :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:combine 14] [:switch 5] [:make-variant 6 0 0 0] [:make-variant 16] [:switch 1]
                             [:nest 19 0 0 0] [:grid-direction 9 0 3 0] [:nest 8 0 0 0] [:add-variant 0]
                             [:switch 12] [:make-variant 2] [:switch 14]]}))))

(t/deftest filling-an-unturned-copy-of-a-turned-main-keeps-its-left-text-width
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :flex :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 7 0 0 0] [:transform 3 3 0 0] [:switch 1 0 0 0] [:style 3 29 1]]}))))

(t/deftest turning-a-flipped-copy-turns-a-child-stored-as-the-other-mirror-turn
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 5 0 0 0] [:delete 8 0 0 0] [:delete 5 0 0 0] [:combine 7 0 0 0]
                             [:restore 0 0 0 0] [:add-variant 5] [:switch 0] [:combine 0] [:switch 3]
                             [:make-variant 14] [:switch 2] [:duplicate 0 0 0 0] [:delete 4 0 0 0]
                             [:add-variant 7 0 0 0] [:transform 15 1 0 2] [:combine 0] [:switch 1]
                             [:duplicate 1 0 0 0] [:transform 1 4 0 4] [:variant-property 0 0 0 0]
                             [:switch 6] [:rotate 10 0 0 0] [:rotate 1 0 0 0]]}))))

(t/deftest switching-a-copy-places-the-child-of-its-turned-nested-copy
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 15 0 0 0] [:resize 9 0 0 0] [:nest 7 0 0 0] [:combine 4 0 0 0]
                             [:make-variant 2 0 0 0] [:make-variant 6 0 0 0] [:swap 3 0 0 0]
                             [:transform 14 3 0 13] [:make-variant 4] [:switch 9]]}))))

(t/deftest updating-a-main-keeps-a-pushed-swap-its-main-converged-to
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:make-variant 1] [:switch 11] [:make-variant 2] [:switch 10] [:swap 1 0 0 0]
                             [:update-main 0 0 0 0]]}))))

(t/deftest random-variant-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/variant-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Variant operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage cg/variant-coverage-kinds))
          (pr-str (cg/coverage-errors @coverage cg/variant-coverage-kinds)))))

(t/deftest random-renames-leave-variant-management-to-its-own-operations
  (let [situation (cg/world [2 2 {:variants true}])
        file (tm/file situation)]
    (doseq [index (range (count (cg/tree situation)))
            :let [shape (:shape (cg/resolve-op situation {} [:edit index 2 1]))]]
      (t/is (not (:is-variant-container shape)))
      (t/is (nil? (:variant-id shape)))
      (t/is (nil? (get-in file [:data :components (:component-id shape) :variant-id]))))))

(t/deftest generated-library-world-has-remote-mains-and-local-copies
  (let [situation (cg/world [2 1 {:library true}])
        file (tm/file situation)
        libraries (tm/aux-files situation)
        remote (filter #(contains? libraries (:component-file (:shape %))) (cg/tree situation))]
    (t/is (= 1 (count libraries)))
    (t/is (seq remote))
    (t/is (every? :shape-ref (map :shape remote)))
    (t/is (not (contains? libraries (:id file))))))

(t/deftest generated-library-update-main-checks-remote-and-local-inverses
  (let [program {:world [2 1 {:library true :variants true}]
                 :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                       [:undo] [:redo] [:sync 1]]}
        result (cg/run program)]
    (t/is (= 1 (get-in result [:executed :update-main])))
    (t/is (= 1 (get-in result [:executed :library-sync])))
    (t/is (= 1 (count (tm/aux-files (:situation result)))))))

(t/deftest clearing-a-library-typography-in-a-copy-drops-its-reference
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true
                                    :stale-libraries true :layout nil :layout-item-margins false
                                    :grid-square false :library-depth 1 :library true
                                    :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 0 0 0 0] [:delete 10 0 0 0] [:swap 3 0 0 0]
                             [:library-text-style 0 8 1] [:delete 0 0 0 0] [:library-edit 0 0 0]
                             [:edit 0 1 1 2] [:library-sync] [:text-style 14 8 0 0]]}))))

(t/deftest switching-a-copy-text-marked-with-an-unapplied-typography-holds-none
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true
                                    :stale-libraries true :layout nil :layout-item-margins false
                                    :grid-square false :library-depth 1 :library true
                                    :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:library-add-variant 0] [:library-sync] [:switch 3] [:library-text-style 11 8 1]
                             [:library-make-variant 20 0 0 0] [:nest 0 0 0 0] [:library-make-variant 0]
                             [:library-sync] [:switch 0] [:nest 12 0 0 0] [:text 12 15 1] [:nest 4 0 0 0]
                             [:library-text-style 19 0 1] [:library-delete 0 0 0 0]
                             [:library-variant-property 0 0 0 0] [:library-sync] [:switch 19]]}))))

(t/deftest random-library-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/library-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Library operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage cg/library-kinds))
          (pr-str (cg/coverage-errors @coverage cg/library-kinds)))))

(t/deftest random-stale-library-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/stale-library-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Stale library operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage cg/stale-library-coverage-kinds #{:sync}))
          (pr-str (cg/coverage-errors @coverage cg/stale-library-coverage-kinds #{:sync})))))

(t/deftest random-library-dependency-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/dependency-program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Library dependency operation coverage:" (pr-str @coverage))
    (t/is (empty? (cg/coverage-errors @coverage cg/dependency-kinds #{:sync}))
          (pr-str (cg/coverage-errors @coverage cg/dependency-kinds #{:sync})))))

(t/deftest update-main-moves-an-authored-swap-to-the-replaced-main-shape
  (let [result (cg/run {:world [2 1 {:library true :variants true}]
                        :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                              [:undo] [:redo] [:reset 0] [:sync 1]
                              [:switch 2 0 0 0] [:update-main 0 0 0 0]]})]
    (t/is (some #(contains? % :swap-group) (vals (:model result))))))

(t/deftest swap-after-updating-a-rotated-main-is-already-fully-synced
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                             [:update-main 0] [:sync 1] [:rotate 0 0 0 0]
                             [:update-main 0 0 0 0] [:swap 0 0 0 0] [:restore 0 0 0 0]]}))))

(t/deftest update-main-after-deleting-a-swapped-main-keeps-the-model-in-step
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:delete 2 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest detach-from-a-deleted-near-main-inherits-only-the-skipped-level
  (t/is (map? (cg/run {:world [3 2 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:delete 1 0 0 0]
                             [:detach 13 0 0 0]]}))))

(t/deftest switch-keeps-root-geometry-inherited-through-a-near-main
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 0 0 0 0] [:switch 1 0 0 0]]}))))

(t/deftest a-main-detach-removes-an-indirect-copys-invalid-swap
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:detach 0 0 0 0] [:delete 0 0 0 0]]}))))

(t/deftest undo-after-a-main-child-add-and-root-rotation-is-fully-settled
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:undo 0 0 0 0]
                             [:add-child 17 0 0 0] [:rotate 19 0 0 0] [:undo 0 0 0 0]]}))))

(t/deftest resizing-a-copy-scales-a-child-through-its-authored-constraint
  (let [program   {:world [2 1 {:text true :text-styles true :layout nil}]
                   :ops [[:delete 0 0 0 0] [:style 5 9 1] [:resize 1 0 0 0]]}
        situation (:situation (cg/run program))
        file      (tm/file situation)
        page      (thf/current-page file)
        child     (:shape (first (filter #(= [2 0 0] (:path %)) (cg/tree situation))))
        expected  (oracle/expected-geometry file page (cf/libraries situation) child)]
    (t/is (= :scale (:constraints-v child)))
    (t/is (= #{:constraints-group} (:touched child)))
    ;; The parent shrinks from 100 to 80, so a scaled 50 child becomes 40.
    (t/is (gsh/close-attrs? :height 40 (:height expected) 0.01))
    (t/is (gsh/close-attrs? :height 40 (:height child) 0.01))))

(t/deftest switching-to-an-equally-rotated-variant-adds-no-geometry-override
  ;; The swapped copy keeps a 45 degree turn, which the new variant main
  ;; already has after update-main; the switch result differs only by noise.
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :library true :rich-variants true :layout-children false
                                    :layout-copies true :text true}]
                       :ops [[:rotate 0 0 0 0] [:edit 0 0 1 1] [:update-main 0] [:delete 0]
                             [:restore 0] [:nest 0 0 0 0] [:swap 0 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest authored-sizes-tolerate-float-noise-but-reject-a-lost-size
  (let [expected {:width 100 :height 100 :rotation 45}
        copy     {:width 99.99999999988826 :height 99.99999999988826 :rotation 45}]
    (t/is (shadow/same-value? copy :geometry-group expected))
    (t/is (false? (shadow/same-value? (assoc copy :width 99.5) :geometry-group expected)))
    (t/is (false? (shadow/same-value? (assoc copy :rotation 45.001) :geometry-group expected)))))

(t/deftest swapping-back-after-a-turned-swap-keeps-the-authored-size
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :rich-variants true :layout-children false
                                    :layout-copies true :text true}]
                       :ops [[:swap 16 0 0 0] [:rotate 1 0 0 0] [:delete 0 0 0 0] [:swap 11 0 0 0]]}))))

(t/deftest updating-a-main-from-a-copy-turned-by-its-parent-keeps-the-main-unturned
  (let [program   {:world [2 2 {:text true :text-styles true :layout nil}]
                   :ops [[:rotate 6 0 0 0] [:nest 13 0 0 0] [:transform 0 0 0 13]
                         [:update-main 3 0 0 0] [:update-main 0 0 0 0]]}
        situation (:situation (cg/run program))
        rotation  #(-> (tm/shape-by-id situation (thi/id %)) :rotation (or 0) (mod 360))]
    ;; The nested copy inherits 45 degrees from its updated parent; its main
    ;; stays unturned and the copy keeps the turn it had.
    (t/is (mth/close? 0 (rotation :sync-random-0-outer-0) 0.01))
    (t/is (mth/close? 45 (rotation :sync-random-0-innercopy-1) 0.01))))

(t/deftest a-pushed-swap-authors-the-constraints-of-its-old-position
  (let [program   {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                :library true :rich-variants true :layout-children false
                                :layout-copies true :text true}]
                   :ops [[:style 12 7 7 0] [:nest 0 0 0 0] [:swap 8 0 0 0] [:edit 0 0 1 1] [:update-main 0]]}
        situation (:situation (cg/run program))
        outer     (tm/shape-by-id situation (thi/id :sync-variant-outer-0))
        pushed    (tm/shape-by-id situation (first (:shapes outer)))]
    ;; The old position authored fixed scroll; the new nested copy keeps it
    ;; against its own main, which does not scroll.
    (t/is (true? (:fixed-scroll pushed)))
    (t/is (contains? (:touched pushed) :constraints-group))))

(t/deftest updating-a-main-from-a-resized-copy-turned-by-its-parent-keeps-its-turn
  ;; The nested copy authors only its size; its 45 degree turn still comes
  ;; from its parent, so update-main must not turn the main.
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :library true :rich-variants true :layout-children false
                                    :layout-copies true :text true}]
                       :ops [[:transform 6 15 0 0] [:resize 0 0 0 0] [:resize 2 0 0 0] [:resize 1 0 0 0]
                             [:edit 22 20 0 0] [:edit 11 0 1 1] [:update-main 11]]}))))

(t/deftest minimum-size-transform-sync-shrunk-regression
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:rotate 2 0 0 0]
                             [:resize 7 0 0 0] [:duplicate 0 0 0 0]]}))))

(t/deftest detached-swapped-head-follows-its-own-component-without-position-overrides
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:duplicate 1 0 0 0] [:swap 13 0 0 0] [:detach 16 0 0 0]]}))))

(t/deftest detaching-an-outer-copy-keeps-its-surviving-inner-swap
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 3 0 0 0] [:detach 3 0 0 0]]}))))

(t/deftest pushed-swap-followed-by-nesting-undoes-exactly
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                             [:update-main 0] [:sync 1] [:swap 0 0 0 0]
                             [:switch 1 0 0 0] [:update-main 0 0 0 0] [:nest 0 0 0 0]]}))))

(t/deftest updating-a-rotated-outer-main-composes-authored-child-orientation
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:duplicate 0 0 0 0]
                             [:resize 0 0 0 0] [:nest 0 0 0 0] [:rotate 4 0 0 0]
                             [:detach 3 0 0 0] [:update-main 4 0 0 0]]}))))

(t/deftest swapping-main-subtrees-with-several-children-undoes-their-order
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1]
                             [:update-main 0] [:sync 1] [:add-child 1 0 0 0]
                             [:add-child 1 0 0 0] [:add-child 1 0 0 0]
                             [:swap 0 0 0 0] [:swap 0 0 0 0] [:text 0 0 0 0]]}))))

(t/deftest updating-main-then-swapping-a-child-restores-parent-state
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5 0 0 0] [:update-main 0 0 0 0] [:swap 13 0 0 0]]}))))

(t/deftest pushed-variant-subtree-restores-its-main-parent-order
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:resize 0 0 0 0] [:duplicate 0 0 0 0]
                             [:switch 2 0 0 0] [:update-main 2 0 0 0]]}))))

(t/deftest updating-a-duplicated-switched-copy-restores-the-main-order
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:duplicate 0 0 0 0]
                             [:update-main 0 0 0 0] [:redo 0 0 0 0]]}))))

(t/deftest switching-a-detached-rotated-copy-keeps-its-resize
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 1 0 0 0] [:detach 1 0 0 0]
                             [:detach 2 0 0 0] [:resize 2 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest minimum-size-parent-orientation-does-not-amplify-oracle-drift
  (t/is (map? (cg/run {:world [3 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:edit 4 0 0 0]
                             [:duplicate 3 0 0 0] [:nest 10 0 0 0] [:rotate 9 0 0 0]
                             [:update-main 7 0 0 0] [:delete 0 0 0 0]]}))))

(t/deftest detaching-a-rotated-parent-inherits-geometry-in-the-copy-frame
  (t/is (map? (cg/run {:world [2 0 {:text true :layout nil}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:resize 0 0 0 0]
                             [:delete 0 0 0 0] [:rotate 0 0 0 0] [:detach 0 0 0 0]]}))))

(t/deftest detach-shadow-does-not-read-the-resulting-child-orientation
  (let [before {:root {:id :root :shapes [:child]}
                :child {:id :child :shape-ref :near :rotation 90}
                :near {:id :near :shape-ref :main :rotation 45}}
        after {:root {:id :root :shapes [:child]}
               :child {:id :child :shape-ref :main :rotation 0}}
        model (shadow/detach {:near {:geometry-group {:rotation 45}}} before after :root)]
    (t/is (= 90 (get-in model [:child :geometry-group :rotation])))
    (t/is (false? (shadow/same-value? (:child after) :geometry-group
                                      (get-in model [:child :geometry-group]))))))

(t/deftest switch-shadow-tolerates-main-size-noise-but-rejects-real-size-changes
  (doseq [[difference expected] [[1e-12 80] [1 nil]]]
    (let [main {:id :old :name "Board" :type :frame :width 100 :height 100 :rotation 0}
          copy (assoc main :id :copy :shape-ref :old :height 80 :parent-id :page)
          target (assoc main :id :new :height (+ 100 difference))
          result (shadow/swap {:copy {:geometry-group (shadow/values copy :geometry-group)}}
                              {:old main :copy copy}
                              {:new target :copy (assoc copy :shape-ref :new)}
                              :copy :old :new true)]
      (t/is (= expected (get-in result [:copy :geometry-group :height]))))))

(t/deftest switch-shadow-follows-authored-content-through-a-swapped-near-main
  (doseq [swapped? [false true]]
    (let [main    {:id :a :name "Board" :type :frame :main-instance true
                   :component-id :a-component :component-file :local :fills [:white]}
          before  {:a main
                   :b (assoc main :id :b :component-id :b-component)
                   :position (assoc main :id :position :main-instance false :shape-ref :b
                                    :component-id :b-component :fills [:blue])
                   :near (assoc main :id :near :main-instance false :shape-ref (if swapped? :position :a)
                                :parent-id :outer-main :fills [:green])
                   :parent {:id :parent :shape-ref :outer-main}
                   :copy (assoc main :id :copy :main-instance false :shape-ref :near
                                :parent-id :parent :fills [:green])}
          after   (assoc before :copy (assoc (:copy before) :component-id :b-component
                                             :fills [:wrong]))
          model   {:near (cond-> {:fill-group {:fills [:green]}}
                           swapped? (assoc :swap-group {}))
                   :position {:fill-group {:fills [:blue]}}}
          result  (shadow/swap model before after :copy :a :b true)
          expected (get-in result [:copy :fill-group])]
      (t/is (= [:green] (:fills expected)))
      (t/is (false? (shadow/same-value? (:copy after) :fill-group expected))))))

(t/deftest switching-a-copy-of-a-swap-keeps-position-overrides-inherited
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 13 0 0 0] [:rotate 0 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest rotated-swapped-head-keeps-its-position-anchor-with-a-different-content-size
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0]
                             [:undo] [:redo] [:reset 0] [:edit 0 1 1 1] [:update-main 0]
                             [:sync 1] [:swap 17 0 0 0] [:resize 0 0 0 0] [:rotate 0 0 0 0]]}))))

(t/deftest pushed-swap-shadow-predicts-orientation-without-reading-the-new-child
  (let [before {:copy {:id :copy :shape-ref :main :shapes [:child]}
                :main {:id :main :rotation 0}
                :child {:id :child :parent-id :copy :component-id :target :shape-ref :position
                        :rotation 45 :width 100 :height 100}
                :target-main {:id :target-main :component-id :target :parent-id :page
                              :rotation 0 :width 100 :height 100}
                :page {:id :page :rotation 0}}
        after (-> before
                  (assoc-in [:child :shape-ref] :pushed)
                  (assoc :pushed (assoc (:target-main before) :id :pushed
                                        :shape-ref :target-main :parent-id :main)))
        model (shadow/update-main {:child {:swap-group {}}} before after :copy)
        expected (get-in model [:pushed :geometry-group])]
    (t/is (= 45 (:rotation expected)))
    (t/is (false? (shadow/same-value? (:pushed after) :geometry-group expected)))))

(t/deftest pushed-swap-shadow-predicts-content-without-reading-the-new-child
  (let [before {:copy {:id :copy :shape-ref :main :shapes [:child]}
                :main {:id :main}
                :child {:id :child :parent-id :copy :component-id :target
                        :fills [:white] :shapes [:leaf]}
                :leaf {:id :leaf :parent-id :child :fills [:white]}
                :position {:id :position :component-id :target :fills [:green]}
                :position-leaf {:id :position-leaf :fills [:green]}}
        after (-> before
                  (assoc-in [:child :shape-ref] :pushed)
                  (assoc-in [:leaf :shape-ref] :pushed-leaf)
                  (assoc :pushed {:id :pushed :component-id :target :shape-ref :position
                                  :parent-id :main :fills [:green]}
                         :pushed-leaf {:id :pushed-leaf :shape-ref :position-leaf
                                       :parent-id :pushed :fills [:green]}))
        model (shadow/update-main {:child {:swap-group {}}} before after :copy)]
    (doseq [id [:pushed :pushed-leaf]]
      (t/is (= {:fills [:white] :hide-fill-on-export nil} (get-in model [id :fill-group])))
      (t/is (false? (shadow/same-value? (get after id) :fill-group (get-in model [id :fill-group])))))))

(t/deftest generated-library-pushed-swap-authors-content-against-its-new-position
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 2 0 0 0] [:edit 8 0 0 0] [:switch 0 0 0 0]
                             [:update-main 7 0 0 0]]}))))

(t/deftest generated-library-swap-back-syncs-the-near-positions-overrides
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 15 0 0 0] [:duplicate 16 0 0 0] [:edit 8 0 0 0]
                             [:switch 4 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-library-move-restores-existing-component-nesting-on-undo
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5 0 0 0] [:nest 8 0 0 0] [:nest 5 0 0 0]
                             [:restore 0 0 0 0]]}))))

(t/deftest generated-library-authored-child-follows-its-parents-synced-turn
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:resize 0 0 0 0] [:switch 2 0 0 0] [:rotate 1 0 0 0]
                             [:remove-child 0 0 0 0]]}))))

(t/deftest switch-shadow-keeps-inherited-orientation-even-if-the-result-loses-it
  (let [before {:page {:id :page}
                :main-a {:id :main-a :parent-id :page :name "A" :rotation 45}
                :main-b {:id :main-b :parent-id :page :name "B" :rotation 0}
                :copy {:id :copy :parent-id :page :shape-ref :main-a :rotation 45}}
        after (assoc before :copy (assoc (:copy before) :shape-ref :main-b :rotation 0))
        model (shadow/swap {} before after :copy :main-a :main-b true)
        expected (get-in model [:copy :geometry-group])]
    (t/is (= 45 (:rotation expected)))
    (t/is (false? (shadow/same-value? (:copy after) :geometry-group expected)))))

(t/deftest switch-shadow-combines-carried-size-with-retained-orientation
  (let [before {:page {:id :page}
                :main-a {:id :main-a :parent-id :page :name "A" :width 100 :height 100 :rotation 45}
                :main-b {:id :main-b :parent-id :page :name "B" :width 100 :height 100 :rotation 0}
                :copy {:id :copy :parent-id :page :shape-ref :main-a
                       :width 100 :height 80 :rotation 90}}
        after (assoc before :copy (assoc (:copy before) :shape-ref :main-b :rotation 0))
        model (shadow/swap {:copy {:geometry-group (shadow/values (:copy before) :geometry-group)}}
                           before after :copy :main-a :main-b true)
        expected (get-in model [:copy :geometry-group])]
    (t/is (= 80 (:height expected)))
    (t/is (= 90 (:rotation expected)))
    (t/is (false? (shadow/same-value? (:copy after) :geometry-group expected)))))

(t/deftest switch-keeps-retained-orientation-together-with-carried-size
  (t/is (map? (cg/run {:world [2 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                             [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                             [:swap 0 0 0 0] [:delete 0 0 0 0] [:rotate 0 0 0 0]
                             [:nest 0 0 0 0] [:detach 0 0 0 0] [:update-main 0 0 0 0]
                             [:switch 0 0 0 0]]}))))

(t/deftest switching-an-untouched-rotated-copy-survives-full-sync
  (t/is (map? (cg/run {:world [3 2 {:variants true :variant-diff false}]
                       :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                             [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                             [:edit 11 0 0 0] [:rotate 2 0 0 0] [:nest 3 0 0 0] [:resize 3 0 0 0]
                             [:nest 3 0 0 0] [:swap 10 0 0 0] [:resize 3 0 0 0]
                             [:update-main 5 0 0 0] [:switch 0 0 0 0] [:detach 0 0 0 0]]}))))

(t/deftest pushing-a-swap-keeps-the-orientation-inherited-from-its-position
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 0 0 0 0] [:swap 13 0 0 0] [:update-main 1 0 0 0]]}))))

(t/deftest resizing-a-copy-of-an-empty-flex-main-keeps-layout-valid
  (t/is (map? (cg/run {:world [3 0 {:text true :layout :flex}]
                       :ops [[:add-child 0] [:remove-child 0] [:duplicate 0] [:nest 0]
                             [:delete 0] [:restore 0] [:text 0 0 1] [:text 1 1 1]
                             [:resize 0 0 2] [:rotate 0 0 0] [:reset 0]
                             [:edit 0 0 1 1] [:update-main 0] [:sync 1]
                             [:detach 0] [:undo] [:redo] [:remove-child 0 0 0 0]
                             [:reset 0 0 0 0] [:nest 0 0 0 0] [:resize 0 0 0 0]]}))))

(t/deftest updating-a-main-after-successive-rotations-keeps-child-flips
  (t/is (map? (cg/run {:world [2 1]
                       :ops [[:rotate 1 0 0 0] [:rotate 1 0 1 0] [:update-main 0 0 0 0]]}))))

(t/deftest pushing-a-switched-child-uses-its-parents-pushed-frame
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5 0 0 0] [:rotate 1 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-library-switch-keeps-overrides-from-a-deleted-near-main
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:delete 0 0 0 0] [:delete 3 0 0 0] [:resize 0 0 0 0]
                             [:delete 9 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest generated-library-duplicate-refuses-an-expanding-component-branch
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 5] [:nest 8] [:duplicate 1]]}))))

(t/deftest generated-library-parent-turn-settles-swapped-placement
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 2 0 0 0] [:resize 0 0 0 0] [:rotate 2 0 0 0]]}))))

(t/deftest generated-library-restored-variant-syncs-its-converted-name
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:delete 3 0 0 0] [:delete 1 0 0 0] [:restore 7 0 0 0]
                             [:delete 1 0 0 0] [:restore 0 0 0 0]]}))))

(t/deftest generated-library-switch-inherits-a-swapped-near-mains-authored-fill
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:edit 12 0 0 0] [:nest 10 0 0 0]
                             [:switch 7 0 0 0] [:switch 2 0 0 0]]}))))

(t/deftest generated-library-reset-places-new-children-after-clearing-rotation
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:rotate 1 0 0 0] [:duplicate 0 0 0 0]
                             [:reset 2 0 0 0] [:redo 0 0 0 0]]}))))

(t/deftest generated-update-main-settles-a-turned-swapped-position
  (let [result (cg/run {:world [2 2 {:variants true :variant-diff false}]
                        :ops [[:edit 0 0 1 1] [:switch 0] [:switch 0] [:swap 0] [:undo] [:redo]
                              [:reset 0] [:edit 0 1 1 1] [:update-main 0] [:sync 1]
                              [:delete 8 0 0 0] [:swap 15 0 0 0] [:edit 14 0 0 0]
                              [:resize 4 0 0 0] [:resize 0 0 0 0] [:nest 4 0 0 0]
                              [:rotate 5 0 0 0] [:update-main 14 0 0 0] [:duplicate 0 0 0 0]]})]
    (t/is (pos? (get-in result [:executed :update-main] 0)))
    (t/is (pos? (get-in result [:executed :rotate] 0)))))

(t/deftest generated-copy-child-made-absolute-keeps-its-own-layouts-place
  ;; the copy's own gap places its Rectangle, which stays there as absolute
  (let [result  (cg/run {:world [2 0 {:layout :grid :grid-square true :layout-children true :layout-copies true}]
                         :ops [[:style 2 11 1] [:style 6 35 1]]})
        objects (:objects (thf/current-page (tm/file (:situation result))))
        child   (some #(when (:layout-item-absolute %) %) (vals objects))]
    (t/is (:shape-ref child))
    (t/is (contains? (:touched child) :geometry-group))))

(t/deftest generated-reset-shows-a-hidden-head-back-in-its-grid-cell
  ;; a detached grid holds a nested head its copy hid; the reset shows it
  ;; again, and the resize lays the grid out
  (let [result  (cg/run {:world [2 0 {:text true :text-styles true :layout :grid}]
                         :ops [[:nest 0 0 0 0] [:detach 1 0 0 0] [:style 1 1 1 1]
                               [:edit 1 1 1 :copy-head] [:reset 1] [:resize 1 0 0 0]]})
        objects (:objects (thf/current-page (tm/file (:situation result))))]
    (doseq [grid (filter #(= :grid (:layout %)) (vals objects))
            :let [in-cells (set (mapcat :shapes (vals (:layout-grid-cells grid))))]
            id (:shapes grid)
            :let [child (get objects id)]
            :when (not (or (:hidden child) (:layout-item-absolute child)))]
      (t/is (contains? in-cells id) (:name child)))))

(t/deftest generated-moved-child-follows-its-nested-parent-shifted-by-a-main-layout
  ;; a child moved in a copy keeps its place in its nested parent copy, which
  ;; sync moves when a new child shifts the main's flex layout
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :library true
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:transform 7 0 0 8] [:delete 0] [:restore 0] [:add-child 9 0 0 0]]}))))

(t/deftest generated-reset-of-an-absolute-grid-head-gives-it-back-its-cell
  ;; a copy makes a nested head absolute and authors its grid's cells; the
  ;; reset takes the head back into the flow and into a cell
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :grid :library-depth 1 :library true :rich-variants true
                                    :layout-children true :library-text true :layout-copies true :text true}]
                       :ops [[:style 11 35 1] [:grid-direction 13 0 0 1] [:grid-cell 13 0 0 1] [:reset 14 0 0 0]]}))))

(t/deftest generated-copy-child-hidden-from-its-own-layout-may-stay-in-place
  ;; the copy's own gap places its Rectangle, which the copy then hides
  (let [result  (cg/run {:world [2 0 {:layout :grid :grid-square true :layout-children true :layout-copies true}]
                         :ops [[:style 1 11 1] [:style 5 1 1]]})
        objects (:objects (thf/current-page (tm/file (:situation result))))
        child   (some #(when (:hidden %) %) (vals objects))]
    (t/is (:shape-ref child))
    (t/is (not (contains? (:touched child) :geometry-group)))))

(t/deftest generated-reset-keeps-a-head-out-of-its-parents-unaccepted-turn
  ;; the base library widens a nested label, the dependent library turns
  ;; the card holding it; the consumer accepts only the first and resets the
  ;; label: it takes the new width upright, from its own corner
  (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true :library-dependency true :library-text true}]
                       :ops [[:library-transform 3 1 4 0] [:library-transform 0 9 0 0]
                             [:library-accept 0 0 0 0] [:reset 9 0 0 0]]}))))

(t/deftest generated-child-of-a-filling-copy-board-follows-its-margin
  ;; a board that fills its main's grid cell gets a margin in a copy: it
  ;; shrinks and moves, and its left-constrained child moves with it
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :rich-variants true :grid-empty true :layout-copies true :text true}]
                       :ops [[:style 0 29 1] [:style 1 10 1]]}))))

(t/deftest generated-child-of-a-resized-filling-board-follows-it
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :flex :library-depth 1 :library true :rich-variants true
                                    :layout-children true :library-text true :layout-copies true :text true}]
                       :ops [[:nest 9 0 0 0] [:resize 6 0 0 0] [:style 6 30 1]]}))))

(t/deftest generated-library-sync-keeps-another-librarys-pending-turn-out
  ;; a copy holds a component of the dependent library swapped into a slot
  ;; of the base library, which turns the slot; accepting the dependent
  ;; library leaves the base library's turn pending
  (t/is (map? (cg/run {:world [2 1 {:library true :stale-libraries true :library-dependency true :library-text true}]
                       :ops [[:swap 4 0 0 0] [:swap 9 0 0 0] [:library-transform 5 3 4 0] [:library-sync 0 0 0 0]]}))))

(t/deftest generated-swap-to-a-board-without-grid-drops-a-copys-cell-override
  ;; a copy authors the cells of a nested grid head; its main swaps the head
  ;; for a component without grid, so the override has no grid to carry to
  (let [result  (cg/run {:world [2 0 {:variants true :text-styles true :layout :grid :grid-square true :library true
                                      :rich-variants true :layout-copies true :text true}]
                         :ops [[:swap 5 0 0 0] [:grid-direction 1 0 0 1] [:grid-cell 1 0 0 1] [:swap 0 0 0 0]]})
        objects (into {} (mapcat cf/object-trees) (vals (cf/all-files (:situation result))))]
    (doseq [shape (vals objects)
            :when (not= :grid (:layout shape))]
      (t/is (empty? (:layout-grid-cells shape)) (:name shape))
      (t/is (not (contains? (:touched shape) :layout-grid-cells)) (:name shape)))))

(t/deftest generated-detach-in-a-stale-file-drops-a-child-the-library-removed
  ;; the library removes a child of a component nested in another; a stale
  ;; file detaches a copy of the outer one, so the nested copy's child has
  ;; no main left in the loaded library and becomes a plain shape
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:library-remove-child 0 0 0 0] [:detach 13 0 0 0]]}))))

(t/deftest generated-update-main-pushes-a-swap-in-its-mains-orientation
  ;; a nested copy turned with the main that holds it swaps a child and
  ;; pushes it into its own unturned main, which takes it unturned
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:swap 6 0 0 0] [:transform 17 3 0 0] [:swap 9 0 0 0] [:edit 0 0 1 1] [:update-main 0]]}))))

(t/deftest generated-library-sync-keeps-what-a-reset-took-inside-a-swap
  ;; a copy of the dependent library swaps in a base component; a reset
  ;; inside the swap takes a pending base move, which accepting the
  ;; dependent library keeps
  (t/is (map? (cg/run {:world [2 0 {:library true :stale-libraries true :library-dependency true :library-text true}]
                       :ops [[:swap 27 0 0 0] [:library-transform 3 0 4 1] [:edit 6 1 1 :copy-head] [:reset 6]
                             [:library-edit 0 0 0] [:edit 0 1 1 2] [:library-sync]]}))))

(t/deftest generated-sync-reflows-a-swapped-head-that-fills-its-layout
  ;; a board swapped into a flex slot is set to fill, which shrinks it and
  ;; its icon; a full sync gives both their main's size and the reflow
  ;; shrinks them again
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :layout :flex :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:swap 6 0 0 0] [:swap 11 0 0 0] [:style 0 29 1]]}))))

(t/deftest generated-reset-under-a-stale-mirrored-main-keeps-a-consistent-rotation
  ;; the library turns and flips an outer component; a file that has not
  ;; accepted it resets a nested head of an unturned copy, which keeps a
  ;; rotation its transform agrees with
  (t/is (map? (cg/run {:world [2 1 {:library true :stale-libraries true :library-dependency true :library-text true}]
                       :ops [[:library-transform 2 3 4 0] [:library-delete 0] [:library-sync] [:library-restore 0]
                             [:library-sync] [:library-undo 0 0 0 0] [:library-transform 16 4 4 0]
                             [:edit 17 1 1 :copy-head] [:reset 17]]}))))

(t/deftest generated-turned-fill-copy-skews-the-grid-children-it-holds
  ;; a copy filling a main's grid cell is turned 45 degrees; the cell skews
  ;; it, and the children of its nested grid skew with it, as a sync places
  ;; them
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :rich-variants true
                                    :layout-children true :grid-empty true :layout-copies true :text true}]
                       :ops [[:style 4 29 1] [:detach 0 0 0 0] [:rotate 5 0 0 0]]}))))

(t/deftest generated-resized-main-keeps-a-turned-copy-at-its-first-corner
  ;; a main is narrowed while a copy of it is turned by a detached parent;
  ;; the copy keeps its first corner, and a child it overrides keeps its
  ;; place against it
  (t/is (map? (cg/run {:world [2 2 {:text true :text-styles true}]
                       :ops [[:detach 6 0 0 0] [:delete 0] [:restore 0] [:duplicate 2 0 0 0] [:edit 0 0 0 0]
                             [:transform 8 0 0 2] [:delete 0 0 0 0] [:rotate 8 0 0 0] [:delete 0] [:restore 0]
                             [:edit 7 0 1 1] [:update-main 7] [:transform 7 7 0 0]]}))))

(t/deftest generated-turned-swap-of-another-size-keeps-the-corner-of-its-position
  ;; a nested head switched to a wider variant is turned 45 degrees with the
  ;; chain of copies it is in; it keeps the corner of its narrower position,
  ;; and a reset lands on it
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:switch 6 0 0 0] [:rotate 0 0 0 0] [:reset 5 0 0 0]]}))))

(t/deftest generated-switch-to-a-turned-variant-keeps-the-orientation-of-its-position
  ;; a nested head whose position in the enclosing main has a resize override
  ;; switches to a variant turned 45 degrees; it keeps the orientation of its
  ;; position, which sync gives it, so it has no geometry override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:transform 3 3 0 0] [:resize 0 0 0 0] [:switch 4 0 0 0]]}))))

(t/deftest generated-full-settle-reflows-a-switched-head-that-fills-its-layout
  ;; a switched head fills a flex main; settling every component writes its
  ;; main's width, which the reflow replaces with the one the flex gives it
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :layout :flex :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:swap 6 0 0 0] [:swap 10 0 0 0] [:switch 0 0 0 0] [:style 0 29 1]
                             [:edit 0 0 1 1] [:update-main 0]]}))))

(t/deftest generated-switch-of-a-moved-child-carries-no-size
  ;; a moved child keeps its old main's size, 50.0 against the new main's
  ;; 50; a switch carries no geometry for it
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:transform 0 0 0 2] [:switch 0 0 0 0]]}))))

(t/deftest generated-switch-in-a-turned-main-keeps-the-first-corner
  ;; a head in a main turned 45 degrees switches to a wider variant; it
  ;; keeps its first corner, as its copies do through sync
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:rotate 1 0 0 0] [:switch 3 0 0 0]]}))))

(t/deftest generated-copy-moved-out-of-a-layout-loses-its-margins-without-an-override
  ;; a copy whose margin type a reset kept is nested out of its flex parent;
  ;; leaving the layout clears its margins, which is no override
  (t/is (map? (cg/run {:world [2 1 {:text true :text-styles true :layout :flex :layout-children true}]
                       :ops [[:remove-child 0 0 0 0] [:nest 0 0 0 0] [:style 2 27 1]
                             [:edit 0 1 1 :copy-head] [:reset 0] [:nest 0 0 0 0]]}))))

(t/deftest generated-nest-refuses-a-loop-through-a-switched-copy
  ;; a copy whose nested head was switched away from Board is nested into
  ;; Board's main; the main of that head still holds a Board, which a reset
  ;; brings back, so the nest would loop
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:switch 3 0 0 0] [:nest 9 0 0 0] [:edit 7 1 1 :copy-head] [:reset 7]]}))))

(t/deftest generated-copy-with-its-own-geometry-keeps-children-of-a-main-set-to-fill
  ;; a nested copy made absolute in a copy keeps its size when its main is
  ;; set to fill a grid; its children stay where the copy has them
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :rich-variants true :grid-empty true :layout-copies true :text true}]
                       :ops [[:style 1 35 1] [:style 0 29 1]]}))))

(t/deftest generated-restyled-copy-text-drops-its-library-typography
  ;; resizing the letters of a copy text styled by a library typography
  ;; drops the whole reference, its file included, as the design panel does
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 2 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:library-text-style 0 8 1] [:library-edit 0 0 0] [:edit 0 1 1 2]
                             [:library-sync] [:text 10 7 1]]}))))

(t/deftest generated-grid-direction-in-a-copy-fixes-cells-after-a-manual-one
  ;; a copy grid turned to flow by columns meets its manual cell before an
  ;; auto one that holds a shape; the layout makes that one manual
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout :grid :layout-children true
                                    :grid-square true}]
                       :ops [[:remove-child 1 0 0 0] [:add-child 1 0 0 0] [:grid-direction 0 0 1 1]
                             [:grid-cell 0 0 0 1]]}))))

(t/deftest generated-duplicate-of-a-stale-copy-is-stale-too
  ;; undoing a library sync leaves its copies on the older library; a
  ;; duplicate of one copies its values, which follow that version too
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:library-edit 2 0 0] [:edit 2 1 1 2] [:library-sync] [:undo 0 0 0 0]
                             [:duplicate 15 0 0 0]]}))))

(t/deftest generated-detached-copy-keeps-the-text-it-shows
  ;; a copy text that overrides its family drops the typography its main
  ;; applies; detaching its parent copy keeps that text as it is
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:text-style 25 0 1] [:duplicate 9 0 0 0] [:delete 0 0 0 0] [:nest 15 0 0 0]
                             [:text-style 18 8 1] [:duplicate 1 0 0 0] [:detach 18 0 0 0]]}))))

(t/deftest generated-reset-nested-head-keeps-the-size-its-resized-parent-gives
  ;; a copy taller than its main scales a nested head set to scale
  ;; vertically; resetting the head keeps the size that sync gives it
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout :grid :grid-empty true}]
                       :ops [[:resize 0 0 0 0] [:nest 20 0 0 0] [:style 9 9 1] [:edit 20 1 1 :copy-head]
                             [:reset 20]]}))))

(t/deftest generated-copy-grid-with-its-own-cells-follows-their-flow
  ;; a copy with its own cells, pushed to its main grid flowing by columns,
  ;; orders the children of both as those cells flow
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :grid-square true :rich-variants true :layout-children true
                                    :layout-copies true :text true}]
                       :ops [[:grid-direction 0 0 0 1] [:grid-cell 0 0 0 1] [:grid-direction 0 0 15 0]
                             [:edit 3 0 1 1] [:update-main 3] [:style 1 11 0 0]]}))))

(t/deftest generated-turned-head-set-to-fill-takes-its-size-from-the-layout
  ;; a nested head turned 45 degrees in a flex main is set to fill; the
  ;; layout resizes it along the main's axis, which sets both its sizes
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :flex :library-depth 1 :library true :rich-variants true
                                    :library-text true :layout-copies true :text true}]
                       :ops [[:rotate 5 0 0 0] [:style 8 29 1]]}))))

(t/deftest generated-removed-flex-child-reflows-its-layout
  ;; a child removed from a flex main closes the gap it leaves in the main
  ;; and its copies, so a later move of a copy child keeps its place
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout :flex :layout-children true}]
                       :ops [[:remove-child 1 0 0 0] [:transform 0 0 0 2]]}))))

(t/deftest generated-copy-nested-in-a-flex-main-takes-its-place-in-the-layout
  ;; a copy nested into a flex main takes its place in that layout, in the
  ;; main and its copies, so a later move of one of those keeps it there
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :variant-diff false
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:nest 6 0 0 0] [:transform 10 0 0 3]]}))))

(t/deftest generated-copy-synced-to-a-gridless-swap-drops-its-own-cells
  ;; a main swaps a nested grid copy for a gridless one; syncing only that
  ;; component turns the copy with its own cells gridless, which drops them
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout :grid :library true
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:swap 5 0 0 0] [:grid-cell 8 0 0 0] [:swap 0 0 0 0] [:sync 3 0 0 0]]}))))

(t/deftest generated-flipped-copy-may-store-the-opposite-turn
  ;; under one flip, a turn before or after the flip stores opposite turns
  ;; for the same placement, so a synced copy of a turned and flipped main
  ;; keeps the turn it authored in either form
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :grid :library-depth 2 :library true :rich-variants true
                                    :library-text true :grid-empty true :layout-copies true :text true}]
                       :ops [[:transform 5 9 0 13] [:library-transform 7 4 4 0] [:library-edit 0 0 0]
                             [:edit 0 1 1 2] [:library-sync]]}))))

(t/deftest generated-copy-text-with-its-own-family-and-weight-keeps-its-variant
  ;; a copy text sets its own family and the weight its main then takes too;
  ;; a later sync keeps the variant its own family names for that weight
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:nest 0 0 0 0] [:text-style 0 1 1] [:duplicate 0 0 0 0]
                             [:update-main 1 0 0 0] [:text-style 0 0 1]]}))))

(t/deftest generated-resized-copy-in-a-turned-board-pushes-its-own-turn
  ;; a copy turned with a board that is detached and then resized pushes its
  ;; size to its main but not the board's turn, which sync gives it back
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:rotate 15 0 0 0] [:detach 15 0 0 0] [:delete 5] [:restore 5]
                             [:resize 14 0 0 0] [:edit 3 0 1 1] [:update-main 3]]}))))

(t/deftest generated-switched-copy-keeps-its-childs-flip
  ;; a copy child flipped horizontally keeps its flip over a variant switch
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:transform 0 4 0 2] [:switch 0 0 0 0]]}))))

(t/deftest generated-turned-copy-child-set-to-fill-takes-both-layout-sizes
  ;; a copy child turned 45 degrees in a flex main fills its parent's width,
  ;; which resizes it along the parent's axes and so changes both its sizes
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :library true
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:rotate 6 0 0 0] [:style 4 29 1]]}))))

(t/deftest generated-copy-grid-with-its-own-direction-pins-a-new-main-child
  ;; a copy grid flowing by columns pins the main's new child in its row-order
  ;; cell, since its own flow would move the child out of that cell
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout :grid :grid-square true}]
                       :ops [[:grid-direction 0 0 1 1] [:grid-cell 0 0 3 1] [:add-child 4 0 0 0]]}))))

(t/deftest generated-restored-main-reflows-its-copies-layouts
  ;; restoring a flex main syncs its copies, and the layout places again a
  ;; copy child with its own margin, so a later move of it overrides nothing
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout :flex :layout-children true}]
                       :ops [[:style 3 10 1] [:delete 4] [:restore 4] [:transform 0 0 0 7]]}))))

(t/deftest generated-single-line-flex-set-to-space-between-lays-out
  ;; aligning the content of a one-line wrapped flex main by space-between
  ;; leaves no gap to share between its lines
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout :flex :library true
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:style 0 23 18 0]]}))))

(t/deftest generated-detached-copy-child-with-its-own-constraint-stays-in-place
  ;; a nested copy child with its own center constraint keeps its place when
  ;; the copy around it is detached, as an override of its new main, and a
  ;; later sync leaves it there
  (t/is (map? (cg/run {:world [2 2 {:text true :text-styles true}]
                       :ops [[:style 8 9 3 1] [:resize 2 0 0 0] [:detach 3 0 0 0]]}))))

(t/deftest generated-turned-main-keeps-a-flipped-copys-rotation-consistent
  ;; a main turned 45 degrees turns the subtree of a flipped copy; a mirrored
  ;; child stores the rotation its transform and flip decompose into
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:rotate 3 0 5 0] [:duplicate 0 0 0 0] [:duplicate 0 0 0 0]
                             [:transform 5 4 0 4] [:rotate 0 0 0 0]]}))))

(t/deftest generated-copy-child-override-keeps-its-constraint-when-sync-turns-and-resizes-its-parent
  ;; a copy child moved on its own stays at its distance from the top of its
  ;; parent when an update main turns the parent back and shrinks it
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true}]
                       :ops [[:resize 2 0 0 0] [:detach 3 0 0 0] [:detach 11 0 0 0] [:rotate 10 0 0 0]
                             [:update-main 0 0 0 0] [:transform 0 0 0 12] [:update-main 7 0 0 0]]}))))

(t/deftest generated-flipped-copies-compare-either-sign-of-their-turn
  ;; normal sync and a full sync may store opposite turns for the same
  ;; mirrored transform; both describe one design
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:rotate 1 0 0 0] [:swap 1 0 0 0] [:swap 1 0 0 0] [:switch 3 0 0 0]
                             [:transform 1 4 0 0]]}))))

(t/deftest generated-detach-around-a-removed-library-child-leaves-it-plain-until-accepted
  ;; detaching a stale consumer copy unlinks a child whose library main was
  ;; removed; undoing the removal in the library leaves it plain until the
  ;; consumer accepts the library
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:library-delete 0 0 0 0] [:library-restore 0 0 0 0] [:library-remove-child 3 0 0 0]
                             [:detach 11 0 0 0] [:library-undo] [:library-redo]]}))))

(t/deftest generated-swap-brings-its-size-to-a-copy-child-with-its-own-constraint
  ;; a copy child with its own scale constraint takes the size of the
  ;; component its main swaps it for, as the other copies do
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:style 24 8 1] [:swap 2 0 0 0] [:edit 3 0 1 1] [:update-main 3]
                             [:update-main 0 0 0 0]]}))))

(t/deftest generated-flipped-copy-child-may-store-another-rotation-for-its-transform
  ;; a flipped copy child turned and resized by an update main, and a copy
  ;; child under a flipped parent after a library sync, keep the corners of
  ;; their expected transform; the rotation each stores for it is not checked
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true}]
                       :ops [[:resize 2 0 0 0] [:detach 3 0 0 0] [:detach 11 0 0 0] [:rotate 10 0 0 0]
                             [:rotate 10 0 0 0] [:update-main 0 0 0 0] [:transform 0 4 0 12]
                             [:update-main 7 0 0 0]]})))
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :library-depth 1 :library true :rich-variants true :library-text true
                                    :layout-copies true :text true}]
                       :ops [[:library-transform 0 3 4 0] [:swap 12 0 0 0] [:transform 10 11 0 3]
                             [:library-transform 0 5 4 1] [:library-edit 0 0 0] [:edit 0 1 1 2] [:library-sync]]}))))

(t/deftest generated-detached-switched-head-keeps-its-own-size-at-a-moved-place
  ;; a switched head linked by a detach to a moved position main keeps that
  ;; place with the size of its own variant
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:switch 9 0 0 0] [:transform 0 0 0 8] [:detach 3 0 0 0]]}))))

(t/deftest generated-pushed-switch-takes-the-place-its-copy-authored
  ;; a switched head pushed into a copy in a main takes the place the copy
  ;; child it replaces had moved to, as its own geometry
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:delete 5 0 0 0] [:switch 3 0 0 0] [:transform 0 0 0 3] [:update-main 0 0 0 0]]}))))

(t/deftest generated-switched-head-in-a-turned-main-inherits-its-turn
  ;; a resized nested head of a turned main switches to a variant of
  ;; another size; it takes that size, and the turn its parent gives it is
  ;; not an override
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:resize 3 0 0 0] [:duplicate 0 0 0 0] [:transform 1 2 0 0] [:rotate 3 0 0 0]
                             [:update-main 4] [:nest 4 0 0 0] [:delete 5] [:resize 1 0 1 0] [:switch 3 0 0 0]]}))))

(t/deftest generated-half-turn-of-a-flipped-copy-child-authors-its-turn
  ;; a half turn of a flipped copy child stores a rotation that reads like
  ;; its old one under the flip, while its corners turn
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:rotate 0 0 5 0] [:transform 0 4 0 3] [:update-main 1 0 0 0] [:rotate 0 0 3 0]]}))))

(t/deftest generated-switch-keeps-a-moved-copy-child-in-place-as-its-own
  ;; a copy child of a copy moved and switched to another variant keeps the
  ;; moved place as its own geometry
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:nest 4 0 0 0] [:transform 1 0 0 3] [:switch 3 0 0 0]]}))))

(t/deftest generated-pushed-swap-of-a-main-child-keeps-its-own-size
  ;; a swap pushed into a main replaces a resized nested copy that the main
  ;; holds directly; the new nested copy keeps the size of its own component
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:swap 23 0 0 0] [:resize 0 0 0 0] [:edit 3 0 1 1] [:update-main 3]]}))))

(t/deftest generated-switch-keeps-the-inherited-turn-of-a-copy-child
  ;; a copy child of a turned copy, whose position main is resized, keeps
  ;; the turn it inherits through a switch without an override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:resize 0 0 0 0] [:rotate 1 0 0 0] [:switch 4 0 0 0]]}))))

(t/deftest generated-reflow-of-a-copy-grid-keeps-its-cells-inherited
  ;; a copy child switched to a hidden variant leaves its grid cell; the
  ;; layout reflow reassigns the copy grid's cells without authoring them
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:nest 6 0 0 0] [:style 8 1 1] [:switch 4 0 0 0]]}))))

(t/deftest generated-reset-in-a-reversed-flex-copy-stays-with-its-parent
  ;; a copy inside a nested copy that a resized, reversed flex copy places
  ;; elsewhere than its main is reset beside that parent
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout :flex :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:edit 18 0 0 0] [:delete 0 0 0 0] [:edit 3 0 1 1] [:update-main 3] [:update-main 0 0 0 0]
                             [:resize 6 0 0 0] [:nest 16 0 0 0] [:swap 16 0 0 0] [:detach 2 0 0 0] [:style 8 20 2 0]
                             [:reset 13 0 0 0]]}))))

(t/deftest generated-switch-keeps-the-layout-item-override-it-carries
  ;; a nested copy in a main with its own layout item margin keeps the
  ;; margin through a switch, and the override that authors it
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout :flex :variant-diff false :rich-variants true
                                    :layout-children true :layout-copies true :text true}]
                       :ops [[:delete 6 0 0 0] [:swap 20 0 0 0] [:nest 7 0 0 0] [:style 13 27 1] [:switch 0 0 0 0]]}))))

(t/deftest generated-pushed-resize-under-a-turned-copy-root-turns-once
  ;; update-main pushes a turned copy root and a resized copy child; the
  ;; main child takes the main's new turn once
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :variant-diff false :rich-variants true
                                    :layout-children true :layout-copies true :text true}]
                       :ops [[:transform 10 1 0 2] [:rotate 7 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-switch-in-a-turned-copy-keeps-a-turn-its-near-main-authors
  ;; a copy child inherits the turn of its turned near main and of its
  ;; turned copy parent; a switch keeps that authored turn as an override
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:rotate 1 0 0 0] [:rotate 0 0 0 0] [:switch 3 0 0 0]]}))))

(t/deftest generated-switch-resizes-a-kept-size-with-its-board
  ;; a copy child widens itself in a wide variant board; a switch to a
  ;; narrower variant resizes that size through the child's constraints
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:switch 0 0 0 0] [:transform 0 1 0 7] [:switch 0 0 0 0]]}))))

(t/deftest generated-swap-keeps-a-moved-copy-child-in-place-as-its-own
  ;; a copy child of a copy moved and swapped for another component keeps
  ;; the moved place as its own geometry
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:text 14 0 0 0] [:nest 0 0 0 0] [:transform 0 0 0 18] [:swap 12 0 0 0]]}))))

(t/deftest generated-update-main-resizes-a-main-childs-own-size-through-its-constraints
  ;; a main's nested copy authors its size and scales vertically; a copy
  ;; of that main pushes a new height, which resizes the nested copy with it
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:resize 1 0 0 0] [:delete 0] [:restore 0] [:style 1 9 1] [:resize 0 0 0 0]
                             [:update-main 1 0 0 0]]}))))

(t/deftest a-swapped-minimum-size-head-takes-the-first-corner-of-its-turned-place
  ;; a 0.01 px head swapped into a turned copy; its transform is noisy at
  ;; that size, and it still takes the first corner of the place it fills
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square true :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 5 0 0 0] [:swap 4 0 0 0] [:nest 0 0 0 0] [:duplicate 1 0 0 0]
                             [:rotate 1 0 0 0] [:resize 1 0 0 0] [:rotate 4 0 2 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-update-main-keeps-the-mains-size-over-a-size-a-constraint-keeps
  ;; a copy child keeps its size through its own scale constraint while its
  ;; main, a main's nested copy, takes a height of 80; update main pushes
  ;; the constraint and the main keeps its 80
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:style 16 8 1] [:edit 11 11 0 0] [:resize 0 0 0 0] [:update-main 4 0 0 0]]}))))

(t/deftest generated-switch-turns-a-carried-size-with-the-heads-kept-turn
  ;; the old variant main is turned, so its copy inherits the turn and keeps
  ;; it on switch; a widened copy child carries its size and turns with it
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 0] [:restore 0] [:transform 0 15 0 0] [:transform 0 1 0 17]
                             [:switch 0 0 0 0]]}))))

(t/deftest generated-detach-keeps-the-size-a-copy-constraint-keeps
  ;; a copy child keeps its size through its own scale constraint while the
  ;; main link it refers to authors a height; a detach of the copy around it
  ;; keeps the size it shows
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:style 16 8 1] [:resize 0 0 0 0] [:detach 1 0 0 0]]}))))

(t/deftest generated-detach-keeps-the-turn-a-swapped-head-had-from-its-position
  ;; a swapped head turned by its position becomes a copy root of its own
  ;; unturned component when its copy root is detached; it keeps the turn
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 15 0 0 0] [:nest 7 0 0 0] [:edit 3 0 1 1] [:update-main 3]
                             [:text-style 18 0 1] [:duplicate 0 0 0 0] [:nest 15 0 0 0] [:switch 6 0 0 0]
                             [:rotate 19 0 0 0] [:detach 3 0 0 0]]}))))

(t/deftest generated-turned-fill-child-of-a-grid-settles
  ;; a grid main's child board fills its width and is turned 45 degrees
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :grid :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children true :library-text true
                                    :grid-empty true :layout-copies true :text true}]
                       :ops [[:style 13 29 1] [:rotate 5 0 0 0]]}))))

(t/deftest generated-detach-leaves-a-nested-copy-that-only-takes-its-parents-turn
  ;; a turned copy root is detached; its nested copy, whose main sits at the
  ;; root of its page, only shows the turn of its now plain parent
  (t/is (map? (cg/run {:world [2 2 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:delete 0] [:restore 0] [:undo 0 0 0 0] [:delete 11] [:restore 11]
                             [:delete 1 0 0 0] [:delete 3 0 0 0] [:rotate 4 0 0 0] [:detach 11 0 0 0]]}))))

(t/deftest generated-switch-does-not-carry-the-turn-a-child-takes-from-its-copy
  ;; a turned copy's child is moved and the copy switches: the child's turn
  ;; comes from the copy, so the switch carries no geometry override
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:rotate 0 0 0 0] [:transform 0 0 0 2] [:switch 0 0 0 0]]}))))

(t/deftest generated-switch-keeps-a-flipped-heads-first-corner
  ;; both variant mains are flipped; a flipped nested copy switches to the
  ;; wider variant and keeps its first corner, its top right
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:transform 1 4 0 0] [:delete 5 0 0 0] [:delete 0] [:restore 0]
                             [:transform 1 4 0 0] [:switch 0 0 0 0]]}))))

(t/deftest generated-move-of-a-resized-nested-copy-in-a-main-keeps-its-size-only
  ;; a main's nested copy is resized, then moved; its place in the main is
  ;; not an override, so only its size is expected
  (t/is (map? (cg/run {:world [2 2 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:resize 1 0 0 0] [:transform 1 0 0 4]]}))))

(t/deftest generated-turning-a-grid-turns-its-fill-child-rigidly
  ;; a grid copy whose child fills its width is turned 45 degrees: the child
  ;; turns with it, unskewed, as a full sync places it
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :grid :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children true :library-text true
                                    :grid-empty true :layout-copies true :text true}]
                       :ops [[:style 13 29 1] [:rotate 6 0 0 0]]}))))

(t/deftest generated-update-main-turns-a-pushed-child-once
  ;; an update main turns a main's nested board and pushes its absolute
  ;; child with the turn it already shows; the child is not turned again
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:style 7 35 1] [:rotate 6 0 0 0] [:update-main 3 0 0 0] [:update-main 0 0 0 0]]}))))

(t/deftest generated-switch-finds-the-main-of-a-copy-in-a-swapped-copy
  ;; a switch of a nested copy inside a swapped copy, itself inside a copy of
  ;; a deleted component, carries its child's text override from the main
  ;; the swapped copy holds
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 6 0 0 0] [:text 6 0 0 0] [:delete 6 0 0 0] [:swap 6 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest generated-switch-carries-no-flip-a-head-takes-from-its-parent
  ;; a switched head inside a flipped copy takes the flip of its parent, and a
  ;; move of its main's nested copy is its position: neither is an override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:transform 1 16 0 3] [:transform 0 0 0 3] [:switch 6 0 0 0]]}))))

(t/deftest generated-duplicate-in-a-grid-resizes-a-copy-subtree-alone
  ;; a sync that resizes a copy in a grid lays out the copy's subtree while
  ;; the grid has a duplicated child with no cell yet; the program reaches
  ;; that sync on every other run, so it runs twice
  (dotimes [_ 2]
    (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :grid :variant-diff false
                                      :layout-item-margins false :grid-square false :rich-variants true
                                      :layout-children false :grid-empty true :layout-copies true :text true}]
                         :ops [[:style 0 29 1] [:nest 9 0 0 0] [:duplicate 5 1 0 0]]})))))

(t/deftest generated-switch-in-a-swapped-turned-copy-keeps-its-place
  ;; a switched copy in a swapped head of a turned copy is placed in the
  ;; main of the head's content, which holds it unturned; no turn is pending
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:rotate 3 0 0 0] [:nest 6 0 0 0] [:edit 0 0 0 2] [:resize 3 0 0 0] [:text 6 0 0 0]
                             [:edit 4 0 1 1] [:update-main 4] [:delete 6 0 0 0] [:swap 6 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest generated-transform-keeps-a-turn-stored-with-both-flips-the-other-way
  ;; a transform of a restored, turned copy stores its flips and turn as the
  ;; other decomposition of the same transform: both flips inverted and the
  ;; turn half a turn on
  (t/is (map? (cg/run {:world [2 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:nest 1 0 0 0] [:delete 0] [:restore 0] [:rotate 0 0 5 0] [:transform 6 4 0 0]]}))))

(t/deftest generated-switch-carries-no-geometry-the-new-copy-already-shows
  ;; a switched head inherits a turn its new copy already shows up to float
  ;; noise in the transform; the switch carries no geometry override for it
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 6 0 0 0] [:rotate 21 0 0 0] [:duplicate 21 0 0 0] [:switch 0 0 0 0]
                             [:switch 0 0 0 0] [:edit 6 0 1 1] [:update-main 6] [:switch 9 0 0 0]]})))
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:rotate 0 0 0 0] [:update-main 0 0 0 0] [:resize 0 0 0 0] [:switch 4 0 0 0]]}))))

(t/deftest generated-switch-in-a-copy-takes-the-turn-of-its-position
  ;; a switch to a turned variant inside a copy carries the copy's height
  ;; override; the head keeps the turn of its position, not its new main's
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 0 0 0 0] [:resize 0 0 0 0] [:transform 14 3 0 0] [:switch 15 0 0 0]]}))))

(t/deftest generated-switch-in-a-copy-keeps-a-turn-that-cancels-its-parents
  ;; a copy child shows no turn: its near main's half turn cancels its turned
  ;; copy parent's; a switch keeps that authored turn as an override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:duplicate 0 0 0 0] [:rotate 16 0 7 0] [:duplicate 0 0 0 0] [:rotate 16 0 7 0]
                             [:switch 12 0 0 0]]}))))

(t/deftest generated-copy-child-released-by-its-layout-keeps-the-layouts-size
  ;; a copy child authors a width, its main then fills its flex parent and
  ;; finally leaves the flow: the child keeps the width the fill gave it
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:transform 4 1 0 12] [:style 0 29 1] [:style 0 35 1]]}))))

(t/deftest generated-switch-carries-no-layout-item-override-its-head-inherits
  ;; a copy's nested board inherits a margin its main's nested copy authors;
  ;; a switch keeps the margin without making it the head's own override
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout :flex :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 6 0 0 0] [:style 1 10 0 0] [:switch 3 0 0 0]]}))))

(t/deftest generated-update-main-turns-a-child-under-a-pushed-turn-once
  ;; a copy turns a nested copy and resizes a grandchild below it; an update
  ;; main pushes both, and the grandchild takes the pushed turn once
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 3 0 0 0] [:detach 1 0 0 0] [:transform 4 1 0 7] [:transform 3 3 0 8]
                             [:update-main 0 0 0 0]]}))))

(t/deftest generated-moved-copy-child-scales-with-a-constraint-its-main-gets-later
  ;; a copy child authors a move; its main then scales it vertically with its
  ;; board, and a resize of the copy's board scales the moved child
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 1
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:transform 0 0 0 2] [:delete 0 0 0 0] [:style 2 9 1] [:resize 0 0 0 0]]}))))

(t/deftest generated-switch-undo-restores-a-swapped-child-the-new-variant-has-no-place-for
  ;; a switched head holds a swapped board whose place the new variant lacks;
  ;; the switch deletes it with the old head, and undo brings it back
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 8 0 0 0] [:edit 18 1 1 :copy-head] [:reset 18] [:undo 0 0 0 0]
                             [:swap 2 0 0 0] [:duplicate 4 0 0 0] [:delete 8] [:restore 8] [:undo 0 0 0 0]
                             [:text-style 4 0 1] [:delete 0 0 0 0] [:nest 21 0 0 0] [:duplicate 0 0 0 0]
                             [:swap 16 0 0 0] [:switch 0 0 0 0]]}))))

(t/deftest generated-switch-to-a-flipped-variant-stores-the-turn-its-transform-has
  ;; a switch to a variant whose main is flipped keeps the copy's unflipped
  ;; turn; the new head stores the rotation its transform decomposes into
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout nil :layout-item-margins false :grid-square false :library-depth 2
                                    :library true :rich-variants true :layout-children false :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:library-transform 9 3 4 0] [:delete 5] [:restore 5] [:library-transform 9 4 0 0]
                             [:library-transform 4 3 0 0] [:library-sync 0 0 0 0] [:switch 13 0 0 0]]}))))

(t/deftest generated-switch-carries-a-heads-turn-and-flips-as-one
  ;; a switched head turned like its position switches between variants that
  ;; share their rotation but not their flip; its turn carries no override
  (t/is (map? (cg/run {:world [3 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:swap 14 0 0 0] [:rotate 1 0 0 0] [:delete 2 0 0 0] [:transform 17 4 0 0]
                             [:nest 4 0 0 0] [:swap 0 0 0 0] [:switch 18 0 0 0]]}))))

(t/deftest generated-direction-change-in-a-copy-lays-out-its-mains-cells
  ;; a copy without cell overrides takes its main's cells in the direction
  ;; it changes to, pins included; changing the direction back gives the
  ;; main's grid again
  (t/is (map? (cg/run {:world [3 0 {:text true :text-styles true :layout :grid :layout-children false
                                    :layout-item-margins false :grid-square true :grid-empty false}]
                       :ops [[:grid-direction 1 0 1 0] [:add-child 5 0 0 0] [:grid-direction 0 0 0 1]
                             [:grid-cell 0 0 0 1]]})))
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout :grid :layout-children true
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:delete 0 0 0 0] [:restore 0 0 0 0] [:delete 0 0 0 0] [:grid-direction 0 0 0 1]
                             [:grid-cell 0 0 0 1] [:restore 0 0 0 0] [:delete 0 0 0 0] [:grid-direction 5 0 3 0]
                             [:add-child 1 0 0 0] [:update-main 0 0 0 0] [:grid-direction 0 0 0 0]
                             [:grid-direction 0 0 3 0]]}))))

(t/deftest generated-tiny-swapped-head-turns-its-content-rigidly
  ;; a swapped head shrunk to 0.01 px and turned has a transform carrying its
  ;; rounding; a nest into its content main turns the new child by the head's
  ;; axes alone, without scaling it
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :layout :flex :layout-item-margins true
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty true :layout-copies true :text true}]
                       :ops [[:rotate 3 0 0 0] [:delete 16] [:restore 16] [:delete 16 0 0 0]
                             [:style 1 10 1] [:delete 1 0 0 0] [:restore 0 0 0 0] [:edit 10 0 1 1]
                             [:update-main 10] [:undo 0 0 0 0] [:nest 19 0 0 0] [:detach 1 0 0 0]
                             [:detach 2 0 0 0] [:delete 23 0 0 0] [:restore 3 0 0 0] [:swap 13 0 0 0]
                             [:nest 21 0 0 0]]}))))

(t/deftest generated-sync-may-store-a-mirror-with-the-other-flip
  ;; a full sync stores a mirrored grid child with flip-x where the settled
  ;; copy has flip-y, both with the same transform: the same design
  (t/is (map? (cg/run {:world [3 1 {:text true :text-styles true :layout :grid :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:rotate 4 0 0 0] [:nest 7 0 0 0] [:duplicate 0 0 0 0] [:rotate 4 0 0 0]
                             [:duplicate 0 0 0 0] [:grid-cell 8 0 0 0] [:detach 0 0 0 0] [:transform 6 4 0 9]]}))))

(t/deftest generated-deleted-component-keeps-its-laid-out-geometry-out-of-sync
  ;; a component whose main holds a fill child in a turned grid is deleted:
  ;; its stored objects keep the geometry the layout gave that child and the
  ;; shapes below it, which a sync, laying nothing out, does not reproduce
  (t/is (map? (cg/run {:world [3 2 {:text true :text-styles true :layout :grid :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty true}]
                       :ops [[:text-style 7 0 1] [:nest 29 0 0 0] [:detach 0 0 0 0] [:style 10 5 1]
                             [:delete 8 0 0 0] [:rotate 12 0 0 0] [:delete 12 0 0 0] [:nest 13 0 0 0]
                             [:duplicate 11 0 0 0] [:style 0 29 4 0] [:update-main 11 0 0 0]
                             [:delete 1 0 0 0]]}))))

(t/deftest generated-copy-may-name-its-weight-by-another-variant-id
  ;; a copy's bold takes the main's Roboto and keeps sourcesanspro's variant
  ;; id "bold"; restyling it to Roboto 700 names the same weight "700",
  ;; which leaves it untouched, and a full sync gives it back "bold"
  (t/is (map? (cg/run {:world [2 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:text-style 3 1 1] [:text-style 0 0 1] [:update-main 0 0 0 0]
                             [:text-style 1 1 3 0]]}))))

(t/deftest generated-turned-mirror-keeps-the-way-its-rotation-turns
  ;; update main pushes a turn into a main whose nested copy is a mirror
  ;; storing the opposite of its transform's turn: the turn is subtracted
  (t/is (map? (cg/run {:world [3 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:nest 2 0 0 0] [:rotate 3 0 0 0] [:rotate 4 0 0 0] [:transform 4 4 0 4]
                             [:update-main 3 0 0 0]]}))))

(t/deftest generated-switched-head-in-a-flex-flow-authors-no-place
  ;; a turned head switched in a copy's flex board keeps its size; the
  ;; layout places it, so its place is not an override
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:nest 6 0 0 0] [:swap 6 0 0 0] [:transform 0 3 0 3] [:switch 1 0 0 0]]}))))

(t/deftest generated-tiny-copy-in-a-resized-turned-main-keeps-its-children-size
  ;; a turned main resized past the minimum size of a nested 0.01 px copy
  ;; leaves a scale in that copy's transform; a full sync turns its children
  ;; without that scale, as the settled sync does
  (t/is (map? (cg/run {:world [2 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:duplicate 0 0 0 0] [:edit 8 0 0 0] [:resize 2 0 2 0] [:nest 0 0 0 0]
                             [:nest 12 0 0 0] [:delete 6 0 0 0] [:detach 8 0 0 0] [:restore 0 0 0 0]
                             [:duplicate 0 0 0 0] [:transform 3 3 0 8] [:delete 0] [:restore 0]
                             [:edit 4 0 1 1] [:update-main 4] [:delete 0 0 0 0] [:transform 2 2 0 0]]}))))

(t/deftest generated-squashed-turned-shape-compares-what-its-inverse-undoes
  ;; a turned shape squashed to a ten-thousandth along one axis keeps a
  ;; rounding translation in its transform that its inverse magnifies; the
  ;; inverse is compared by what it undoes of the transform
  (t/is (map? (cg/run {:world [3 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:delete 0] [:restore 0] [:delete 2] [:restore 2] [:undo 0 0 0 0]
                             [:rotate 0 0 1 0] [:edit 0 0 1 1] [:update-main 0] [:duplicate 0 0 0 0]
                             [:nest 6 0 0 0] [:transform 9 0 0 3] [:delete 1 0 0 0] [:resize 0 0 0 0]
                             [:duplicate 0 0 0 0] [:edit 0 0 1 1] [:update-main 0] [:resize 2 0 0 0]
                             [:sync 0 0 0 0]]}))))

(t/deftest generated-detached-nested-copy-kept-in-place-follows-its-parent-resize
  ;; a detach leaves a nested copy child where its new main would not place
  ;; it, so it keeps that place; a later resize of its copy parent still
  ;; sizes it through its scale constraint
  (t/is (map? (cg/run {:world [3 1 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:delete 0 0 0 0] [:delete 0] [:restore 0] [:delete 0 0 0 0] [:style 3 9 1]
                             [:restore 0 0 0 0] [:transform 5 0 0 1] [:detach 1 0 0 0]
                             [:transform 1 2 0 4]]}))))

(t/deftest generated-direction-change-in-a-swapped-copy-overrides-its-moved-cells
  ;; a copy that keeps another component than its main's slot takes its
  ;; cells from its own component's main; a direction change that moves its
  ;; items makes them an override, though the slot's board has no grid
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :library-variants true :stale-libraries true
                                    :layout :grid :layout-item-margins false :grid-square true :library-depth 1
                                    :library true :rich-variants true :layout-children true :library-text true
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:delete 9 0 0 0] [:delete 0] [:restore 0] [:delete 17] [:restore 17]
                             [:swap 4 0 0 0] [:grid-direction 0 0 1 1] [:grid-cell 0 0 0 1]]}))))

(t/deftest generated-turn-and-flips-match-by-the-transform-they-decompose
  ;; a stored rotation and flips are compared by the transform they
  ;; decompose: a mirrored child turned with its parent stores the turn
  ;; added, and both flips may store the turn their transform shows
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout :flex :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:transform 8 4 0 0] [:transform 5 3 5 2] [:transform 5 4 0 8]]})))
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :layout nil :layout-item-margins false
                                    :grid-square false :library true :rich-variants true :layout-children false
                                    :grid-empty false :layout-copies true :text true}]
                       :ops [[:rotate 8 0 0 0] [:transform 9 4 0 14] [:rotate 9 0 0 0] [:edit 11 0 1 1]
                             [:update-main 11]]}))))

(t/deftest generated-skewed-turned-copy-compares-its-stored-turn
  ;; a turned child of a copy squashed to almost no width skews: no turn
  ;; and flips make up its transform, so its stored rotation is compared
  (t/is (map? (cg/run {:world [2 0 {:text true :text-styles true :layout nil :layout-children false
                                    :layout-item-margins false :grid-square false :grid-empty false}]
                       :ops [[:detach 1 0 0 0] [:resize 0 0 0 0] [:nest 0 0 0 0] [:duplicate 0 0 0 0]
                             [:duplicate 0 0 0 0] [:detach 2 0 0 0] [:style 3 9 1] [:rotate 6 0 0 0]
                             [:detach 12 0 0 0]]}))))
