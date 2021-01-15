(ns app.test-library-sync
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [app.main.data.workspace.libraries :as dwl]))

;; ---- Helpers

(defn do-update
  [state event cb]
  (let [new-state (ptk/update event state)]
    (cb new-state)))

(defn do-watch
  [state event cb]
  (->> (ptk/watch event state nil)
       (rx/reduce conj [])
       (rx/subs cb)))

(defn do-watch-update
  [state event & cbs]
  (do-watch state event
    (fn [events]
      (t/is (= (count events) (count cbs)))
      (reduce
        (fn [new-state [event cb]]
          (do-update new-state event cb))
        state
        (map list events cbs)))))

;; ---- Tests

(t/deftest synctest
  (t/testing "synctest"
    (let [state {:workspace-local {:color-for-rename "something"}}]
      (do-update
        state
        dwl/clear-color-for-rename
        (fn [new-state]
          (t/is (= (get-in new-state [:workspace-local :color-for-rename])
                   nil)))))))

(t/deftest asynctest
  (t/testing "asynctest"
    (t/async done
      (let [state {}
            color {:color "#ffffff"}]
        (do-watch-update
          state
          (dwl/add-recent-color color)
          (fn [new-state]
            (t/is (= (get-in new-state [:workspace-file
                                        :data
                                        :recent-colors])
                     [color]))
            (t/is (= (get-in new-state [:workspace-data
                                        :recent-colors])
                     [color]))
            (done)))))))

