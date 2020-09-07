;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.test-common-pages
  (:require
   [clojure.test :as t]
   [clojure.pprint :refer [pprint]]
   [promesa.core :as p]
   [mockery.core :refer [with-mock]]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.tests.helpers :as th]))

(t/deftest process-change-set-option
  (let [page-id (uuid/custom 1 1)
        data    (cp/make-file-data page-id)]
    (t/testing "Sets option single"
      (let [chg {:type :set-option
                 :page-id page-id
                 :option :test
                 :value "test"}
            res (cp/process-changes data [chg])]
        (t/is (= "test" (get-in res [:pages-index page-id :options :test])))))

    (t/testing "Sets option nested"
      (let [chgs [{:type :set-option
                   :page-id page-id
                   :option [:values :test :a]
                   :value "a"}
                  {:type :set-option
                   :page-id page-id
                   :option [:values :test :b]
                   :value "b"}]
            res (cp/process-changes data chgs)]
        (t/is (= {:a "a" :b "b"}
                 (get-in res [:pages-index page-id :options :values :test])))))

    (t/testing "Remove option single"
      (let [chg {:type :set-option
                 :page-id page-id
                 :option :test
                 :value nil}
            res (cp/process-changes data [chg])]
        (t/is (empty? (keys (get-in res [:pages-index page-id :options]))))))

    (t/testing "Remove option nested 1"
      (let [chgs [{:type :set-option
                   :page-id page-id
                   :option [:values :test :a]
                   :value "a"}
                  {:type :set-option
                   :page-id page-id
                   :option [:values :test :b]
                   :value "b"}
                  {:type :set-option
                   :page-id page-id
                   :option [:values :test]
                   :value nil}]
            res (cp/process-changes data chgs)]
        (t/is (empty? (keys (get-in res [:pages-index page-id :options]))))))

    (t/testing "Remove option nested 2"
      (let [chgs [{:type :set-option
                   :option [:values :test1 :a]
                   :page-id page-id
                   :value "a"}
                  {:type :set-option
                   :option [:values :test2 :b]
                   :page-id page-id
                   :value "b"}
                  {:type :set-option
                   :page-id page-id
                   :option [:values :test2]
                   :value nil}]
            res (cp/process-changes data chgs)]
        (t/is (= [:test1] (keys (get-in res [:pages-index page-id :options :values]))))))
    ))

(t/deftest process-change-add-obj
  (let [page-id (uuid/custom 1 1)
        data    (cp/make-file-data page-id)
        id-a    (uuid/custom 2 1)
        id-b    (uuid/custom 2 2)
        id-c    (uuid/custom 2 3)]

    (t/testing "Adds single object"
      (let [chg  {:type :add-obj
                  :page-id page-id
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
        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= 2 (count objects)))
          (t/is (= (:obj chg) (get objects id-a)))
          (t/is (= [id-a] (get-in objects [uuid/zero :shapes]))))))


    (t/testing "Adds several objects with different indexes"
      (let [chg  (fn [id index]
                   {:type :add-obj
                    :page-id page-id
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

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)
        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= 4 (count objects)))
          (t/is (not (nil? (get objects id-a))))
          (t/is (not (nil? (get objects id-b))))
          (t/is (not (nil? (get objects id-c))))
          (t/is (= [id-b id-c id-a] (get-in objects [uuid/zero :shapes]))))))
    ))

(t/deftest process-change-mod-obj
  (let [page-id (uuid/custom 1 1)
        data    (cp/make-file-data page-id)]
    (t/testing "simple mod-obj"
      (let [chg  {:type :mod-obj
                  :page-id page-id
                  :id uuid/zero
                  :operations [{:type :set
                                :attr :name
                                :val "foobar"}]}
            res (cp/process-changes data [chg])]
        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= "foobar" (get-in objects [uuid/zero :name]))))))

    (t/testing "mod-obj for not existing shape"
      (let [chg  {:type :mod-obj
                  :page-id page-id
                  :id (uuid/next)
                  :operations [{:type :set
                                :attr :name
                                :val "foobar"}]}
            res (cp/process-changes data [chg])]
        (t/is (= res data))))))


