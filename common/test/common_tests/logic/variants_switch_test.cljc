;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.variants-switch-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-simple-switch
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant-with-child
                       :v01 :c01 :m01 :c02 :m02 :r01 :r02
                       {:child1-params {:width 5}
                        :child2-params  {:width 15}})

                      (thc/instantiate-component :c01
                                                 :copy01
                                                 :children-labels [:copy-r01]))

        page   (thf/current-page file)
        copy01 (ths/get-shape file :copy01)
        rect01 (get-in page [:objects (-> copy01 :shapes first)])

        ;; ==== Action
        file'     (tho/swap-component file copy01 :c02 {:new-shape-label :copy02 :keep-touched? true})

        page'     (thf/current-page file')
        copy02'   (ths/get-shape file' :copy02)
        rect02'      (get-in page' [:objects (-> copy02' :shapes first)])]
    ;; The rect had width 5 before the switch
    (t/is (= (:width rect01) 5))
    ;; The rect has width 15 after the switch
    (t/is (= (:width rect02') 15))))

(t/deftest test-switch-with-override
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant-with-child
                       :v01 :c01 :m01 :c02 :m02 :r01 :r02
                       {:child1-params {:width 5}
                        :child2-params  {:width 5}})

                      (thc/instantiate-component :c01
                                                 :copy01
                                                 :children-labels [:copy-r01]))

        page   (thf/current-page file)
        copy01 (ths/get-shape file :copy01)
        rect01 (get-in page [:objects (-> copy01 :shapes first)])

        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(:id rect01)}
                                            (fn [shape]
                                              (assoc shape :width 25))
                                            (:objects page)
                                            {})

        file   (thf/apply-changes file changes)
        page   (thf/current-page file)
        rect01 (get-in page [:objects (:id rect01)])

        ;; ==== Action
        file'     (tho/swap-component file copy01 :c02 {:new-shape-label :copy02 :keep-touched? true})

        page'     (thf/current-page file')
        copy02'   (ths/get-shape file' :copy02)
        rect02'   (get-in page' [:objects (-> copy02' :shapes first)])]

    ;; The rect had width 25 before the switch
    (t/is (= (:width rect01) 25))
    ;; The override is keept: The rect still has width 25 after the switch
    (t/is (= (:width rect02') 25))))

(t/deftest test-switch-with-no-override
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant-with-child
                       :v01 :c01 :m01 :c02 :m02 :r01 :r02
                       {:child1-params {:width 5}
                        :child2-params  {:width 15}})

                      (thc/instantiate-component :c01
                                                 :copy01
                                                 :children-labels [:copy-r01]))

        page   (thf/current-page file)
        copy01 (ths/get-shape file :copy01)
        rect01 (get-in page [:objects (-> copy01 :shapes first)])

        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(:id rect01)}
                                            (fn [shape]
                                              (assoc shape :width 25))
                                            (:objects page)
                                            {})

        file   (thf/apply-changes file changes)
        page   (thf/current-page file)
        rect01 (get-in page [:objects (:id rect01)])

        ;; ==== Action
        file'     (tho/swap-component file copy01 :c02 {:new-shape-label :copy02 :keep-touched? true})

        page'     (thf/current-page file')
        copy02'   (ths/get-shape file' :copy02)
        rect02'   (get-in page' [:objects (-> copy02' :shapes first)])]

    ;; The rect had width 25 before the switch
    (t/is (= (:width rect01) 25))
    ;; The override isn't keept, because the property is different in the mains
    ;; The rect has width 15 after the switch
    (t/is (= (:width rect02') 15))))

(def font-size-path-paragraph [:content :children 0 :children 0 :font-size])
(def font-size-path-0 [:content :children 0 :children 0 :children 0 :font-size])
(def font-size-path-1 [:content :children 0 :children 0 :children 1 :font-size])

(def text-path-0 [:content :children 0 :children 0 :children 0 :text])
(def text-path-1 [:content :children 0 :children 0 :children 1 :text])
(def text-lines-path [:content :children 0 :children 0 :children])

(defn- update-attr
  [file label path value]
  (let [page    (thf/current-page file)
        shape   (ths/get-shape file label)
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{(:id shape)}
                 (fn [shape]
                   (cond-> (assoc-in shape path value)
                     (or (= path font-size-path-0) (= path font-size-path-1))
                     (assoc-in font-size-path-paragraph value)))
                 (:objects page)
                 {})]
    (thf/apply-changes file changes)))

(defn- change-structure
  [file label]
  (let [page    (thf/current-page file)
        shape   (ths/get-shape file label)
        line1   (-> (get-in shape text-lines-path)
                    first
                    (assoc :text "new line 1"))
        line2   (assoc line1 :text "new line 2")
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{(:id shape)}
                 (fn [shape]
                   (assoc-in shape text-lines-path [line1 line2]))
                 (:objects page)
                 {})]
    (thf/apply-changes file changes)))

(t/deftest test-switch-with-identical-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; Both components are identical: have the same text and props
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "hello world")
                 (thc/instantiate-component :c01
                                            :copy-clean
                                            :children-labels [:copy-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-font-size
                                            :children-labels [:copy-font-size-t])
                 (thc/instantiate-component :c01
                                            :copy-text
                                            :children-labels [:copy-text-t])
                 (thc/instantiate-component :c01
                                            :copy-both
                                            :children-labels [:copy-both-t]))


        ;; The copy clean has no overrides


        copy-clean       (ths/get-shape file :copy-clean)
        copy-clean-t     (ths/get-shape file :copy-clean-t)

        ;; Override font size on copy-font-size
        file             (update-attr file :copy-font-size-t font-size-path-0 "25")
        copy-font-size   (ths/get-shape file :copy-font-size)
        copy-font-size-t (ths/get-shape file :copy-font-size-t)

        ;; Override text on copy-text
        file             (update-attr file :copy-text-t text-path-0 "text overriden")
        copy-text        (ths/get-shape file :copy-text)
        copy-text-t      (ths/get-shape file :copy-text-t)

        ;; Override both on copy-both
        file             (update-attr file :copy-both-t font-size-path-0 "25")
        file             (update-attr file :copy-both-t text-path-0 "text overriden")
        copy-both        (ths/get-shape file :copy-both)
        copy-both-t      (ths/get-shape file :copy-both-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-clean :c02 {:new-shape-label :copy-clean-2 :keep-touched? true})
                  (tho/swap-component copy-font-size :c02 {:new-shape-label :copy-font-size-2 :keep-touched? true})
                  (tho/swap-component copy-text :c02 {:new-shape-label :copy-text-2 :keep-touched? true})
                  (tho/swap-component copy-both :c02 {:new-shape-label :copy-both-2 :keep-touched? true}))
        page'             (thf/current-page file')
        copy-clean'       (ths/get-shape file' :copy-clean-2)
        copy-clean-t'     (get-in page' [:objects (-> copy-clean' :shapes first)])

        copy-font-size'   (ths/get-shape file' :copy-font-size-2)
        copy-font-size-t' (get-in page' [:objects (-> copy-font-size' :shapes first)])

        copy-text'        (ths/get-shape file' :copy-text-2)
        copy-text-t'      (get-in page' [:objects (-> copy-text' :shapes first)])

        copy-both'        (ths/get-shape file' :copy-both-2)
        copy-both-t'      (get-in page' [:objects (-> copy-both' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})


    ;;;;;;;;;;; Clean copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "hello world"


    (t/is (= (get-in copy-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-clean-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 25 (value of c02, because there was no override)
    ;;   * text "hello world" (value of c02, because there was no override)
    (t/is (= (get-in copy-clean-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-clean-t' text-path-0) "hello world"))


    ;;;;;;;;;;; Font size copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "hello world"


    (t/is (= (get-in copy-font-size-t font-size-path-0) "25"))
    (t/is (= (get-in copy-font-size-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "hello world" (value of c02, because there was no override)
    (t/is (= (get-in copy-font-size-t' font-size-path-0) "25"))
    (t/is (= (get-in copy-font-size-t' text-path-0) "hello world"))

    ;;;;;;;;;;; Text copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "text overriden"
    (t/is (= (get-in copy-text-t font-size-path-0) "14"))
    (t/is (= (get-in copy-text-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 14 (value of c02, because there was no override)
    ;;   * text "text overriden" (the override is preserved)
    (t/is (= (get-in copy-text-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-text-t' text-path-0) "text overriden"))

    ;;;;;;;;;;; Both copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "text overriden"
    (t/is (= (get-in copy-both-t font-size-path-0) "25"))
    (t/is (= (get-in copy-both-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "text overriden" (the override is preserved)
    (t/is (= (get-in copy-both-t' font-size-path-0) "25"))
    (t/is (= (get-in copy-both-t' text-path-0) "text overriden"))))

(t/deftest test-switch-with-different-prop-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; The second component has a different prop
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "hello world")
                 (update-attr :t02 font-size-path-0 "50")

                 (thc/instantiate-component :c01
                                            :copy-clean
                                            :children-labels [:copy-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-font-size
                                            :children-labels [:copy-font-size-t])
                 (thc/instantiate-component :c01
                                            :copy-text
                                            :children-labels [:copy-text-t])
                 (thc/instantiate-component :c01
                                            :copy-both
                                            :children-labels [:copy-both-t]))


        ;; The copy clean has no overrides


        copy-clean       (ths/get-shape file :copy-clean)
        copy-clean-t     (ths/get-shape file :copy-clean-t)

        ;; Override font size on copy-font-size
        file             (update-attr file :copy-font-size-t font-size-path-0 "25")
        copy-font-size   (ths/get-shape file :copy-font-size)
        copy-font-size-t (ths/get-shape file :copy-font-size-t)

        ;; Override text on copy-text
        file             (update-attr file :copy-text-t text-path-0 "text overriden")
        copy-text        (ths/get-shape file :copy-text)
        copy-text-t      (ths/get-shape file :copy-text-t)

        ;; Override both on copy-both
        file             (update-attr file :copy-both-t font-size-path-0 "25")
        file             (update-attr file :copy-both-t text-path-0 "text overriden")
        copy-both        (ths/get-shape file :copy-both)
        copy-both-t      (ths/get-shape file :copy-both-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-clean :c02 {:new-shape-label :copy-clean-2 :keep-touched? true})
                  (tho/swap-component copy-font-size :c02 {:new-shape-label :copy-font-size-2 :keep-touched? true})
                  (tho/swap-component copy-text :c02 {:new-shape-label :copy-text-2 :keep-touched? true})
                  (tho/swap-component copy-both :c02 {:new-shape-label :copy-both-2 :keep-touched? true}))
        page'             (thf/current-page file')
        copy-clean'       (ths/get-shape file' :copy-clean-2)
        copy-clean-t'     (get-in page' [:objects (-> copy-clean' :shapes first)])

        copy-font-size'   (ths/get-shape file' :copy-font-size-2)
        copy-font-size-t' (get-in page' [:objects (-> copy-font-size' :shapes first)])

        copy-text'        (ths/get-shape file' :copy-text-2)
        copy-text-t'      (get-in page' [:objects (-> copy-text' :shapes first)])

        copy-both'        (ths/get-shape file' :copy-both-2)
        copy-both-t'      (get-in page' [:objects (-> copy-both' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})


    ;;;;;;;;;;; Clean copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "hello world"


    (t/is (= (get-in copy-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-clean-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "hello world" (value of c02, because there was no override)
    (t/is (= (get-in copy-clean-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-clean-t' text-path-0) "hello world"))


    ;;;;;;;;;;; Font size copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "hello world"


    (t/is (= (get-in copy-font-size-t font-size-path-0) "25"))
    (t/is (= (get-in copy-font-size-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 50 (value of c02: the override is not preserved)
    ;;   * text "hello world" (value of c02, because there was no override)
    (t/is (= (get-in copy-font-size-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-font-size-t' text-path-0) "hello world"))

    ;;;;;;;;;;; Text copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "text overriden"
    (t/is (= (get-in copy-text-t font-size-path-0) "14"))
    (t/is (= (get-in copy-text-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "text overriden" (the override is preserved)
    (t/is (= (get-in copy-text-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-text-t' text-path-0) "text overriden"))

    ;;;;;;;;;;; Both copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "text overriden"
    (t/is (= (get-in copy-both-t font-size-path-0) "25"))
    (t/is (= (get-in copy-both-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 50 (value of c02: the override is not preserved)
    ;;   * text "text overriden" (the override is preserved)
    (t/is (= (get-in copy-both-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-both-t' text-path-0) "text overriden"))))

(t/deftest test-switch-with-different-text-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; Second comp has different text
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "bye")
                 (thc/instantiate-component :c01
                                            :copy-clean
                                            :children-labels [:copy-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-font-size
                                            :children-labels [:copy-font-size-t])
                 (thc/instantiate-component :c01
                                            :copy-text
                                            :children-labels [:copy-text-t])
                 (thc/instantiate-component :c01
                                            :copy-both
                                            :children-labels [:copy-both-t]))


        ;; The copy clean has no overrides


        copy-clean       (ths/get-shape file :copy-clean)
        copy-clean-t     (ths/get-shape file :copy-clean-t)

        ;; Override font size on copy-font-size
        file             (update-attr file :copy-font-size-t font-size-path-0 "25")
        copy-font-size   (ths/get-shape file :copy-font-size)
        copy-font-size-t (ths/get-shape file :copy-font-size-t)

        ;; Override text on copy-text
        file             (update-attr file :copy-text-t text-path-0 "text overriden")
        copy-text        (ths/get-shape file :copy-text)
        copy-text-t      (ths/get-shape file :copy-text-t)

        ;; Override both on copy-both
        file             (update-attr file :copy-both-t font-size-path-0 "25")
        file             (update-attr file :copy-both-t text-path-0 "text overriden")
        copy-both        (ths/get-shape file :copy-both)
        copy-both-t      (ths/get-shape file :copy-both-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-clean :c02 {:new-shape-label :copy-clean-2 :keep-touched? true})
                  (tho/swap-component copy-font-size :c02 {:new-shape-label :copy-font-size-2 :keep-touched? true})
                  (tho/swap-component copy-text :c02 {:new-shape-label :copy-text-2 :keep-touched? true})
                  (tho/swap-component copy-both :c02 {:new-shape-label :copy-both-2 :keep-touched? true}))
        page'             (thf/current-page file')
        copy-clean'       (ths/get-shape file' :copy-clean-2)
        copy-clean-t'     (get-in page' [:objects (-> copy-clean' :shapes first)])

        copy-font-size'   (ths/get-shape file' :copy-font-size-2)
        copy-font-size-t' (get-in page' [:objects (-> copy-font-size' :shapes first)])

        copy-text'        (ths/get-shape file' :copy-text-2)
        copy-text-t'      (get-in page' [:objects (-> copy-text' :shapes first)])

        copy-both'        (ths/get-shape file' :copy-both-2)
        copy-both-t'      (get-in page' [:objects (-> copy-both' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})


    ;;;;;;;;;;; Clean copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "hello world"


    (t/is (= (get-in copy-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-clean-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 25 (value of c02, because there was no override)
    ;;   * text "bye" (value of c02, because there was no override)
    (t/is (= (get-in copy-clean-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-clean-t' text-path-0) "bye"))


    ;;;;;;;;;;; Font size copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "hello world"


    (t/is (= (get-in copy-font-size-t font-size-path-0) "25"))
    (t/is (= (get-in copy-font-size-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "bye" (value of c02, because there was no override)
    (t/is (= (get-in copy-font-size-t' font-size-path-0) "25"))
    (t/is (= (get-in copy-font-size-t' text-path-0) "bye"))

    ;;;;;;;;;;; Text copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "text overriden"
    (t/is (= (get-in copy-text-t font-size-path-0) "14"))
    (t/is (= (get-in copy-text-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 14 (value of c02, because there was no override)
    ;;   * text "text overriden" (value of c02: the override is not preserved)
    (t/is (= (get-in copy-text-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-text-t' text-path-0) "bye"))

    ;;;;;;;;;;; Both copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "text overriden"
    (t/is (= (get-in copy-both-t font-size-path-0) "25"))
    (t/is (= (get-in copy-both-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "text overriden" (value of c02: the override is not preserved)
    (t/is (= (get-in copy-both-t' font-size-path-0) "25"))
    (t/is (= (get-in copy-both-t' text-path-0) "bye"))))

(t/deftest test-switch-with-different-text-and-prop-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; The second component has a different text and prop
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "bye")
                 (update-attr :t02 font-size-path-0 "50")

                 (thc/instantiate-component :c01
                                            :copy-clean
                                            :children-labels [:copy-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-font-size
                                            :children-labels [:copy-font-size-t])
                 (thc/instantiate-component :c01
                                            :copy-text
                                            :children-labels [:copy-text-t])
                 (thc/instantiate-component :c01
                                            :copy-both
                                            :children-labels [:copy-both-t]))


        ;; The copy clean has no overrides


        copy-clean       (ths/get-shape file :copy-clean)
        copy-clean-t     (ths/get-shape file :copy-clean-t)

        ;; Override font size on copy-font-size
        file             (update-attr file :copy-font-size-t font-size-path-0 "25")
        copy-font-size   (ths/get-shape file :copy-font-size)
        copy-font-size-t (ths/get-shape file :copy-font-size-t)

        ;; Override text on copy-text
        file             (update-attr file :copy-text-t text-path-0 "text overriden")
        copy-text        (ths/get-shape file :copy-text)
        copy-text-t      (ths/get-shape file :copy-text-t)

        ;; Override both on copy-both
        file             (update-attr file :copy-both-t font-size-path-0 "25")
        file             (update-attr file :copy-both-t text-path-0 "text overriden")
        copy-both        (ths/get-shape file :copy-both)
        copy-both-t      (ths/get-shape file :copy-both-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-clean :c02 {:new-shape-label :copy-clean-2 :keep-touched? true})
                  (tho/swap-component copy-font-size :c02 {:new-shape-label :copy-font-size-2 :keep-touched? true})
                  (tho/swap-component copy-text :c02 {:new-shape-label :copy-text-2 :keep-touched? true})
                  (tho/swap-component copy-both :c02 {:new-shape-label :copy-both-2 :keep-touched? true}))
        page'             (thf/current-page file')
        copy-clean'       (ths/get-shape file' :copy-clean-2)
        copy-clean-t'     (get-in page' [:objects (-> copy-clean' :shapes first)])

        copy-font-size'   (ths/get-shape file' :copy-font-size-2)
        copy-font-size-t' (get-in page' [:objects (-> copy-font-size' :shapes first)])

        copy-text'        (ths/get-shape file' :copy-text-2)
        copy-text-t'      (get-in page' [:objects (-> copy-text' :shapes first)])

        copy-both'        (ths/get-shape file' :copy-both-2)
        copy-both-t'      (get-in page' [:objects (-> copy-both' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})


    ;;;;;;;;;;; Clean copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "hello world"


    (t/is (= (get-in copy-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-clean-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "bye" (value of c02, because there was no override)
    (t/is (= (get-in copy-clean-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-clean-t' text-path-0) "bye"))


    ;;;;;;;;;;; Font size copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "hello world"


    (t/is (= (get-in copy-font-size-t font-size-path-0) "25"))
    (t/is (= (get-in copy-font-size-t text-path-0) "hello world"))

    ;; After the switch:
    ;;   * font size 50 (value of c02: the override is not preserved)
    ;;   * text "bye" (value of c02, because there was no override)
    (t/is (= (get-in copy-font-size-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-font-size-t' text-path-0) "bye"))

    ;;;;;;;;;;; Text copy
    ;; Before the switch:
    ;;   * font size 14
    ;;   * text "text overriden"
    (t/is (= (get-in copy-text-t font-size-path-0) "14"))
    (t/is (= (get-in copy-text-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "bye" (value of c02: the override is not preserved)
    (t/is (= (get-in copy-text-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-text-t' text-path-0) "bye"))

    ;;;;;;;;;;; Both copy
    ;; Before the switch:
    ;;   * font size 25
    ;;   * text "text overriden"
    (t/is (= (get-in copy-both-t font-size-path-0) "25"))
    (t/is (= (get-in copy-both-t text-path-0) "text overriden"))

    ;; After the switch:
    ;;   * font size 50 (value of c02: the override is not preserved)
    ;;   * text "bye" (value of c02: the override is not preserved)
    (t/is (= (get-in copy-both-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-both-t' text-path-0) "bye"))))

(t/deftest test-switch-with-identical-structure-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; Both components are identical: have the same text and props
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "hello world")
                 (thc/instantiate-component :c01
                                            :copy-structure-clean
                                            :children-labels [:copy-structure-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-unif
                                            :children-labels [:copy-structure-unif-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-mixed
                                            :children-labels [:copy-structure-mixed-t]))



        ;; Duplicate a text line in copy-structure-clean


        file                   (change-structure file :copy-structure-clean-t)
        copy-structure-clean   (ths/get-shape file :copy-structure-clean)
        copy-structure-clean-t (ths/get-shape file :copy-structure-clean-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; both lines with the same attrs
        file                   (-> (update-attr file :copy-structure-unif-t font-size-path-0 "25")
                                   (change-structure :copy-structure-unif-t))
        copy-structure-unif   (ths/get-shape file :copy-structure-unif)
        copy-structure-unif-t (ths/get-shape file :copy-structure-unif-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; each line with a different attr
        file                   (-> (change-structure file :copy-structure-mixed-t)
                                   (update-attr :copy-structure-mixed-t font-size-path-0 "35")
                                   (update-attr :copy-structure-mixed-t font-size-path-1 "40"))
        copy-structure-mixed   (ths/get-shape file :copy-structure-mixed)
        copy-structure-mixed-t (ths/get-shape file :copy-structure-mixed-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-structure-clean :c02 {:new-shape-label :copy-structure-clean-2 :keep-touched? true})
                  (tho/swap-component copy-structure-unif :c02 {:new-shape-label :copy-structure-unif-2 :keep-touched? true})
                  (tho/swap-component copy-structure-mixed :c02 {:new-shape-label :copy-structure-mixed-2 :keep-touched? true}))
        page'                   (thf/current-page file')
        copy-structure-clean'   (ths/get-shape file' :copy-structure-clean-2)
        copy-structure-clean-t' (get-in page' [:objects (-> copy-structure-clean' :shapes first)])

        copy-structure-unif'    (ths/get-shape file' :copy-structure-unif-2)
        copy-structure-unif-t'  (get-in page' [:objects (-> copy-structure-unif' :shapes first)])

        copy-structure-mixed'   (ths/get-shape file' :copy-structure-mixed-2)
        copy-structure-mixed-t' (get-in page' [:objects (-> copy-structure-mixed' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})

    ;;;;;;;;;;; Copy structure clean
    ;; Before the switch, first line:
    ;;   * font size 14
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 14
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-clean-t font-size-path-1) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 14 (value of c02, because there was no override)
    ;;   * text "new line 1" (the override is preserved)
    ;; Second line:
    ;;   * font size 14 (value of c02, because there was no override)
    ;;   * text "new line 2" (the override is preserved)
    (t/is (= (get-in copy-structure-clean-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-clean-t' text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-clean-t' font-size-path-1) "14"))
    (t/is (= (get-in copy-structure-clean-t' text-path-1) "new line 2"))

    ;;;;;;;;;;; Copy structure unif
    ;; Before the switch, first line:
    ;;   * font size 25
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 25
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-unif-t font-size-path-0) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-unif-t font-size-path-1) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "new line 1" (the override is preserved)
    ;; Second line:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "new line 2" (the override is preserved)
    (t/is (= (get-in copy-structure-unif-t' font-size-path-0) "25"))
    (t/is (= (get-in copy-structure-unif-t' text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-unif-t' font-size-path-1) "25"))
    (t/is (= (get-in copy-structure-unif-t' text-path-1) "new line 2"))

    ;;;;;;;;;;; Copy structure mixed
    ;; Before the switch, first line:
    ;;   * font size 35
    ;;   * text "new line 1"
    ;; Before the switch, second line:
    ;;   * font size 40
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-mixed-t font-size-path-0) "35"))
    (t/is (= (get-in copy-structure-mixed-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-mixed-t font-size-path-1) "40"))
    (t/is (= (get-in copy-structure-mixed-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 35 (the override is preserved)
    ;;   * text "new line 1" (the override is preserved)
    ;; Second line:
    ;;   * font size 40 (the override is preserved)
    ;;   * text "new line 2" (the override is preserved)
    (t/is (= (get-in copy-structure-mixed-t' font-size-path-0) "35"))
    (t/is (= (get-in copy-structure-mixed-t' text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-mixed-t' font-size-path-1) "40"))
    (t/is (= (get-in copy-structure-mixed-t' text-path-1) "new line 2"))))

(t/deftest test-switch-with-different-prop-structure-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; The second component has a different prop
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "hello world")
                 (update-attr :t02 font-size-path-0 "50")
                 (thc/instantiate-component :c01
                                            :copy-structure-clean
                                            :children-labels [:copy-structure-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-unif
                                            :children-labels [:copy-structure-unif-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-mixed
                                            :children-labels [:copy-structure-mixed-t]))



        ;; Duplicate a text line in copy-structure-clean


        file                   (change-structure file :copy-structure-clean-t)
        copy-structure-clean   (ths/get-shape file :copy-structure-clean)
        copy-structure-clean-t (ths/get-shape file :copy-structure-clean-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; both lines with the same attrs
        file                   (-> (update-attr file :copy-structure-unif-t font-size-path-0 "25")
                                   (change-structure :copy-structure-unif-t))
        copy-structure-unif   (ths/get-shape file :copy-structure-unif)
        copy-structure-unif-t (ths/get-shape file :copy-structure-unif-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; each line with a different attr
        file                   (-> (change-structure file :copy-structure-mixed-t)
                                   (update-attr :copy-structure-mixed-t font-size-path-0 "35")
                                   (update-attr :copy-structure-mixed-t font-size-path-1 "40"))
        copy-structure-mixed   (ths/get-shape file :copy-structure-mixed)
        copy-structure-mixed-t (ths/get-shape file :copy-structure-mixed-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-structure-clean :c02 {:new-shape-label :copy-structure-clean-2 :keep-touched? true})
                  (tho/swap-component copy-structure-unif :c02 {:new-shape-label :copy-structure-unif-2 :keep-touched? true})
                  (tho/swap-component copy-structure-mixed :c02 {:new-shape-label :copy-structure-mixed-2 :keep-touched? true}))
        page'                   (thf/current-page file')
        copy-structure-clean'   (ths/get-shape file' :copy-structure-clean-2)
        copy-structure-clean-t' (get-in page' [:objects (-> copy-structure-clean' :shapes first)])

        copy-structure-unif'    (ths/get-shape file' :copy-structure-unif-2)
        copy-structure-unif-t'  (get-in page' [:objects (-> copy-structure-unif' :shapes first)])

        copy-structure-mixed'   (ths/get-shape file' :copy-structure-mixed-2)
        copy-structure-mixed-t' (get-in page' [:objects (-> copy-structure-mixed' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})

    ;;;;;;;;;;; Copy structure clean
    ;; Before the switch, first line:
    ;;   * font size 14
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 14
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-clean-t font-size-path-1) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "new line 1" (the override is preserved)
    ;; Second line:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "new line 2" (the override is preserved)
    (t/is (= (get-in copy-structure-clean-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-structure-clean-t' text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-clean-t' font-size-path-1) "50"))
    (t/is (= (get-in copy-structure-clean-t' text-path-1) "new line 2"))

    ;;;;;;;;;;; Copy structure unif
    ;; Before the switch, first line:
    ;;   * font size 25
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 25
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-unif-t font-size-path-0) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-unif-t font-size-path-1) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 50 (the override is not preserved)
    ;;   * text "new line 1" (the override is preserved)
    ;; Second line:
    ;;   * font size 50 (the override is not preserved)
    ;;   * text "new line 2" (the override is preserved)
    (t/is (= (get-in copy-structure-unif-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-structure-unif-t' text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-unif-t' font-size-path-1) "50"))
    (t/is (= (get-in copy-structure-unif-t' text-path-1) "new line 2"))

    ;;;;;;;;;;; Copy structure mixed
    ;; Before the switch, first line:
    ;;   * font size 35
    ;;   * text "new line 1"
    ;; Before the switch, second line:
    ;;   * font size 40
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-mixed-t font-size-path-0) "35"))
    (t/is (= (get-in copy-structure-mixed-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-mixed-t font-size-path-1) "40"))
    (t/is (= (get-in copy-structure-mixed-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 50 (the override is not preserved)
    ;;   * text "hello world" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-mixed-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-structure-mixed-t' text-path-0) "hello world"))
    (t/is (nil? (get-in copy-structure-mixed-t' font-size-path-1)))))

(t/deftest test-switch-with-different-text-structure-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; Second comp has different text
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "bye")
                 (thc/instantiate-component :c01
                                            :copy-structure-clean
                                            :children-labels [:copy-structure-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-unif
                                            :children-labels [:copy-structure-unif-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-mixed
                                            :children-labels [:copy-structure-mixed-t]))



        ;; Duplicate a text line in copy-structure-clean


        file                   (change-structure file :copy-structure-clean-t)
        copy-structure-clean   (ths/get-shape file :copy-structure-clean)
        copy-structure-clean-t (ths/get-shape file :copy-structure-clean-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; both lines with the same attrs
        file                   (-> (update-attr file :copy-structure-unif-t font-size-path-0 "25")
                                   (change-structure :copy-structure-unif-t))
        copy-structure-unif   (ths/get-shape file :copy-structure-unif)
        copy-structure-unif-t (ths/get-shape file :copy-structure-unif-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; each line with a different attr
        file                   (-> (change-structure file :copy-structure-mixed-t)
                                   (update-attr :copy-structure-mixed-t font-size-path-0 "35")
                                   (update-attr :copy-structure-mixed-t font-size-path-1 "40"))
        copy-structure-mixed   (ths/get-shape file :copy-structure-mixed)
        copy-structure-mixed-t (ths/get-shape file :copy-structure-mixed-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-structure-clean :c02 {:new-shape-label :copy-structure-clean-2 :keep-touched? true})
                  (tho/swap-component copy-structure-unif :c02 {:new-shape-label :copy-structure-unif-2 :keep-touched? true})
                  (tho/swap-component copy-structure-mixed :c02 {:new-shape-label :copy-structure-mixed-2 :keep-touched? true}))
        page'                   (thf/current-page file')
        copy-structure-clean'   (ths/get-shape file' :copy-structure-clean-2)
        copy-structure-clean-t' (get-in page' [:objects (-> copy-structure-clean' :shapes first)])

        copy-structure-unif'    (ths/get-shape file' :copy-structure-unif-2)
        copy-structure-unif-t'  (get-in page' [:objects (-> copy-structure-unif' :shapes first)])

        copy-structure-mixed'   (ths/get-shape file' :copy-structure-mixed-2)
        copy-structure-mixed-t' (get-in page' [:objects (-> copy-structure-mixed' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})

    ;;;;;;;;;;; Copy structure clean
    ;; Before the switch, first line:
    ;;   * font size 14
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 14
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-clean-t font-size-path-1) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 14 (value of c02, because there was no override)
    ;;   * text "bye" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-clean-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-clean-t' text-path-0) "bye"))
    (t/is (nil? (get-in copy-structure-clean-t' font-size-path-1)))


    ;;;;;;;;;;; Copy structure unif
    ;; Before the switch, first line:
    ;;   * font size 25
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 25
    ;;   * text "new line 2"


    (t/is (= (get-in copy-structure-unif-t font-size-path-0) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-unif-t font-size-path-1) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 25 (the override is preserved)
    ;;   * text "bye" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-unif-t' font-size-path-0) "25"))
    (t/is (= (get-in copy-structure-unif-t' text-path-0) "bye"))
    (t/is (nil? (get-in copy-structure-unif-t' font-size-path-1)))


    ;;;;;;;;;;; Copy structure mixed
    ;; Before the switch, first line:
    ;;   * font size 35
    ;;   * text "new line 1"
    ;; Before the switch, second line:
    ;;   * font size 40
    ;;   * text "new line 2"


    (t/is (= (get-in copy-structure-mixed-t font-size-path-0) "35"))
    (t/is (= (get-in copy-structure-mixed-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-mixed-t font-size-path-1) "40"))
    (t/is (= (get-in copy-structure-mixed-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 14 (the override is not preserved)
    ;;   * text "bye" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-mixed-t' font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-mixed-t' text-path-0) "bye"))
    (t/is (nil? (get-in copy-structure-mixed-t' font-size-path-1)))))

(t/deftest test-switch-with-different-text-and-prop-structure-text-override
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 ;; The second component has a different text and prop
                 (thv/add-variant-with-text
                  :v01 :c01 :m01 :c02 :m02 :t01 :t02 "hello world" "bye")
                 (update-attr :t02 font-size-path-0 "50")
                 (thc/instantiate-component :c01
                                            :copy-structure-clean
                                            :children-labels [:copy-structure-clean-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-unif
                                            :children-labels [:copy-structure-unif-t])
                 (thc/instantiate-component :c01
                                            :copy-structure-mixed
                                            :children-labels [:copy-structure-mixed-t]))



        ;; Duplicate a text line in copy-structure-clean


        file                   (change-structure file :copy-structure-clean-t)
        copy-structure-clean   (ths/get-shape file :copy-structure-clean)
        copy-structure-clean-t (ths/get-shape file :copy-structure-clean-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; both lines with the same attrs
        file                   (-> (update-attr file :copy-structure-unif-t font-size-path-0 "25")
                                   (change-structure :copy-structure-unif-t))
        copy-structure-unif   (ths/get-shape file :copy-structure-unif)
        copy-structure-unif-t (ths/get-shape file :copy-structure-unif-t)

        ;; Duplicate a text line in copy-structure-clean, updating
        ;; each line with a different attr
        file                   (-> (change-structure file :copy-structure-mixed-t)
                                   (update-attr :copy-structure-mixed-t font-size-path-0 "35")
                                   (update-attr :copy-structure-mixed-t font-size-path-1 "40"))
        copy-structure-mixed   (ths/get-shape file :copy-structure-mixed)
        copy-structure-mixed-t (ths/get-shape file :copy-structure-mixed-t)


        ;; ==== Action: Switch all the copies


        file' (-> file
                  (tho/swap-component copy-structure-clean :c02 {:new-shape-label :copy-structure-clean-2 :keep-touched? true})
                  (tho/swap-component copy-structure-unif :c02 {:new-shape-label :copy-structure-unif-2 :keep-touched? true})
                  (tho/swap-component copy-structure-mixed :c02 {:new-shape-label :copy-structure-mixed-2 :keep-touched? true}))
        page'                   (thf/current-page file')
        copy-structure-clean'   (ths/get-shape file' :copy-structure-clean-2)
        copy-structure-clean-t' (get-in page' [:objects (-> copy-structure-clean' :shapes first)])

        copy-structure-unif'    (ths/get-shape file' :copy-structure-unif-2)
        copy-structure-unif-t'  (get-in page' [:objects (-> copy-structure-unif' :shapes first)])

        copy-structure-mixed'   (ths/get-shape file' :copy-structure-mixed-2)
        copy-structure-mixed-t' (get-in page' [:objects (-> copy-structure-mixed' :shapes first)])]

    (thf/dump-file file' {:keys [:name #_:content]})

    ;;;;;;;;;;; Copy structure clean
    ;; Before the switch, first line:
    ;;   * font size 14
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 14
    ;;   * text "new line 2"
    (t/is (= (get-in copy-structure-clean-t font-size-path-0) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-clean-t font-size-path-1) "14"))
    (t/is (= (get-in copy-structure-clean-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 50 (value of c02, because there was no override)
    ;;   * text "bye" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-clean-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-structure-clean-t' text-path-0) "bye"))
    (t/is (nil? (get-in copy-structure-clean-t' font-size-path-1)))


    ;;;;;;;;;;; Copy structure unif
    ;; Before the switch, first line:
    ;;   * font size 25
    ;;   * text "new line 1"
    ;; Second line:
    ;;   * font size 25
    ;;   * text "new line 2"


    (t/is (= (get-in copy-structure-unif-t font-size-path-0) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-unif-t font-size-path-1) "25"))
    (t/is (= (get-in copy-structure-unif-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 50 (the override is not preserved)
    ;;   * text "bye" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-unif-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-structure-unif-t' text-path-0) "bye"))
    (t/is (nil? (get-in copy-structure-unif-t' font-size-path-1)))


    ;;;;;;;;;;; Copy structure mixed
    ;; Before the switch, first line:
    ;;   * font size 35
    ;;   * text "new line 1"
    ;; Before the switch, second line:
    ;;   * font size 40
    ;;   * text "new line 2"


    (t/is (= (get-in copy-structure-mixed-t font-size-path-0) "35"))
    (t/is (= (get-in copy-structure-mixed-t text-path-0) "new line 1"))
    (t/is (= (get-in copy-structure-mixed-t font-size-path-1) "40"))
    (t/is (= (get-in copy-structure-mixed-t text-path-1) "new line 2"))

    ;; After the switch, first line:
    ;;   * font size 50 (the override is not preserved)
    ;;   * text "bye" (the override is not preserved)
    ;; No second line
    (t/is (= (get-in copy-structure-mixed-t' font-size-path-0) "50"))
    (t/is (= (get-in copy-structure-mixed-t' text-path-0) "bye"))
    (t/is (nil? (get-in copy-structure-mixed-t' font-size-path-1)))))

(t/deftest test-switch-variant-for-other-with-same-nested-component
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (tho/add-simple-component :external01 :external01-root :external01-child)
                      (tho/add-simple-component :external02 :external02-root :external02-child)
                      (thv/add-variant-with-copy
                       :v01 :c01 :m01 :c02 :m02 :cp01 :cp02 :external01)

                      (thc/instantiate-component :c01
                                                 :copy01
                                                 :children-labels [:copy-cp01]))

        page   (thf/current-page file)
        copy01 (ths/get-shape file :copy01)
        copy-cp01 (ths/get-shape file :copy-cp01)
        copy-cp01-rect-id (-> copy-cp01 :shapes first)


        ;; On :copy-cp01, change the width of the rect


        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{copy-cp01-rect-id}
                                            (fn [shape]
                                              (assoc shape :width 25))
                                            (:objects page)
                                            {})
        file (thf/apply-changes file changes)
        copy-cp01-rect (ths/get-shape-by-id file copy-cp01-rect-id)

        ;; ==== Action
        ;; Switch :c01 for :c02
        file'     (tho/swap-component file copy01 :c02 {:new-shape-label :copy02 :keep-touched? true})
        copy02    (ths/get-shape file' :copy02)
        copy-cp02' (ths/get-shape-by-id file' (-> copy02 :shapes first))
        copy-cp02-rect' (ths/get-shape-by-id file' (-> copy-cp02' :shapes first))]

    ;; The width of copy-cp01-rect was 25
    (t/is (= (:width copy-cp01-rect) 25))

    ;; The width of copy-cp02-rect' is 25 (change is preserved)
    (t/is (= (:width copy-cp02-rect') 25))))

(t/deftest test-switch-variant-that-has-swaped-copy
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (tho/add-simple-component :external01 :external01-root :external01-child)
                      (tho/add-simple-component :external02 :external02-root :external02-child)
                      (thv/add-variant-with-copy
                       :v01 :c01 :m01 :c02 :m02 :cp01 :cp02 :external01)

                      (thc/instantiate-component :c01
                                                 :copy01
                                                 :children-labels [:copy-cp01]))

        copy01 (ths/get-shape file :copy01)
        copy-cp01 (ths/get-shape file :copy-cp01)
        external02 (thc/get-component file :external02)

        ;; On :c01, swap the copy of :external01 for a copy of :external02
        file (-> file
                 (tho/swap-component copy-cp01 :external02 {:new-shape-label :copy-cp02 :keep-touched? false}))
        copy-cp02 (ths/get-shape file :copy-cp02)

        ;; ==== Action
        ;; Switch :c01 for :c02
        file'     (tho/swap-component file copy01 :c02 {:new-shape-label :copy02 :keep-touched? true})

        copy02'    (ths/get-shape file' :copy02)
        copy-cp02' (ths/get-shape file' :copy-cp02)]
    (thf/dump-file file')
    ;;copy-cp02 is a copy of external02
    (t/is (= (:component-id copy-cp02) (:id external02)))
    ;;copy-01 had copy-cp02 as child
    (t/is (= (-> copy01 :shapes first) (:id copy-cp02)))

    ;;copy-cp02' is a copy of external02
    (t/is (= (:component-id copy-cp02') (:id external02)))
    ;;copy-02' had copy-cp02' as child
    (t/is (= (-> copy02' :shapes first) (:id copy-cp02')))))

(t/deftest test-switch-variant-that-has-swaped-copy-with-changed-attr
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (tho/add-simple-component :external01 :external01-root :external01-child)
                      (tho/add-simple-component :external02 :external02-root :external02-child)
                      (thv/add-variant-with-copy
                       :v01 :c01 :m01 :c02 :m02 :cp01 :cp02 :external01)

                      (thc/instantiate-component :c01
                                                 :copy01
                                                 :children-labels [:copy-cp01]))

        page   (thf/current-page file)
        copy01 (ths/get-shape file :copy01)
        copy-cp01 (ths/get-shape file :copy-cp01)
        external02 (thc/get-component file :external02)

        ;; On :c01, swap the copy of :external01 for a copy of :external02
        file (-> file
                 (tho/swap-component copy-cp01 :external02 {:new-shape-label :copy-cp02 :keep-touched? false}))
        copy-cp02 (ths/get-shape file :copy-cp02)
        copy-cp02-rect-id (-> copy-cp02 :shapes first)

        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{copy-cp02-rect-id}
                                            (fn [shape]
                                              (assoc shape :width 25))
                                            (:objects page)
                                            {})
        file (thf/apply-changes file changes)
        copy-cp02-rect (ths/get-shape-by-id file copy-cp02-rect-id)

        ;; ==== Action
        ;; Switch :c01 for :c02
        file'     (tho/swap-component file copy01 :c02 {:new-shape-label :copy02 :keep-touched? true})

        copy02'    (ths/get-shape file' :copy02)
        copy-cp02' (ths/get-shape file' :copy-cp02)
        copy-cp02-rect' (ths/get-shape-by-id file' (-> copy-cp02' :shapes first))]
    (thf/dump-file file')
    ;;copy-cp02 is a copy of external02
    (t/is (= (:component-id copy-cp02) (:id external02)))
    ;;copy-01 had copy-cp02 as child
    (t/is (= (-> copy01 :shapes first) (:id copy-cp02)))
    ;; The width of copy-cp02-rect was 25
    (t/is (= (:width copy-cp02-rect) 25))

    ;;copy-cp02' is a copy of external02
    (t/is (= (:component-id copy-cp02') (:id external02)))
    ;;copy-02' had copy-cp02' as child
    (t/is (= (-> copy02' :shapes first) (:id copy-cp02')))
    ;; The width of copy-cp02-rect' is 25 (change is preserved)
    (t/is (= (:width copy-cp02-rect') 25))))

(t/deftest test-switch-variant-without-touched-but-touched-parent
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant-with-child
                       :v01 :c01 :m01 :c02 :m02 :r01 :r02
                       {:child1-params {:width 5}
                        :child2-params  {:width 5}})
                      (tho/add-simple-component :external01 :external01-root :external01-child)

                      (thc/instantiate-component :c01
                                                 :c01-in-root
                                                 :children-labels [:r01-in-c01-in-root]
                                                 :parent-label :external01-root))

        ;; Make a change on r01-in-c01-in-root so it is touched
        page               (thf/current-page file)
        r01-in-c01-in-root (ths/get-shape file :r01-in-c01-in-root)

        changes            (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                       #{(:id r01-in-c01-in-root)}
                                                       (fn [shape]
                                                         (assoc shape :width 25))
                                                       (:objects page)
                                                       {})

        file               (thf/apply-changes file changes)


        ;; Instantiate the component :external01


        file        (thc/instantiate-component file
                                               :external01
                                               :external-copy01
                                               :children-labels [:external-copy01-rect :c01-in-copy])
        page        (thf/current-page file)
        c01-in-copy (ths/get-shape file :c01-in-copy)
        rect01      (get-in page [:objects (-> c01-in-copy :shapes first)])


        ;; ==== Action


        file'        (tho/swap-component file c01-in-copy :c02 {:new-shape-label :c02-in-copy :keep-touched? true})

        page'        (thf/current-page file')
        c02-in-copy' (ths/get-shape file' :c02-in-copy)
        rect02'      (get-in page' [:objects (-> c02-in-copy' :shapes first)])]

    (thf/dump-file file :keys [:width :touched])
    ;; The rect had width 25 before the switch
    (t/is (= (:width rect01) 25))
    ;; The rect still has width 25 after the switch
    (t/is (= (:width rect02') 25))))
