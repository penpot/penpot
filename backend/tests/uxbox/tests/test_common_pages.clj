;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tests.test-common-pages
  (:require
   [clojure.test :as t]
   [clojure.pprint :refer [pprint]]
   [promesa.core :as p]
   [mockery.core :refer [with-mock]]
   [uxbox.common.pages :as cp]
   [uxbox.common.uuid :as uuid]
   [uxbox.tests.helpers :as th]))

(t/deftest process-change-set-option
  (let [data cp/default-page-data]
    (t/testing "Sets option single"
      (let [chg {:type :set-option
                 :option :test
                 :value "test"}
            res (cp/process-changes data [chg])]
        (t/is (= "test" (get-in res [:options :test])))))

    (t/testing "Sets option nested"
      (let [chgs [{:type :set-option
                   :option [:values :test :a]
                   :value "a"}
                  {:type :set-option
                   :option [:values :test :b]
                   :value "b"}]
            res (cp/process-changes data chgs)]
        (t/is (= {:a "a" :b "b"} (get-in res [:options :values :test])))))

    (t/testing "Remove option single"
      (let [chg {:type :set-option
                 :option :test
                 :value nil}
            res (cp/process-changes data [chg])]
        (t/is (empty? (keys (get res :options))))))

    (t/testing "Remove option nested 1"
      (let [chgs [{:type :set-option
                   :option [:values :test :a]
                   :value "a"}
                  {:type :set-option
                   :option [:values :test :b]
                   :value "b"}
                  {:type :set-option
                   :option [:values :test]
                   :value nil}]
            res (cp/process-changes data chgs)]
        (t/is (empty? (keys (get res :options))))))

    (t/testing "Remove option nested 2"
      (let [chgs [{:type :set-option
                   :option [:values :test1 :a]
                   :value "a"}
                  {:type :set-option
                   :option [:values :test2 :b]
                   :value "b"}
                  {:type :set-option
                   :option [:values :test2]
                   :value nil}]
            res (cp/process-changes data chgs)]
        (t/is (= [:test1] (keys (get-in res [:options :values]))))))))

(t/deftest process-change-add-obj
  (let [data cp/default-page-data
        id-a (uuid/next)
        id-b (uuid/next)
        id-c (uuid/next)]
    (t/testing "Adds single object"
      (let [chg  {:type :add-obj
                  :id id-a
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:id id-a
                        :frame-id uuid/zero
                        :parent-id uuid/zero
                        :type :rect
                        :name "rect"}}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= 2 (count (:objects res))))
        (t/is (= (:obj chg) (get-in res [:objects id-a])))
        (t/is (= [id-a] (get-in res [:objects uuid/zero :shapes])))))


    (t/testing "Adds several objects with different indexes"
      (let [data cp/default-page-data

            chg  (fn [id index] {:type :add-obj
                                 :id id
                                 :frame-id uuid/zero
                                 :index index
                                 :obj {:id id
                                       :frame-id uuid/zero
                                       :type :rect
                                       :name (str id)}})
            res (cp/process-changes data [(chg id-a 0)
                                          (chg id-b 0)
                                          (chg id-c 1)])]

        (t/is (= 4 (count (:objects res))))
        (t/is (not (nil? (get-in res [:objects id-a]))))
        (t/is (not (nil? (get-in res [:objects id-b]))))
        (t/is (not (nil? (get-in res [:objects id-c]))))
        (t/is (= [id-b id-c id-a] (get-in res [:objects uuid/zero :shapes])))))))

(t/deftest process-change-mod-obj
  (t/testing "simple mod-obj"
    (let [data cp/default-page-data
          chg  {:type :mod-obj
                :id uuid/zero
                :operations [{:type :set
                              :attr :name
                              :val "foobar"}]}
          res (cp/process-changes data [chg])]
      (t/is (= "foobar" (get-in res [:objects uuid/zero :name])))))

  (t/testing "mod-obj for not existing shape"
    (let [data cp/default-page-data
          chg  {:type :mod-obj
                :id (uuid/next)
                :operations [{:type :set
                              :attr :name
                              :val "foobar"}]}
          res (cp/process-changes data [chg])]
      (t/is (= res cp/default-page-data)))))