(t/deftest process-change-del-obj
  (let [page-id (uuid/custom 1 1)
        id      (uuid/custom 2 1)
        data    (cp/make-file-data page-id)
        data    (-> data
                    (assoc-in [:pages-index page-id :objects uuid/zero :shapes] [id])
                    (assoc-in [:pages-index page-id :objects id]
                              {:id id
                               :frame-id uuid/zero
                               :type :rect
                               :name "rect"}))]
    (t/testing "delete"
      (let [chg  {:type :del-obj
                  :page-id page-id
                  :id id}
            res  (cp/process-changes data [chg])]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= 1 (count objects)))
          (t/is (= [] (get-in objects [uuid/zero :shapes]))))))

    (t/testing "delete idempotency"
      (let [chg  {:type :del-obj
                  :page-id page-id
                  :id id}
            res1 (cp/process-changes data [chg])
            res2 (cp/process-changes res1 [chg])]

        (t/is (= res1 res2))
        (let [objects (get-in res1 [:pages-index page-id :objects])]
          (t/is (= 1 (count objects)))
          (t/is (= [] (get-in objects [uuid/zero :shapes]))))))))


(t/deftest process-change-move-objects
  (let [frame-a-id (uuid/custom 0 1)
        frame-b-id (uuid/custom 0 2)
        group-a-id (uuid/custom 0 3)
        group-b-id (uuid/custom 0 4)
        rect-a-id  (uuid/custom 0 5)
        rect-b-id  (uuid/custom 0 6)
        rect-c-id  (uuid/custom 0 7)
        rect-d-id  (uuid/custom 0 8)
        rect-e-id  (uuid/custom 0 9)

        page-id (uuid/custom 1 1)
        data    (cp/make-file-data page-id)

        data    (update-in data [:pages-index page-id :objects]
                           #(-> %
                                (assoc-in [uuid/zero :shapes] [frame-a-id frame-b-id])
                                (assoc-in [frame-a-id]
                                          {:id frame-a-id
                                           :parent-id uuid/zero
                                           :frame-id uuid/zero
                                           :name "Frame a"
                                           :shapes [group-a-id group-b-id rect-e-id]
                                           :type :frame})

                                (assoc-in [frame-b-id]
                                          {:id frame-b-id
                                           :parent-id uuid/zero
                                           :frame-id uuid/zero
                                           :name "Frame b"
                                           :shapes []
                                           :type :frame})

                                ;; Groups
                                (assoc-in [group-a-id]
                                          {:id group-a-id
                                           :name "Group A"
                                           :type :group
                                           :parent-id frame-a-id
                                           :frame-id frame-a-id
                                           :shapes [rect-a-id rect-b-id rect-c-id]})
                                (assoc-in [group-b-id]
                                          {:id group-b-id
                                           :name "Group B"
                                           :type :group
                                           :parent-id frame-a-id
                                           :frame-id frame-a-id
                                           :shapes [rect-d-id]})

                                ;; Shapes
                                (assoc-in [rect-a-id]
                                          {:id rect-a-id
                                           :name "Rect A"
                                           :type :rect
                                           :parent-id group-a-id
                                           :frame-id frame-a-id})

                                (assoc-in [rect-b-id]
                                          {:id rect-b-id
                                           :name "Rect B"
                                           :type :rect
                                           :parent-id group-a-id
                                           :frame-id frame-a-id})

                                (assoc-in [rect-c-id]
                                          {:id rect-c-id
                                           :name "Rect C"
                                           :type :rect
                                           :parent-id group-a-id
                                           :frame-id frame-a-id})

                                (assoc-in [rect-d-id]
                                          {:id rect-d-id
                                           :name "Rect D"
                                           :parent-id group-b-id
                                           :type :rect
                                           :frame-id frame-a-id})

                                (assoc-in [rect-e-id]
                                          {:id rect-e-id
                                           :name "Rect E"
                                           :type :rect
                                           :parent-id frame-a-id
                                           :frame-id frame-a-id})))]

    (t/testing "Create new group an add objects from the same group"
      (let [new-group-id (uuid/next)
            changes [{:type :add-obj
                      :page-id page-id
                      :id new-group-id
                      :frame-id frame-a-id
                      :obj {:id new-group-id
                            :type :group
                            :frame-id frame-a-id
                            :name "Group C"}}
                     {:type :mov-objects
                      :page-id page-id
                      :parent-id new-group-id
                      :shapes [rect-b-id rect-c-id]}]
            res (cp/process-changes data changes)]

        ;; (clojure.pprint/pprint data)
        ;; (println "===============")
        ;; (clojure.pprint/pprint res)

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id group-b-id rect-e-id new-group-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (= [rect-b-id rect-c-id]
                   (get-in objects [new-group-id :shapes])))
          (t/is (= [rect-a-id]
                   (get-in objects [group-a-id :shapes]))))))

    (t/testing "Move elements to an existing group at index"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-b-id
                      :index 0
                      :shapes [rect-a-id rect-c-id]}]
            res (cp/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id group-b-id rect-e-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (= [rect-b-id]
                   (get-in objects [group-a-id :shapes])))
          (t/is (= [rect-a-id rect-c-id rect-d-id]
                   (get-in objects [group-b-id :shapes]))))))

    (t/testing "Move elements from group and frame to an existing group at index"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-b-id
                      :index 0
                      :shapes [rect-a-id rect-e-id]}]
            res (cp/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id group-b-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (= [rect-b-id rect-c-id]
                   (get-in objects [group-a-id :shapes])))
          (t/is (= [rect-a-id rect-e-id rect-d-id]
                   (get-in objects [group-b-id :shapes]))))))

    (t/testing "Move elements from several groups"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-b-id
                      :index 0
                      :shapes [rect-a-id rect-e-id]}]
            res (cp/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id group-b-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (= [rect-b-id rect-c-id]
                   (get-in objects [group-a-id :shapes])))
          (t/is (= [rect-a-id rect-e-id rect-d-id]
                   (get-in objects [group-b-id :shapes]))))))

    (t/testing "Move elements and delete the empty group"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-a-id
                      :shapes [rect-d-id]}]
            res (cp/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id rect-e-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (nil? (get-in objects [group-b-id]))))))

    (t/testing "Move elements to a group with different frame"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id frame-b-id
                      :shapes [group-a-id]}]
            res (cp/process-changes data changes)]

        ;; (pprint (get-in data [:pages-index page-id :objects]))
        ;; (println "==========")
        ;; (pprint (get-in res [:pages-index page-id :objects]))

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-b-id rect-e-id] (get-in objects [frame-a-id :shapes])))
          (t/is (= [group-a-id] (get-in objects [frame-b-id :shapes])))
          (t/is (= frame-b-id (get-in objects [group-a-id :frame-id])))
          (t/is (= frame-b-id (get-in objects [rect-a-id :frame-id])))
          (t/is (= frame-b-id (get-in objects [rect-b-id :frame-id])))
          (t/is (= frame-b-id (get-in objects [rect-c-id :frame-id]))))))

    (t/testing "Move elements to frame zero"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id uuid/zero
                      :shapes [group-a-id]
                      :index 0}]
            res (cp/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          ;; (pprint (get-in data [:objects uuid/zero]))
          ;; (println "==========")
          ;; (pprint (get-in objects [uuid/zero]))

          (t/is (= [group-a-id frame-a-id frame-b-id]
                   (get-in objects [cp/root :shapes]))))))

    (t/testing "Don't allow to move inside self"
      (let [changes [{:type :mov-objects
                      :page-id page-id
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
        page-id    (uuid/custom 0 1)

        changes [{:type :add-obj
                  :id frame-id
                  :page-id page-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:type :frame
                        :name "Frame"}}
                 {:type :add-obj
                  :page-id page-id
                  :frame-id frame-id
                  :parent-id frame-id
                  :id shape-1-id
                  :obj {:type :shape
                        :name "Shape 1"}}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-2-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:type :rect
                        :name "Shape 2"}}

                 {:type :add-obj
                  :page-id page-id
                  :id shape-3-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj {:type :rect
                        :name "Shape 3"}}
                 ]
        data (cp/make-file-data page-id)
        data (cp/process-changes data changes)]

    (t/testing "preserve order on multiple shape mov 1"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :shapes [shape-2-id shape-3-id]
                      :parent-id uuid/zero
                      :index 0}]
            res (cp/process-changes data changes)]

        ;; (println "==> BEFORE")
        ;; (pprint (get-in data [:objects]))
        ;; (println "==> AFTER")
        ;; (pprint (get-in res [:objects]))

        (t/is (= [frame-id shape-2-id shape-3-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))
        (t/is (= [shape-2-id shape-3-id frame-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))))

    (t/testing "preserve order on multiple shape mov 1"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :shapes [shape-3-id shape-2-id]
                      :parent-id uuid/zero
                      :index 0}]
            res (cp/process-changes data changes)]

        ;; (println "==> BEFORE")
        ;; (pprint (get-in data [:objects]))
        ;; (println "==> AFTER")
        ;; (pprint (get-in res [:objects]))

        (t/is (= [frame-id shape-2-id shape-3-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))
        (t/is (= [shape-3-id shape-2-id frame-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))))

    (t/testing "move inside->outside-inside"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :shapes [shape-2-id]
                      :parent-id frame-id}
                     {:type :mov-objects
                      :page-id page-id
                      :shapes [shape-2-id]
                      :parent-id uuid/zero}]
            res (cp/process-changes data changes)]

        (t/is (= (get-in res [:pages-index page-id :objects shape-1-id :frame-id])
                 (get-in data [:pages-index page-id :objects shape-1-id :frame-id])))
        (t/is (= (get-in res [:pages-index page-id :objects shape-2-id :frame-id])
                 (get-in data [:pages-index page-id :objects shape-2-id :frame-id])))))

    ))


