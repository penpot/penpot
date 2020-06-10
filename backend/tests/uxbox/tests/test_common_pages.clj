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

    (t/testing "Remove option"
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
        (t/is (= nil (get-in res [:options :values :test])))))))

(t/deftest process-change-add-obj
  (let [data cp/default-page-data
        id-a (uuid/next)
        id-b (uuid/next)
        id-c (uuid/next)]
    (t/testing "Adds single object"
      (let [chg  {:type :add-obj
                  :id id-a
                  :frame-id uuid/zero
                  :obj {:id id-a
                        :frame-id uuid/zero
                        :type :rect
                        :name "rect"}}
            res (cp/process-changes data [chg])]

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

(t/deftest process-change-mod-obj-abs-order
  (let [id1  (uuid/next)
        id2  (uuid/next)
        id3  (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id1 id2 id3]))]

    (t/testing "abs order 1"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :abs-order
                               :id id3
                               :index 0}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id3 id1 id2] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "abs order 2"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :abs-order
                               :id id1
                               :index 100}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id2 id3 id1] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "abs order 3"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :abs-order
                               :id id3
                               :index 1}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id1 id3 id2] (get-in res [:objects uuid/zero :shapes])))))
    ))


(t/deftest process-change-mod-obj-rel-order
  (let [id1  (uuid/next)
        id2  (uuid/next)
        id3  (uuid/next)
        data (-> cp/default-page-data
                 (assoc-in [:objects uuid/zero :shapes] [id1 id2 id3]))]

    (t/testing "rel order 1"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id3
                               :loc :down}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id1 id3 id2] (get-in res [:objects uuid/zero :shapes])))))


    (t/testing "rel order 2"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id1
                               :loc :top}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id2 id3 id1] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "rel order 3"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id2
                               :loc :up}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id1 id3 id2] (get-in res [:objects uuid/zero :shapes])))))

    (t/testing "rel order 4"
      (let [chg {:type :mod-obj
                 :id uuid/zero
                 :operations [{:type :rel-order
                               :id id3
                               :loc :bottom}]}
            res (cp/process-changes data [chg])]

        ;; (clojure.pprint/pprint data)
        ;; (clojure.pprint/pprint res)

        (t/is (= [id3 id1 id2] (get-in res [:objects uuid/zero :shapes])))))
    ))

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
        data (-> cp/default-page-data
                 (assoc-in [cp/root :shapes] [frame-a-id])
                 (assoc-in [:objects frame-a-id]
                           {:id frame-a-id :name "Frame a" :type :frame})
                 (assoc-in [:objects frame-b-id]
                           {:id frame-b-id :name "Frame b" :type :frame})

                 ;; Groups
                 (assoc-in [:objects group-a-id]
                           {:id group-a-id :name "Group A" :type :group :frame-id frame-a-id})
                 (assoc-in [:objects group-b-id]
                           {:id group-b-id :name "Group B" :type :group :frame-id frame-a-id})

                 ;; Shapes
                 (assoc-in [:objects rect-a-id]
                           {:id rect-a-id :name "Rect A" :type :rect :frame-id frame-a-id})
                 (assoc-in [:objects rect-b-id]
                           {:id rect-b-id :name "Rect B" :type :rect :frame-id frame-a-id})
                 (assoc-in [:objects rect-c-id]
                           {:id rect-c-id :name "Rect C" :type :rect :frame-id frame-a-id})
                 (assoc-in [:objects rect-d-id]
                           {:id rect-d-id :name "Rect D" :type :rect :frame-id frame-a-id})
                 (assoc-in [:objects rect-e-id]
                           {:id rect-e-id :name "Rect E" :type :rect :frame-id frame-a-id})

                 ;; Relationships
                 (assoc-in [:objects cp/root :shapes] [frame-a-id frame-b-id])
                 (assoc-in [:objects frame-a-id :shapes] [group-a-id group-b-id rect-e-id])
                 (assoc-in [:objects group-a-id :shapes] [rect-a-id rect-b-id rect-c-id])
                 (assoc-in [:objects group-b-id :shapes] [rect-d-id]))]

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
                      :parent-id cp/root
                      :shapes [group-a-id]
                      :index 0}]
            res (cp/process-changes data changes)]
        (t/is (= [group-a-id frame-a-id frame-b-id]
                 (get-in res [:objects cp/root :shapes])))))

    (t/testing "Don't allow to move inside self"
      (let [changes [{:type :mov-objects
                      :parent-id group-a-id
                      :shapes [group-a-id]}]
            res (cp/process-changes data changes)]
        (t/is (= data res))))))


(t/deftest process-change-move-objects-regression
  (let [shape-2-id (uuid/custom 1 2)
        shape-3-id (uuid/custom 1 3)
        frame-id   (uuid/custom 1 1)
        changes [{:type :add-obj
                  :id frame-id
                  :frame-id uuid/zero
                  :obj {:type :frame
                        :name "Frame"}}
                 {:type :add-obj
                  :frame-id frame-id
                  :id shape-2-id
                  :obj {:type :shape
                        :name "Shape"}}
                 {:type :add-obj
                  :id shape-3-id
                  :frame-id uuid/zero
                  :obj {:type :rect
                        :name "Shape"}}]
        data (cp/process-changes cp/default-page-data changes)]
    (t/testing "move inside->outside-inside"
      (let [changes [{:type :mov-objects
                      :shapes [shape-3-id]
                      :parent-id frame-id}
                     {:type :mov-objects
                      :shapes [shape-3-id]
                      :parent-id uuid/zero}]
            res (cp/process-changes data changes)]

        (t/is (= (get-in res [:objects shape-2-id :frame-id])
                 (get-in data [:objects shape-2-id :frame-id])))
        (t/is (= (get-in res [:objects shape-3-id :frame-id])
                 (get-in data [:objects shape-3-id :frame-id])))))))


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


