(ns uxbox.data.workspace-tests
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer (pprint)]
            [uxbox.rstore :as rs]
            [uxbox.data.workspace :as dw]))

;; delete shape: delete from page
(t/deftest delete-shape-test1
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        event (dw/delete-shape 4)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 3])
                     (update-in [:shapes-by-id] dissoc 4))
        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; delete shape: delete from group
(t/deftest delete-shape-test2
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        event (dw/delete-shape 2)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [3 4])
                     (update-in [:shapes-by-id] dissoc 2)
                     (update-in [:shapes-by-id] dissoc 1))
        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))