(t/deftest process-change-move-objects-2
  (let [shape-1-id (uuid/custom 1 1)
        shape-2-id (uuid/custom 1 2)
        shape-3-id (uuid/custom 1 3)
        shape-4-id (uuid/custom 1 4)
        group-1-id (uuid/custom 1 5)
        page-id    (uuid/custom 0 1)

        changes [{:type :add-obj
                  :page-id page-id
                  :id shape-1-id
                  :frame-id cp/root
                  :obj {:id shape-1-id
                        :type :rect
                        :name "Shape a"}}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-2-id
                  :frame-id cp/root
                  :obj {:id shape-2-id
                        :type :rect
                        :name "Shape b"}}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-3-id
                  :frame-id cp/root
                  :obj {:id shape-3-id
                        :type :rect
                        :name "Shape c"}}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-4-id
                  :frame-id cp/root
                  :obj {:id shape-4-id
                        :type :rect
                        :name "Shape d"}}
                 {:type :add-obj
                  :page-id page-id
                  :id group-1-id
                  :frame-id cp/root
                  :obj {:id group-1-id
                        :type :group
                        :name "Group"}}
                 {:type :mov-objects
                  :page-id page-id
                  :parent-id group-1-id
                  :shapes [shape-1-id shape-2-id]}]

        data (cp/make-file-data page-id)
        data (cp/process-changes data changes)]

    (t/testing "case 1"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id cp/root
                      :index 2
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects cp/root :shapes])))

        ;; After

        (t/is (= [shape-4-id shape-3-id group-1-id]
                 (get-in res [:pages-index page-id :objects cp/root :shapes])))

        ;; (pprint (get-in data [:pages-index page-id :objects cp/root]))
        ;; (pprint (get-in res [:pages-index page-id :objects cp/root]))
        ))

    (t/testing "case 2"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-1-id
                      :index 2
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After:

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id shape-3-id]
                 (get-in res [:pages-index page-id :objects group-1-id :shapes])))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))
        ))

    (t/testing "case 3"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-1-id
                      :index 1
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-3-id shape-2-id]
                 (get-in res [:pages-index page-id :objects group-1-id :shapes])))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))
        ))

    (t/testing "case 4"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-1-id
                      :index 0
                      :shapes [shape-3-id]}]
            res (cp/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-3-id shape-1-id shape-2-id]
                 (get-in res [:pages-index page-id :objects group-1-id :shapes])))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))
        ))

    (t/testing "case 5"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id cp/root
                      :index 0
                      :shapes [shape-2-id]}]
            res (cp/process-changes data changes)]

        ;; (pprint (get-in data [:pages-index page-id :objects cp/root]))
        ;; (pprint (get-in res [:pages-index page-id :objects cp/root]))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-2-id shape-3-id shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id]
                 (get-in res [:pages-index page-id :objects group-1-id :shapes])))

        ))

    (t/testing "case 6"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id cp/root
                      :index 0
                      :shapes [shape-2-id shape-1-id]}]
            res (cp/process-changes data changes)]

        ;; (pprint (get-in data [:pages-index page-id :objects cp/root]))
        ;; (pprint (get-in res [:pages-index page-id :objects cp/root]))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-2-id shape-1-id shape-3-id shape-4-id]
                 (get-in res [:pages-index page-id :objects cp/root :shapes])))

        (t/is (= nil
                 (get-in res [:pages-index page-id :objects group-1-id])))

        ))

    ))
