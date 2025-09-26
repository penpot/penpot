;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files-changes-test
  (:require
   [app.common.features :as ffeat]
   [app.common.files.changes :as ch]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [common-tests.types.shape-decode-encode-test :refer [json-roundtrip]]))

(defn- make-file-data
  [file-id page-id]
  (binding [ffeat/*current* #{"components/v2"}]
    (ctf/make-file-data file-id page-id)))

(t/deftest add-obj
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)
        id-a    (uuid/custom 2 1)
        id-b    (uuid/custom 2 2)
        id-c    (uuid/custom 2 3)]

    (t/testing "Adds single object"
      (let [chg  {:type :add-obj
                  :page-id page-id
                  :id id-a
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:frame-id uuid/zero
                         :parent-id uuid/zero
                         :id id-a
                         :type :rect
                         :name "rect"})}
            res (ch/process-changes data [chg])]

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
                    :obj (cts/setup-shape
                          {:id id
                           :frame-id uuid/zero
                           :type :rect
                           :name (str id)})})
            res (ch/process-changes data [(chg id-a 0)
                                          (chg id-b 0)
                                          (chg id-c 1)])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)
        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= 4 (count objects)))
          (t/is (not (nil? (get objects id-a))))
          (t/is (not (nil? (get objects id-b))))
          (t/is (not (nil? (get objects id-c))))
          (t/is (= [id-b id-c id-a] (get-in objects [uuid/zero :shapes]))))))))

(t/deftest mod-obj
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (t/testing "simple mod-obj"
      (let [chg  {:type :mod-obj
                  :page-id page-id
                  :id uuid/zero
                  :operations [{:type :set
                                :attr :name
                                :val "foobar"}]}
            res (ch/process-changes data [chg])]
        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= "foobar" (get-in objects [uuid/zero :name]))))))

    (t/testing "mod-obj for not existing shape"
      (let [chg  {:type :mod-obj
                  :page-id page-id
                  :id (uuid/next)
                  :operations [{:type :set
                                :attr :name
                                :val "foobar"}]}
            res (ch/process-changes data [chg])]
        (t/is (= res data))))))


(t/deftest del-obj
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        id      (uuid/custom 2 1)
        data    (make-file-data file-id page-id)
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
            res  (ch/process-changes data [chg])]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= 1 (count objects)))
          (t/is (= [] (get-in objects [uuid/zero :shapes]))))))

    (t/testing "delete idempotency"
      (let [chg  {:type :del-obj
                  :page-id page-id
                  :id id}
            res1 (ch/process-changes data [chg])
            res2 (ch/process-changes res1 [chg])]

        (t/is (= res1 res2))
        (let [objects (get-in res1 [:pages-index page-id :objects])]
          (t/is (= 1 (count objects)))
          (t/is (= [] (get-in objects [uuid/zero :shapes]))))))))


(t/deftest move-objects-1
  (let [frame-a-id (uuid/custom 0 1)
        frame-b-id (uuid/custom 0 2)
        group-a-id (uuid/custom 0 3)
        group-b-id (uuid/custom 0 4)
        rect-a-id  (uuid/custom 0 5)
        rect-b-id  (uuid/custom 0 6)
        rect-c-id  (uuid/custom 0 7)
        rect-d-id  (uuid/custom 0 8)
        rect-e-id  (uuid/custom 0 9)

        file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)

        data    (update-in data [:pages-index page-id :objects]
                           #(-> %
                                (assoc-in [uuid/zero :shapes] [frame-a-id frame-b-id])
                                (assoc-in [frame-a-id]
                                          (cts/setup-shape
                                           {:id frame-a-id
                                            :parent-id uuid/zero
                                            :frame-id uuid/zero
                                            :name "Frame a"
                                            :shapes [group-a-id group-b-id rect-e-id]
                                            :type :frame}))

                                (assoc-in [frame-b-id]
                                          (cts/setup-shape
                                           {:id frame-b-id
                                            :parent-id uuid/zero
                                            :frame-id uuid/zero
                                            :name "Frame b"
                                            :shapes []
                                            :type :frame}))

                                ;; Groups
                                (assoc-in [group-a-id]
                                          (cts/setup-shape
                                           {:id group-a-id
                                            :name "Group A"
                                            :type :group
                                            :parent-id frame-a-id
                                            :frame-id frame-a-id
                                            :shapes [rect-a-id rect-b-id rect-c-id]}))
                                (assoc-in [group-b-id]
                                          (cts/setup-shape
                                           {:id group-b-id
                                            :name "Group B"
                                            :type :group
                                            :parent-id frame-a-id
                                            :frame-id frame-a-id
                                            :shapes [rect-d-id]}))

                                ;; Shapes
                                (assoc-in [rect-a-id]
                                          (cts/setup-shape
                                           {:id rect-a-id
                                            :name "Rect A"
                                            :type :rect
                                            :parent-id group-a-id
                                            :frame-id frame-a-id}))

                                (assoc-in [rect-b-id]
                                          (cts/setup-shape
                                           {:id rect-b-id
                                            :name "Rect B"
                                            :type :rect
                                            :parent-id group-a-id
                                            :frame-id frame-a-id}))

                                (assoc-in [rect-c-id]
                                          (cts/setup-shape
                                           {:id rect-c-id
                                            :name "Rect C"
                                            :type :rect
                                            :parent-id group-a-id
                                            :frame-id frame-a-id}))

                                (assoc-in [rect-d-id]
                                          (cts/setup-shape
                                           {:id rect-d-id
                                            :name "Rect D"
                                            :parent-id group-b-id
                                            :type :rect
                                            :frame-id frame-a-id}))

                                (assoc-in [rect-e-id]
                                          (cts/setup-shape
                                           {:id rect-e-id
                                            :name "Rect E"
                                            :type :rect
                                            :parent-id frame-a-id
                                            :frame-id frame-a-id}))))]

    (t/testing "Create new group an add objects from the same group"
      (let [new-group-id (uuid/next)
            changes [{:type :add-obj
                      :page-id page-id
                      :id new-group-id
                      :frame-id frame-a-id
                      :obj (cts/setup-shape
                            {:id new-group-id
                             :type :group
                             :frame-id frame-a-id
                             :name "Group C"})}
                     {:type :mov-objects
                      :page-id page-id
                      :parent-id new-group-id
                      :shapes [rect-b-id rect-c-id]}]
            res (ch/process-changes data changes)]

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
            res (ch/process-changes data changes)]

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
            res (ch/process-changes data changes)]

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
            res (ch/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id group-b-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (= [rect-b-id rect-c-id]
                   (get-in objects [group-a-id :shapes])))
          (t/is (= [rect-a-id rect-e-id rect-d-id]
                   (get-in objects [group-b-id :shapes]))))))

    (t/testing "Move all elements from a group"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-a-id
                      :shapes [rect-d-id]}]
            res (ch/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          (t/is (= [group-a-id group-b-id rect-e-id]
                   (get-in objects [frame-a-id :shapes])))
          (t/is (empty? (get-in objects [group-b-id :shapes]))))))

    (t/testing "Move elements to a group with different frame"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id frame-b-id
                      :shapes [group-a-id]}]
            res (ch/process-changes data changes)]

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
            res (ch/process-changes data changes)]

        (let [objects (get-in res [:pages-index page-id :objects])]
          ;; (pprint (get-in data [:objects uuid/zero]))
          ;; (println "==========")
          ;; (pprint (get-in objects [uuid/zero]))

          (t/is (= [group-a-id frame-a-id frame-b-id]
                   (get-in objects [uuid/zero :shapes]))))))

    (t/testing "Don't allow to move inside self"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-a-id
                      :shapes [group-a-id]}]
            res (ch/process-changes data changes)]
        (t/is (= data res))))))


(t/deftest mov-objects-regression-1
  (let [shape-1-id (uuid/custom 2 1)
        shape-2-id (uuid/custom 2 2)
        shape-3-id (uuid/custom 2 3)
        frame-id   (uuid/custom 1 1)
        file-id    (uuid/custom 4 4)
        page-id    (uuid/custom 0 1)

        changes [{:type :add-obj
                  :id frame-id
                  :page-id page-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:type :frame
                         :name "Frame"})}
                 {:type :add-obj
                  :page-id page-id
                  :frame-id frame-id
                  :parent-id frame-id
                  :id shape-1-id
                  :obj (cts/setup-shape
                        {:type :rect
                         :name "Shape 1"})}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-2-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:type :rect
                         :name "Shape 2"})}

                 {:type :add-obj
                  :page-id page-id
                  :id shape-3-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:type :rect
                         :name "Shape 3"})}]
        data (make-file-data file-id page-id)
        data (ch/process-changes data changes)]

    (t/testing "preserve order on multiple shape mov 1"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :shapes [shape-2-id shape-3-id]
                      :parent-id uuid/zero
                      :index 0}]
            res (ch/process-changes data changes)]

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
            res (ch/process-changes data changes)]

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
            res (ch/process-changes data changes)]

        (t/is (= (get-in res [:pages-index page-id :objects shape-1-id :frame-id])
                 (get-in data [:pages-index page-id :objects shape-1-id :frame-id])))
        (t/is (= (get-in res [:pages-index page-id :objects shape-2-id :frame-id])
                 (get-in data [:pages-index page-id :objects shape-2-id :frame-id])))))))


(t/deftest move-objects-2
  (let [shape-1-id (uuid/custom 1 1)
        shape-2-id (uuid/custom 1 2)
        shape-3-id (uuid/custom 1 3)
        shape-4-id (uuid/custom 1 4)
        group-1-id (uuid/custom 1 5)
        file-id    (uuid/custom 1 6)
        page-id    (uuid/custom 0 1)

        changes [{:type :add-obj
                  :page-id page-id
                  :id shape-1-id
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:id shape-1-id
                         :type :rect
                         :name "Shape a"})}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-2-id
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:id shape-2-id
                         :type :rect
                         :name "Shape b"})}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-3-id
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:id shape-3-id
                         :type :rect
                         :name "Shape c"})}
                 {:type :add-obj
                  :page-id page-id
                  :id shape-4-id
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:id shape-4-id
                         :type :rect
                         :name "Shape d"})}
                 {:type :add-obj
                  :page-id page-id
                  :id group-1-id
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:id group-1-id
                         :type :group
                         :name "Group"})}
                 {:type :mov-objects
                  :page-id page-id
                  :parent-id group-1-id
                  :shapes [shape-1-id shape-2-id]}]

        data (make-file-data file-id page-id)
        data (ch/process-changes data changes)]

    (t/testing "case 1"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id uuid/zero
                      :index 2
                      :shapes [shape-3-id]}]
            res (ch/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))

        ;; After

        (t/is (= [shape-4-id shape-3-id group-1-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))

        ;; (pprint (get-in data [:pages-index page-id :objects uuid/zero]))
        ;; (pprint (get-in res [:pages-index page-id :objects uuid/zero]))
        ))

    (t/testing "case 2"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id group-1-id
                      :index 2
                      :shapes [shape-3-id]}]
            res (ch/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After:

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))

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
            res (ch/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))

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
            res (ch/process-changes data changes)]

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-3-id shape-1-id shape-2-id]
                 (get-in res [:pages-index page-id :objects group-1-id :shapes])))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))
        ))

    (t/testing "case 5"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id uuid/zero
                      :index 0
                      :shapes [shape-2-id]}]
            res (ch/process-changes data changes)]

        ;; (pprint (get-in data [:pages-index page-id :objects uuid/zero]))
        ;; (pprint (get-in res [:pages-index page-id :objects uuid/zero]))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-2-id shape-3-id shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-1-id]
                 (get-in res [:pages-index page-id :objects group-1-id :shapes])))))

    (t/testing "case 6"
      (let [changes [{:type :mov-objects
                      :page-id page-id
                      :parent-id uuid/zero
                      :index 0
                      :shapes [shape-2-id shape-1-id]}]
            res (ch/process-changes data changes)]

        ;; (pprint (get-in data [:pages-index page-id :objects uuid/zero]))
        ;; (pprint (get-in res [:pages-index page-id :objects uuid/zero]))

        ;; (pprint (get-in data [:pages-index page-id :objects group-1-id]))
        ;; (pprint (get-in res [:pages-index page-id :objects group-1-id]))

        ;; Before

        (t/is (= [shape-3-id shape-4-id group-1-id]
                 (get-in data [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (= [shape-1-id shape-2-id]
                 (get-in data [:pages-index page-id :objects group-1-id :shapes])))

        ;; After

        (t/is (= [shape-2-id shape-1-id shape-3-id shape-4-id group-1-id]
                 (get-in res [:pages-index page-id :objects uuid/zero :shapes])))

        (t/is (not= nil
                    (get-in res [:pages-index page-id :objects group-1-id])))))))

(t/deftest set-guide-json-encode-decode
  (let [schema ch/schema:set-guide-change
        encode (sm/encoder schema (sm/json-transformer))
        decode (sm/decoder schema (sm/json-transformer))]
    (smt/check!
     (smt/for [data (sg/generator schema)]
       (let [data-1 (encode data)
             data-2 (json-roundtrip data-1)
             data-3 (decode data-2)]
         ;; (app.common.pprint/pprint data-2)
         ;; (app.common.pprint/pprint data-3)
         (= data data-3)))
     {:num 1000})))

(t/deftest set-guide-1
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (smt/check!
     (smt/for [change (sg/generator ch/schema:set-guide-change)]
       (let [change (assoc change :page-id page-id)
             result (ch/process-changes data [change])]
         (= (:params change)
            (get-in result [:pages-index page-id :guides (:id change)]))))
     {:num 1000})))

(t/deftest set-guide-2
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (smt/check!
     (smt/for [change (->> (sg/generator ch/schema:set-guide-change)
                           (sg/filter :params))]
       (let [change1 (assoc change :page-id page-id)
             result1 (ch/process-changes data [change1])

             change2 (assoc change1 :params nil)
             result2 (ch/process-changes result1 [change2])]

         (and (some? (:params change1))
              (= (:params change1)
                 (get-in result1 [:pages-index page-id :guides (:id change1)]))

              (nil? (:params change2))
              (nil? (get-in result2 [:pages-index page-id :guides])))))

     {:num 1000})))

(t/deftest set-plugin-data-json-encode-decode
  (let [schema ch/schema:set-plugin-data-change
        encode (sm/encoder schema (sm/json-transformer))
        decode (sm/decoder schema (sm/json-transformer))]
    (smt/check!
     (smt/for [data (sg/generator schema)]
       (let [data-1 (encode data)
             data-2 (json-roundtrip data-1)
             data-3 (decode data-2)]
         (= data data-3)))
     {:num 1000})))

(t/deftest set-plugin-data-gen-and-validate
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]
    (smt/check!
     (smt/for [change (sg/generator ch/schema:set-plugin-data-change)]
       (sm/validate ch/schema:set-plugin-data-change change))
     {:num 1000})))

(t/deftest set-flow-json-encode-decode
  (let [schema ch/schema:set-flow-change
        encode (sm/encoder schema (sm/json-transformer))
        decode (sm/decoder schema (sm/json-transformer))]
    (smt/check!
     (smt/for [data (sg/generator schema)]
       (let [data-1 (encode data)
             data-2 (json-roundtrip data-1)
             data-3 (decode data-2)]
         ;; (app.common.pprint/pprint data-2)
         ;; (app.common.pprint/pprint data-3)
         (= data data-3)))
     {:num 1000})))

(t/deftest set-flow-1
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (smt/check!
     (smt/for [change (sg/generator ch/schema:set-flow-change)]
       (let [change (assoc change :page-id page-id)
             result (ch/process-changes data [change])]
         (= (:params change)
            (get-in result [:pages-index page-id :flows (:id change)]))))
     {:num 1000})))

(t/deftest set-flow-2
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (smt/check!
     (smt/for [change (->> (sg/generator ch/schema:set-flow-change)
                           (sg/filter :params))]
       (let [change1 (assoc change :page-id page-id)
             result1 (ch/process-changes data [change1])

             change2 (assoc change1 :params nil)
             result2 (ch/process-changes result1 [change2])]

         (and (some? (:params change1))
              (= (:params change1)
                 (get-in result1 [:pages-index page-id :flows (:id change1)]))

              (nil? (:params change2))
              (nil? (get-in result2 [:pages-index page-id :flows])))))

     {:num 1000})))

(t/deftest set-default-grid-json-encode-decode
  (let [schema ch/schema:set-default-grid-change
        encode (sm/encoder schema (sm/json-transformer))
        decode (sm/decoder schema (sm/json-transformer))]
    (smt/check!
     (smt/for [data (sg/generator schema)]
       (let [data-1 (encode data)
             data-2 (json-roundtrip data-1)
             data-3 (decode data-2)]
         ;; (println "==========")
         ;; (app.common.pprint/pprint data-2)
         ;; (app.common.pprint/pprint data-3)
         ;; (println "==========")
         (= data data-3)))
     {:num 1000})))

(t/deftest set-default-grid-1
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (smt/check!
     (smt/for [change (sg/generator ch/schema:set-default-grid-change)]
       (let [change (assoc change :page-id page-id)
             result (ch/process-changes data [change])]
         ;; (app.common.pprint/pprint change)
         (= (:params change)
            (get-in result [:pages-index page-id :default-grids (:grid-type change)]))))
     {:num 1000})))

(t/deftest set-default-grid-2
  (let [file-id (uuid/custom 2 2)
        page-id (uuid/custom 1 1)
        data    (make-file-data file-id page-id)]

    (smt/check!
     (smt/for [change (->> (sg/generator ch/schema:set-default-grid-change)
                           (sg/filter :params))]
       (let [change1 (assoc change :page-id page-id)
             result1 (ch/process-changes data [change1])

             change2 (assoc change1 :params nil)
             result2 (ch/process-changes result1 [change2])]

         ;; (app.common.pprint/pprint change1)

         (and (some? (:params change1))
              (= (:params change1)
                 (get-in result1 [:pages-index page-id :default-grids (:grid-type change1)]))

              (nil? (:params change2))
              (nil? (get-in result2 [:pages-index page-id :default-grids])))))

     {:num 1000})))
