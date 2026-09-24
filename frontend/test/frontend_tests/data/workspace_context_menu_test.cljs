;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-context-menu-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.selection :as dws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private rect-id (uuid/next))

(defn- make-state
  [selected]
  {:current-file-id file-id
   :current-page-id page-id
   :workspace-local {:selected selected}
   :files {file-id
           {:data
            {:pages-index
             {page-id
              {:objects {uuid/zero {:id uuid/zero :type :frame :shapes [rect-id]}
                         rect-id   {:id rect-id :type :rect :parent-id uuid/zero
                                    :frame-id uuid/zero}}}}}}}})

(defn- emitted-types
  "Collect the types of the events emitted by the event's watch."
  [event state done f]
  (let [types (atom [])]
    (->> (ptk/watch event state (rx/empty))
         (rx/subs!
          #(swap! types conj (ptk/type %))
          (fn [err]
            (t/do-report {:type :error :message "Stream error" :actual err})
            (done))
          (fn []
            (f @types)
            (done))))))

(defn- menu-event
  [shape-id]
  (dw/show-shape-context-menu {:position (gpt/point 10 10)
                               :shape {:id shape-id :type :rect}}))

(t/deftest shape-context-menu-ignores-shape-outside-page
  (t/async
    done
    (emitted-types (menu-event (uuid/next)) (make-state #{}) done
                   #(t/is (= [] %)))))

(t/deftest shape-context-menu-selects-unselected-shape
  (t/async
    done
    (emitted-types (menu-event rect-id) (make-state #{}) done
                   #(t/is (= [::dws/select-shape ::dw/show-shape-context-menu] %)))))

(t/deftest shape-context-menu-opens-for-selected-shape
  (t/async
    done
    (emitted-types (menu-event rect-id) (make-state #{rect-id}) done
                   #(t/is (= [::dw/show-context-menu] %)))))
