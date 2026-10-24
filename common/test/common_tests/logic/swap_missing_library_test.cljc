;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.swap-missing-library-test
  (:require
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-library []
  ;; {:frame-a} [:name Frame1]    # [Component :component-a]
  ;;     :child-a [:name Rect1]
  (-> (thf/sample-file :library)
      (tho/add-simple-component :component-a :frame-a :child-a
                                :root-params {:name "component-a"}
                                :child-params {:name "child-a"
                                               :type :rect
                                               :fills (ths/sample-fills-color :fill-color "#111111")})))

(defn- setup-file [library]
  ;; {:frame-b} [:name Frame1]            # [Component :component-b]
  ;;     :copy-a [:name component-a]      @--> [Component :component-a] (in library) :frame-a
  ;;         <no-label> [:name child-a]   ---> :child-a
  ;;
  ;; :copy-b [:name Frame1]               #--> [Component :component-b] :frame-b
  ;;     <no-label> [:name component-a]   @--> [Component :component-a] (in library) :copy-a
  ;;         <no-label> [:name child-a]   ---> <no-label>
  ;;
  ;; {:frame-c} [:name Frame1]            # [Component :component-c]
  ;;     :child-c [:name Rect1]
  (-> (thf/sample-file :file)
      (tho/add-frame :frame-b)
      (thc/instantiate-component :component-a :copy-a
                                 :parent-label :frame-b
                                 :library library)
      (thc/make-component :component-b :frame-b)
      (thc/instantiate-component :component-b :copy-b)
      (tho/add-simple-component :component-c :frame-c :child-c
                                :root-params {:name "component-c"}
                                :child-params {:name "child-c"
                                               :type :rect
                                               :fills (ths/sample-fills-color :fill-color "#333333")})))

(defn- propagate-all-component-changes [file]
  (-> file
      (tho/propagate-component-changes :component-c)
      (tho/propagate-component-changes :component-b)))

(defn- fill-colors [file]
  [(tho/bottom-fill-color file :frame-b)
   (tho/bottom-fill-color file :copy-b)])

(t/deftest test-swap-nested-copy-with-main-in-missing-library
  (let [library (setup-library)
        file    (setup-file library)

        ;; The library is not passed to the swap, so the main of A is not
        ;; available.
        file'   (tho/swap-component-in-first-child file :copy-b :component-c)]

    (cfv/validate-file! file' {(:id library) library})

    (t/testing "after the swap, B and its copy contain the new copy of C"
      (t/is (= ["#111111" "#333333"] (fill-colors file'))))

    (t/testing "changes in the main of C propagate to B and its copy"
      (let [file'' (tho/update-bottom-color file' :frame-c "#444444"
                                            :propagate-fn propagate-all-component-changes)]

        (t/is (= ["#111111" "#444444"] (fill-colors file'')))))))
