;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.convert-swap-slots-test
  "The conversion of swap slots, the form files older than the feature
  components/swap-group store swaps in, to override groups."
  (:require
   [app.common.features :as cfeat]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.comp-processors :as cfcp]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.files.swap-slots :as cfss]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- add-components
  [file]
  ;; {:star-main}  [:name Frame1]          # [Component :star]
  ;;     :star-rect [:name Rect1]
  ;; {:heart-main} [:name Frame1]          # [Component :heart]
  ;;     :heart-rect [:name Rect1]
  ;; {:dot-main}   [:name Frame1]          # [Component :dot]
  ;;     :dot-rect [:name Rect1]
  ;; {:badge-main} [:name Frame1]          # [Component :badge]
  ;;     :badge-dot [:name Frame1]         @--> [Component :dot] :dot-main
  ;; {:card-main}  [:name Frame1]          # [Component :card]
  ;;     :card-star [:name Frame1]         @--> [Component :star] :star-main
  (-> file
      (tho/add-simple-component :star :star-main :star-rect
                                :child-params {:fills (ths/sample-fills-color :fill-color "#111111")})
      (tho/add-simple-component :heart :heart-main :heart-rect
                                :child-params {:fills (ths/sample-fills-color :fill-color "#222222")})
      (tho/add-simple-component :dot :dot-main :dot-rect)
      (tho/add-frame :badge-main)
      (thc/instantiate-component :dot :badge-dot :parent-label :badge-main)
      (thc/make-component :badge :badge-main)
      (tho/add-frame :card-main)
      (thc/instantiate-component :star :card-star :parent-label :card-main)
      (thc/make-component :card :card-main)))

(defn- setup
  []
  ;; :copy [:name Frame1]                  #--> [Component :card] :card-main
  ;;     :copy-star [:name Frame1]         @--> [Component :star] :card-star
  (-> (thf/sample-file :file1)
      (add-components)
      (thc/instantiate-component :card :copy :children-labels [:copy-star])))

(defn- setup-two-levels
  [file]
  ;; {:page-main}  [:name Frame1]          # [Component :page]
  ;;     :inner-copy [:name Frame1]        @--> [Component :card] :card-main
  ;;         :inner-star [:name Frame1]    @--> [Component :star] :card-star
  ;; :outer-copy [:name Frame1]            #--> [Component :page] :page-main
  ;;     :outer-inner [:name Frame1]       @--> [Component :card] :inner-copy
  ;;         :outer-star [:name Frame1]    @--> [Component :star] :inner-star
  (-> file
      (tho/add-frame :page-main)
      (thc/instantiate-component :card :inner-copy
                                 :parent-label :page-main
                                 :children-labels [:inner-star])
      (thc/make-component :page :page-main)
      (thc/instantiate-component :page :outer-copy
                                 :children-labels [:outer-inner :outer-star])))

