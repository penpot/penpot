;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.branch-review-common-test
  "How the decisions dialog splits a conflict: the properties both sides
  moved clash, the ones a single side moved come filled in from it."
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [cljs.test :as t :include-macros true]))

(def ^:private card
  "Both sides edited the card: the radius on both, the width on the
  branch alone and the opacity on main alone."
  {:kind :shape
   :status :conflict
   :reason :modify-modify
   :base   {:rx 8 :width 320 :opacity 1}
   :main   {:rx 12 :width 320 :opacity 0.5}
   :branch {:rx 4 :width 360 :opacity 1}
   :changed-attrs {:rx      {:main 12 :branch 4}
                   :width   {:main 320 :branch 360}
                   :opacity {:main 0.5 :branch 1}}})

(defn- sources
  [conflict]
  (into {} (map (juxt :attr :source)) (brc/conflict-attr-rows conflict)))

(t/deftest only-properties-both-sides-moved-clash
  (t/is (= {:rx :clash :width :branch :opacity :main} (sources card))))

(t/deftest without-a-base-every-property-clashes
  (t/is (= #{:clash} (set (vals (sources (assoc card :base nil :reason :add-add))))))
  (t/is (= #{:clash} (set (vals (sources (dissoc card :base)))))))

(t/deftest independent-edits-come-filled-in
  (let [res (brc/prefill-resolution card)]
    (t/is (= {:width :branch :opacity :main} res))
    (t/is (not (bm/conflict-resolved? card res))
          "the clashing radius still waits for a choice")))

(t/deftest choosing-the-clash-resolves-the-conflict
  (let [res (brc/choose-attr card nil :rx :branch)]
    (t/is (= {:rx :branch :width :branch :opacity :main} res))
    (t/is (bm/conflict-resolved? card res))
    (t/is (= [{:attr :rx :side :main :value 12}]
             (brc/dropped-values card res))
          "keeping the branch radius drops main's, and nothing else")))

(t/deftest a-conflict-without-a-clash-comes-out-resolved
  (let [conflict (-> card
                     (assoc-in [:main :rx] 8)
                     (update :changed-attrs dissoc :rx))]
    (t/is (bm/conflict-resolved? conflict (brc/prefill-resolution conflict)))))

(t/deftest bulk-choice-keeps-independent-edits
  (t/is (= {:rx :main :width :branch :opacity :main} (brc/choose-side card :main)))
  (t/is (= :branch (brc/choose-side (assoc card :changed-attrs {} :main nil) :branch))
        "an entity that cannot be split takes the whole side"))

(t/deftest paragraphs-say-which-edits-a-side-keeps-and-drops
  (let [text   (fn [& ps]
                 {:type "root"
                  :children [{:type "paragraph-set"
                              :children (mapv (fn [p] {:type "paragraph"
                                                       :children [{:text p}]})
                                              ps)}]})
        note   {:kind :shape
                :status :conflict
                :base   {:content (text "One" "Two" "Three")}
                :main   {:content (text "One step" "Two" "Three")}
                :branch {:content (text "One" "Two" "Three more")}
                :changed-attrs {:content {}}}]
    (t/is (= [:kept nil :dropped] (mapv :status (brc/paragraph-rows note :main))))
    (t/is (= [:dropped nil :kept] (mapv :status (brc/paragraph-rows note :branch))))))

;; --- What a new or deleted entry is, where it lands, what it affects

(def ^:private board-id #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private group-id #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private rect-id  #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private copy-id  #uuid "00000000-0000-0000-0000-000000000004")
(def ^:private comp-id  #uuid "00000000-0000-0000-0000-000000000005")
(def ^:private color-id #uuid "00000000-0000-0000-0000-000000000006")
(def ^:private page-id  #uuid "00000000-0000-0000-0000-000000000007")

(def ^:private objects
  {uuid/zero {:id uuid/zero :type :frame :shapes [board-id]}
   board-id  {:id board-id :type :frame :name "Checkout" :parent-id uuid/zero
              :frame-id uuid/zero :shapes [group-id copy-id]}
   group-id  {:id group-id :type :group :name "Summary" :parent-id board-id
              :frame-id board-id :shapes [rect-id]}
   rect-id   {:id rect-id :type :rect :name "Price" :parent-id group-id
              :frame-id board-id
              :fills [{:fill-color "#000000" :fill-color-ref-id color-id}]
              :applied-tokens {:fill "brand.primary"}}
   copy-id   {:id copy-id :type :frame :name "Button" :parent-id board-id
              :frame-id board-id :component-id comp-id}})

(def ^:private data
  {:pages ["p"]
   :pages-index {page-id {:id page-id :name "Page 1" :objects objects}}})

(t/deftest a-page-counts-its-boards-and-layers
  (let [{:keys [boards layers]} (brc/page-contents {:objects objects})]
    (t/is (= ["Checkout"] (map :name boards)))
    (t/is (= 4 layers) "the root frame does not count")))

(t/deftest a-layer-names-the-containers-around-it
  (t/is (= ["Checkout" "Summary"] (brc/shape-path objects rect-id)))
  (t/is (= [] (brc/shape-path objects board-id))))

(t/deftest library-entries-count-the-layers-that-use-them
  (t/is (= 1 (brc/component-copies data comp-id)))
  (t/is (= 1 (brc/color-usages data color-id)))
  (t/is (= 1 (brc/token-usages data "brand.primary")))
  (t/is (= 0 (brc/token-usages data "brand.secondary"))))

(t/deftest pages-get-their-own-group-before-the-library
  (let [groups (brc/group-by-place [[0 {:kind :page :status :added :label "Page 2"}]
                                    [1 {:kind :color :status :added :label "Red"}]]
                                   {} [])]
    (t/is (= ["pages" "library"] (map :id groups)))))

(t/deftest a-board-folds-only-two-or-more-changes
  (let [pages-index {page-id {:id page-id :name "Page 1" :objects objects}}
        rect   [0 {:kind :shape :status :modified :id rect-id :page-id page-id}]
        button [1 {:kind :shape :status :modified :id copy-id :page-id page-id}]
        one    (first (brc/group-by-place [rect] pages-index []))
        two    (first (brc/group-by-place [rect button] pages-index []))]
    (t/is (= [] (:boards one)) "a lone change stays loose")
    (t/is (= [rect] (:rows one)))
    (t/is (= ["Checkout"] (map :name (:boards two))))
    (t/is (= [] (:rows two)))))

(t/deftest only-decisions-carry-a-colour
  (t/is (= :variants (brc/category {:kind :shape :status :modified :variant? true :component? true})))
  (t/is (= :components (brc/category {:kind :shape :status :modified :component? true})))
  (t/is (= #{"neutral"} (set (vals (dissoc brc/category->tone :decisions)))))
  (t/is (= "warning" (brc/category->tone :decisions))))
