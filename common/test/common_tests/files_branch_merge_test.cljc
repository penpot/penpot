;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files-branch-merge-test
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.files.changes :as cfc]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- mkdata
  [objects & {:keys [colors]}]
  {:pages-index  {:p1 {:id :p1 :name "Page 1" :objects objects}}
   :colors       (or colors {})
   :typographies {}
   :components   {}
   :media        {}})

(defn- pages-data
  [pages-index pages & {:keys [components]}]
  {:pages-index pages-index
   :pages pages
   :colors {} :typographies {} :media {}
   :components (or components {})})

(t/deftest identity-no-changes
  (let [base (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        r    (bm/compute-merge base base base :branch->main)]
    (t/is (= [] (:changes r)))
    (t/is (= [] (:conflicts r)))
    (t/is (= {:added 0 :modified 0 :deleted 0 :conflicts 0} (:stats r)))))

(t/deftest added-in-branch
  (let [base   (mkdata {:s1 {:id :s1 :name "A"}})
        branch (mkdata {:s1 {:id :s1 :name "A"}
                        :s2 {:id :s2 :name "B"}})
        r      (bm/compute-merge base base branch :branch->main)
        c      (first (filter #(= :added (:status %)) (:changes r)))]
    (t/is (= 1 (-> r :stats :added)))
    (t/is (= 0 (-> r :stats :conflicts)))
    (t/is (= :shape (:kind c)))
    (t/is (= :s2 (:id c)))
    (t/is (= :p1 (:page-id c)))
    (t/is (= "B" (:label c)))))

(t/deftest modified-in-branch
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"}})
        r      (bm/compute-merge base base branch :branch->main)
        c      (first (:changes r))]
    (t/is (= 1 (-> r :stats :modified)))
    (t/is (= :modified (:status c)))
    (t/is (= {:main "red" :branch "blue"} (get-in c [:changed-attrs :fill])))))

(t/deftest root-frame-and-shapes-churn-hidden
  ;; adding a top-level shape mutates the root frame's `:shapes`; that churn
  ;; (and the root frame itself) must NOT show up as a change — only the new shape.
  (let [base   (mkdata {uuid/zero {:id uuid/zero :name "Root Frame" :shapes [:s1]}
                        :s1 {:id :s1 :name "A"}})
        branch (mkdata {uuid/zero {:id uuid/zero :name "Root Frame" :shapes [:s1 :s2]}
                        :s1 {:id :s1 :name "A"}
                        :s2 {:id :s2 :name "B"}})
        r      (bm/compute-merge base base branch :branch->main)]
    (t/is (= #{:s2} (set (map :id (:changes r)))))
    (t/is (= 1 (-> r :stats :added)))
    (t/is (= 0 (-> r :stats :modified)))))

(t/deftest shape-with-only-structural-change-hidden
  ;; a shape whose ONLY diff is structural/derived (here children reorder)
  ;; is not reported as modified in the summary.
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :shapes [:c1 :c2]}})
        branch (mkdata {:s1 {:id :s1 :name "A" :shapes [:c2 :c1]}})
        r      (bm/compute-merge base base branch :branch->main)]
    (t/is (= [] (:changes r)))
    (t/is (= 0 (-> r :stats :modified)))))

