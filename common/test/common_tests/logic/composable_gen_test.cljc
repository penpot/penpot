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

(t/deftest structure-and-text-operations-run-through-production-nodes
  (let [result (cg/run {:world [2 0 {:text true :layout :flex}]
                        :ops [[:edit 0 0 1] [:add-child 0] [:remove-child 0]
                              [:duplicate 0] [:nest 0] [:delete 0] [:restore 0]
                              [:text 0 0 1] [:undo] [:redo]]})]
    (doseq [kind [:add-child :remove-child :duplicate :nest :delete :restore :text]]
      (t/is (pos? (get-in result [:executed kind] 0)) (str kind " did not execute")))))

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
  (let [initial (cg/initial-state [3 0])
        source (get-in (cg/resolve-op (:situation initial) {} [:nest 0]) [:shape :id])
        nested (-> initial (cg/step [:nest 0]) (cg/settle-state))
        candidates (cg/candidates (:situation nested) (:model nested) :nest nil)
        index (first (keep-indexed #(when (= source (get-in %2 [:shape :id])) %1) candidates))
        state (cg/step nested [:nest index])
        partial (cg/step state [:sync 1])
        settled (cg/settle-state partial)]
    (t/is (= 2 (count (r/pending-components (:situation state)))))
    (t/is (= 1 (count (r/pending-components (:situation partial)))))
    (t/is (empty? (r/pending-components (:situation settled))))))

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

(t/deftest update-main-after-deleting-a-swapped-main-keeps-the-model-in-step
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 1 0 0 0] [:delete 2 0 0 0] [:update-main 0 0 0 0]]}))))

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

(t/deftest switching-a-copy-of-a-swap-keeps-position-overrides-inherited
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:switch 13 0 0 0] [:rotate 0 0 0 0] [:switch 2 0 0 0]]}))))

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

(t/deftest pushing-a-swap-keeps-the-orientation-inherited-from-its-position
  (t/is (map? (cg/run {:world [2 1 {:library true :variants true}]
                       :ops [[:edit 0 0 1 2] [:update-main 0] [:library-sync]
                             [:undo] [:redo] [:reset 0] [:sync 1]
                             [:rotate 0 0 0 0] [:swap 13 0 0 0] [:update-main 1 0 0 0]]}))))

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

(t/deftest generated-update-main-pushes-a-swap-in-its-mains-orientation
  ;; a nested copy turned with the main that holds it swaps a child and
  ;; pushes it into its own unturned main, which takes it unturned
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:swap 6 0 0 0] [:transform 17 3 0 0] [:swap 9 0 0 0] [:edit 0 0 1 1] [:update-main 0]]}))))

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

(t/deftest generated-swap-brings-its-size-to-a-copy-child-with-its-own-constraint
  ;; a copy child with its own scale constraint takes the size of the
  ;; component its main swaps it for, as the other copies do
  (t/is (map? (cg/run {:world [2 1 {:variants true :text-styles true :library true
                                    :rich-variants true :layout-copies true :text true}]
                       :ops [[:style 24 8 1] [:swap 2 0 0 0] [:edit 3 0 1 1] [:update-main 3]
                             [:update-main 0 0 0 0]]}))))

(t/deftest generated-pushed-switch-takes-the-place-its-copy-authored
  ;; a switched head pushed into a copy in a main takes the place the copy
  ;; child it replaces had moved to, as its own geometry
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :variant-diff false :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:delete 5 0 0 0] [:switch 3 0 0 0] [:transform 0 0 0 3] [:update-main 0 0 0 0]]}))))

(t/deftest generated-switch-keeps-a-moved-copy-child-in-place-as-its-own
  ;; a copy child of a copy moved and switched to another variant keeps the
  ;; moved place as its own geometry
  (t/is (map? (cg/run {:world [2 0 {:variants true :text-styles true :library true :rich-variants true
                                    :layout-copies true :text true}]
                       :ops [[:nest 4 0 0 0] [:transform 1 0 0 3] [:switch 3 0 0 0]]}))))

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

(t/deftest generated-swap-keeps-a-moved-copy-child-in-place-as-its-own
  ;; a copy child of a copy moved and swapped for another component keeps
  ;; the moved place as its own geometry
  (t/is (map? (cg/run {:world [2 2 {:variants true :text-styles true :layout nil :variant-diff false
                                    :layout-item-margins false :grid-square false :rich-variants true
                                    :layout-children false :grid-empty false :layout-copies true :text true}]
                       :ops [[:text 14 0 0 0] [:nest 0 0 0 0] [:transform 0 0 0 18] [:swap 12 0 0 0]]}))))

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
