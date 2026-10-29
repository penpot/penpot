;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-gen-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
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
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

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
            copies     (filter #(= (:shape-ref shape) (get-in % [:shape :shape-ref])) (cg/tree situation))]
        (t/is (= expected (get-in edited [:model id group])))
        (t/is (= #{group} (:touched (tm/shape-by-id after id))))
        (t/is (= (:layout-item-margin shape) (:layout-item-margin (tm/shape-by-id after id))))
        (t/is (= :I9 (invariant reverted)))
        (t/is (= :I3 (invariant (update-in file (conj path :touched) disj group))))
        (t/is (some? main-index))
        (t/is (= 2 (count copies)))
        (doseq [{copy :shape} copies]
          (let [actual (tm/shape-by-id (:situation updated) (:id copy))]
            (t/is (= (if (= id (:id copy)) (:value resolved) value) (get actual attr)))
            (t/is (= (if (= id (:id copy)) #{group} #{}) (set (:touched actual))))))
        (t/is (= expected (get-in updated [:model id group])))))))

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
    (t/is (empty? (cg/coverage-errors @coverage cg/stale-library-kinds #{:sync}))
          (pr-str (cg/coverage-errors @coverage cg/stale-library-kinds #{:sync})))))

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
