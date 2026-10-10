;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.file-test
  "Component helpers of `app.common.types.file`: the export detach of
   external references, the library page that absorbed components go to,
   the original ref of a swapped shape, and the lookups on deleted
   components."
  (:require
   [app.common.data :as d]
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private link-attrs
  [:component-id :component-file :shape-ref :component-root])

(defn- links
  [file label]
  (select-keys (ths/get-shape file label) link-attrs))

(defn- validation-errors
  [file]
  (set (map :code (cfv/validate-file file {}))))

(defn- library-with-component
  []
  (-> (thf/sample-file :library :is-shared true)
      (tho/add-simple-component :lib-component :lib-main :lib-main-child)))

;; ----- Export detach

(t/deftest detach-external-references-drops-library-links
  (let [library (library-with-component)
        file    (-> (thf/sample-file :file)
                    (tho/add-simple-component-with-copy :component :main :main-child
                                                        :copy
                                                        :copy-root-params {:children-labels [:copy-child]})
                    (thc/instantiate-component :lib-component :lib-copy
                                               :library library
                                               :children-labels [:lib-copy-child])
                    (ths/update-shape :lib-copy-child :touched #{:fill-group}))
        file'   (ctf/detach-external-references file (:id file))]
    (t/testing "a copy of a library component becomes plain shapes"
      (t/is (= {} (links file' :lib-copy)))
      (t/is (= {} (links file' :lib-copy-child)))
      (tm/known-failure "F52" nil #{:fill-group} (:touched (ths/get-shape file' :lib-copy-child))))
    (t/testing "a copy of a local component keeps its links"
      (t/is (= (links file :copy) (links file' :copy)))
      (t/is (= (links file :copy-child) (links file' :copy-child))))
    (t/is (= #{} (validation-errors file')))))

(t/deftest detach-external-references-keeps-a-nested-library-copy-in-place
  (let [library (library-with-component)
        file    (-> (thf/sample-file :file)
                    (tho/add-frame :main {:name "Frame2"})
                    (thc/instantiate-component :lib-component :nested-head
                                               :library library
                                               :parent-label :main)
                    (thc/make-component :component :main)
                    (thc/instantiate-component :component :copy
                                               :children-labels [:copy-nested-head]))
        file'   (ctf/detach-external-references file (:id file))]
    (t/testing "the nested library copy in the main becomes plain shapes"
      (t/is (= {} (links file' :nested-head))))
    (t/testing "its copy keeps the ref to the main's shape, which is local"
      (tm/known-failure "F52"
                        {:shape-ref (thi/id :nested-head)}
                        {}
                        (links file' :copy-nested-head)))
    (t/testing "the validation does not see the lost ref"
      (t/is (= #{} (validation-errors file'))))))

;; Fill and typography refs on the shape and on the text nodes themselves
;; (the older format), next to a fill ref inside `:fills`.
(defn- file-with-color-refs
  [library-id]
  (let [file (thf/sample-file :file)]
    (-> file
        (ths/add-sample-shape :rect
                              :type :rect
                              :fill-color-ref-id (thi/id :color)
                              :fill-color-ref-file library-id
                              :stroke-color-ref-id (thi/id :color)
                              :stroke-color-ref-file library-id
                              :fills [{:fill-color "#abcdef"
                                       :fill-opacity 1
                                       :fill-color-ref-id (thi/id :color)
                                       :fill-color-ref-file library-id}])
        (ths/add-sample-shape :text
                              :type :text
                              :content {:type "root"
                                        :children [{:type "paragraph-set"
                                                    :children [{:type "paragraph"
                                                                :children [{:text "library"
                                                                            :fill-color-ref-id (thi/id :color)
                                                                            :fill-color-ref-file library-id
                                                                            :typography-ref-id (thi/id :typography)
                                                                            :typography-ref-file library-id}
                                                                           {:text "local"
                                                                            :typography-ref-id (thi/id :typography)
                                                                            :typography-ref-file (:id file)}]}]}]})
        (ths/add-sample-shape :local-rect
                              :type :rect
                              :fill-color-ref-id (thi/id :local-color)
                              :fill-color-ref-file (:id file)
                              :stroke-color-ref-id (thi/id :local-color)
                              :stroke-color-ref-file (:id file)))))

(defn- text-leaves
  [file]
  (->> (get-in (ths/get-shape file :text) [:content :children 0 :children 0 :children])
       (mapv #(select-keys % [:fill-color-ref-file :typography-ref-file]))))

(t/deftest detach-external-references-drops-library-color-refs
  (let [library-id (thi/new-id! :library)
        file       (file-with-color-refs library-id)
        file'      (ctf/detach-external-references file (:id file))
        rect'      (ths/get-shape file' :rect)]
    (t/is (= {} (select-keys rect' [:fill-color-ref-file :stroke-color-ref-file])))
    (t/testing "local refs are kept"
      (t/is (= {:fill-color-ref-file (:id file) :stroke-color-ref-file (:id file)}
               (select-keys (ths/get-shape file' :local-rect)
                            [:fill-color-ref-file :stroke-color-ref-file]))))
    (t/is (= [{} {:typography-ref-file (:id file)}] (text-leaves file')))
    (t/testing "a fill ref inside `:fills`"
      (tm/known-failure "F57" nil library-id (-> rect' :fills first :fill-color-ref-file)))))

;; ----- Asset usage

(t/deftest component-used-in-a-file
  (let [library (library-with-component)
        file    (-> (thf/sample-file :file)
                    (thc/instantiate-component :lib-component :lib-copy :library library))
        unused  (-> (thf/sample-file :other)
                    (tho/add-rect :rect))
        lib-c   (thc/get-component library :lib-component)]
    (t/is (ctf/used-in? (:data file) (:id library) lib-c :component))
    (t/is (not (ctf/used-in? (:data unused) (:id library) lib-c :component)))))

;; ----- Library page for absorbed components

(t/deftest absorbed-components-reuse-the-library-page
  (let [library1 (-> (thf/sample-file :library1 :is-shared true)
                     (tho/add-simple-component :component1 :main1 :main1-child))
        library2 (-> (thf/sample-file :library2 :is-shared true)
                     (tho/add-simple-component :component2 :main2 :main2-child))
        file     (-> (thf/sample-file :file)
                     (thc/instantiate-component :component1 :copy1 :library library1)
                     (thc/instantiate-component :component2 :copy2 :library library2))
        file'    (ctf/update-file-data file
                                       #(-> %
                                            (ctf/absorb-assets (:data library1))
                                            (ctf/absorb-assets (:data library2))))
        data'    (ctf/file-data file')
        pages'   (ctpl/pages-seq data')
        roots    (->> (ctpl/get-page data' (:id (second pages')))
                      (ctn/shapes-seq)
                      (filter ctk/main-instance?)
                      (d/index-by :component-id))]
    (t/is (= ["Page 1" "Main components"] (map :name pages')))
    (t/is (= #{(thi/id :component1) (thi/id :component2)} (set (keys roots))))
    (t/testing "the second component goes below the first, a grid gap apart"
      (let [first-root  (get roots (thi/id :component1))
            second-root (get roots (thi/id :component2))]
        (t/is (<= (+ (:y first-root) (:height first-root) 50) (:y second-root)))))
    (t/testing "the copies now use the file's own components"
      (t/is (= (:id file) (:component-file (ths/get-shape file' :copy1))))
      (t/is (= (:id file) (:component-file (ths/get-shape file' :copy2)))))
    (t/is (= #{} (validation-errors file')))))

;; ----- Original ref of a swapped shape

(defn- swap-at-second-level
  "Swaps, in a copy of :c3, the copy of :c1 nested two levels down
   (:c3 > :c2 > :c1) for :c4. Returns the file with the new shape as
   :swapped."
  [file]
  (let [copy-c2 (first (:shapes (ths/get-shape file :copy3)))
        copy-c1 (first (:shapes (ths/get-shape-by-id file copy-c2)))]
    (thi/set-id! :copy-c1 copy-c1)
    (-> file
        (tho/add-simple-component :c4 :main4 :main4-child)
        (tho/swap-component-in-shape :copy-c1 :c4 :new-shape-label :swapped))))

(defn- ref-id-for-swapped
  [file]
  (ctf/find-ref-id-for-swapped (ths/get-shape file :swapped)
                               (thf/current-page file)
                               {(:id file) file}))

(t/deftest ref-id-for-swapped-goes-up-the-ref-chain
  (let [file (-> (thf/sample-file :file)
                 (tho/add-two-levels-nested-component-with-copy
                  :c1 :main1 :main1-child
                  :c2 :main2 :nested-head1
                  :c3 :main3 :nested-head2 :nested-subhead2
                  :copy3)
                 (swap-at-second-level))]
    (t/is (= (thi/id :nested-head1) (ctk/get-swap-slot (ths/get-shape file :swapped))))
    (t/is (= (thi/id :nested-subhead2) (ref-id-for-swapped file)))))

(t/deftest ref-id-for-swapped-with-mains-on-other-pages
  (let [file (-> (thf/sample-file :file :page-label :page1)
                 (tho/add-nested-component :c1 :main1 :main1-child
                                           :c2 :main2 :nested-head1)
                 (thf/add-sample-page :page2)
                 (tho/add-frame :main3 {:name "Frame3"})
                 (thc/instantiate-component :c2 :nested-head2
                                            :parent-label :main3
                                            :children-labels [:nested-subhead2])
                 (thc/make-component :c3 :main3)
                 (thc/instantiate-component :c3 :copy3)
                 (swap-at-second-level))]
    (t/is (= (thi/id :nested-head1) (ctk/get-swap-slot (ths/get-shape file :swapped))))
    (tm/known-failure "F56" (thi/id :nested-subhead2) nil (ref-id-for-swapped file))))

;; ----- Deleted components

(defn- file-with-deleted-component
  []
  (let [file (-> (thf/sample-file :file)
                 (tho/add-simple-component :component :main :main-child))]
    (ctf/update-file-data file #(ctf/delete-component % (thi/id :component) false nil))))

(t/deftest lookups-on-a-deleted-component
  (let [file      (file-with-deleted-component)
        data      (ctf/file-data file)
        component (thc/get-component file :component :include-deleted? true)]
    (t/is (:deleted component))
    (t/is (= (thi/id :main) (:id (ctf/get-component-root data component))))
    (t/is (= #{(thi/id :main) (thi/id :main-child)}
             (set (map :id (ctf/get-component-shapes data component)))))
    (t/testing "a shape of the component, with the component as its container"
      (let [child (ctf/get-component-shape data component (thi/id :main-child)
                                           :with-context? true)]
        (t/is (= (thi/id :main-child) (:id child)))
        (t/is (= :component (:type (:container (meta child)))))))
    (t/testing "loading the objects again leaves the component as it is"
      (t/is (= component (ctf/load-component-objects data component))))))

;; ----- Containers and ref chains

(t/deftest update-container-of-a-component
  (let [file      (file-with-deleted-component)
        component (thc/get-component file :component :include-deleted? true)
        container (ctn/make-container component :component)
        data'     (ctf/update-container (ctf/file-data file) container
                                        #(assoc % :name "Renamed"))]
    (t/is (= "Renamed" (:name (thc/get-component {:data data'} :component
                                                 :include-deleted? true))))))

(t/deftest update-objects-tree-updates-and-removes
  (let [file      (-> (thf/sample-file :file)
                      (tho/add-frame-with-child :frame :child)
                      (tho/add-frame-with-child :gone :gone-child)
                      (tho/add-rect :rect))
        container (ctn/make-container (thf/current-page file) :page)
        visited   (atom #{})
        result    (ctf/update-objects-tree
                   container
                   (fn [shape]
                     (swap! visited conj (:id shape))
                     (condp = (:id shape)
                       (thi/id :rect) {:result :remove}
                       (thi/id :gone) {:result :remove}
                       {:result :update
                        :updated-shape (assoc shape :name "Updated")})))]
    (t/testing "an update reaches the children of an updated shape"
      (t/is (= "Updated" (:name (ctst/get-shape result (thi/id :frame)))))
      (t/is (= "Updated" (:name (ctst/get-shape result (thi/id :child))))))
    (t/is (nil? (ctst/get-shape result (thi/id :rect))))
    (t/testing "a removed shape goes with its children, which are not visited"
      (t/is (nil? (ctst/get-shape result (thi/id :gone))))
      (t/is (nil? (ctst/get-shape result (thi/id :gone-child))))
      (t/is (not (contains? @visited (thi/id :gone-child)))))
    (t/testing "an unknown result"
      (t/is (thrown? #?(:clj Exception :cljs :default)
                     (ctf/update-objects-tree container (constantly {:result :other})))))))

(t/deftest ref-chain-without-the-target
  (let [file      (-> (thf/sample-file :file)
                      (tho/add-simple-component-with-copy :component :main :main-child
                                                          :copy
                                                          :copy-root-params {:children-labels [:copy-child]}))
        page      (thf/current-page file)
        libraries {(:id file) file}
        chain     (ctf/get-ref-chain-until-target-ref page libraries
                                                      (ths/get-shape file :copy-child)
                                                      (thi/id :copy))]
    (t/testing "the chain runs to the main and stops there"
      (t/is (= [(thi/id :copy-child) (thi/id :main-child)] (map :id chain))))))

(t/deftest ref-chain-stops-at-the-target
  ;; the target is the second link of a chain three links long
  (let [file      (-> (thf/sample-file :file)
                      (tho/add-two-levels-nested-component-with-copy
                       :c1 :main1 :main1-child
                       :c2 :main2 :nested-head1
                       :c3 :main3 :nested-head2 :nested-subhead2
                       :copy3))
        copy-c2   (first (:shapes (ths/get-shape file :copy3)))
        copy-c1   (ths/get-shape-by-id file (first (:shapes (ths/get-shape-by-id file copy-c2))))
        chain     (ctf/get-ref-chain-until-target-ref (thf/current-page file) {(:id file) file}
                                                      copy-c1 (thi/id :nested-subhead2))]
    ;; the stop compares each shape with the target's id, so it never holds
    (tm/known-failure "F62"
                      [(:id copy-c1) (thi/id :nested-subhead2)]
                      [(:id copy-c1) (thi/id :nested-subhead2) (thi/id :nested-head1) (thi/id :main1)]
                      (mapv :id chain))))

(t/deftest near-match-with-context
  (let [file  (-> (thf/sample-file :file)
                  (tho/add-simple-component-with-copy :component :main :main-child
                                                      :copy
                                                      :copy-root-params {:children-labels [:copy-child]}))
        match (ctf/find-near-match file (thf/current-page file) {(:id file) file}
                                   (ths/get-shape file :copy-child)
                                   :with-context? true)]
    (t/is (= (thi/id :main-child) (:id match)))
    (t/is (= :page (:type (:container (meta match)))))
    (t/is (= (:id (thf/current-page file)) (:id (:container (meta match)))))))