(t/deftest process-change-del-obj-1
  (let [id   (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id])
                 (assoc-in [:objects id] {:id id
                                          :frame-id uuid/zero
                                          :type :rect
                                          :name "rect"}))
        chg  {:type :del-obj
              :id id}
        res (cp/process-changes data [chg])]

    (t/is (= 1 (count (:objects res))))
    (t/is (= [] (get-in res [:objects uuid/zero :shapes])))))

(t/deftest process-change-del-obj-2
  (let [id   (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id])
                 (assoc-in [:objects id] {:id id
                                          :frame-id uuid/zero
                                          :type :rect
                                          :name "rect"}))
        chg  {:type :del-obj
              :id uuid/zero}
        res (cp/process-changes data [chg])]
    (t/is (= 0 (count (:objects res))))))

(t/deftest process-change-move-objects
  (let [frame-a-id (uuid/custom 1)
        frame-b-id (uuid/custom 2)
        group-a-id (uuid/custom 3)
        group-b-id (uuid/custom 4)
        rect-a-id  (uuid/custom 5)
        rect-b-id  (uuid/custom 6)
        rect-c-id  (uuid/custom 7)
        rect-d-id  (uuid/custom 8)
        rect-e-id  (uuid/custom 9)

        data
        (-> cp/default-page-data
            (assoc-in [:objects uuid/zero :shapes] [frame-a-id frame-b-id])
            (assoc-in [:objects frame-a-id]
                      {:id frame-a-id
                       :parent-id uuid/zero
                       :frame-id uuid/zero
                       :name "Frame a"
                       :shapes [group-a-id group-b-id rect-e-id]
                       :type :frame})

            (assoc-in [:objects frame-b-id]
                      {:id frame-b-id
                       :parent-id uuid/zero
                       :frame-id uuid/zero
                       :name "Frame b"
                       :shapes []
                       :type :frame})

            ;; Groups
            (assoc-in [:objects group-a-id]
                      {:id group-a-id
                       :name "Group A"
                       :type :group
                       :parent-id frame-a-id
                       :frame-id frame-a-id
                       :shapes [rect-a-id rect-b-id rect-c-id]})
            (assoc-in [:objects group-b-id]
                      {:id group-b-id
                       :name "Group B"
                       :type :group
                       :parent-id frame-a-id
                       :frame-id frame-a-id
                       :shapes [rect-d-id]})

                 ;; Shapes
            (assoc-in [:objects rect-a-id]
                      {:id rect-a-id
                       :name "Rect A"
                       :type :rect
                       :parent-id group-a-id
                       :frame-id frame-a-id})

            (assoc-in [:objects rect-b-id]
                      {:id rect-b-id
                       :name "Rect B"
                       :type :rect
                       :parent-id group-a-id
                       :frame-id frame-a-id})

            (assoc-in [:objects rect-c-id]
                      {:id rect-c-id
                       :name "Rect C"
                       :type :rect
                       :parent-id group-a-id
                       :frame-id frame-a-id})

            (assoc-in [:objects rect-d-id]
                      {:id rect-d-id
                       :name "Rect D"
                       :parent-id group-b-id
                       :type :rect
                       :frame-id frame-a-id})

            (assoc-in [:objects rect-e-id]
                      {:id rect-e-id
                       :name "Rect E"
                       :type :rect
                       :parent-id frame-a-id
                       :frame-id frame-a-id}))]

    (t/testing "Create new group an add objects from the same group"
      (let [new-group-id (uuid/next)
            changes [{:type :add-obj
                      :id new-group-id
                      :frame-id frame-a-id
                      :obj {:id new-group-id
                            :type :group
                            :frame-id frame-a-id
                            :name "Group C"}}
                     {:type :mov-objects
                      :parent-id new-group-id
                      :shapes [rect-b-id rect-c-id]}]
            res (cp/process-changes data changes)]

        ;; (clojure.pprint/pprint data)
        ;; (println "===============")
        ;; (clojure.pprint/pprint res)

        (t/is (= [group-a-id group-b-id rect-e-id new-group-id]
                 (get-in res [:objects frame-a-id :shapes])))
        (t/is (= [rect-b-id rect-c-id]
                 (get-in res [:objects new-group-id :shapes])))
        (t/is (= [rect-a-id]
                 (get-in res [:objects group-a-id :shapes])))))

    (t/testing "Move elements to an existing group at index"
      (let [changes [{:type :mov-objects
                      :parent-id group-b-id
                      :index 0
                      :shapes [rect-a-id rect-c-id]}]
            res (cp/process-changes data changes)]

        (t/is (= [group-a-id group-b-id rect-e-id]
                 (get-in res [:objects frame-a-id :shapes])))
        (t/is (= [rect-b-id]
                 (get-in res [:objects group-a-id :shapes])))
        (t/is (= [rect-a-id rect-c-id rect-d-id]
                 (get-in res [:objects group-b-id :shapes])))))

    (t/testing "Move elements from group and frame to an existing group at index"
      (let [changes [{:type :mov-objects
                      :parent-id group-b-id
                      :index 0
                      :shapes [rect-a-id rect-e-id]}]
            res (cp/process-changes data changes)]

        (t/is (= [group-a-id group-b-id]
                 (get-in res [:objects frame-a-id :shapes])))
        (t/is (= [rect-b-id rect-c-id]
                 (get-in res [:objects group-a-id :shapes])))
        (t/is (= [rect-a-id rect-e-id rect-d-id]
                 (get-in res [:objects group-b-id :shapes])))))

    (t/testing "Move elements from several groups"
      (let [changes [{:type :mov-objects
                      :parent-id group-b-id
                      :index 0
                      :shapes [rect-a-id rect-e-id]}]
            res (cp/process-changes data changes)]

        (t/is (= [group-a-id group-b-id]
                 (get-in res [:objects frame-a-id :shapes])))
        (t/is (= [rect-b-id rect-c-id]
                 (get-in res [:objects group-a-id :shapes])))
        (t/is (= [rect-a-id rect-e-id rect-d-id]
                 (get-in res [:objects group-b-id :shapes])))))

    (t/testing "Move elements and delete the empty group"
      (let [changes [{:type :mov-objects
                      :parent-id group-a-id
                      :shapes [rect-d-id]}]
            res (cp/process-changes data changes)]

        (t/is (= [group-a-id rect-e-id]
                 (get-in res [:objects frame-a-id :shapes])))
        (t/is (nil? (get-in res [:objects group-b-id])))))

    (t/testing "Move elements to a group with different frame"
      (let [changes [{:type :mov-objects
                      :parent-id frame-b-id
                      :shapes [group-a-id]}]
            res (cp/process-changes data changes)]

        (t/is (= [group-b-id rect-e-id] (get-in res [:objects frame-a-id :shapes])))
        (t/is (= [group-a-id] (get-in res [:objects frame-b-id :shapes])))
        (t/is (= frame-b-id (get-in res [:objects group-a-id :frame-id])))
        (t/is (= frame-b-id (get-in res [:objects rect-a-id :frame-id])))
        (t/is (= frame-b-id (get-in res [:objects rect-b-id :frame-id])))
        (t/is (= frame-b-id (get-in res [:objects rect-c-id :frame-id])))))

    (t/testing "Move elements to frame zero"
      (let [changes [{:type :mov-objects
                      :parent-id uuid/zero
                      :shapes [group-a-id]
                      :index 0}]
            res (cp/process-changes data changes)]

        ;; (pprint (get-in data [:objects uuid/zero]))
        ;; (println "==========")
        ;; (pprint (get-in res [:objects uuid/zero]))

        (t/is (= [group-a-id frame-a-id frame-b-id]
                 (get-in res [:objects cp/root :shapes])))))

    (t/testing "Don't allow to move inside self"
      (let [changes [{:type :mov-objects
                      :parent-id group-a-id
                      :shapes [group-a-id]}]
            res (cp/process-changes data changes)]
        (t/is (= data res))))
    ))