(defn- convert
  ([file] (convert file {}))
  ([file libraries]
   (ctf/update-file-data file #(cfcp/convert-swap-slots % libraries))))

(defn- swap-state
  "What the conversion changes on a shape."
  [file label]
  (let [shape (ths/get-shape file label)]
    {:shape-ref (:shape-ref shape)
     :touched   (into #{} (map #(if (cfss/swap-slot-group? %) :swap-slot %)) (:touched shape))}))

(defn- first-child-id
  [file label]
  (first (:shapes (ths/get-shape file label))))

(t/deftest swap-in-a-copy-refers-to-the-replaced-child
  (let [old  (-> (setup)
                 (tho/swap-component-in-shape :copy-star :heart :as-slot? true))
        file (-> old
                 (convert)
                 (thf/validate-file!))]
    (t/is (= {:shape-ref (thi/id :heart-main) :touched #{:swap-slot}}
             (swap-state old :copy-star)))
    (t/is (= {:shape-ref (thi/id :card-star) :touched #{:swap-group}}
             (swap-state file :copy-star)))
    (t/is (= "#333333" (-> file
                           (tho/update-color :heart-rect "#333333")
                           (tho/propagate-component-changes :heart)
                           (thf/validate-file!)
                           (tho/bottom-fill-color :copy))))))

(t/deftest swap-in-a-main-drops-the-slot
  (let [old  (-> (setup)
                 (tho/swap-component-in-shape :card-star :heart
                                              :as-slot? true
                                              :propagate-fn #(tho/propagate-component-changes % :card)))
        file (-> old
                 (convert)
                 (thf/validate-file!))]
    (t/is (= #{:swap-slot} (:touched (swap-state old :card-star))))
    (t/is (= {:shape-ref (thi/id :heart-main) :touched #{}}
             (swap-state file :card-star)))))

(t/deftest slot-inherited-from-the-main-is-dropped
  (let [old  (-> (setup)
                 (tho/swap-component-in-shape :card-star :heart
                                              :as-slot? true
                                              :propagate-fn #(tho/propagate-component-changes % :card))
                 (thc/instantiate-component :card :copy2 :children-labels [:copy2-star]))
        slot (cfss/get-swap-slot (ths/get-shape old :card-star))
        old  (ths/update-shape old :copy2-star :touched #{(cfss/build-swap-slot-group slot)})
        file (-> old
                 (convert)
                 (thf/validate-file!))]
    (t/is (= {:shape-ref (thi/id :card-star) :touched #{}}
             (swap-state file :copy2-star)))))

(t/deftest swaps-nested-in-swaps-are-paired-on-the-old-data
  (let [old  (-> (setup)
                 (setup-two-levels)
                 (tho/swap-component-in-shape :outer-star :badge :as-slot? true))
        _    (thi/set-id! :outer-dot (first-child-id old :outer-star))
        file (-> old
                 (tho/swap-component-in-shape :outer-dot :heart :as-slot? true)
                 (convert)
                 (thf/validate-file!))]
    (t/is (= {:shape-ref (thi/id :inner-star) :touched #{:swap-group}}
             (swap-state file :outer-star)))
    (t/is (= {:shape-ref (thi/id :badge-dot) :touched #{:swap-group}}
             (swap-state file :outer-dot)))))

(defn- setup-library
  []
  ;; The library holds the components, and a swap in a copy inside its
  ;; :page main: :inner-star swapped for :heart. The file holds a copy of
  ;; :page, whose star is swapped for :badge.
  (let [library (-> (thf/sample-file :library)
                    (add-components)
                    (setup-two-levels)
                    (tho/swap-component-in-shape :inner-star :heart
                                                 :as-slot? true
                                                 :propagate-fn #(tho/propagate-component-changes % :page)))
        file    (-> (thf/sample-file :file1)
                    (thc/instantiate-component :page :outer-copy
                                               :library library
                                               :children-labels [:outer-inner :outer-star])
                    (tho/swap-component-in-shape :outer-star :badge
                                                 :library library
                                                 :as-slot? true))]
    [library file]))

(t/deftest pairing-reads-a-library-converted-or-not
  (let [[library file] (setup-library)
        converted      (convert library)
        expected       {:shape-ref (thi/id :inner-star) :touched #{:swap-group}}]
    (t/is (= expected (swap-state (convert file {(:id library) library}) :outer-star)))
    (t/is (= expected (swap-state (convert file {(:id converted) converted}) :outer-star)))))

(t/deftest unreachable-near-main-refers-to-the-slot
  (let [[_ file] (setup-library)
        slot     (cfss/get-swap-slot (ths/get-shape file :outer-star))
        file'    (convert file)]
    (t/is (= {:shape-ref slot :touched #{:swap-group}}
             (swap-state file' :outer-star)))))

(t/deftest unreachable-near-main-can-be-left-for-later
  (let [[library file] (setup-library)
        file'          (binding [cfcp/*defer-unreachable-swaps* true]
                         (convert file))]
    (t/is (= (swap-state file :outer-star) (swap-state file' :outer-star)))
    (t/is (::cfcp/deferred-swaps (meta (:data file'))))
    (t/is (= {:shape-ref (thi/id :inner-star) :touched #{:swap-group}}
             (swap-state (convert file' {(:id library) library}) :outer-star)))))

(t/deftest slot-inherited-from-an-unreachable-main-is-dropped
  ;; the near main is in a library that is not loaded: the copy refers to
  ;; its slot, the child of that main that was swapped there
  (let [library (-> (thf/sample-file :library)
                    (add-components)
                    (tho/swap-component-in-shape :card-star :heart
                                                 :as-slot? true
                                                 :propagate-fn #(tho/propagate-component-changes % :card)))
        slot    (cfss/get-swap-slot (ths/get-shape library :card-star))
        file    (-> (thf/sample-file :file1)
                    (thc/instantiate-component :card :copy
                                               :library library
                                               :children-labels [:copy-star])
                    (ths/update-shape :copy-star :touched #{(cfss/build-swap-slot-group slot)}))]
    (t/is (= (thi/id :card-star) slot (:shape-ref (ths/get-shape file :copy-star))))
    (t/is (= {:shape-ref (thi/id :card-star) :touched #{}}
             (swap-state (convert file) :copy-star)))
    (t/is (= {:shape-ref (thi/id :card-star) :touched #{}}
             (swap-state (convert file {(:id library) library}) :copy-star)))))

(t/deftest slot-matching-no-child-is-kept-as-its-ref
  (let [slot (uuid/next)
        file (-> (setup)
                 (tho/swap-component-in-shape :copy-star :heart :as-slot? true)
                 (ths/update-shape :copy-star :touched #{(cfss/build-swap-slot-group slot)})
                 (convert))]
    (t/is (= {:shape-ref slot :touched #{:swap-group}}
             (swap-state file :copy-star)))))

(t/deftest migration-converts-and-adds-the-feature
  (let [old  (-> (setup)
                 (tho/swap-component-in-shape :copy-star :heart :as-slot? true)
                 (update :features disj "components/swap-group")
                 (assoc :migrations (disj fmg/available-migrations
                                          "0032-convert-swap-slots-to-swap-groups")))
        file (fmg/migrate-file old (delay {}))]
    (t/is (contains? (:features file) "components/swap-group"))
    (t/is (not (contains? (:features old) "components/swap-group")))
    (t/is (contains? cfeat/default-features "components/swap-group"))
    (t/is (= {:shape-ref (thi/id :card-star) :touched #{:swap-group}}
             (swap-state file :copy-star)))))

(defn- move-shape
  "Move the shape and its children by the given delta, with no sync."
  [file label dx dy]
  (let [page    (thf/current-page file)
        ids     (set (cfh/get-children-ids-with-self (:objects page) (thi/id label)))
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            ids
                                            #(gsh/move % (gpt/point dx dy))
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes :validate? false)))

(defn- position
  [file label]
  (let [shape (ths/get-shape file label)]
    [(get-in shape [:selrect :x]) (get-in shape [:selrect :y])]))

(t/deftest swap-away-from-its-position-keeps-it
  ;; the main moved the replaced child after the swap; the swapped head did
  ;; not follow, and it keeps its place
  (let [file (-> (setup)
                 (tho/swap-component-in-shape :copy-star :heart :as-slot? true)
                 (move-shape :card-star 10 20)
                 (convert))
        head (ths/get-shape file :copy-star)
        file' (-> file
                  (tho/update-color :card-star "#555555")
                  (tho/propagate-component-changes :card)
                  (thf/validate-file!))]
    (t/is (= {:shape-ref (thi/id :card-star) :touched #{:swap-group :geometry-group}}
             (swap-state file :copy-star)))
    (t/is (= [(get-in head [:selrect :x]) (get-in head [:selrect :y])]
             (position file' :copy-star)))))

(t/deftest swap-in-its-position-follows-the-main
  (let [file  (-> (setup)
                  (tho/swap-component-in-shape :copy-star :heart :as-slot? true)
                  (convert)
                  (thf/validate-file!))
        [x y] (position file :copy-star)
        file' (-> file
                  (move-shape :card-star 10 20)
                  (tho/propagate-component-changes :card)
                  (thf/validate-file!))]
    (t/is (= #{:swap-group} (:touched (ths/get-shape file :copy-star))))
    (t/is (= [(+ x 10) (+ y 20)] (position file' :copy-star)))))
