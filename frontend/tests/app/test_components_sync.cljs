(ns app.test-components-sync
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [clojure.stacktrace :as stk]
            [beicon.core :as rx]
            [linked.core :as lks]
            [app.test-helpers.events :as the]
            [app.test-helpers.pages :as thp]
            [app.test-helpers.libraries :as thl]
            [app.common.geom.point :as gpt]
            [app.common.data :as d]
            [app.common.pages.helpers :as cph]
            [app.main.data.workspace.common :as dwc]
            [app.main.data.workspace.libraries :as dwl]
            [app.main.data.workspace.libraries-helpers :as dwlh]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(t/deftest test-touched
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color "#ffffff"
                                         :fill-opacity 1})
                      (thp/make-component :instance1
                                          [(thp/id :shape1)]))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            update-shape (fn [shape]
                           (merge shape {:fill-color "#fabada"
                                         :fill-opacity 0.5}))]

        (->> state
             (the/do-watch-update (dwc/update-shapes [(:id shape1)]
                                                      update-shape))
             (rx/do
               (fn [new-state]
                 (let [shape1 (thp/get-shape new-state :shape1)

                       [[group shape1] [c-group c-shape1] component]
                       (thl/resolve-instance-and-master
                         new-state
                         (:id instance1))

                       file (dwlh/get-local-file new-state)]

                   (t/is (= (:fill-color shape1) "#fabada"))
                   (t/is (= (:fill-opacity shape1) 0.5))
                   (t/is (= (:touched shape1) #{:fill-group}))
                   (t/is (= (:fill-color c-shape1) "#ffffff"))
                   (t/is (= (:fill-opacity c-shape1) 1))
                   (t/is (= (:touched c-shape1) nil)))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

        (catch :default e
          (println (.-stack e))
          (done)))))

(t/deftest test-reset-changes
  (t/async done
    (try
      (let [state (-> thp/initial-state
                      (thp/sample-page)
                      (thp/sample-shape :shape1 :rect
                                        {:name "Rect 1"
                                         :fill-color "#ffffff"
                                         :fill-opacity 1})
                      (thp/make-component :instance1
                                          [(thp/id :shape1)]))

            shape1    (thp/get-shape state :shape1)
            instance1 (thp/get-shape state :instance1)

            update-shape (fn [shape]
                           (merge shape {:fill-color "#fabada"
                                         :fill-opacity 0.5}))]

        (->> state
             (the/do-watch-update (dwc/update-shapes [(:id shape1)]
                                                      update-shape))

             (rx/mapcat #(the/do-watch-update
                           (dwl/reset-component (:id instance1)) %))

             (rx/do
               (fn [new-state]
                 (let [shape1 (thp/get-shape new-state :shape1)

                       [[group shape1] [c-group c-shape1] component]
                       (thl/resolve-instance-and-master
                         new-state
                         (:id instance1))

                       file (dwlh/get-local-file new-state)]

                   (t/is (= (:fill-color shape1) "#ffffff"))
                   (t/is (= (:fill-opacity shape1) 1))
                   (t/is (= (:touched shape1) nil))
                   (t/is (= (:fill-color c-shape1) "#ffffff"))
                   (t/is (= (:fill-opacity c-shape1) 1))
                   (t/is (= (:touched c-shape1) nil)))))

             (rx/subs
               done
               #(do
                  (println (.-stack %))
                  (done)))))

        (catch :default e
          (println (.-stack e))
          (done)))))