(t/deftest process-change-mov-objects-regression
  (let [shape-1-id (uuid/custom 2 1)
        shape-2-id (uuid/custom 2 2)
        shape-3-id (uuid/custom 2 3)
        frame-id   (uuid/custom 1 1)
        changes [{:type :add-obj
                  :id frame-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:type :frame
                        :name "Frame"}}
                 {:type :add-obj
                  :frame-id frame-id
                  :parent-id frame-id
                  :id shape-1-id
                  :obj {:type :shape
                        :name "Shape 1"}}
                 {:type :add-obj
                  :id shape-2-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:type :rect
                        :name "Shape 2"}}

                 {:type :add-obj
                  :id shape-3-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:type :rect
                        :name "Shape 3"}}
                 ]
        data (cp/process-changes cp/default-page-data changes)]

    (t/testing "preserve order on multiple shape mov 1"
      (let [changes [{:type :mov-objects
                      :shapes [shape-2-id shape-3-id]
                      :parent-id uuid/zero
                      :index 0}]
            res (cp/process-changes data changes)]

        ;; (println "==> BEFORE")
        ;; (pprint (get-in data [:objects]))
        ;; (println "==> AFTER")
        ;; (pprint (get-in res [:objects]))

        (t/is (= [frame-id shape-2-id shape-3-id]
                 (get-in data [:objects uuid/zero :shapes])))
        (t/is (= [shape-2-id shape-3-id frame-id]
                 (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "preserve order on multiple shape mov 1"
      (let [changes [{:type :mov-objects
                      :shapes [shape-3-id shape-2-id]
                      :parent-id uuid/zero
                      :index 0}]
            res (cp/process-changes data changes)]

        ;; (println "==> BEFORE")
        ;; (pprint (get-in data [:objects]))
        ;; (println "==> AFTER")
        ;; (pprint (get-in res [:objects]))

        (t/is (= [frame-id shape-2-id shape-3-id]
                 (get-in data [:objects uuid/zero :shapes])))
        (t/is (= [shape-3-id shape-2-id frame-id]
                 (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "move inside->outside-inside"
      (let [changes [{:type :mov-objects
                      :shapes [shape-2-id]
                      :parent-id frame-id}
                     {:type :mov-objects
                      :shapes [shape-2-id]
                      :parent-id uuid/zero}]
            res (cp/process-changes data changes)]

        (t/is (= (get-in res [:objects shape-1-id :frame-id])
                 (get-in data [:objects shape-1-id :frame-id])))
        (t/is (= (get-in res [:objects shape-2-id :frame-id])
                 (get-in data [:objects shape-2-id :frame-id])))))

    ))


(t/deftest process-change-move-objects-2
  (let [shape-1-id (uuid/custom 1 1)
        shape-2-id (uuid/custom 1 2)
        shape-3-id (uuid/custom 1 3)
        shape-4-id (uuid/custom 1 4)
        group-1-id (uuid/custom 1 5)
        changes [{:type :add-obj
                  :id shape-1-id
                  :frame-id cp/root
                  :obj {:id shape-1-id
                        :type :rect
                        :name "Shape a"}}
                 {:type :add-obj
                  :id shape-2-id
                  :frame-id cp/root
                  :obj {:id shape-2-id
                        :type :rect
                        :name "Shape b"}}
                 {:type :add-obj
                  :id shape-3-id
                  :frame-id cp/root
                  :obj {:id shape-3-id
                        :type :rect
                        :name "Shape c"}}
                 {:type :add-obj
                  :id shape-4-id
                  :frame-id cp/root
                  :obj {:id shape-4-id
                        :type :rect
                        :name "Shape d"}}
                 {:type :add-obj
                  :id group-1-id
                  :frame-id cp/root
                  :obj {:id group-1-id
                        :type :group
                        :name "Group"}}
                 {:type :mov-objects
                  :parent-id group-1-id
                  :shapes [shape-1-id shape-2-id]}]
        data (cp/process-changes cp/default-page-data changes)]

    (t/testing "case 1"
      (let [changes [{:type :mov-objects
                      :parent-id cp/root
                      :index 2
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:objects cp/root :shapes])))

        ;; After

        (t/is (= [shape-4-id shape-3-id group-1-id]
                 (get-in res [:objects cp/root :shapes])))

        ;; (pprint (get-in data [:objects cp/root]))
        ;; (pprint (get-in res [:objects cp/root]))
        ))

    (t/testing "case 2"
      (let [changes [{:type :mov-objects
                      :parent-id group-1-id
                      :index 2
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:objects group-1-id :shapes])))

        ;; After:

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id shape-3-id]
                 (get-in res [:objects group-1-id :shapes])))

        ;; (pprint (get-in data [:objects group-1-id]))
        ;; (pprint (get-in res [:objects group-1-id]))
        ))

    (t/testing "case 3"
      (let [changes [{:type :mov-objects
                      :parent-id group-1-id
                      :index 1
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-3-id shape-2-id]
                 (get-in res [:objects group-1-id :shapes])))

        ;; (pprint (get-in data [:objects group-1-id]))
        ;; (pprint (get-in res [:objects group-1-id]))
        ))

    (t/testing "case 4"
      (let [changes [{:type :mov-objects
                      :parent-id group-1-id
                      :index 0
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:objects cp/root :shapes])))

        (t/is (= [shape-3-id shape-1-id shape-2-id]
                 (get-in res [:objects group-1-id :shapes])))

        ;; (pprint (get-in data [:objects group-1-id]))
        ;; (pprint (get-in res [:objects group-1-id]))
        ))

    (t/testing "case 5"
      (let [changes [{:type :mov-objects
                      :parent-id cp/root
                      :index 0
                      :shapes [shape-2-id]}]
            res (cp/process-changes data changes)]

        ;; (pprint (get-in data [:objects cp/root]))
        ;; (pprint (get-in res [:objects cp/root]))

        ;; (pprint (get-in data [:objects group-1-id]))
        ;; (pprint (get-in res [:objects group-1-id]))

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-2-id shape-3-id shape-4-id group-1-id]
                 (get-in res [:objects cp/root :shapes])))

        (t/is (= [shape-1-id]
                 (get-in res [:objects group-1-id :shapes])))

        ))

    (t/testing "case 6"
      (let [changes [{:type :mov-objects
                      :parent-id cp/root
                      :index 0
                      :shapes [shape-2-id shape-1-id]}]
            res (cp/process-changes data changes)]

        ;; (pprint (get-in data [:objects cp/root]))
        ;; (pprint (get-in res [:objects cp/root]))

        ;; (pprint (get-in data [:objects group-1-id]))
        ;; (pprint (get-in res [:objects group-1-id]))

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-2-id shape-1-id shape-3-id shape-4-id]
                 (get-in res [:objects cp/root :shapes])))

        (t/is (= nil
                 (get-in res [:objects group-1-id])))

        ))

    ))

