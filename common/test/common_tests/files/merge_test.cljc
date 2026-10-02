;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.merge-test
  (:require
   [app.common.files.merge :as fmerge]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- base-file
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-shape :frame1 :type :frame :name "Frame")
      (ths/add-sample-shape :rect1 :parent-label :frame1 :name "Rect")
      (ths/add-sample-library-color :color1 :name "Primary" :color "#ff0000")))

(defn- merge-files
  ([base ours theirs]
   (merge-files base ours theirs nil))
  ([base ours theirs opts]
   (let [result (fmerge/merge-file-data (:data base) (:data ours) (:data theirs) opts)]
     (assoc result :file (assoc ours :data (:data result))))))

(defn- shape
  [file label]
  (ths/get-shape file label))

(t/deftest merge-seq
  (t/is (= [:a :b :c] (fmerge/merge-seq [:a :b] [:a :b] [:a :b :c])))
  (t/is (= [:x :a :b :c] (fmerge/merge-seq [:a :b] [:x :a :b] [:a :b :c])))
  (t/is (= [:x :a :c] (fmerge/merge-seq [:a :b] [:x :a :b] [:a :c])))
  (t/is (= [:a :y :c] (fmerge/merge-seq [:a :b :c] [:a :b :c] [:a :y :c])))
  (t/is (= [:a :t :o] (fmerge/merge-seq [:a] [:a :o] [:a :t]))))

(t/deftest one-side-changes-are-taken
  (let [base   (base-file)
        ours   (ths/update-shape base :rect1 :name "Renamed")
        theirs (ths/update-shape base :rect1 :opacity 0.5)
        {:keys [file conflicts changes]} (merge-files base ours theirs)]
    (t/is (empty? conflicts))
    (t/is (= "Renamed" (:name (shape file :rect1))))
    (t/is (= 0.5 (:opacity (shape file :rect1))))
    (t/is (= [{:kind :shape :op :mod :name "Rect" :groups [:layer-effects-group]}]
             (map #(select-keys % [:kind :op :name :groups]) changes)))
    (thf/validate-file! file)))

(t/deftest same-group-edits-conflict
  (let [base   (base-file)
        ours   (ths/update-shape base :rect1 :opacity 0.2)
        theirs (ths/update-shape base :rect1 :opacity 0.5)
        {:keys [file conflicts]} (merge-files base ours theirs)
        [conflict] conflicts]
    (t/is (= 1 (count conflicts)))
    (t/is (= :layer-effects-group (:group conflict)))
    (t/is (= 0.2 (:opacity (shape file :rect1))))

    (let [{:keys [file]} (merge-files base ours theirs {:resolutions {(:id conflict) :theirs}})]
      (t/is (= 0.5 (:opacity (shape file :rect1)))))))

(t/deftest equal-edits-do-not-conflict
  (let [base   (base-file)
        ours   (ths/update-shape base :rect1 :opacity 0.5)
        theirs (ths/update-shape base :rect1 :opacity 0.5)
        {:keys [conflicts]} (merge-files base ours theirs)]
    (t/is (empty? conflicts))))

(t/deftest both-sides-add-children
  (let [base   (base-file)
        ours   (ths/add-sample-shape base :rect-ours :parent-label :frame1 :name "Ours")
        theirs (ths/add-sample-shape base :rect-theirs :parent-label :frame1 :name "Theirs")
        {:keys [file conflicts]} (merge-files base ours theirs)]
    (t/is (empty? conflicts))
    (t/is (= #{(thi/id :rect1) (thi/id :rect-ours) (thi/id :rect-theirs)}
             (set (:shapes (shape file :frame1)))))
    (thf/validate-file! file)))

(t/deftest delete-versus-modify-conflicts
  (let [base   (base-file)
        page   (thf/current-page base)
        ours   (ths/update-shape base :rect1 :opacity 0.2)
        theirs (-> base
                   (update-in [:data :pages-index (:id page) :objects] dissoc (thi/id :rect1))
                   (update-in [:data :pages-index (:id page) :objects (thi/id :frame1) :shapes]
                              (fn [shapes] (filterv #(not= % (thi/id :rect1)) shapes))))
        {:keys [file conflicts]} (merge-files base ours theirs)]
    (t/is (= [:entity] (map :group conflicts)))
    (t/is (some? (shape file :rect1)))

    (let [{:keys [file]} (merge-files base ours theirs {:resolutions {(:id (first conflicts)) :theirs}})]
      (t/is (nil? (shape file :rect1)))
      (t/is (empty? (:shapes (shape file :frame1))))
      (thf/validate-file! file))))

(t/deftest library-color-merge
  (let [base   (base-file)
        id     (thi/id :color1)
        ours   (assoc-in base [:data :colors id :name] "Brand")
        theirs (assoc-in base [:data :colors id :color] "#00ff00")
        {:keys [file conflicts]} (merge-files base ours theirs)]
    (t/is (empty? conflicts))
    (t/is (= {:name "Brand" :color "#00ff00"}
             (select-keys (get-in file [:data :colors id]) [:name :color])))))

(t/deftest excluded-changes-are-not-merged
  (let [base   (base-file)
        ours   (ths/update-shape base :rect1 :opacity 0.2)
        theirs (-> base
                   (ths/update-shape :rect1 :opacity 0.5)
                   (ths/update-shape :frame1 :name "Renamed frame"))
        {:keys [changes]} (merge-files base ours theirs)
        rect-key (:key (first (filter #(= (thi/id :rect1) (:id %)) changes)))
        {:keys [file conflicts]} (merge-files base ours theirs {:excluded #{rect-key}})]
    (t/is (= 2 (count changes)))
    (t/is (empty? conflicts))
    (t/is (= 0.2 (:opacity (shape file :rect1))))
    (t/is (= "Renamed frame" (:name (shape file :frame1))))
    (thf/validate-file! file)))

(t/deftest excluded-new-child-is-dropped
  (let [base   (base-file)
        theirs (ths/add-sample-shape base :rect2 :parent-label :frame1 :name "New")
        {:keys [changes]} (merge-files base base theirs)
        new-key (:key (first (filter #(= (thi/id :rect2) (:id %)) changes)))
        {:keys [file]} (merge-files base base theirs {:excluded #{new-key}})]
    (t/is (nil? (shape file :rect2)))
    (t/is (= [(thi/id :rect1)] (:shapes (shape file :frame1))))
    (thf/validate-file! file)))