(t/deftest derived-attrs-stripped-containment-kept-and-merged
  ;; SUMMARY strips only derived/cache attrs (:shapes/:selrect/:points), so
  ;; meaningful attrs incl. reparenting (:parent-id/:frame-id) stay visible.
  ;; MERGE applies scalar attrs via :set, but containment via :mov-objects
  ;; (never :set parent-id/frame-id — that would corrupt the tree).
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"
                             :parent-id :old :frame-id :old
                             :selrect {:x 0} :points [1 2] :shapes [:c1]}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"
                             :parent-id :new :frame-id :new
                             :selrect {:x 9} :points [3 4] :shapes [:c1 :c2]}})
        r      (bm/compute-merge base base branch :branch->main)
        c      (first (:changes r))
        chg    (bm/compute-changes base base branch)
        ops    (->> (:changes chg)
                    (filter #(= :mod-obj (:type %)))
                    first :operations (map :attr) set)
        movs   (filter #(= :mov-objects (:type %)) (:changes chg))]
    (t/is (= 1 (-> r :stats :modified)))
    ;; summary: meaningful attrs (incl. containment) survive; caches stripped
    (t/is (= #{:fill :parent-id :frame-id} (set (keys (:changed-attrs c)))))
    ;; merge: scalar attrs are :set; containment is NOT a :set op
    (t/is (contains? ops :fill))
    (t/is (not (contains? ops :parent-id)))
    (t/is (not (contains? ops :frame-id)))
    (t/is (not (contains? ops :shapes)))
    ;; merge: reparenting is applied via mov-objects to the branch parent
    (t/is (= 1 (count movs)))
    (t/is (= :new (:parent-id (first movs))))
    (t/is (= [:s1] (:shapes (first movs))))))

(t/deftest move-existing-layer-into-board-is-shown
  ;; reparenting an existing layer into a new board must surface as a change
  ;; on that layer (the user's "I moved the text into the board" action).
  (let [base   (mkdata {:t1 {:id :t1 :name "Text" :type :text
                             :parent-id uuid/zero :frame-id uuid/zero}})
        branch (mkdata {:t1 {:id :t1 :name "Text" :type :text
                             :parent-id :board :frame-id :board}
                        :board {:id :board :name "Board" :type :frame :shapes [:t1]}})
        r      (bm/compute-merge base base branch :branch->main)
        moved  (first (filter #(and (= :modified (:status %)) (= :t1 (:id %))) (:changes r)))]
    (t/is (some? moved))
    (t/is (contains? (:changed-attrs moved) :frame-id))))

(t/deftest shape-entries-carry-type-metadata
  ;; diff entries expose the shape type / component nature so the compare
  ;; view can pick a type-accurate icon and label.
  (let [base   (mkdata {})
        branch (mkdata {:t1 {:id :t1 :name "Title" :type :text}
                        :r1 {:id :r1 :name "Box" :type :rect}
                        :c1 {:id :c1 :name "Btn" :type :frame
                             :component-id (uuid/random) :main-instance true}})
        r      (bm/compute-merge base base branch :branch->main)
        by-id  (into {} (map (juxt :id identity)) (:changes r))]
    (t/is (= :text (get-in by-id [:t1 :shape-type])))
    (t/is (= :rect (get-in by-id [:r1 :shape-type])))
    (t/is (true? (get-in by-id [:c1 :component?])))))

(t/deftest deleted-in-branch
  (let [base   (mkdata {:s1 {:id :s1 :name "A"} :s2 {:id :s2 :name "B"}})
        branch (mkdata {:s1 {:id :s1 :name "A"}})
        r      (bm/compute-merge base base branch :branch->main)
        c      (first (:changes r))]
    (t/is (= 1 (-> r :stats :deleted)))
    (t/is (= :deleted (:status c)))
    (t/is (= :s2 (:id c)))))

(t/deftest fast-forward-main-untouched
  ;; main == base; branch changed -> all branch changes apply cleanly
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"}})
        r      (bm/compute-merge base base branch :branch->main)]
    (t/is (= 0 (-> r :stats :conflicts)))
    (t/is (= 1 (-> r :stats :modified)))))

(t/deftest main-only-change-not-reported
  ;; branch == base; main changed -> branch contributes nothing
  (let [base (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        main (mkdata {:s1 {:id :s1 :name "A" :fill "green"}})
        r    (bm/compute-merge base main base :branch->main)]
    (t/is (= [] (:changes r)))
    (t/is (= 0 (-> r :stats :conflicts)))))

(t/deftest modify-modify-conflict
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        main   (mkdata {:s1 {:id :s1 :name "A" :fill "green"}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"}})
        r      (bm/compute-merge base main branch :branch->main)
        cf     (first (:conflicts r))]
    (t/is (= 1 (-> r :stats :conflicts)))
    (t/is (= :modify-modify (:reason cf)))
    (t/is (= "green" (get-in cf [:changed-attrs :fill :main])))
    (t/is (= "blue" (get-in cf [:changed-attrs :fill :branch])))))

(t/deftest delete-modify-conflict
  ;; deleted in branch, modified in main
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        main   (mkdata {:s1 {:id :s1 :name "A" :fill "green"}})
        branch (mkdata {})
        r      (bm/compute-merge base main branch :branch->main)
        cf     (first (:conflicts r))]
    (t/is (= 1 (-> r :stats :conflicts)))
    (t/is (= :delete-modify (:reason cf)))))

(t/deftest color-added-in-branch
  (let [base   (mkdata {} :colors {})
        branch (mkdata {} :colors {:c1 {:id :c1 :name "Primary"}})
        r      (bm/compute-merge base base branch :branch->main)
        c      (first (filter #(= :color (:kind %)) (:changes r)))]
    (t/is (= :added (:status c)))
    (t/is (= "Primary" (:label c)))))

;; --- component :modified-at noise (bookkeeping timestamp)

(defn- comp-data
  [components]
  {:pages-index  {:p1 {:id :p1 :name "Page 1" :objects {}}}
   :colors {} :typographies {} :media {}
   :components components})

(t/deftest component-modified-at-only-change-is-ignored
  ;; editing a component bumps its `:modified-at`; if that timestamp is the
  ;; ONLY difference it must not surface as a change or conflict, and must not
  ;; emit a spurious :mod-component.
  (let [cid    (uuid/random)
        base   (comp-data {cid {:id cid :name "Button" :path "" :modified-at "t0"}})
        branch (comp-data {cid {:id cid :name "Button" :path "" :modified-at "t1"}})
        r      (bm/compute-merge base base branch :branch->main)
        {:keys [changes unsupported]} (bm/compute-changes base base branch)]
    (t/is (= [] (:changes r)))
    (t/is (= [] (:conflicts r)))
    (t/is (= 0 (-> r :stats :modified)))
    (t/is (empty? (filter #(= :mod-component (:type %)) changes)))
    (t/is (empty? unsupported))))

(t/deftest component-modified-at-no-spurious-conflict
  ;; both sides edited the component (different timestamps) but nothing else;
  ;; the differing :modified-at must NOT create a modify-modify conflict.
  (let [cid    (uuid/random)
        base   (comp-data {cid {:id cid :name "Button" :path "" :modified-at "t0"}})
        main   (comp-data {cid {:id cid :name "Button" :path "" :modified-at "t1"}})
        branch (comp-data {cid {:id cid :name "Button" :path "" :modified-at "t2"}})
        r      (bm/compute-merge base main branch :branch->main)]
    (t/is (= [] (:conflicts r)))
    (t/is (= 0 (-> r :stats :conflicts)))))

(t/deftest component-real-change-excludes-modified-at
  ;; a genuine metadata change (rename) is reported, but the timestamp churn
  ;; is stripped from the property changes shown to the user.
  (let [cid    (uuid/random)
        base   (comp-data {cid {:id cid :name "Button" :path "" :modified-at "t0"}})
        branch (comp-data {cid {:id cid :name "Primary Button" :path "" :modified-at "t1"}})
        r      (bm/compute-merge base base branch :branch->main)
        c      (first (filter #(= :component (:kind %)) (:changes r)))]
    (t/is (= :modified (:status c)))
    (t/is (= {:main "Button" :branch "Primary Button"} (get-in c [:changed-attrs :name])))
    (t/is (not (contains? (:changed-attrs c) :modified-at)))))

;; --- compute-changes (merge -> change maps)

(t/deftest compute-changes-colors
  (let [base   (mkdata {} :colors {:c1 {:id :c1 :name "A"}})
        branch (mkdata {} :colors {:c1 {:id :c1 :name "A2"}
                                   :c2 {:id :c2 :name "B"}})
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        by-type (group-by :type changes)]
    (t/is (empty? unsupported))
    (t/is (= :c2 (-> by-type :add-color first :color :id)))
    (t/is (= "A2" (-> by-type :mod-color first :color :name)))))

(t/deftest remap-refs-remaps-local-only
  ;; only ids present in the map are re-pointed; external-library refs and
  ;; shapes with no refs are left untouched. Covers component, library
  ;; color/typography and media references plus the :media/:colors indexes.
  (let [branch-id (uuid/next)
        main-id   (uuid/next)
        ext-lib   (uuid/next)
        media-old (uuid/next)
        media-new (uuid/next)
        data (-> (mkdata {:s1 {:id :s1 :component-file branch-id}
                          :s2 {:id :s2 :component-file ext-lib}
                          :s3 {:id :s3}
                          :s4 {:id :s4 :fill-color-ref-file branch-id
                               :typography-ref-file branch-id}
                          :s5 {:id :s5 :type :image :metadata {:id media-old}
                               :fill-image {:id media-old}}})
                 (assoc :media {media-old {:id media-old :name "img"}})
                 (assoc :colors {:c1 {:id :c1 :image {:id media-old}}}))
        data' (bm/remap-refs data {branch-id main-id
                                   media-old media-new})
        objs  (get-in data' [:pages-index :p1 :objects])]
    (t/is (= main-id (get-in objs [:s1 :component-file])))
    (t/is (= ext-lib (get-in objs [:s2 :component-file])))
    (t/is (not (contains? (get objs :s3) :component-file)))
    (t/is (= main-id (get-in objs [:s4 :fill-color-ref-file])))
    (t/is (= main-id (get-in objs [:s4 :typography-ref-file])))
    (t/is (= media-new (get-in objs [:s5 :metadata :id])))
    (t/is (= media-new (get-in objs [:s5 :fill-image :id])))
    (t/is (= {media-new {:id media-new :name "img"}} (:media data')))
    (t/is (= media-new (get-in data' [:colors :c1 :image :id])))))

(t/deftest merge-component-keeps-head-and-remaps-file
  ;; a component created on a branch must merge into main as a VALID head:
  ;; its main instance keeps component-id/main-instance and its
  ;; component-file is re-pointed from the branch id to main's id (else main
  ;; can't resolve the component and repair detaches it).
  (let [comp-id   :c1
        mi-id     :mi
        branch-id (uuid/next)
        main-id   (uuid/next)
        base    (mkdata {uuid/zero {:id uuid/zero :type :frame :shapes []}})
        branch  (-> (mkdata {uuid/zero {:id uuid/zero :type :frame :shapes [mi-id]}
                             mi-id {:id mi-id :type :frame :name "Comp" :shapes []
                                    :parent-id uuid/zero :frame-id uuid/zero
                                    :component-root true :main-instance true
                                    :component-id comp-id :component-file branch-id}})
                    (assoc :components {comp-id {:id comp-id :name "Comp" :path ""
                                                 :main-instance-id mi-id :main-instance-page :p1}}))
        ;; emulate the RPC: canonicalize the branch's local file id to main's
        branch'  (bm/remap-refs branch {branch-id main-id})
        {:keys [changes unsupported]} (bm/compute-changes base base branch')
        add-mi   (first (filter #(and (= :add-obj (:type %)) (= mi-id (:id %))) changes))
        add-comp (first (filter #(= :add-component (:type %)) changes))]
    (t/is (empty? unsupported))
    ;; registry row merged
    (t/is (= comp-id (:id add-comp)))
    (t/is (= mi-id (:main-instance-id add-comp)))
    ;; the merged main instance is still a head, re-pointed to main's id
    (t/is (= main-id (get-in add-mi [:obj :component-file])))
    (t/is (= comp-id (get-in add-mi [:obj :component-id])))
    (t/is (true? (get-in add-mi [:obj :main-instance])))
    (t/is (true? (get-in add-mi [:obj :component-root])))))

(t/deftest compute-changes-shape-mod-del
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"}
                        :s2 {:id :s2 :name "B"}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"}})
        {:keys [changes]} (bm/compute-changes base base branch)
        by-type (group-by :type changes)]
    (t/is (= {:type :set :attr :fill :val "blue"}
             (-> by-type :mod-obj first :operations first)))
    (t/is (= :p1 (-> by-type :mod-obj first :page-id)))
    (t/is (= :s2 (-> by-type :del-obj first :id)))))

(t/deftest compute-changes-shape-add-topological
  (let [objs   {:root {:id :root :name "Root" :shapes []}}
        base   (mkdata objs)
        branch (mkdata {:root {:id :root :name "Root" :shapes [:f1]}
                        :f1   {:id :f1 :name "Frame" :parent-id :root :frame-id :root :shapes [:c1]}
                        :c1   {:id :c1 :name "Child" :parent-id :f1 :frame-id :f1}})
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        adds   (filterv #(= :add-obj (:type %)) changes)]
    (t/is (empty? unsupported))
    ;; parent added before child
    (t/is (= [:f1 :c1] (mapv :id adds)))
    (t/is (= :root (:parent-id (first adds))))
    (t/is (= 0 (:index (first adds))))
    ;; container shapes keep `:shapes` (reset to []), not dropped — frames
    ;; require the key and would otherwise fail schema validation on apply
    (t/is (= [] (get-in (first adds) [:obj :shapes])))
    (t/is (contains? (:obj (first adds)) :shapes))
    ;; leaf shapes that never had `:shapes` don't get the key added
    (t/is (not (contains? (:obj (second adds)) :shapes)))
    ;; children membership is not re-set via :shapes ops
    (t/is (empty? (filter #(and (= :mod-obj (:type %)) (= :root (:id %))) changes)))))

(t/deftest compute-changes-reparent-existing-shape
  ;; main: rect R loose at root. branch: R moved into a new group G.
  ;; the merge must MOVE R into G (mov-objects), not :set its parent-id —
  ;; otherwise R is left loose at root and duplicated by file repair.
  (let [base   (mkdata {uuid/zero {:id uuid/zero :type :frame :shapes [:r]}
                        :r {:id :r :name "Rect" :type :rect
                            :parent-id uuid/zero :frame-id uuid/zero}})
        branch (mkdata {uuid/zero {:id uuid/zero :type :frame :shapes [:g]}
                        :g {:id :g :name "Group" :type :group :shapes [:r]
                            :parent-id uuid/zero :frame-id uuid/zero}
                        :r {:id :r :name "Rect" :type :rect
                            :parent-id :g :frame-id uuid/zero}})
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        adds   (filterv #(= :add-obj (:type %)) changes)
        movs   (filterv #(= :mov-objects (:type %)) changes)
        r-mod  (first (filter #(and (= :mod-obj (:type %)) (= :r (:id %))) changes))]
    (t/is (empty? unsupported))
    ;; G is added empty
    (t/is (= [:g] (mapv :id adds)))
    (t/is (= [] (get-in (first adds) [:obj :shapes])))
    ;; R is MOVED into G at the right index (not re-added)
    (t/is (= 1 (count movs)))
    (t/is (= :g (:parent-id (first movs))))
    (t/is (= [:r] (:shapes (first movs))))
    (t/is (= 0 (:index (first movs))))
    ;; the reparent is NOT emitted as a plain :set op on R
    (let [set-attrs (set (map :attr (:operations r-mod)))]
      (t/is (not (contains? set-attrs :parent-id)))
      (t/is (not (contains? set-attrs :frame-id))))))


(t/deftest compute-changes-resolve-shape-conflict
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"}})
        main   (mkdata {:s1 {:id :s1 :name "A" :fill "green"}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"}})]
    ;; resolved to branch -> sets the branch value
    (let [{:keys [changes]} (bm/compute-changes base main branch {:s1 :branch})
          op (-> (group-by :type changes) :mod-obj first :operations first)]
      (t/is (= {:type :set :attr :fill :val "blue"} op)))
    ;; resolved to main -> nothing emitted
    (let [{:keys [changes]} (bm/compute-changes base main branch {:s1 :main})]
      (t/is (empty? (filterv #(= :mod-obj (:type %)) changes))))
    ;; unresolved -> nothing emitted (caller refuses the merge)
    (let [{:keys [changes]} (bm/compute-changes base main branch {})]
      (t/is (empty? (filterv #(= :mod-obj (:type %)) changes))))))

(t/deftest compute-changes-resolve-color-conflict
  (let [base   (mkdata {} :colors {:c1 {:id :c1 :name "A"}})
        main   (mkdata {} :colors {:c1 {:id :c1 :name "Main"}})
        branch (mkdata {} :colors {:c1 {:id :c1 :name "Branch"}})
        {:keys [changes]} (bm/compute-changes base main branch {:c1 :branch})]
    (t/is (= "Branch" (-> (group-by :type changes) :mod-color first :color :name)))))

;; --- per-property (per-attr) conflict resolution

(t/deftest conflict-resolved?-predicate
  (let [cf {:changed-attrs {:fill {:main "g" :branch "b"}
                            :rx   {:main 0 :branch 8}}}]
    ;; whole-entity keyword always resolves
    (t/is (true? (bm/conflict-resolved? cf :main)))
    (t/is (true? (bm/conflict-resolved? cf :branch)))
    ;; a full per-attr map resolves
    (t/is (true? (bm/conflict-resolved? cf {:fill :branch :rx :main})))
    ;; a partial map does not
    (t/is (false? (bm/conflict-resolved? cf {:fill :branch})))
    ;; nil / empty map do not
    (t/is (false? (bm/conflict-resolved? cf nil)))
    (t/is (false? (bm/conflict-resolved? cf {})))
    ;; a map for a conflict without changed-attrs does not resolve
    (t/is (false? (bm/conflict-resolved? {} {:fill :branch})))))

(t/deftest compute-changes-resolve-shape-per-attr
  ;; both fill and rx conflict; keep branch fill, keep main rx -> only the
  ;; fill set op is emitted (rx resolved to main is a no-op against main)
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"   :rx 0}})
        main   (mkdata {:s1 {:id :s1 :name "A" :fill "green" :rx 4}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"  :rx 8}})
        {:keys [changes]} (bm/compute-changes base main branch {:s1 {:fill :branch :rx :main}})
        ops (-> (group-by :type changes) :mod-obj first :operations)]
    (t/is (= [{:type :set :attr :fill :val "blue"}] ops))))

(t/deftest compute-changes-resolve-shape-per-attr-both-branch
  ;; per-attr map selecting branch for every attr equals the whole `:branch`
  ;; resolution
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :fill "red"   :rx 0}})
        main   (mkdata {:s1 {:id :s1 :name "A" :fill "green" :rx 4}})
        branch (mkdata {:s1 {:id :s1 :name "A" :fill "blue"  :rx 8}})
        per    (-> (bm/compute-changes base main branch {:s1 {:fill :branch :rx :branch}})
                   :changes (->> (group-by :type)) :mod-obj first :operations set)
        whole  (-> (bm/compute-changes base main branch {:s1 :branch})
                   :changes (->> (group-by :type)) :mod-obj first :operations set)]
    (t/is (= whole per))
    (t/is (= #{{:type :set :attr :fill :val "blue"}
               {:type :set :attr :rx :val 8}} per))))

(t/deftest compute-changes-resolve-color-per-attr
  ;; color conflict on two attrs; take branch name, keep main description ->
  ;; merged color carries branch name + main description
  (let [base   (mkdata {} :colors {:c1 {:id :c1 :name "A"    :opacity 1}})
        main   (mkdata {} :colors {:c1 {:id :c1 :name "Main" :opacity 1}})
        branch (mkdata {} :colors {:c1 {:id :c1 :name "Brnc" :opacity 0.5}})
        {:keys [changes]} (bm/compute-changes base main branch {:c1 {:name :branch :opacity :main}})
        color (-> (group-by :type changes) :mod-color first :color)]
    (t/is (= "Brnc" (:name color)))
    (t/is (= 1 (:opacity color)))))

;; --- tokens

(defn- token-lib
  [set-id token-id value]
  (-> (ctob/make-tokens-lib)
      (ctob/add-set (ctob/make-token-set {:id set-id :name "core"}))
      (ctob/add-token set-id (ctob/make-token {:id token-id
                                               :name "color.primary"
                                               :type :color
                                               :value value}))))

(defn- with-tokens
  [lib]
  (assoc (mkdata {}) :tokens-lib lib))

(t/deftest compute-changes-token-value-roundtrip
  (let [sid (uuid/next)
        tid (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        branch-lib (ctob/update-token base-lib sid tid
                                      (fn [t] (ctob/make-token (assoc (into {} t) :value "#0000ff"))))
        base   (with-tokens base-lib)
        main   (with-tokens base-lib)
        branch (with-tokens branch-lib)

        {:keys [changes unsupported]} (bm/compute-changes base main branch)
        set-token-change (first (filter #(= :set-token (:type %)) changes))]

    (t/is (empty? unsupported))
    (t/is (= sid (:set-id set-token-change)))
    (t/is (= tid (:token-id set-token-change)))
    (t/is (= "#0000ff" (-> set-token-change :attrs :value)))

    ;; round-trip: apply the change to main's tokens-lib and read the value back
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)
          tok   (ctob/get-token (:tokens-lib data') sid tid)]
      (t/is (= "#0000ff" (:value tok))))))

(t/deftest compute-changes-token-set-add
  (let [sid  (uuid/next)
        tid  (uuid/next)
        sid2 (uuid/next)
        tid2 (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        branch-lib (-> base-lib
                       (ctob/add-set (ctob/make-token-set {:id sid2 :name "extra"}))
                       (ctob/add-token sid2 (ctob/make-token {:id tid2
                                                              :name "color.secondary"
                                                              :type :color
                                                              :value "#00ff00"})))
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))
        by-type (group-by :type changes)]
    (t/is (empty? unsupported))
    (t/is (some #(= sid2 (:id %)) (:set-token-set by-type)))
    (t/is (some #(= tid2 (:token-id %)) (:set-token by-type)))
    ;; round-trip: the new set and its token exist in main afterwards
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)]
      (t/is (some? (ctob/get-set (:tokens-lib data') sid2)))
      (t/is (some? (ctob/get-token (:tokens-lib data') sid2 tid2))))))

(t/deftest compute-changes-token-set-delete
  (let [sid  (uuid/next)
        tid  (uuid/next)
        sid2 (uuid/next)
        tid2 (uuid/next)
        base-lib   (-> (token-lib sid tid "#ff0000")
                       (ctob/add-set (ctob/make-token-set {:id sid2 :name "extra"}))
                       (ctob/add-token sid2 (ctob/make-token {:id tid2 :name "color.x"
                                                              :type :color :value "#00ff00"})))
        branch-lib (ctob/delete-set base-lib sid2)
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))
        del (filter #(and (= :set-token-set (:type %)) (nil? (:attrs %))) changes)]
    (t/is (empty? unsupported))
    (t/is (= sid2 (:id (first del))))
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)]
      (t/is (nil? (ctob/get-set (:tokens-lib data') sid2))))))

(t/deftest compute-changes-token-theme-add
  (let [sid  (uuid/next)
        tid  (uuid/next)
        thid (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        branch-lib (ctob/add-theme base-lib (ctob/make-token-theme {:id thid :name "Dark" :group ""}))
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))
        thm (filter #(= :set-token-theme (:type %)) changes)]
    (t/is (empty? unsupported))
    (t/is (= thid (:id (first thm))))
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)]
      (t/is (some? (ctob/get-theme (:tokens-lib data') thid))))))

;; --- pages

(t/deftest compute-changes-page-add
  (let [p1 {:id :p1 :name "Page 1" :objects {}}
        p2 {:id :p2 :name "Page 2" :objects {:s1 {:id :s1 :name "A"}}}
        base   (pages-data {:p1 p1} [:p1])
        branch (pages-data {:p1 p1 :p2 p2} [:p1 :p2])
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        add (first (filter #(= :add-page (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= :p2 (-> add :page :id)))
    (t/is (contains? (-> add :page :objects) :s1))))

(t/deftest compute-changes-page-delete
  (let [p1 {:id :p1 :name "Page 1" :objects {}}
        p2 {:id :p2 :name "Page 2" :objects {}}
        base   (pages-data {:p1 p1 :p2 p2} [:p1 :p2])
        branch (pages-data {:p1 p1} [:p1])
        {:keys [changes]} (bm/compute-changes base base branch)
        del (first (filter #(= :del-page (:type %)) changes))]
    (t/is (= :p2 (:id del)))))

(t/deftest compute-changes-page-rename
  (let [base   (pages-data {:p1 {:id :p1 :name "Page 1" :objects {}}} [:p1])
        branch (assoc-in base [:pages-index :p1 :name] "Renamed")
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        mod (first (filter #(= :mod-page (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= :p1 (:id mod)))
    (t/is (= "Renamed" (:name mod)))))

(t/deftest compute-changes-page-order
  (let [p1 (uuid/next) p2 (uuid/next) p3 (uuid/next)
        pi {p1 {:id p1 :name "P1" :objects {}}
            p2 {:id p2 :name "P2" :objects {}}
            p3 {:id p3 :name "P3" :objects {}}}
        base   (pages-data pi [p1 p2 p3])
        branch (pages-data pi [p3 p1 p2])
        {:keys [changes unsupported]} (bm/compute-changes base base branch)]
    (t/is (empty? unsupported))
    (t/is (seq (filter #(= :mov-page (:type %)) changes)))
    ;; round-trip: main ends up in branch's page order
    (let [data' (cfc/process-changes base changes)]
      (t/is (= [p3 p1 p2] (:pages data'))))))

(t/deftest compute-changes-page-guide
  (let [pid   (uuid/next)
        gid   (uuid/next)
        guide {:id gid :axis :x :position 100}
        base   (pages-data {pid {:id pid :name "P" :objects {} :guides {}}} [pid])
        branch (assoc-in base [:pages-index pid :guides gid] guide)
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        ch (first (filter #(= :set-guide (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= gid (:id ch)))
    (t/is (= pid (:page-id ch)))
    ;; round-trip: the guide is present on main afterwards
    (let [data' (cfc/process-changes base changes)]
      (t/is (contains? (get-in data' [:pages-index pid :guides]) gid)))))

(t/deftest compute-changes-page-attrs-unsupported
  ;; an unknown/unhandled residual page attr must be refused, never dropped
  (let [base   (pages-data {:p1 {:id :p1 :name "Page 1" :objects {}}} [:p1])
        branch (assoc-in base [:pages-index :p1 :some-future-attr] {:x 1})
        {:keys [unsupported]} (bm/compute-changes base base branch)]
    (t/is (contains? unsupported :page-attrs))))

(t/deftest compute-changes-page-default-grid
  (let [pid    (uuid/next)
        grid   {:size 16 :color {:color "#cccccc" :opacity 0.5}}
        base   (pages-data {pid {:id pid :name "P" :objects {} :default-grids {}}} [pid])
        branch (assoc-in base [:pages-index pid :default-grids :square] grid)
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        ch (first (filter #(= :set-default-grid (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= pid (:page-id ch)))
    (t/is (= :square (:grid-type ch)))
    (t/is (= grid (:params ch)))
    ;; round-trip: the grid is present on main afterwards
    (let [data' (cfc/process-changes base changes)]
      (t/is (= grid (get-in data' [:pages-index pid :default-grids :square]))))))

(t/deftest compute-changes-page-default-grid-delete
  (let [pid    (uuid/next)
        grid   {:size 16 :color {:color "#cccccc" :opacity 0.5}}
        base   (pages-data {pid {:id pid :name "P" :objects {} :default-grids {:square grid}}} [pid])
        branch (assoc-in base [:pages-index pid :default-grids] {})
        {:keys [changes]} (bm/compute-changes base base branch)
        ch (first (filter #(= :set-default-grid (:type %)) changes))]
    (t/is (= :square (:grid-type ch)))
    (t/is (nil? (:params ch)))
    (let [data' (cfc/process-changes base changes)]
      (t/is (not (contains? (get-in data' [:pages-index pid :default-grids]) :square))))))

(t/deftest compute-changes-page-plugin-data
  (let [pid    (uuid/next)
        base   (pages-data {pid {:id pid :name "P" :objects {} :plugin-data {}}} [pid])
        branch (assoc-in base [:pages-index pid :plugin-data :my-plugin] {"foo" "bar"})
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        ch (first (filter #(= :set-plugin-data (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= :page (:object-type ch)))
    (t/is (= pid (:object-id ch)))
    (t/is (= :my-plugin (:namespace ch)))
    (t/is (= "foo" (:key ch)))
    (t/is (= "bar" (:value ch)))
    ;; round-trip
    (let [data' (cfc/process-changes base changes)]
      (t/is (= "bar" (get-in data' [:pages-index pid :plugin-data :my-plugin "foo"]))))))

;; --- components

(t/deftest compute-changes-component-add
  (let [pid (uuid/next)
        mi  (uuid/next)
        cid (uuid/next)
        base   (pages-data {pid {:id pid :name "Page 1" :objects {}}} [pid])
        cmp    {:id cid :name "Button" :path "" :main-instance-id mi :main-instance-page pid}
        branch (assoc base :components {cid cmp})
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        add (first (filter #(= :add-component (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= cid (:id add)))
    (t/is (= "Button" (:name add)))
    ;; round-trip: the component row exists in main afterwards
    (let [data' (cfc/process-changes {:components {}} changes)]
      (t/is (contains? (:components data') cid)))))

(t/deftest compute-changes-component-delete
  (let [cmp    {:id :c1 :name "Button" :path "" :main-instance-id :mi :main-instance-page :p1}
        base   (pages-data {:p1 {:id :p1 :name "Page 1" :objects {}}} [:p1] :components {:c1 cmp})
        branch (assoc base :components {})
        {:keys [changes]} (bm/compute-changes base base branch)
        del (first (filter #(= :del-component (:type %)) changes))]
    (t/is (= :c1 (:id del)))))

(t/deftest compute-changes-component-soft-delete
  (let [cmp    {:id :c1 :name "Button" :path "" :main-instance-id :mi :main-instance-page :p1}
        base   (pages-data {:p1 {:id :p1 :name "Page 1" :objects {}}} [:p1] :components {:c1 cmp})
        ;; soft-delete keeps the row but marks it deleted
        branch (assoc-in base [:components :c1 :deleted] true)
        {:keys [changes]} (bm/compute-changes base base branch)
        del (first (filter #(= :del-component (:type %)) changes))]
    ;; surfaced as a real delete, not a no-op mod
    (t/is (= :c1 (:id del)))
    (t/is (empty? (filter #(= :mod-component (:type %)) changes)))))

(t/deftest compute-changes-component-variant-add
  (let [cmp    {:id :c1 :name "Button" :path "" :main-instance-id :mi :main-instance-page :p1
                :variant-id :v1 :variant-properties [{:name "size" :value "lg"}]}
        base   (pages-data {:p1 {:id :p1 :name "Page 1" :objects {}}} [:p1])
        branch (assoc base :components {:c1 cmp})
        {:keys [changes unsupported]} (bm/compute-changes base base branch)
        add (first (filter #(= :add-component (:type %)) changes))]
    (t/is (empty? unsupported))
    ;; variant metadata is carried into the add-component change
    (t/is (= :v1 (:variant-id add)))
    (t/is (= [{:name "size" :value "lg"}] (:variant-properties add)))))

(t/deftest compute-changes-token-set-rename
  (let [sid  (uuid/next)
        tid  (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        ;; rename the set but keep its token
        branch-lib (ctob/update-set base-lib sid
                                    (fn [s] (ctob/make-token-set {:id (ctob/get-id s)
                                                                  :name "renamed"
                                                                  :tokens (ctob/get-tokens base-lib sid)})))
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))
        ch (first (filter #(= :set-token-set (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (= "renamed" (-> ch :attrs :name)))
    ;; round-trip: set renamed AND its token preserved
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)]
      (t/is (= "renamed" (ctob/get-name (ctob/get-set (:tokens-lib data') sid))))
      (t/is (some? (ctob/get-token (:tokens-lib data') sid tid))))))

(t/deftest compute-changes-token-set-order
  (let [a (uuid/next) b (uuid/next) c (uuid/next)
        base-lib   (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set {:id a :name "a"}))
                       (ctob/add-set (ctob/make-token-set {:id b :name "b"}))
                       (ctob/add-set (ctob/make-token-set {:id c :name "c"})))
        ;; branch reorders to c, a, b
        branch-lib (ctob/move-set base-lib ["c"] ["c"] ["a"] false)
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))]
    (t/is (empty? unsupported))
    (t/is (seq (filter #(= :move-token-set (:type %)) changes)))
    ;; round-trip: main ends up in branch's set order
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)
          order (mapv ctob/get-name (ctob/get-sets (:tokens-lib data')))]
      (t/is (= ["c" "a" "b"] order)))))

(t/deftest compute-changes-token-active-sets
  (let [sid (uuid/next)
        tid (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        ;; branch toggles the "core" set active in the hidden theme
        branch-lib (ctob/toggle-set-in-theme base-lib ctob/hidden-theme-id "core")
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))
        ch (first (filter #(= :set-token-theme (:type %)) changes))
        hidden-sets (fn [lib] (set (:sets (ctob/get-theme lib ctob/hidden-theme-id))))]
    (t/is (empty? unsupported))
    (t/is (= ctob/hidden-theme-id (:id ch)))
    ;; round-trip: main's hidden theme active sets match the branch
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)]
      (t/is (= (hidden-sets branch-lib) (hidden-sets (:tokens-lib data')))))))

(t/deftest compute-changes-token-active-themes
  (let [thid (uuid/next)
        base-lib   (-> (ctob/make-tokens-lib)
                       (ctob/add-theme (ctob/make-token-theme {:id thid :name "Dark" :group ""})))
        branch-lib (ctob/activate-theme base-lib thid)
        {:keys [changes unsupported]} (bm/compute-changes (with-tokens base-lib)
                                                          (with-tokens base-lib)
                                                          (with-tokens branch-lib))
        ch (first (filter #(= :set-active-token-themes (:type %)) changes))]
    (t/is (empty? unsupported))
    (t/is (some? ch))
    ;; round-trip: active theme paths match the branch
    (let [data' (cfc/process-changes {:tokens-lib base-lib} changes)]
      (t/is (= (set (ctob/get-active-theme-paths branch-lib))
               (set (ctob/get-active-theme-paths (:tokens-lib data'))))))))

;;; --- Regression tests: classification on stripped shapes ---

(t/deftest no-false-conflict-when-both-add-children-to-same-frame
  ;; both sides adding a child to the same frame only diverge on the
  ;; frame's :shapes (derived) — that must NOT be a conflict on the frame.
  (let [base   (mkdata {:f1 {:id :f1 :name "Frame" :type :frame :shapes [:a]}
                        :a  {:id :a :name "A"}})
        main   (mkdata {:f1 {:id :f1 :name "Frame" :type :frame :shapes [:a :m]}
                        :a  {:id :a :name "A"}
                        :m  {:id :m :name "M" :parent-id :f1 :frame-id :f1}})
        branch (mkdata {:f1 {:id :f1 :name "Frame" :type :frame :shapes [:a :b]}
                        :a  {:id :a :name "A"}
                        :b  {:id :b :name "B" :parent-id :f1 :frame-id :f1}})
        r      (bm/compute-merge base main branch :branch->main)]
    (t/is (= [] (:conflicts r)))
    (t/is (= #{:b} (set (map :id (:changes r)))))))

(t/deftest no-delete-conflict-on-touched-only-main-change
  ;; branch deletes a shape; main only flipped its :touched (library sync,
  ;; not a user change) — that must stay a CLEAN delete, not a conflict.
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :touched nil}})
        main   (mkdata {:s1 {:id :s1 :name "A" :touched #{:fill-group}}})
        branch (mkdata {})
        r      (bm/compute-merge base main branch :branch->main)]
    (t/is (= [] (:conflicts r)))
    (t/is (= [:deleted] (mapv :status (:changes r))))))

(t/deftest compute-changes-restores-shape-on-modify-delete-branch
  ;; main deleted a frame the branch modified; resolving :branch must
  ;; re-add the frame AND its surviving child (main's delete was recursive).
  (let [base   (mkdata {:f1 {:id :f1 :name "Frame" :type :frame :shapes [:c1]
                             :parent-id uuid/zero :frame-id uuid/zero}
                        :c1 {:id :c1 :name "Child" :parent-id :f1 :frame-id :f1}})
        main   (mkdata {})
        branch (mkdata {:f1 {:id :f1 :name "Frame RENAMED" :type :frame :shapes [:c1]
                             :parent-id uuid/zero :frame-id uuid/zero}
                        :c1 {:id :c1 :name "Child" :parent-id :f1 :frame-id :f1}})
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        {:keys [changes]}   (bm/compute-changes base main branch {:f1 :branch})
        adds (into {} (comp (filter #(= :add-obj (:type %)))
                            (map (juxt :id identity)))
                   changes)]
    (t/is (= [:modify-delete] (mapv :reason conflicts)))
    ;; both the frame and its child come back, parent before child
    (t/is (contains? adds :f1))
    (t/is (contains? adds :c1))
    (let [add-order (mapv :id (filter #(= :add-obj (:type %)) changes))
          idx       (fn [id] (first (keep-indexed (fn [i v] (when (= v id) i)) add-order)))]
      (t/is (< (idx :f1) (idx :c1))))
    (t/is (= "Frame RENAMED" (get-in adds [:f1 :obj :name])))))

(t/deftest compute-changes-modify-delete-resolved-main-drops-shape
  ;; same conflict resolved to :main -> nothing is re-added
  (let [base   (mkdata {:s1 {:id :s1 :name "A"}})
        main   (mkdata {})
        branch (mkdata {:s1 {:id :s1 :name "A2"}})
        {:keys [changes]} (bm/compute-changes base main branch {:s1 :main})]
    (t/is (= [] (filterv #(= :add-obj (:type %)) changes)))))

;;; --- Regression tests: order resolution ids ---

(t/deftest page-order-conflict-id-and-resolution
  ;; both sides reordered pages differently: the conflict id must be
  ;; :page-order (not :order) and resolving it to :branch must apply
  (let [p1 (uuid/next) p2 (uuid/next) p3 (uuid/next)
        pi {p1 {:id p1 :name "P1" :objects {}}
            p2 {:id p2 :name "P2" :objects {}}
            p3 {:id p3 :name "P3" :objects {}}}
        base   (pages-data pi [p1 p2 p3])
        main   (pages-data pi [p2 p1 p3])
        branch (pages-data pi [p3 p1 p2])
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        conflict (first (filter #(= :page-order (:kind %)) conflicts))
        {:keys [changes]} (bm/compute-changes base main branch {:page-order :branch})]
    (t/is (= :page-order (:id conflict)))
    (t/is (seq (filter #(= :mov-page (:type %)) changes)))
    (let [data' (cfc/process-changes (pages-data pi [p2 p1 p3]) changes)]
      (t/is (= [p3 p1 p2] (:pages data'))))))

;;; --- Regression tests: page delete-vs-modify ---

(t/deftest page-deleted-in-branch-edited-in-main-conflicts
  (let [p1 (uuid/next) p2 (uuid/next) s1 (uuid/next)
        mk (fn [p2-page]
             {:pages-index (cond-> {p1 {:id p1 :name "P1" :objects {}}}
                             p2-page (assoc p2 p2-page))
              :pages (if p2-page [p1 p2] [p1])
              :colors {} :typographies {} :media {} :components {}})
        base   (mk {:id p2 :name "P2" :objects {}})
        main   (mk {:id p2 :name "P2" :objects {s1 {:id s1 :name "New"}}})
        branch (mk nil)
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        conflict (first (filter #(= :page (:kind %)) conflicts))]
    (t/is (some? conflict))
    (t/is (= :delete-modify (:reason conflict)))
    (t/is (= p2 (:id conflict)))
    ;; resolved to :branch -> the page is deleted
    (let [{:keys [changes]} (bm/compute-changes base main branch {p2 :branch})]
      (t/is (= [p2] (mapv :id (filter #(= :del-page (:type %)) changes)))))
    ;; resolved to :main -> the page is kept, nothing emitted for it
    (let [{:keys [changes]} (bm/compute-changes base main branch {p2 :main})]
      (t/is (= [] (filterv #(= :del-page (:type %)) changes))))))

(t/deftest page-deleted-in-main-edited-in-branch-restores
  (let [p1 (uuid/next) p2 (uuid/next) s1 (uuid/next)
        pi-with (fn [objects]
                  {p1 {:id p1 :name "P1" :objects {}}
                   p2 {:id p2 :name "P2" :objects objects}})
        base   (pages-data (pi-with {}) [p1 p2])
        main   (pages-data {p1 {:id p1 :name "P1" :objects {}}} [p1])
        branch (pages-data (pi-with {s1 {:id s1 :name "New"}}) [p1 p2])
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        conflict (first (filter #(= :page (:kind %)) conflicts))]
    (t/is (= :modify-delete (:reason conflict)))
    ;; resolved to :branch -> the full branch page is re-added
    (let [{:keys [changes]} (bm/compute-changes base main branch {p2 :branch})
          add (first (filter #(= :add-page (:type %)) changes))]
      (t/is (= p2 (get-in add [:page :id])))
      (t/is (contains? (get-in add [:page :objects]) s1)))))

(t/deftest page-clean-delete-still-clean
  ;; branch deletes a page main did not touch -> clean delete, no conflict
  (let [p1 (uuid/next) p2 (uuid/next)
        pi {p1 {:id p1 :name "P1" :objects {}}
            p2 {:id p2 :name "P2" :objects {}}}
        base   (pages-data pi [p1 p2])
        branch (pages-data {p1 {:id p1 :name "P1" :objects {}}} [p1])
        {:keys [conflicts]} (bm/compute-merge base base branch :branch->main)
        {:keys [changes]}   (bm/compute-changes base base branch)]
    (t/is (= [] conflicts))
    (t/is (= [p2] (mapv :id (filter #(= :del-page (:type %)) changes))))))

;;; --- Regression tests: token-set delete-vs-modify ---

(t/deftest token-set-deleted-in-branch-edited-in-main-conflicts
  (let [sid (uuid/next) tid (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        ;; main edits a token inside the set the branch deletes
        main-lib   (ctob/update-token base-lib sid tid
                                      (fn [t] (ctob/make-token (assoc (into {} t) :value "#00ff00"))))
        branch-lib (ctob/delete-set base-lib sid)
        base   (with-tokens base-lib)
        main   (with-tokens main-lib)
        branch (with-tokens branch-lib)
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        conflict (first (filter #(= :token-set (:kind %)) conflicts))]
    (t/is (some? conflict))
    (t/is (= :delete-modify (:reason conflict)))
    ;; no redundant token-level conflicts for the deleted set
    (t/is (= [] (filterv #(= :token (:kind %)) conflicts)))
    ;; resolved to :branch -> set deleted; to :main -> set (and token) kept
    (let [{:keys [changes]} (bm/compute-changes base main branch {sid :branch})]
      (t/is (some #(and (= :set-token-set (:type %)) (nil? (:attrs %))) changes)))
    (let [{:keys [changes]} (bm/compute-changes base main branch {sid :main})]
      (t/is (= [] (filterv #(= :set-token-set (:type %)) changes)))
      (t/is (= [] (filterv #(= :set-token (:type %)) changes))))))

(t/deftest token-set-deleted-in-main-edited-in-branch-restores
  (let [sid (uuid/next) tid (uuid/next)
        base-lib   (token-lib sid tid "#ff0000")
        main-lib   (ctob/delete-set base-lib sid)
        branch-lib (ctob/update-token base-lib sid tid
                                      (fn [t] (ctob/make-token (assoc (into {} t) :value "#0000ff"))))
        base   (with-tokens base-lib)
        main   (with-tokens main-lib)
        branch (with-tokens branch-lib)
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        conflict (first (filter #(= :token-set (:kind %)) conflicts))]
    (t/is (= :modify-delete (:reason conflict)))
    ;; resolved to :branch -> the set is re-created WITH its tokens
    (let [{:keys [changes]} (bm/compute-changes base main branch {sid :branch})
          restore (first (filter #(and (= :set-token-set (:type %)) (some? (:attrs %))) changes))
          data'   (cfc/process-changes (with-tokens main-lib) changes)]
      (t/is (some? restore))
      (t/is (seq (get-in restore [:attrs :tokens])))
      (t/is (= "#0000ff" (:value (ctob/get-token (:tokens-lib data') sid tid)))))))

(t/deftest per-attr-geometry-resolution-carries-caches
  ;; a modify-modify conflict where :x is resolved to :branch must also
  ;; bring the branch's :selrect/:points so geometry stays consistent
  (let [base   (mkdata {:s1 {:id :s1 :name "A" :x 0
                             :selrect {:x 0} :points [{:x 0}]}})
        main   (mkdata {:s1 {:id :s1 :name "MAIN" :x 0
                             :selrect {:x 0} :points [{:x 0}]}})
        branch (mkdata {:s1 {:id :s1 :name "A" :x 100
                             :selrect {:x 100} :points [{:x 100}]}})
        {:keys [changes]} (bm/compute-changes base main branch
                                              {:s1 {:x :branch :name :main}})
        ops (->> changes
                 (filter #(= :mod-obj (:type %)))
                 first :operations
                 (map (juxt :attr :val))
                 (into {}))]
    (t/is (= 100 (get ops :x)))
    (t/is (= {:x 100} (get ops :selrect)))
    (t/is (not (contains? ops :name)))))

(t/deftest shape-conflicts-carry-full-shapes
  ;; classification runs on STRIPPED shapes, but the conflict payload must
  ;; carry the FULL sides: the resolution UI renders real shape previews
  ;; that need :selrect/:points (frame-clip-def asserts (rect? selrect))
  (let [selrect {:x 0 :y 0 :width 10 :height 10 :x1 0 :y1 0 :x2 10 :y2 10}
        mk      (fn [name] {:id :s1 :name name :type :frame
                            :selrect selrect :points [{:x 0 :y 0}] :shapes []})
        base    (mkdata {:s1 (mk "A")})
        main    (mkdata {:s1 (mk "MAIN")})
        branch  (mkdata {:s1 (mk "BRANCH")})
        {:keys [conflicts]} (bm/compute-merge base main branch :branch->main)
        c       (first conflicts)]
    (t/is (= :modify-modify (:reason c)))
    ;; changed-attrs stay noise-free (no derived attrs)
    (t/is (= #{:name} (set (keys (:changed-attrs c)))))
    ;; but every side carries the full shape, caches included
    (t/is (= selrect (get-in c [:base :selrect])))
    (t/is (= selrect (get-in c [:main :selrect])))
    (t/is (= selrect (get-in c [:branch :selrect])))
    (t/is (= "MAIN" (get-in c [:main :name])))
    (t/is (= "BRANCH" (get-in c [:branch :name])))))