(t/deftest idenpotency-regression-1
  (let [data {:version 5
              :objects
              {#uuid "00000000-0000-0000-0000-000000000000"
               {:id #uuid "00000000-0000-0000-0000-000000000000",
                :type :frame,
                :name "root",
                :shapes
                [#uuid "f5d51910-ab23-11ea-ac38-e1abed64181a"
                 #uuid "f6a36590-ab23-11ea-ac38-e1abed64181a"]},
               #uuid "f5d51910-ab23-11ea-ac38-e1abed64181a"
               {:name "Rect-1",
                :type :rect,
                :id #uuid "f5d51910-ab23-11ea-ac38-e1abed64181a",
                :parent-id #uuid "00000000-0000-0000-0000-000000000000",
                :frame-id #uuid "00000000-0000-0000-0000-000000000000"}
               #uuid "f6a36590-ab23-11ea-ac38-e1abed64181a"
               {:name "Rect-2",
                :type :rect,
                :id #uuid "f6a36590-ab23-11ea-ac38-e1abed64181a",
                :parent-id #uuid "00000000-0000-0000-0000-000000000000",
                :frame-id #uuid "00000000-0000-0000-0000-000000000000"}}}
        chgs [{:type :add-obj,
               :id #uuid "3375ec40-ab24-11ea-b512-b945e8edccf5",
               :frame-id #uuid "00000000-0000-0000-0000-000000000000",
               :index 0
               :obj {:name "Group-1",
                     :type :group,
                     :id #uuid "3375ec40-ab24-11ea-b512-b945e8edccf5",
                     :frame-id #uuid "00000000-0000-0000-0000-000000000000"}}
              {:type :mov-objects,
               :parent-id #uuid "3375ec40-ab24-11ea-b512-b945e8edccf5",
               :shapes
               [#uuid "f5d51910-ab23-11ea-ac38-e1abed64181a"
                #uuid "f6a36590-ab23-11ea-ac38-e1abed64181a"]}]

        res1 (cp/process-changes data chgs)
        res2 (cp/process-changes res1 chgs)]

    ;; (clojure.pprint/pprint data)
    ;; (println "==============")
    ;; (clojure.pprint/pprint res2)

    (t/is (= [#uuid "f5d51910-ab23-11ea-ac38-e1abed64181a"
              #uuid "f6a36590-ab23-11ea-ac38-e1abed64181a"]
             (get-in data [:objects uuid/zero :shapes])))
    (t/is (= [#uuid "3375ec40-ab24-11ea-b512-b945e8edccf5"]
             (get-in res2 [:objects uuid/zero :shapes])))
    (t/is (= [#uuid "3375ec40-ab24-11ea-b512-b945e8edccf5"]
             (get-in res1 [:objects uuid/zero :shapes])))
    ))
